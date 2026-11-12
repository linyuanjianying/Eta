package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AssistantBlockKind
import io.github.mangi.eta.agent.model.ProviderEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 预算的计时/计数语义：看门狗只停子控制器、心跳不能续命、次数是硬上限、并发按父 run 共享。
 *
 * 全部用真实定时线程 + 短阈值（几十到几百毫秒），断言留足余量以免 CI 抖动。
 */
class AgentExecutionBudgetTest {

    @Test
    fun blockingRequestIsStoppedByWatchdogWithoutTouchingParent() {
        val budget = AgentExecutionBudget(
            AgentExecutionBudget.Limits(requestTimeoutMillis = 150, idleTimeoutMillis = 60_000),
        )
        val parent = AgentRunController()
        val child = AgentRunController()
        val run = budget.childRun(parent, child)
        try {
            run.start()
            val startedAt = System.nanoTime()
            // 请求已经跑在独立 worker 上：命中的请求超时按预算终止归类。
            val thrown = assertThrows(AgentExecutionBudgetStopException::class.java) {
                run.guardRequest {
                    // 模拟供应商流式读取：只有控制器检查点，没有协程取消点。
                    while (true) {
                        Thread.sleep(5)
                        child.throwIfCancelled()
                    }
                }
            }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            assertEquals(AgentExecutionBudget.Termination.REQUEST_TIMEOUT, thrown.termination)
            assertEquals(AgentExecutionBudget.Termination.REQUEST_TIMEOUT, run.termination)
            assertTrue("请求应在超时后立即停止，实际 ${elapsedMs}ms", elapsedMs < 3_000)
            assertTrue(child.isCancelled)
            assertFalse("预算只停子代理，不能取消父 run", parent.isCancelled)
        } finally {
            run.close()
        }
    }

    @Test
    fun transportHeartbeatsDoNotExtendIdleBudget() {
        val budget = AgentExecutionBudget(
            AgentExecutionBudget.Limits(requestTimeoutMillis = 10_000, idleTimeoutMillis = 120),
        )
        val parent = AgentRunController()
        val child = AgentRunController()
        val run = budget.childRun(parent, child)
        try {
            run.start()
            val startedAt = System.nanoTime()
            // 心跳不续命：空闲看门狗命中后，即使 block 自己正常返回也必须按终止抛出，不能算成功。
            val thrown = assertThrows(AgentExecutionBudgetStopException::class.java) {
                run.guardRequest {
                    // 服务端只在发心跳（请求开始标记 + 空增量），不应据此认为它还在干活。
                    while (!child.isCancelled && System.nanoTime() - startedAt < TimeUnit.SECONDS.toNanos(5)) {
                        run.noteProviderEvent(ProviderEvent.RequestStarted)
                        run.noteProviderEvent(
                            ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, ""),
                        )
                        Thread.sleep(10)
                    }
                }
            }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            assertEquals(AgentExecutionBudget.Termination.IDLE_TIMEOUT, thrown.termination)
            assertEquals(AgentExecutionBudget.Termination.IDLE_TIMEOUT, run.termination)
            assertTrue("空闲看门狗应在 ${elapsedMs}ms 内触发", elapsedMs < 3_000)
            assertFalse(parent.isCancelled)
        } finally {
            run.close()
        }
    }

    @Test
    fun realProgressEventsKeepTheRequestAlive() {
        val budget = AgentExecutionBudget(
            AgentExecutionBudget.Limits(requestTimeoutMillis = 10_000, idleTimeoutMillis = 600),
        )
        val run = budget.childRun(AgentRunController(), AgentRunController())
        try {
            run.start()
            val result = run.guardRequest {
                // 总时长 ~800ms 超过空闲阈值 600ms，但每 100ms 有真实增量，因此不该被判定空闲。
                repeat(8) {
                    Thread.sleep(100)
                    run.noteProviderEvent(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "字"))
                }
                "done"
            }
            assertEquals("done", result)
            assertNull(run.termination)
        } finally {
            run.close()
        }
    }

    @Test
    fun progressClassificationIgnoresTransportOnlyEvents() {
        assertFalse(AgentExecutionBudget.isProgressEvent(ProviderEvent.RequestStarted))
        assertFalse(
            AgentExecutionBudget.isProgressEvent(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "")),
        )
        assertTrue(
            AgentExecutionBudget.isProgressEvent(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "x")),
        )
        assertTrue(AgentExecutionBudget.isProgressEvent(ProviderEvent.ResponseHeaders(200)))
    }

    @Test
    fun toolCallBoundStopsTheChildImmediately() {
        val budget = AgentExecutionBudget(AgentExecutionBudget.Limits(maxToolCalls = 1))
        val parent = AgentRunController()
        val child = AgentRunController()
        val run = budget.childRun(parent, child)
        try {
            run.beforeToolCall()
            val thrown = assertThrows(AgentExecutionBudgetStopException::class.java) {
                run.beforeToolCall()
            }
            assertEquals(AgentExecutionBudget.Termination.TOOL_CALLS, thrown.termination)
            assertEquals(AgentExecutionBudget.Termination.TOOL_CALLS, run.termination)
            assertTrue(child.isCancelled)
            assertFalse(parent.isCancelled)
        } finally {
            run.close()
        }
    }

    @Test
    fun concurrencySlotIsSharedPerParentAndWaitIsBounded() {
        val budget = AgentExecutionBudget(
            AgentExecutionBudget.Limits(maxConcurrent = 1, concurrencyWaitMillis = 120),
        )
        val parent = AgentRunController()
        val first = budget.childRun(parent, AgentRunController())
        assertTrue(first.awaitConcurrencySlot())
        val second = budget.childRun(parent, AgentRunController())
        try {
            val startedAt = System.nanoTime()
            assertFalse(second.awaitConcurrencySlot())
            val waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            assertEquals(AgentExecutionBudget.Termination.CONCURRENCY_WAIT, second.termination)
            assertTrue("应至少等满配置的等待预算，实际 ${waitedMs}ms", waitedMs >= 90)
            assertTrue("等待必须可中止，实际 ${waitedMs}ms", waitedMs < 3_000)
        } finally {
            first.close()
            second.close()
        }
        // 名额归还后同一父 run 可以继续派发。
        val third = budget.childRun(parent, AgentRunController())
        try {
            assertTrue(third.awaitConcurrencySlot())
        } finally {
            third.close()
        }
        // 别的父 run 有自己的名额，不会被占用。
        val otherParentRun = budget.childRun(AgentRunController(), AgentRunController())
        try {
            assertTrue(otherParentRun.awaitConcurrencySlot())
        } finally {
            otherParentRun.close()
        }
    }

    @Test
    fun concurrencyWaitIsInterruptedByParentCancellation() {
        val budget = AgentExecutionBudget(
            AgentExecutionBudget.Limits(maxConcurrent = 1, concurrencyWaitMillis = 10_000),
        )
        val parent = AgentRunController()
        val holder = budget.childRun(parent, AgentRunController())
        assertTrue(holder.awaitConcurrencySlot())
        val waiter = budget.childRun(parent, AgentRunController())
        val canceller = Thread {
            Thread.sleep(120)
            parent.cancel()
        }.apply { isDaemon = true; start() }
        try {
            assertThrows(AgentRunCancelledException::class.java) { waiter.awaitConcurrencySlot() }
        } finally {
            canceller.join(2_000)
            holder.close()
            waiter.close()
        }
    }

    @Test
    fun disabledBudgetIsANoOp() {
        val budget = AgentExecutionBudget.disabled()
        val parent = AgentRunController()
        val child = AgentRunController()
        val run = budget.childRun(parent, child)
        try {
            assertTrue(run.awaitConcurrencySlot())
            run.start()
            run.beforeModelAttempt()
            run.beforeToolCall()
            run.afterToolCall()
            assertEquals("ok", run.guardRequest { "ok" })
            assertNull(run.termination)
            assertFalse(run.isTerminated)
        } finally {
            run.close()
        }
    }

    @Test
    fun closedRunIsUnregisteredAndStopsGuarding() {
        val budget = AgentExecutionBudget(
            AgentExecutionBudget.Limits(requestTimeoutMillis = 60_000, idleTimeoutMillis = 60_000),
        )
        val child = AgentRunController()
        val run = budget.childRun(AgentRunController(), child)
        assertTrue(AgentExecutionBudget.forController(child) === run)
        run.close()
        run.close()
        assertNull(AgentExecutionBudget.forController(child))
        assertNull(AgentExecutionBudget.terminationFor(child))
        assertFalse("关闭预算运行体不应取消仍在正常工作的控制器", child.isCancelled)
    }

    @Test
    fun blockingToolIsAbortedByRunBudgetWithInterruptLatch() {
        val budget = AgentExecutionBudget(AgentExecutionBudget.Limits(runTimeoutMillis = 200))
        val parent = AgentRunController()
        val child = AgentRunController()
        val run = budget.childRun(parent, child)
        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val startedAt = System.nanoTime()
        try {
            run.start()
            val thrown = assertThrows(AgentExecutionBudgetStopException::class.java) {
                run.runTool {
                    entered.countDown()
                    // 模拟共享执行器里不认控制器的阻塞工具：只对线程中断有反应。
                    try {
                        while (true) Thread.sleep(100)
                    } catch (_: InterruptedException) {
                        interrupted.countDown()
                    } finally {
                        exited.countDown()
                    }
                }
            }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            assertEquals(AgentExecutionBudget.Termination.RUN_TIMEOUT, thrown.termination)
            assertTrue("工具线程必须真的启动过", entered.await(2, TimeUnit.SECONDS))
            assertTrue("取消必须落到工具线程的中断上", interrupted.await(3, TimeUnit.SECONDS))
            assertTrue("工具线程应已退出", exited.await(3, TimeUnit.SECONDS))
            assertTrue("父任务必须及时拿回控制权，实际 ${elapsedMs}ms", elapsedMs < 3_000)
            assertFalse("预算只停子代理", parent.isCancelled)
        } finally {
            run.close()
        }
    }

    @Test
    fun blockingToolReturnsPromptlyWhenParentCancels() {
        val budget = AgentExecutionBudget(AgentExecutionBudget.Limits())
        val parent = AgentRunController()
        val child = AgentRunController()
        val run = budget.childRun(parent, child)
        // 与 SubagentExecutor 一致：父取消会级联到子控制器。
        val parentBinding = parent.register(child::cancel)
        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val canceller = Thread {
            entered.await(2, TimeUnit.SECONDS)
            parent.cancel()
        }.apply { isDaemon = true; start() }
        val startedAt = System.nanoTime()
        try {
            assertThrows(AgentRunCancelledException::class.java) {
                run.runTool {
                    entered.countDown()
                    try {
                        while (true) Thread.sleep(100)
                    } catch (_: InterruptedException) {
                        interrupted.countDown()
                    } finally {
                        exited.countDown()
                    }
                }
            }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            assertTrue("中断必须落到工具线程", interrupted.await(3, TimeUnit.SECONDS))
            assertTrue("工具线程应已退出", exited.await(3, TimeUnit.SECONDS))
            assertTrue("父取消后必须及时返回，实际 ${elapsedMs}ms", elapsedMs < 3_000)
            assertNull("父取消不是预算终止", run.termination)
        } finally {
            canceller.join(1_000)
            parentBinding.close()
            run.close()
        }
    }

    @Test
    fun toolResultPassesThroughAndWorkerThreadDoesNotLinger() {
        val budget = AgentExecutionBudget(AgentExecutionBudget.Limits())
        val run = budget.childRun(AgentRunController(), AgentRunController())
        try {
            assertEquals("ok", run.runTool { "ok" })
            assertNull(run.termination)
            // 正常返回的 worker 线程必须自己结束，不能留下常驻线程。
            val alive = waitForNoWorker("eta-subagent-tool")
            assertTrue("正常执行后残留工具线程：$alive", alive.isEmpty())
        } finally {
            run.close()
        }
    }

    @Test
    fun lateSuccessIsRejectedAfterWatchdogFired() {
        val budget = AgentExecutionBudget(
            AgentExecutionBudget.Limits(requestTimeoutMillis = 100, idleTimeoutMillis = 60_000),
        )
        val run = budget.childRun(AgentRunController(), AgentRunController())
        try {
            run.start()
            val thrown = assertThrows(AgentExecutionBudgetStopException::class.java) {
                run.guardRequest {
                    // 不看控制器、直接睡过阈值再返回「成功」：这种情况绝不能当成成功。
                    Thread.sleep(300)
                    "late success"
                }
            }
            assertEquals(AgentExecutionBudget.Termination.REQUEST_TIMEOUT, thrown.termination)
        } finally {
            run.close()
        }
    }

    @Test
    fun exactlyOneRequestWorkerRunsAndExitsNormally() {
        val budget = AgentExecutionBudget(
            AgentExecutionBudget.Limits(requestTimeoutMillis = 5_000, idleTimeoutMillis = 5_000),
        )
        val run = budget.childRun(AgentRunController(), AgentRunController())
        try {
            val result = run.guardRequest {
                // 请求体跑在唯一的请求 worker 上：此刻只允许一个请求线程存在（就是自己）。
                val duringRequest = aliveThreads("eta-subagent-request")
                assertEquals("同一子代理只允许一个请求 worker：$duringRequest", 1, duringRequest.size)
                Thread.sleep(80)
                "ok"
            }
            assertEquals("ok", result)
            val alive = waitForNoWorker("eta-subagent-request")
            assertTrue("正常返回后残留请求线程：$alive", alive.isEmpty())
        } finally {
            run.close()
        }
    }

    private fun waitForNoWorker(name: String): List<String> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        var alive = aliveThreads(name)
        while (alive.isNotEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20)
            alive = aliveThreads(name)
        }
        return alive
    }

    private fun aliveThreads(name: String): List<String> =
        Thread.getAllStackTraces().keys
            .filter { it.isAlive && it.name == name }
            .map { "${it.name}#${it.id}" }
}

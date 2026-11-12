package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentExecutionBudget
import io.github.mangi.eta.agent.runtime.AgentExecutionBudgetStopException
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 预算与模型重试器的接线：请求卡死、只有心跳、重试次数都必须在预算内停下，
 * 并且只取消子控制器（父 run 不受影响）。
 */
class AgentModelRetryBudgetTest {
    private val retry = AgentModelRetry { _, _ -> }

    @Test
    fun stalledRequestIsStoppedByRequestWatchdog() {
        val fixture = fixture(AgentExecutionBudget.Limits(requestTimeoutMillis = 150, idleTimeoutMillis = 60_000))
        val startedAt = System.nanoTime()
        try {
            // 供应商一直卡在流式读取上，不看任何进度：只有看门狗能把它掀掉。
            assertThrows(AgentRunCancelledException::class.java) {
                complete(
                    fixture = fixture,
                    provider = provider { _, _ ->
                        fixture.calls += 1
                        while (true) {
                            Thread.sleep(5)
                            fixture.child.throwIfCancelled()
                        }
                        @Suppress("UNREACHABLE_CODE")
                        response()
                    },
                )
            }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            assertEquals(AgentExecutionBudget.Termination.REQUEST_TIMEOUT, fixture.run.termination)
            assertTrue("应在请求超时后停止，实际 ${elapsedMs}ms", elapsedMs < 3_000)
            assertTrue(fixture.child.isCancelled)
            assertFalse(fixture.parent.isCancelled)
            assertEquals("超时不该被当成可重试的传输错误", 1, fixture.calls)
        } finally {
            fixture.run.close()
        }
    }

    @Test
    fun heartbeatOnlyStreamHitsIdleBudget() {
        val fixture = fixture(AgentExecutionBudget.Limits(requestTimeoutMillis = 10_000, idleTimeoutMillis = 120))
        val startedAt = System.nanoTime()
        try {
            // 只发心跳（RequestStarted + 空增量），总请求时长还远没到，但实际没有产出。
            assertThrows(AgentRunCancelledException::class.java) {
                complete(
                    fixture = fixture,
                    provider = provider { controller, emit ->
                        fixture.calls += 1
                        while (true) {
                            emit(ProviderEvent.RequestStarted)
                            emit(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, ""))
                            Thread.sleep(10)
                            controller.throwIfCancelled()
                        }
                        @Suppress("UNREACHABLE_CODE")
                        response()
                    },
                )
            }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            assertEquals(AgentExecutionBudget.Termination.IDLE_TIMEOUT, fixture.run.termination)
            assertTrue("空闲看门狗应在 ${elapsedMs}ms 内触发", elapsedMs < 3_000)
            assertFalse(fixture.parent.isCancelled)
        } finally {
            fixture.run.close()
        }
    }

    @Test
    fun providerThatIgnoresCancellationDoesNotHangTheRun() {
        val fixture = fixture(AgentExecutionBudget.Limits(requestTimeoutMillis = 150, idleTimeoutMillis = 60_000))
        // 不响应中断的供应商：只有测试放行才会结束，用来证明等待方不依赖它配合。
        val release = CountDownLatch(1)
        val workerExited = CountDownLatch(1)
        val startedAt = System.nanoTime()
        try {
            // 预算终止会让 guardRequest 抛 StopException；重试层把已取消的 run 归一成取消异常。
            assertThrows(AgentRunCancelledException::class.java) {
                complete(
                    fixture = fixture,
                    provider = provider { _, emit ->
                        fixture.calls += 1
                        emit(ProviderEvent.RequestStarted)
                        try {
                            // 第一次 await 会被 cancel(true) 打断；故意吞掉中断继续等：
                            // 模拟既不响应中断、也不检查取消点的供应商。
                            while (true) {
                                val released = try {
                                    release.await(50, TimeUnit.MILLISECONDS)
                                } catch (_: InterruptedException) {
                                    false
                                }
                                if (released) break
                            }
                        } finally {
                            workerExited.countDown()
                        }
                        @Suppress("UNREACHABLE_CODE")
                        response()
                    },
                )
            }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            assertEquals(AgentExecutionBudget.Termination.REQUEST_TIMEOUT, fixture.run.termination)
            assertTrue("忽略取消的供应商也必须及时返回控制权，实际 ${elapsedMs}ms", elapsedMs < 3_000)
            assertFalse(fixture.parent.isCancelled)
        } finally {
            // 放行后台线程，避免测试泄漏线程。
            release.countDown()
            assertTrue("provider 线程应在放行后退出", workerExited.await(3, TimeUnit.SECONDS))
            fixture.run.close()
        }
    }

    @Test
    fun modelCallBudgetCountsRetries() {
        val fixture = fixture(AgentExecutionBudget.Limits(maxModelCalls = 2))
        try {
            val thrown = assertThrows(AgentExecutionBudgetStopException::class.java) {
                complete(fixture = fixture, provider = provider { _, _ ->
                    fixture.calls += 1
                    throw IOException("connection reset")
                })
            }
            assertEquals(AgentExecutionBudget.Termination.MODEL_CALLS, thrown.termination)
            assertEquals("重试也算一次：2 次尝试后必须停下", 2, fixture.calls)
            assertEquals(AgentExecutionBudget.Termination.MODEL_CALLS, fixture.run.termination)
            assertTrue(fixture.child.isCancelled)
            assertFalse(fixture.parent.isCancelled)
        } finally {
            fixture.run.close()
        }
    }

    @Test
    fun successfulRequestUsesASingleAttemptWithinBudget() {
        val fixture = fixture(AgentExecutionBudget.Limits(maxModelCalls = 3, requestTimeoutMillis = 2_000))
        try {
            val result = complete(
                fixture = fixture,
                provider = provider { _, emit ->
                    fixture.calls += 1
                    emit(ProviderEvent.RequestStarted)
                    emit(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "完成"))
                    response()
                },
            )
            assertEquals("完成", result.response.assistantMessage.optString("content"))
            assertEquals(1, fixture.calls)
            assertEquals(null, fixture.run.termination)
        } finally {
            fixture.run.close()
        }
    }

    private class Fixture(
        val budget: AgentExecutionBudget,
        val parent: AgentRunController,
        val child: AgentRunController,
    ) {
        val run = budget.childRun(parent, child)
        var calls = 0
    }

    private fun fixture(limits: AgentExecutionBudget.Limits) = Fixture(
        budget = AgentExecutionBudget(limits),
        parent = AgentRunController(),
        child = AgentRunController(),
    )

    private fun complete(
        fixture: Fixture,
        provider: AgentProviderClient,
    ) = retry.complete(
        initialRound = 1,
        request = ProviderRequest(
            AgentModelClient.ModelConfig(
                baseUrl = "https://example.invalid",
                apiKey = "test-key",
                model = "test-model",
                systemPrompt = "",
            ),
            JSONArray(),
            JSONArray(),
        ),
        provider = provider,
        controller = fixture.child,
        onEvent = {},
        onProviderEvent = { _, _ -> },
        discardAttemptReasoning = {},
    )

    private fun response() = ProviderResponse(JSONObject().put("content", "完成").put("finish_reason", "stop"))

    private fun provider(
        action: (AgentRunController, (ProviderEvent) -> Unit) -> ProviderResponse,
    ) = object : AgentProviderClient {
        override val id = "test"
        override val capabilities =
            ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, false, false, false)

        override fun complete(
            request: ProviderRequest,
            runController: AgentRunController,
            onEvent: (ProviderEvent) -> Unit,
        ): ProviderResponse = action(runController, onEvent)
    }
}

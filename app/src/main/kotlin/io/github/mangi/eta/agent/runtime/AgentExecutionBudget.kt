package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.ProviderEvent
import io.github.mangi.eta.data.model.SubagentBudgetSettings
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 一次父 run 的 Subagent 执行预算。
 *
 * 预算本身是纯数据（[limits]），真正的计时与计数发生在每个子代理自己的 [ChildRun] 上：
 * - 总运行时长（墙上时间）到点直接取消**子**控制器；
 * - 单次模型请求两条独立看门狗：总请求时长、以及「有意义的 ProviderEvent」空闲时长；
 * - 模型调用次数（含重试）与工具调用次数是硬上限，超出即终止；
 * - 并发名额按父控制器共享，先抢名额再登记子代理，执行体用 finally 兜底归还。
 *
 * 关键约束：
 * 1. 只取消子控制器（`childController`），父 run 完全不受影响，其它子代理也不会被牵连；
 * 2. 看门狗走独立的 daemon 定时线程，不用协程 `withTimeout` 去包阻塞式网络调用
 *    （阻塞调用不响应协程取消，只有控制器 + 底层连接 abort 才能真停下来）；
 * 3. 时间一律用单调时钟（[System.nanoTime]），不看墙上时间，避免改系统时间绕过预算；
 * 4. 不向界面伪造「心跳进度」事件，空闲判定只看真实进度事件。
 *
 * 注册表让阻塞的模型重试器可以按控制器找回预算：
 * `AgentExecutionBudget.forController(childController)`。
 */
internal class AgentExecutionBudget(
    val limits: Limits,
    private val clock: () -> Long = System::nanoTime,
) {
    /** 预算终止原因；[code] 是回给父 Agent 的机器可读错误码（不是用户取消）。 */
    enum class Termination(val code: String, val reasonZh: String) {
        RUN_TIMEOUT("SUBAGENT_TIMEOUT", "单次任务总运行时长已用尽"),
        REQUEST_TIMEOUT("SUBAGENT_TIMEOUT", "单次模型请求超时"),
        IDLE_TIMEOUT("SUBAGENT_TIMEOUT", "模型长时间没有有效输出"),
        MODEL_CALLS("SUBAGENT_BUDGET_EXCEEDED", "模型调用次数已用尽"),
        TOOL_CALLS("SUBAGENT_BUDGET_EXCEEDED", "工具调用次数已用尽"),
        CONCURRENCY_WAIT("SUBAGENT_BUDGET_EXCEEDED", "等待并发名额超时，未开始执行"),
    }

    /**
     * 预算上限。
     *
     * 超时用毫秒、次数用个；[UNBOUNDED_MILLIS]/[UNBOUNDED_COUNT] 表示该维度不设限
     * （测试与老调用方的默认路径就是全不设限，行为与引入预算前一致）。
     */
    data class Limits(
        val runTimeoutMillis: Long = UNBOUNDED_MILLIS,
        val requestTimeoutMillis: Long = UNBOUNDED_MILLIS,
        val idleTimeoutMillis: Long = UNBOUNDED_MILLIS,
        val maxModelCalls: Int = UNBOUNDED_COUNT,
        val maxToolCalls: Int = UNBOUNDED_COUNT,
        val maxConcurrent: Int = UNBOUNDED_COUNT,
        /**
         * 等并发名额的上限：超时返回 [Termination.CONCURRENCY_WAIT]，避免父工具调用无限挂住。
         *
         * 生产路径由 [fromSettings] 取本次 run 的总时长预算（等于整笔预算，够 fanout 排队），
         * 这里的 30 秒只用于直接构造 [Limits] 的调用方与测试。
         */
        val concurrencyWaitMillis: Long = DEFAULT_CONCURRENCY_WAIT_MILLIS,
    ) {
        init {
            require(runTimeoutMillis > 0) { "runTimeoutMillis 必须为正数" }
            require(requestTimeoutMillis > 0) { "requestTimeoutMillis 必须为正数" }
            require(idleTimeoutMillis > 0) { "idleTimeoutMillis 必须为正数" }
            require(maxModelCalls > 0) { "maxModelCalls 必须为正数" }
            require(maxToolCalls > 0) { "maxToolCalls 必须为正数" }
            require(maxConcurrent > 0) { "maxConcurrent 必须为正数" }
            require(concurrencyWaitMillis > 0) { "concurrencyWaitMillis 必须为正数" }
        }

        internal val hasRequestTimeLimits: Boolean
            get() = requestTimeoutMillis.isBudgetFinite() || idleTimeoutMillis.isBudgetFinite()

        internal val hasTimeLimits: Boolean
            get() = runTimeoutMillis.isBudgetFinite() || hasRequestTimeLimits
    }

    /**
     * 看门狗轮询间隔：取最短阈值的四分之一，夹在 25~250ms 之间，
     * 既不漏掉短预算，也不为长预算空转。
     */
    private val tickMillis: Long = tickMillisFor(limits)

    /** 为一个子代理创建预算运行体；子控制器与父控制器的映射在此建立。 */
    fun childRun(
        parentController: AgentRunController,
        childController: AgentRunController,
    ): ChildRun = ChildRun(parentController, childController).also { run ->
        runs[childController] = run
    }

    /**
     * 单个子代理的预算运行体。
     *
     * 生命周期：`childRun()` → [awaitConcurrencySlot]（抢名额，失败直接返回）→ [start]（武装总时长看门狗）
     * → 模型请求里 [guardRequest]，工具执行前后 [beforeToolCall]/[afterToolCall] → [close]（释放线程与名额）。
     */
    inner class ChildRun internal constructor(
        private val parentController: AgentRunController,
        private val childController: AgentRunController,
    ) : AutoCloseable {
        private val modelAttempts = AtomicInteger(0)
        private val toolCalls = AtomicInteger(0)
        private val stopped = AtomicReference<Termination?>(null)
        private val slotHeld = AtomicBoolean(false)
        private val lock = Any()

        private val timers: ScheduledThreadPoolExecutor? =
            if (limits.hasTimeLimits) newTimer() else null
        private val parentSemaphore: Semaphore? =
            if (limits.maxConcurrent >= UNBOUNDED_COUNT) null
            else parentSemaphoreFor(parentController, limits.maxConcurrent)

        private var runTask: ScheduledFuture<*>? = null
        private var requestTask: ScheduledFuture<*>? = null
        private var requestStartedNanos = 0L
        private var lastProgressNanos = 0L
        private var pauseStartedNanos: Long? = null
        private var closed = false

        /** 当前在等待的请求 worker；同一子代理同时只允许一个（换掉前一个要先打断它）。 */
        private val requestWorker = AtomicReference<FutureTask<*>?>(null)

        /** 当前线程是否已经在某个 guardRequest 之内：嵌套调用不再起第二个请求线程。 */
        private val guardDepth = ThreadLocal<Int>()

        /** 已有的终止原因；非 null 表示本子代理是被预算停下的，不是用户取消。 */
        val termination: Termination?
            get() = stopped.get()

        val isTerminated: Boolean
            get() = stopped.get() != null

        /** 给父 Agent 看的说明：带上实际命中的上限，避免只说一句“超预算”。 */
        val terminationMessage: String
            get() {
                val kind = stopped.get() ?: return ""
                val detail = when (kind) {
                    Termination.RUN_TIMEOUT -> "（预算 ${limits.runTimeoutMillis / 60_000} 分钟）"
                    Termination.REQUEST_TIMEOUT -> "（预算 ${limits.requestTimeoutMillis / 1_000} 秒）"
                    Termination.IDLE_TIMEOUT -> "（空闲预算 ${limits.idleTimeoutMillis / 1_000} 秒）"
                    Termination.MODEL_CALLS -> "（预算 ${limits.maxModelCalls} 次，含重试与上下文压缩）"
                    Termination.TOOL_CALLS -> "（预算 ${limits.maxToolCalls} 次）"
                    Termination.CONCURRENCY_WAIT ->
                        "（并发上限 ${limits.maxConcurrent}，已等待 ${limits.concurrencyWaitMillis / 1_000} 秒）"
                }
                return "${kind.reasonZh}$detail；已停止该 Subagent 并保留已完成部分，父任务未受影响。"
            }

        /**
         * 抢父 run 维度的并发名额。
         *
         * 可取消：轮询期间父 run 被取消会立刻抛 [AgentRunCancelledException]；
         * 等满 [Limits.concurrencyWaitMillis] 则记 [Termination.CONCURRENCY_WAIT] 并返回 false。
         * 前置条件：必须在创建/登记「运行中」子代理之前调用。
         */
        fun awaitConcurrencySlot(): Boolean {
            stopped.get()?.let { throw AgentExecutionBudgetStopException(it) }
            val semaphore = parentSemaphore ?: return true
            val deadlineNanos = clock() + nanos(limits.concurrencyWaitMillis)
            while (true) {
                parentController.throwIfCancelled()
                stopped.get()?.let { return false }
                val remainingNanos = deadlineNanos - clock()
                if (remainingNanos <= 0L) {
                    stop(Termination.CONCURRENCY_WAIT)
                    return false
                }
                val waitMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos)
                    .coerceIn(1L, CONCURRENCY_POLL_MILLIS)
                val acquired = try {
                    semaphore.tryAcquire(waitMillis, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw AgentRunCancelledException()
                }
                if (acquired) {
                    slotHeld.set(true)
                    return true
                }
            }
        }

        /**
         * 武装总运行时长看门狗；名额抢到、子代理真正开始跑之后调用。
         *
         * 这是**墙上时间**硬上限：暂停、排队等并发名额之后的执行、工具耗时、模型耗时统统计入，
         * 不因用户暂停而顺延（界面按 walltime 展示这个额度）。
         */
        fun start() {
            stopped.get()?.let { throw AgentExecutionBudgetStopException(it) }
            val executor = timers ?: return
            if (!limits.runTimeoutMillis.isBudgetFinite()) return
            val task = try {
                executor.schedule(
                    { stop(Termination.RUN_TIMEOUT) },
                    limits.runTimeoutMillis,
                    TimeUnit.MILLISECONDS,
                )
            } catch (_: RejectedExecutionException) {
                return
            }
            synchronized(lock) {
                if (closed) task.cancel(false) else runTask = task
            }
        }

        /** 记一次模型调用尝试（重试也算），超出总额度即终止；调用方在发请求前调用。 */
        fun beforeModelAttempt() {
            stopped.get()?.let { throw AgentExecutionBudgetStopException(it) }
            val used = modelAttempts.incrementAndGet()
            if (used > limits.maxModelCalls) {
                stop(Termination.MODEL_CALLS)
                throw AgentExecutionBudgetStopException(Termination.MODEL_CALLS)
            }
        }

        /** 记一次工具调用，超出总额度即终止；在真正执行工具之前调用。 */
        fun beforeToolCall() {
            stopped.get()?.let { throw AgentExecutionBudgetStopException(it) }
            val used = toolCalls.incrementAndGet()
            if (used > limits.maxToolCalls) {
                stop(Termination.TOOL_CALLS)
                throw AgentExecutionBudgetStopException(Termination.TOOL_CALLS)
            }
        }

        /** 工具返回后复核：运行时长可能在工具执行期间耗尽，此时必须立刻停下。 */
        fun afterToolCall() {
            stopped.get()?.let { throw AgentExecutionBudgetStopException(it) }
            childController.throwIfCancelled()
        }

        /**
         * 预算内的工具调用：先记账，再在**子代理本地**的守护线程上有界等待，最后复核终止状态。
         *
         * 为什么不是直接在子代理循环线程上调用共享的工具执行器：预算终止只能取消子控制器，
         * 而共享执行器里的工具（终端、浏览器、GUI）不一定响应控制器取消；
         * 一旦工具卡住，子代理循环线程就会一直挂着，父任务也拿不回结果。
         * 这里把调用放到本子代理独占的 daemon 线程上，并把 `FutureTask.cancel(true)` 绑到
         * 子控制器取消上：预算命中或用户单停时，等待方立刻返回，不需要关掉共享执行器
         * （那样会波及其它兄弟子代理与父 run 的工具）。
         *
         * 边界与已知限制：
         * - 命中取消后**不会**读取工具结果，晚到的返回值只留在已放弃的 Future 里，无法复活状态；
         * - `cancel(true)` 只是线程中断：不响应中断的工具可能继续在后台跑完（daemon 线程，
         *   随进程结束回收）。本类**不承诺**能杀死这种工具，只保证不再阻塞父任务；
         * - 正常返回时 worker 线程自然结束，不会留下线程池或常驻线程。
         */
        fun <T> runTool(block: () -> T): T {
            beforeToolCall()
            val result = runToolBounded(block)
            afterToolCall()
            return result
        }

        private fun <T> runToolBounded(block: () -> T): T =
            runOnWorker(TOOL_THREAD_NAME, block)

        /**
         * 在子代理本地 daemon 线程上跑一次阻塞调用，并在调用线程上有界等待。
         *
         * 工具与模型请求共用这一套机制：停止只有两个来源——子控制器取消（预算命中／父 run 取消／
         * 界面单停）→ `FutureTask.cancel(true)`，以及调用线程自身被中断。等待方每
         * [WORKER_POLL_MILLIS] 复核一次终止状态，因此取消后最多多等一个轮询周期。
         * 共享执行器（工具路由 / provider 连接池）一律不关，兄弟子代理与父 run 不受影响。
         */
        private fun <T> runOnWorker(
            threadName: String,
            block: () -> T,
            onStarted: (FutureTask<T>) -> Unit = {},
        ): T {
            stopped.get()?.let { throw AgentExecutionBudgetStopException(it) }
            if (parentController.isCancelled) throw AgentRunCancelledException()
            childController.throwIfCancelled()
            val task = FutureTask(block)
            // 子控制器取消（预算命中 / 父 run 取消 / 界面单停）→ 立刻中断 worker。
            val binding = childController.register { task.cancel(true) }
            try {
                Thread(task, threadName).apply {
                    isDaemon = true
                    start()
                }
                onStarted(task)
                return awaitWorker(task)
            } finally {
                binding.close()
                // 不响应中断的调用可能仍在后台跑（daemon）；这里只保证句柄与绑定不泄漏。
                if (!task.isDone) task.cancel(true)
            }
        }

        private fun <T> awaitWorker(task: FutureTask<T>): T {
            while (true) {
                try {
                    return task.get(WORKER_POLL_MILLIS, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    // 继续有界等待；停止由控制器取消 → binding → cancel(true) 驱动。
                } catch (_: CancellationException) {
                    throw cancellationFor()
                } catch (failure: ExecutionException) {
                    val cause = failure.cause ?: failure
                    // 取消引发的失败（含被中断打断的阻塞调用）统一按取消归类，
                    // 不要冒成普通工具错误或可重试的传输错误。
                    if (stopped.get() != null) throw cancellationFor()
                    if (childController.isCancelled || parentController.isCancelled) {
                        throw AgentRunCancelledException()
                    }
                    throw cause
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    task.cancel(true)
                    throw AgentRunCancelledException()
                }
                stopped.get()?.let { throw AgentExecutionBudgetStopException(it) }
                if (childController.isCancelled || parentController.isCancelled) {
                    throw AgentRunCancelledException()
                }
            }
        }

        private fun cancellationFor(): RuntimeException =
            stopped.get()?.let { AgentExecutionBudgetStopException(it) } ?: AgentRunCancelledException()

        /**
         * 包住一次阻塞式模型请求。
         *
         * 请求跑在本子代理独占的 daemon 线程上：供应商即使忽略控制器取消（不关连接、不检查
         * 取消点），等待方也会在取消后立刻返回，父任务不会被一条卡死的 HTTP 拖住。
         * 两条独立看门狗（请求总时长、有意义进度空闲）命中时取消子控制器，取消会同时
         * abort 底层连接并打断请求线程。暂停不计入请求看门狗；总运行时长仍是含暂停的
         * 墙上时间硬上限，见 [start]。
         *
         * 同一子代理同一时刻只有一个请求 worker：嵌套调用（同一线程在请求内部再发请求）
         * 复用外层 worker，不另起线程。
         */
        fun <T> guardRequest(block: () -> T): T {
            stopped.get()?.let { throw AgentExecutionBudgetStopException(it) }
            if (parentController.isCancelled) throw AgentRunCancelledException()
            childController.throwIfCancelled()
            if ((guardDepth.get() ?: 0) > 0) {
                // 同一线程已在受控请求里：沿用外层 worker 与看门狗，避免出现第二个请求线程。
                return verified(block())
            }
            armRequestDeadlines()
            guardDepth.set(1)
            try {
                val result = runOnWorker(REQUEST_THREAD_NAME, block) { task ->
                    // 上一次请求的 worker 可能仍未退出（不响应中断的供应商）：先打断再替换，
                    // 保证任一时刻只有一个请求线程在等结果。
                    requestWorker.getAndSet(task)?.let { stale -> if (!stale.isDone) stale.cancel(true) }
                }
                return verified(result)
            } finally {
                guardDepth.remove()
                disarmRequestDeadlines()
                // 不要长期持有已完成请求的 FutureTask（它连着整段请求与响应）。
                requestWorker.get()?.takeIf { it.isDone }?.let { done ->
                    requestWorker.compareAndSet(done, null)
                }
            }
        }

        /** 武装请求看门狗（只在本子代理有请求时限时）。 */
        private fun armRequestDeadlines() {
            val executor = timers ?: return
            if (!limits.hasRequestTimeLimits) return
            val startedAt = clock()
            synchronized(lock) {
                requestStartedNanos = startedAt
                lastProgressNanos = startedAt
                pauseStartedNanos = null
                requestTask?.cancel(false)
                requestTask = try {
                    executor.scheduleWithFixedDelay(
                        { checkRequestDeadlines() },
                        tickMillis,
                        tickMillis,
                        TimeUnit.MILLISECONDS,
                    )
                } catch (_: RejectedExecutionException) {
                    null
                }
            }
        }

        private fun disarmRequestDeadlines() {
            synchronized(lock) {
                requestTask?.cancel(false)
                requestTask = null
            }
        }

        /** 任何“成功”返回前都要复核：预算已终止或子控制器已取消时，结果一律作废。 */
        private fun <T> verified(result: T): T {
            stopped.get()?.let { throw AgentExecutionBudgetStopException(it) }
            if (childController.isCancelled) throw AgentRunCancelledException()
            return result
        }

        /**
         * 收到一个 ProviderEvent 时调用。
         *
         * 只有真实进度才算进度：请求开始标记、纯用量分片、空的增量分片都属于传输层心跳，
         * 不能用来把一个卡死的请求续命（否则服务端只要发 ping 就永远不超时）。
         */
        fun noteProviderEvent(event: ProviderEvent) {
            if (!isProgressEvent(event)) return
            val now = clock()
            synchronized(lock) {
                if (requestTask != null) lastProgressNanos = now
            }
        }

        override fun close() {
            synchronized(lock) {
                if (closed) return
                closed = true
                requestTask?.cancel(false)
                requestTask = null
                runTask?.cancel(false)
                runTask = null
            }
            timers?.shutdownNow()
            if (slotHeld.compareAndSet(true, false)) {
                parentSemaphore?.release()
            }
            runs.remove(childController, this)
        }

        private fun checkRequestDeadlines() {
            if (closed || stopped.get() != null) return
            // 父 run 已取消时由父的清理路径接手；子控制器已被别人停下时也不必再补刀。
            if (parentController.isCancelled || childController.isCancelled) return
            val now = clock()
            val hit = synchronized(lock) {
                if (requestTask == null) return
                if (childController.isPaused) {
                    if (pauseStartedNanos == null) pauseStartedNanos = now
                    return
                }
                pauseStartedNanos?.let { pausedAt ->
                    val pausedFor = (now - pausedAt).coerceAtLeast(0L)
                    requestStartedNanos += pausedFor
                    lastProgressNanos += pausedFor
                    pauseStartedNanos = null
                }
                when {
                    limits.requestTimeoutMillis.isBudgetFinite() &&
                        now - requestStartedNanos >= nanos(limits.requestTimeoutMillis) ->
                        Termination.REQUEST_TIMEOUT
                    limits.idleTimeoutMillis.isBudgetFinite() &&
                        now - lastProgressNanos >= nanos(limits.idleTimeoutMillis) ->
                        Termination.IDLE_TIMEOUT
                    else -> null
                }
            }
            if (hit != null) stop(hit)
        }

        private fun stop(kind: Termination) {
            if (!stopped.compareAndSet(null, kind)) return
            // 只停这个子代理：父 run 与其它子代理的控制器都不在这里。
            runCatching { childController.cancel() }
            synchronized(lock) {
                requestTask?.cancel(false)
                requestTask = null
                runTask?.cancel(false)
                runTask = null
            }
        }
    }

    companion object {
        /** 不设限：老调用方与既有测试的默认值，行为与引入预算前一致。 */
        const val UNBOUNDED_MILLIS: Long = Long.MAX_VALUE
        const val UNBOUNDED_COUNT: Int = Int.MAX_VALUE
        const val DEFAULT_CONCURRENCY_WAIT_MILLIS: Long = 30_000L

        private const val CONCURRENCY_POLL_MILLIS: Long = 50L

        /** worker 等待轮询周期：取消后最多多等这么久就返回。 */
        private const val WORKER_POLL_MILLIS: Long = 50L
        private const val TIMER_THREAD_NAME = "eta-subagent-budget"
        private const val TOOL_THREAD_NAME = "eta-subagent-tool"
        private const val REQUEST_THREAD_NAME = "eta-subagent-request"

        /** 子控制器 → 运行体；模型重试器靠它找到「自己这次子代理」的预算。 */
        private val runs = ConcurrentHashMap<AgentRunController, ChildRun>()

        /** 父控制器 → 并发信号量；父 run 结束后随弱引用回收，不需要手动清理。 */
        private val parentSemaphores: MutableMap<AgentRunController, Semaphore> =
            Collections.synchronizedMap(WeakHashMap())

        private val DISABLED = AgentExecutionBudget(Limits())

        /**
         * 设置页快照 → 运行时预算；归一化由 [SubagentBudgetSettings.normalized] 保证。
         *
         * 并发排队上限跟随本次 run 的总时长预算（同一份额度），而不是固定 30 秒：
         * 超过并发上限的 fanout 是**正常用法**，排队里的子代理应当等到名额空出来再干，
         * 而不是在 30 秒后被判成预算失败。排队本身仍受父 run 取消与这笔预算约束。
         */
        fun fromSettings(settings: SubagentBudgetSettings): AgentExecutionBudget {
            val normalized = settings.normalized
            return AgentExecutionBudget(
                Limits(
                    runTimeoutMillis = normalized.runTimeoutMillis,
                    requestTimeoutMillis = normalized.requestTimeoutMillis,
                    idleTimeoutMillis = normalized.idleTimeoutMillis,
                    maxModelCalls = normalized.maxModelCalls,
                    maxToolCalls = normalized.maxToolCalls,
                    maxConcurrent = normalized.maxConcurrent,
                    concurrencyWaitMillis = normalized.runTimeoutMillis,
                ),
            )
        }

        /** 无预算模式：没有定时线程、没有并发名额、没有次数上限。 */
        fun disabled(): AgentExecutionBudget = DISABLED

        /** 该控制器所属子代理的运行体；父 run 或未登记控制器返回 null（即不受预算约束）。 */
        fun forController(controller: AgentRunController): ChildRun? = runs[controller]

        /** 该控制器是否已被预算终止，以及终止原因；给上层状态展示用。 */
        fun terminationFor(controller: AgentRunController): Termination? =
            runs[controller]?.termination

        /**
         * 事件是否代表真实进度。
         *
         * 传输层心跳（`RequestStarted`、纯 `Usage` 分片、空 `BlockDelta`）不算进度：
         * 它们可以在服务端什么都没产出时反复到来。
         */
        fun isProgressEvent(event: ProviderEvent): Boolean = when (event) {
            ProviderEvent.RequestStarted -> false
            is ProviderEvent.Usage -> false
            is ProviderEvent.BlockDelta -> event.delta.isNotEmpty()
            else -> true
        }

        private fun parentSemaphoreFor(
            controller: AgentRunController,
            permits: Int,
        ): Semaphore = synchronized(parentSemaphores) {
            parentSemaphores.getOrPut(controller) { Semaphore(permits, true) }
        }

        private fun newTimer(): ScheduledThreadPoolExecutor =
            ScheduledThreadPoolExecutor(1) { runnable ->
                Thread(runnable, TIMER_THREAD_NAME).apply { isDaemon = true }
            }.apply {
                removeOnCancelPolicy = true
                executeExistingDelayedTasksAfterShutdownPolicy = false
            }

        private fun tickMillisFor(limits: Limits): Long {
            val smallest = listOf(
                limits.requestTimeoutMillis,
                limits.idleTimeoutMillis,
            ).filter { it.isBudgetFinite() }.minOrNull() ?: return 250L
            return (smallest / 4).coerceIn(25L, 250L)
        }
    }
}

/** 超时毫秒数超过这个值视为不设限，也避免换算纳秒时溢出。 */
private const val MAX_TIMEOUT_MILLIS: Long = Long.MAX_VALUE / 1_000_000L

private fun Long.isBudgetFinite(): Boolean = this in 1..MAX_TIMEOUT_MILLIS

private fun nanos(millis: Long): Long =
    if (millis >= MAX_TIMEOUT_MILLIS) Long.MAX_VALUE
    else TimeUnit.MILLISECONDS.toNanos(millis)

/** 预算（而不是用户取消）终止了一次子代理；[termination] 说明命中的是哪条上限。 */
internal class AgentExecutionBudgetStopException(
    val termination: AgentExecutionBudget.Termination,
) : RuntimeException("Subagent 预算终止：${termination.code}（${termination.reasonZh}）")

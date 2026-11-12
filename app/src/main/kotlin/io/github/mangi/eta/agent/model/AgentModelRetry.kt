package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentExecutionBudget
import io.github.mangi.eta.agent.runtime.AgentRunController

/** 重试只包围模型请求；完整响应返回前不提交历史或执行本地工具。 */
internal class AgentModelRetry(
    private val waitBeforeRetry: (AgentRunController, Long) -> Unit = { controller, delay ->
        controller.awaitRetryDelay(delay)
    },
) {
    data class Result(val round: Int, val response: ProviderResponse)

    fun complete(
        initialRound: Int,
        request: ProviderRequest,
        provider: AgentProviderClient,
        controller: AgentRunController,
        onEvent: (AgentEvent) -> Unit,
        onProviderEvent: (Int, ProviderEvent) -> Unit,
        discardAttemptReasoning: () -> Unit,
    ): Result {
        // 子代理控制器上登记了运行预算；父 run 与未登记的控制器查不到，行为不变。
        val budgetRun = AgentExecutionBudget.forController(controller)
        var round = initialRound
        var retries = 0
        while (true) {
            controller.throwIfCancelled()
            // 每次真正发出的请求（含重试）都记一次，预算是硬的：超了就不再打服务端。
            budgetRun?.beforeModelAttempt()
            onEvent(AgentEvent.RoundStarted(round, request.messages.length()))
            var hostedToolStarted = false
            var callbackFailed = false
            try {
                val requestBlock = {
                    provider.complete(request, controller) { event ->
                        // 取消之后仍可能继续到达事件（供应商缓存的尾包）：先过控制器闸门，
                        // 迟到事件不能再更新 tracker、事件流或会话历史。
                        controller.throwIfCancelled()
                        // 只有真实进度事件才重置空闲看门狗，传输层心跳不算。
                        budgetRun?.noteProviderEvent(event)
                        if (event is ProviderEvent.HostedToolStarted) hostedToolStarted = true
                        try {
                            onProviderEvent(round, event)
                        } catch (failure: Exception) {
                            callbackFailed = true
                            throw failure
                        }
                    }
                }
                // 阻塞式请求由独立看门狗（子控制器 + 定时取消）兜住，而不是协程 withTimeout。
                val response = if (budgetRun != null) budgetRun.guardRequest(requestBlock)
                else requestBlock()
                return Result(round, response)
            } catch (failure: Exception) {
                controller.throwIfCancelled()
                if (callbackFailed || Thread.currentThread().isInterrupted) throw failure
                val classified = AgentModelFailure.transport(failure) ?: throw failure
                if (hostedToolStarted) throw AgentModelFailure(
                    classified.code, false, classified.message.orEmpty(), classified,
                )
                if (!classified.retryable) throw classified
                if (retries == MAX_RETRIES) {
                    throw AgentModelFailure(
                        classified.code, false,
                        "${classified.message} 已重试 $MAX_RETRIES 次仍未恢复，已保留此前完成的工具结果。",
                        classified,
                    )
                }
                retries += 1
                val delayMs = BASE_DELAY_MS shl (retries - 1)
                onEvent(AgentEvent.ModelRetryScheduled(round, retries, MAX_RETRIES, delayMs.toInt(), classified.code))
                waitBeforeRetry(controller, delayMs)
                controller.throwIfCancelled()
                // 展示保留失败尝试，模型上下文与最终思考摘要只接纳成功尝试。
                discardAttemptReasoning()
                round += 1
            }
        }
    }

    companion object {
        private const val MAX_RETRIES = 3
        private const val BASE_DELAY_MS = 2_000L
    }
}

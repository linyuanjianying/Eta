package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentExecutionBudget
import io.github.mangi.eta.agent.runtime.AgentExecutionBudgetStopException
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * 压缩请求也要进预算：子代理历史压缩走同一个 provider.complete，不设防的话一次卡死的
 * 压缩请求能绕过请求看门狗，模型调用次数也能被压缩悄悄撑爆。
 */
class AgentContextSummarizerBudgetTest {

    @Test
    fun compactionRequestsCountAgainstTheSubagentModelBudget() {
        val budget = AgentExecutionBudget(AgentExecutionBudget.Limits(maxModelCalls = 1))
        val parent = AgentRunController()
        val child = AgentRunController()
        val run = budget.childRun(parent, child)
        var calls = 0
        try {
            val summarizer = AgentContextSummarizer(
                config = config(),
                provider = provider {
                    calls += 1
                    summaryResponse()
                },
                controller = child,
                roleplay = false,
            )
            assertEquals("摘要", summarizer.summarize(history()))
            assertEquals(1, calls)

            val thrown = assertThrows(AgentExecutionBudgetStopException::class.java) {
                summarizer.summarize(history())
            }
            assertEquals(AgentExecutionBudget.Termination.MODEL_CALLS, thrown.termination)
            assertEquals("超预算后不得再打服务端", 1, calls)
            assertFalse("预算只停子代理", parent.isCancelled)
        } finally {
            run.close()
        }
    }

    @Test
    fun stalledCompactionRequestIsStoppedByRequestWatchdog() {
        val budget = AgentExecutionBudget(
            AgentExecutionBudget.Limits(requestTimeoutMillis = 150, idleTimeoutMillis = 60_000),
        )
        val parent = AgentRunController()
        val child = AgentRunController()
        val run = budget.childRun(parent, child)
        val startedAt = System.nanoTime()
        try {
            val summarizer = AgentContextSummarizer(
                config = config(),
                provider = provider { controller ->
                    // 卡在流式读取上，只在控制器检查点响应取消。
                    while (true) {
                        Thread.sleep(5)
                        controller.throwIfCancelled()
                    }
                    @Suppress("UNREACHABLE_CODE")
                    summaryResponse()
                },
                controller = child,
                roleplay = false,
            )
            val thrown = assertThrows(AgentExecutionBudgetStopException::class.java) {
                summarizer.summarize(history())
            }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            assertEquals(AgentExecutionBudget.Termination.REQUEST_TIMEOUT, thrown.termination)
            assertEquals(AgentExecutionBudget.Termination.REQUEST_TIMEOUT, run.termination)
            assertEquals(
                AgentExecutionBudget.Termination.REQUEST_TIMEOUT,
                AgentExecutionBudget.terminationFor(child),
            )
            assertTrue("压缩请求应有自己的超时，实际 ${elapsedMs}ms", elapsedMs < 3_000)
            assertFalse(parent.isCancelled)
        } finally {
            run.close()
        }
    }

    private fun history() = listOf(AgentModelClient.ConversationMessage("user", "把这一段历史总结一下"))

    private fun config() = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid",
        apiKey = "test-key",
        model = "test-model",
        systemPrompt = "",
    )

    private fun summaryResponse() =
        ProviderResponse(JSONObject().put("content", "摘要").put("finish_reason", "stop"))

    private fun provider(
        action: (AgentRunController) -> ProviderResponse,
    ) = object : AgentProviderClient {
        override val id = "test"
        override val capabilities =
            ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, false, false, false)

        override fun complete(
            request: ProviderRequest,
            runController: AgentRunController,
            onEvent: (ProviderEvent) -> Unit,
        ): ProviderResponse = action(runController)
    }
}

package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.model.format.AgentTraceFormatter
import io.github.mangi.eta.agent.model.provider.AgentModelClient
import io.github.mangi.eta.agent.model.provider.AgentModelFailure
import io.github.mangi.eta.agent.model.provider.AgentModelRetry
import io.github.mangi.eta.agent.model.provider.AgentProviderClient
import io.github.mangi.eta.agent.model.provider.AnthropicEphemeralState
import io.github.mangi.eta.agent.model.provider.EndpointKind
import io.github.mangi.eta.agent.model.provider.ProviderCapabilities
import io.github.mangi.eta.agent.model.provider.ProviderEvent
import io.github.mangi.eta.agent.model.provider.ProviderRequest
import io.github.mangi.eta.agent.model.provider.ProviderRequestPurpose
import io.github.mangi.eta.agent.model.provider.ProviderResponse
import io.github.mangi.eta.agent.model.provider.gemini.GeminiEphemeralState
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.ipc.AgentEvent
import java.io.IOException
import java.util.concurrent.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentRequestCompactionTest {
    private val config = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = "规则",
        autoCompactionEnabled = false, requestCompactionEnabled = true,
    )

    @Test
    fun defaultIsDisabledWithInterval95AndDoesNotCompactRetainedHistory() {
        val defaults = AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = "规则",
        )
        assertFalse(defaults.requestCompactionEnabled)
        assertEquals(95, defaults.requestCompactionInterval)
        val messages = messages(100)
        var summaries = 0
        session(messages, defaults, provider { _, _ ->
            summaries++
            response("此前操作已完成。")
        }).compact()
        assertEquals(0, summaries)
        assertEquals(100, count(messages))
    }

    @Test
    fun request94DoesNotTriggerBut95DoesAndOnlyCommitsAfterCheckpoint() {
        val messages = messages(94)
        var summaries = 0
        var originalAtCommit = ""
        var checkpoints = 0
        val session = session(messages, provider = provider { _, _ ->
            summaries++
            response("此前操作已完成。")
        }, onSnapshot = { candidate ->
            if (checkpoints++ == 0) assertTrue("候选快照必须先提交，不能先替换历史", originalAtCommit == messages.toString())
            assertTrue(candidate.messages.any { it.contextSummary })
        })
        session.compact()
        assertEquals(0, summaries)
        messages.put(assistant("第 95 个请求"))
        originalAtCommit = messages.toString()
        session.compact()
        assertEquals(1, summaries)
        assertEquals(0, count(messages))
        assertNotNull(session.snapshot())
        session.compact()
        assertEquals("摘要本身不能成为下次触发的请求", 1, summaries)
    }

    @Test
    fun requestCompactionWorksWithoutUsageOrKnownWindowIndependentlyOfTokenSwitch() {
        for (window in listOf(null, 0, -1)) {
            for (tokenEnabled in listOf(false, true)) {
                val messages = messages(95)
                var summaries = 0
                session(messages, config.copy(contextWindow = window, autoCompactionEnabled = tokenEnabled), provider { _, _ ->
                    summaries++
                    response("此前操作已完成。")
                }).compact()
                assertEquals(1, summaries)
                assertEquals(0, count(messages))
            }
        }
    }

    @Test
    fun tokenAndRequestSwitchesFormIndependentOrConditions() {
        for (tokenEnabled in listOf(false, true)) {
            for (requestEnabled in listOf(false, true)) {
                var summaries = 0
                val session = session(messages(95), config.copy(
                    autoCompactionEnabled = tokenEnabled, requestCompactionEnabled = requestEnabled, contextWindow = 1000,
                ), provider { _, _ ->
                    summaries++
                    response("此前操作已完成。")
                })
                session.observeInputTokens(850)
                session.compact()
                assertEquals(if (tokenEnabled || requestEnabled) 1 else 0, summaries)
            }
        }
    }

    @Test
    fun invalidIntervalsFallBackTo95RatherThanCompactingEveryRequest() {
        for (interval in listOf(0, -1, Int.MIN_VALUE)) {
            val messages = messages(94)
            var summaries = 0
            val session = session(messages, config.copy(requestCompactionInterval = interval), provider { _, _ ->
                summaries++
                response("此前操作已完成。")
            })
            session.compact()
            assertEquals(0, summaries)
            messages.put(assistant("第 95 个请求"))
            session.compact()
            assertEquals(1, summaries)
        }
    }

    @Test
    fun onlyAssistantResponsesAfterLatestSummaryCountAndSystemPrefixIsExcluded() {
        val messages = messages(5)
        messages.put(summary("旧摘要"))
        messages.put(assistant("摘要后的第一次响应"))
        messages.put(summary("最新摘要"))
        messages.put(AgentConversationCodec.userTextMessage("新用户消息"))
        messages.put(assistant("一次响应可包含多个工具", "a", "b"))
        messages.put(toolResult("a"))
        messages.put(toolResult("b"))
        messages.put(AgentConversationCodec.userTextMessage("插话不计数"))
        messages.put(AgentConversationCodec.userTextMessage("截图观察").put("_eta_observation", true))
        assertEquals(1, count(messages))
        assertEquals(0, AgentRequestCompactionPolicy.completedRequests(JSONArray().put(assistant("前缀")), 1))
        assertEquals(0, AgentRequestCompactionPolicy.completedRequests(JSONArray(), 0))
    }

    @Test
    fun localGreetingIsExcludedWithoutRequiringIdsOnLegacyAssistantResponses() {
        // AgentAppState.startCharacterConversation 创建此本地消息，没有对应的 CHAT 请求。
        val history = listOf(
            AgentModelClient.ConversationMessage("assistant", "你好，欢迎来到故事。", messageId = "greeting-fixture-conversation"),
            AgentModelClient.ConversationMessage("assistant", "没有 ID 的旧版真实回答"),
            AgentModelClient.ConversationMessage("assistant", "其他旧版 ID 的真实回答", messageId = "legacy-reply"),
            AgentModelClient.ConversationMessage("assistant", "当前真实回答", messageId = "assistant-fixture-1"),
        )
        val restored = AgentConversationCodec.decodeTranscript(AgentConversationCodec.encodeTranscriptForStorage(history))
        val messages = messages(restored)
        assertEquals("greeting-fixture-conversation", messages.getJSONObject(1).getString("_eta_message_id"))
        assertEquals(3, count(messages))
        assertEquals(history, AgentConversationCodec.transcript(messages, 1).dropLast(1))
    }

    @Test
    fun productionLoopDoesNotCountGreetingBeforeFirstOr95thSuccessfulChat() {
        for ((interval, previousRequests) in listOf(1 to 0, 95 to 93, 95 to 94)) {
            val history = listOf(AgentModelClient.ConversationMessage(
                "assistant", "你好，欢迎来到故事。", messageId = "greeting-fixture-conversation",
            )) + (1..previousRequests).map {
                AgentModelClient.ConversationMessage("assistant", "此前真实回答 $it ".repeat(40))
            }
            val restored = AgentConversationCodec.decodeTranscript(AgentConversationCodec.encodeTranscriptForStorage(history))
            val messages = messages(restored)
            val purposes = mutableListOf<ProviderRequestPurpose>()
            var chats = 0
            val loop = loop(messages, config.copy(requestCompactionInterval = interval), provider { request, _ ->
                purposes += request.purpose
                when (request.purpose) {
                    ProviderRequestPurpose.CHAT -> {
                        assertEquals("开场白不能提前触发摘要", listOf(ProviderRequestPurpose.CHAT), purposes)
                        assertEquals(previousRequests, count(request.messages))
                        assertEquals("greeting-fixture-conversation", request.messages.getJSONObject(1).getString("_eta_message_id"))
                        chats++
                        response("本轮真实回答。".repeat(100))
                    }
                    ProviderRequestPurpose.COMPACTION -> {
                        assertEquals("摘要只能发生在成功接纳本轮 CHAT 之后", 1, chats)
                        assertEquals(interval, count(messages))
                        response("故事对话已完成。")
                    }
                    else -> error("不应发起其他用途请求")
                }
            })
            assertEquals("本轮真实回答。".repeat(100), loop.run().content)
            assertEquals(1, chats)
            val reachedInterval = previousRequests + 1 >= interval
            assertEquals(if (reachedInterval) listOf(ProviderRequestPurpose.CHAT, ProviderRequestPurpose.COMPACTION)
                else listOf(ProviderRequestPurpose.CHAT), purposes)
            assertEquals(if (reachedInterval) 0 else previousRequests + 1, count(messages))
            assertEquals(reachedInterval, loop.contextSnapshot() != null)
        }
    }

    @Test
    fun codecAndSnapshotsCarryCountAcrossRunsWithoutSeparateCounterOrLosingJournal() {
        val config = config.copy(requestCompactionInterval = 3)
        var history = emptyList<AgentModelClient.ConversationMessage>()
        val journal = mutableListOf<AgentModelClient.ConversationMessage>()
        var summaries = 0
        for (turn in 1..4) {
            val messages = messages(history, "问题 $turn")
            val transcript = JSONArray()
            val loop = loop(messages, config, provider { request, _ ->
                if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                    summaries++
                    response("此前操作已完成。")
                } else response("回答 $turn ".repeat(200))
            }, transcript = transcript, operationId = "run-$turn")
            loop.run()
            journal += AgentModelClient.buildUserHistoryMessage("问题 $turn", emptyList())
            journal += AgentConversationCodec.transcript(transcript, 0)
            val snapshot = loop.contextSnapshot()?.let { AgentContextSnapshot.decode(it.encode())!! }
            history = snapshot?.messages ?: AgentConversationCodec.transcript(messages, 1)
            history = AgentConversationCodec.decodeTranscript(AgentConversationCodec.encodeTranscriptForStorage(history))
            assertEquals(listOf(1, 2, 0, 1)[turn - 1], count(messages(history)))
            assertEquals(if (turn < 3) 0 else 1, summaries)
        }
        assertEquals(4, journal.count { it.role == "assistant" })
        assertTrue(journal.none { it.contextSummary })
        assertTrue(journal.any { it.content.startsWith("回答 1") })
        assertTrue(history.any { it.contextSummary })
    }

    @Test
    fun multipleToolResultsDoNotCountAsExtraRequests() {
        val purposes = mutableListOf<ProviderRequestPurpose>()
        val executed = mutableListOf<String>()
        val messages = messages(1)
        val loop = loop(messages, config.copy(requestCompactionInterval = 3), provider { request, _ ->
            purposes += request.purpose
            when (purposes.size) {
                1 -> ProviderResponse(assistant("查询", "a", "b").put("finish_reason", "tool_calls"))
                2 -> {
                    assertEquals(listOf("a", "b"), executed)
                    assertEquals(listOf("assistant", "tool", "tool"),
                        (request.messages.length() - 3 until request.messages.length()).map {
                            request.messages.getJSONObject(it).optString("role")
                        })
                    response("完成查询")
                }
                else -> {
                    assertEquals(ProviderRequestPurpose.COMPACTION, request.purpose)
                    response("已完成查询。")
                }
            }
        }, toolExecutor = AgentModelClient.ToolExecutor { call ->
            executed += call.id
            AgentModelClient.ToolResult("真实结果 ${call.id}")
        })
        assertEquals("完成查询", loop.run().content)
        assertEquals(listOf(ProviderRequestPurpose.CHAT, ProviderRequestPurpose.CHAT, ProviderRequestPurpose.COMPACTION), purposes)
        assertEquals(0, count(messages))
    }

    @Test
    fun countCompactionAtToolBoundaryKeepsLatestUserAndUnconsumedObservation() {
        val purposes = mutableListOf<ProviderRequestPurpose>()
        val messages = messages(1)
        val loop = loop(messages, config.copy(requestCompactionInterval = 2), provider(toolResultImages = false) { request, _ ->
            purposes += request.purpose
            when (purposes.size) {
                1 -> ProviderResponse(assistant("查询", "a", "b").put("finish_reason", "tool_calls"))
                2 -> {
                    assertEquals(ProviderRequestPurpose.COMPACTION, request.purpose)
                    assertFalse(request.messages.toString().contains("PRIVATE_OBSERVATION"))
                    assertTrue(request.messages.toString().contains("真实结果 a"))
                    assertTrue(request.messages.toString().contains("真实结果 b"))
                    response("已完成查询。")
                }
                else -> {
                    assertEquals(ProviderRequestPurpose.CHAT, request.purpose)
                    assertTrue(request.messages.toString().contains("当前请求"))
                    assertTrue(request.messages.toString().contains("PRIVATE_OBSERVATION"))
                    response("完成查询")
                }
            }
        }, toolExecutor = AgentModelClient.ToolExecutor { call ->
            AgentModelClient.ToolResult("真实结果 ${call.id}", images = if (call.id == "a") listOf(
                AgentModelClient.ModelImage("data:image/png;base64,PRIVATE_OBSERVATION", "image/png", 10),
            ) else emptyList())
        })
        assertEquals("完成查询", loop.run().content)
        assertEquals(listOf(ProviderRequestPurpose.CHAT, ProviderRequestPurpose.COMPACTION, ProviderRequestPurpose.CHAT), purposes)
        assertEquals(1, count(messages))
        assertFalse(loop.contextSnapshot()!!.messages.toString().contains("PRIVATE_OBSERVATION"))
    }

    @Test
    fun failedNetworkAttemptsDoNotCountAsSuccessfulChatResponses() {
        val messages = messages(93)
        var attempts = 0
        val transcript = JSONArray()
        loop(messages, config, provider { request, _ ->
            assertEquals(ProviderRequestPurpose.CHAT, request.purpose)
            if (++attempts == 1) throw AgentModelFailure.incompleteStream("fixture")
            response("完成")
        }, transcript = transcript).run()
        assertEquals(2, attempts)
        assertEquals(1, transcript.length())
        assertEquals(94, count(messages))
    }

    @Test
    fun replyRewriteAndOtherNonChatRequestsNeverTriggerCompaction() {
        for (purpose in listOf(ProviderRequestPurpose.REPLY_REWRITE,
            ProviderRequestPurpose.TITLE_GENERATION, ProviderRequestPurpose.TRANSCRIPT_REFINE)) {
            loop(messages(95), config.copy(requestCompactionInterval = 1), provider { request, _ ->
                assertEquals(purpose, request.purpose)
                response("正文")
            }, purpose = purpose).run()
        }
    }

    @Test
    fun countOnlyFailurePreservesCountAndWaitsForNewAssistantBeforeRetrying() {
        val messages = messages(95)
        val original = messages.toString()
        val events = mutableListOf<AgentEvent>()
        var summaries = 0
        val session = session(messages, provider = provider { _, _ ->
            summaries++
            throw AgentModelFailure("MODEL_TIMEOUT", true, "fixture")
        }, events = events)
        session.compact()
        session.compact()
        assertTrue("原始上下文必须保留", original == messages.toString())
        assertEquals(95, count(messages))
        assertEquals(1, summaries)
        assertNull(session.snapshot())
        messages.put(AgentConversationCodec.userTextMessage("插话"))
        session.userAppended()
        session.compact()
        session.compact(final = true)
        assertEquals(1, summaries)
        assertEquals(95, count(messages))
        assertEquals(AgentConversationCodec.transcript(messages, 1), session.snapshot()!!.messages)
        messages.put(assistant("新的成功 CHAT"))
        session.compact()
        assertEquals(2, summaries)
        assertEquals(96, count(messages))
        assertEquals(2, events.filterIsInstance<AgentEvent.ContextCompaction>().count { it.phase == "failed" })
    }

    @Test
    fun successfulRetryResetsFailureCooldownForTheNextCompactionCycle() {
        val messages = messages(2)
        var summaries = 0
        val session = session(messages, config.copy(requestCompactionInterval = 2), provider { _, _ ->
            if (++summaries == 1) throw AgentModelFailure("MODEL_TIMEOUT", true, "fixture")
            response("此前操作已完成。")
        })
        session.compact()
        session.compact()
        assertEquals(1, summaries)
        messages.put(assistant("新的成功响应"))
        session.compact()
        assertEquals(2, summaries)
        assertEquals(0, count(messages))
        messages.put(assistant("新周期第一次响应".repeat(100)))
        session.compact()
        assertEquals(2, summaries)
        messages.put(assistant("新周期第二次响应".repeat(100)))
        session.compact()
        assertEquals(3, summaries)
        assertEquals(0, count(messages))
    }

    @Test
    fun simultaneousTokenAndCountTriggerKeepsStrictFailurePolicy() {
        val messages = messages(95)
        val original = messages.toString()
        val session = session(messages, config.copy(autoCompactionEnabled = true, contextWindow = 1000), provider { _, _ ->
            throw AgentModelFailure("MODEL_TIMEOUT", true, "fixture")
        })
        session.observeInputTokens(850)
        assertThrows(AgentModelFailure::class.java) { session.compact() }
        assertTrue("原始上下文必须保留", original == messages.toString())
        assertEquals(95, count(messages))
    }

    @Test
    fun interval1AndNoReductionCannotBlockFollowingChatRuns() {
        var history = emptyList<AgentModelClient.ConversationMessage>()
        var summaries = 0
        var chats = 0
        for (turn in 1..2) {
            val messages = messages(history)
            val loop = loop(messages, config.copy(requestCompactionInterval = 1), provider { request, _ ->
                if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                    summaries++
                    response("摘要")
                } else {
                    chats++
                    response("好")
                }
            })
            assertEquals("好", loop.run().content)
            history = loop.contextSnapshot()!!.messages
            assertTrue(history.none { it.contextSummary })
            assertEquals(turn, count(messages(history)))
        }
        assertEquals(2, chats)
        assertEquals(3, summaries)
    }

    @Test
    fun countOnlyCheckpointFailureDoesNotReplaceHistoryOrResetCountAndChatContinues() {
        val messages = messages(95)
        var checkpoints = 0
        var chats = 0
        val original = AgentConversationCodec.transcript(messages, 1)
        val loop = loop(messages, config, provider { request, _ ->
            if (request.purpose == ProviderRequestPurpose.COMPACTION) response("已完成此前操作。")
            else {
                chats++
                assertEquals(95, count(request.messages))
                response("已完成本轮")
            }
        }, onSnapshot = { candidate ->
            checkpoints++
            assertTrue(candidate.messages.any { it.contextSummary })
            assertEquals(95 + chats, count(messages))
            throw IOException("fixture checkpoint failure")
        })
        assertEquals("已完成本轮", loop.run().content)
        assertEquals(1, chats)
        assertEquals(2, checkpoints)
        assertEquals(96, count(messages))
        assertEquals(original, AgentConversationCodec.transcript(messages, 1).take(original.size))
        val finalSnapshot = loop.contextSnapshot()!!
        assertEquals(AgentConversationCodec.transcript(messages, 1), finalSnapshot.messages)
        assertTrue(finalSnapshot.messages.none { it.contextSummary })
    }

    @Test
    fun cancellationAlwaysPropagatesForCountOnlyCompactionAndKeepsOriginals() {
        for (final in listOf(false, true)) {
            for (directException in listOf(false, true)) {
                val messages = messages(95)
                val original = messages.toString()
                val controller = AgentRunController()
                val session = session(messages, provider = provider { _, _ ->
                    if (directException) throw CancellationException("fixture cancellation")
                    controller.cancel()
                    response("不得提交")
                }, controller = controller, onSnapshot = { fail("取消不得提交") })
                assertThrows(if (directException) CancellationException::class.java else AgentRunCancelledException::class.java) {
                    session.compact(final = final)
                }
                assertTrue("原始上下文必须保留", original == messages.toString())
                assertEquals(95, count(messages))
                assertNull(session.snapshot())
            }
        }
    }

    @Test
    fun interruptedSummaryCannotBecomeASoftCountOnlyFailure() {
        for (final in listOf(false, true)) {
            val messages = messages(95)
            val original = messages.toString()
            val session = session(messages, provider = provider { _, _ -> throw InterruptedException("fixture interruption") })
            try {
                assertThrows(InterruptedException::class.java) { session.compact(final = final) }
                assertTrue(Thread.currentThread().isInterrupted)
                assertTrue("原始上下文必须保留", original == messages.toString())
                assertEquals(95, count(messages))
                assertNull(session.snapshot())
            } finally {
                Thread.interrupted() // 不把中断标记带到其他 JVM 测试。
            }
        }
    }

    @Test
    fun manualAndTokenFailuresRemainStrictEvenAfterCountOnlyFailure() {
        for (tokenTriggered in listOf(false, true)) {
            val messages = messages(95)
            val original = messages.toString()
            var summaries = 0
            val session = session(messages, config.copy(autoCompactionEnabled = true, contextWindow = 1000), provider { _, _ ->
                summaries++
                throw AgentModelFailure("MODEL_TIMEOUT", true, "fixture")
            })
            session.compact() // 仅次数：允许继续，记下失败次数。
            if (tokenTriggered) session.observeInputTokens(850)
            val failure = assertThrows(AgentModelFailure::class.java) { session.compact(force = !tokenTriggered) }
            assertEquals("MODEL_TIMEOUT", failure.code)
            assertEquals(2, summaries)
            assertTrue("原始上下文必须保留", original == messages.toString())
            assertEquals(95, count(messages))
            session.compact(force = !tokenTriggered, final = true)
            assertEquals(3, summaries)
            assertEquals(AgentConversationCodec.transcript(messages, 1), session.snapshot()!!.messages)
        }
    }

    @Test
    fun manualAndTokenSuccessAlsoResetRequestCount() {
        for (tokenTriggered in listOf(false, true)) {
            val messages = messages(94)
            var summaries = 0
            val session = session(messages, config.copy(autoCompactionEnabled = true, contextWindow = 1000), provider { _, _ ->
                summaries++
                response("已完成此前操作。")
            })
            if (tokenTriggered) session.observeInputTokens(850)
            session.compact(force = !tokenTriggered)
            session.compact()
            assertEquals(1, summaries)
            assertEquals(0, count(messages))
        }
    }

    @Test
    fun signedToolChainsDeferCountCompactionWithoutBypassingManualSafety() {
        for (gemini in listOf(false, true)) {
            val messages = messages(95)
            val call = assistant("", "signed")
            if (gemini) GeminiEphemeralState.attachSteps(call, JSONArray().put(
                JSONObject().put("type", "function_call").put("id", "signed"),
            )) else AnthropicEphemeralState.attachContentBlocks(call, JSONArray()
                .put(JSONObject().put("type", "thinking").put("thinking", "").put("signature", "SIGNATURE"))
                .put(JSONObject().put("type", "tool_use").put("id", "signed")
                    .put("name", "get_current_context").put("input", JSONObject())))
            messages.put(call).put(toolResult("signed"))
            val original = messages.toString()
            var summaries = 0
            val session = session(messages, provider = provider { _, _ ->
                summaries++
                response("已完成此前操作。")
            })
            session.compact()
            session.compact(final = true)
            assertEquals(0, summaries)
            assertTrue("原始上下文必须保留", original == messages.toString())
            val failure = assertThrows(AgentModelFailure::class.java) { session.compact(force = true) }
            assertEquals(if (gemini) "GEMINI_THINKING_CONTEXT_LOCKED" else "ANTHROPIC_THINKING_CONTEXT_LOCKED", failure.code)
            messages.put(assistant("签名工具链已完成"))
            session.compact()
            assertEquals(1, summaries)
            assertEquals(0, count(messages))
        }
    }

    private fun session(
        messages: JSONArray,
        config: AgentModelClient.ModelConfig = this.config,
        provider: AgentProviderClient,
        controller: AgentRunController = AgentRunController(),
        events: MutableList<AgentEvent> = mutableListOf(),
        onSnapshot: (AgentContextSnapshot) -> Unit = {},
    ) = AgentContextSession(config, messages, 1, "operation", provider, controller, { emptySet() }, events::add, onSnapshot)

    private fun loop(
        messages: JSONArray,
        config: AgentModelClient.ModelConfig,
        provider: AgentProviderClient,
        transcript: JSONArray = JSONArray(),
        operationId: String = "operation",
        purpose: ProviderRequestPurpose = ProviderRequestPurpose.CHAT,
        onSnapshot: (AgentContextSnapshot) -> Unit = {},
        toolExecutor: AgentModelClient.ToolExecutor = AgentModelClient.ToolExecutor { error("不应执行工具") },
    ) = AgentLoop(
        config, messages, JSONArray().put(JSONObject().put("type", "function").put("function",
            JSONObject().put("name", "get_current_context").put("parameters", JSONObject().put("type", "object")))),
        provider, toolExecutor, AgentRunController(), AgentTraceFormatter(), {},
        modelRetry = AgentModelRetry { _, _ -> }, transcript = transcript, systemCount = 1,
        operationId = operationId, onContextSnapshot = onSnapshot, purpose = purpose,
    )

    private fun count(messages: JSONArray): Int = AgentRequestCompactionPolicy.completedRequests(messages, 1)

    private fun messages(requests: Int): JSONArray = messages((1..requests).map {
        AgentModelClient.ConversationMessage("assistant", "已确认事实 $it ".repeat(40))
    })

    private fun messages(
        history: List<AgentModelClient.ConversationMessage> = emptyList(),
        prompt: String = "当前请求",
    ) = JSONArray().put(JSONObject().put("role", "system").put("content", "规则")).also { messages ->
        history.forEach { messages.put(AgentConversationCodec.toJsonObject(it)) }
        messages.put(AgentConversationCodec.userTextMessage(prompt))
    }

    private fun assistant(content: String, vararg ids: String): JSONObject =
        JSONObject().put("role", "assistant").put("content", content).also { message ->
            if (ids.isNotEmpty()) message.put("tool_calls", JSONArray().also { calls ->
                ids.forEach { id -> calls.put(JSONObject().put("id", id).put("type", "function").put("function",
                    JSONObject().put("name", "get_current_context").put("arguments", "{}"))) }
            })
        }

    private fun toolResult(id: String): JSONObject =
        JSONObject().put("role", "tool").put("tool_call_id", id).put("content", "真实结果 $id")

    private fun summary(content: String): JSONObject = assistant(content).put("_eta_context_summary", true)

    private fun response(content: String): ProviderResponse = ProviderResponse(assistant(content).put("finish_reason", "stop"))

    private fun provider(
        toolResultImages: Boolean = true,
        block: (ProviderRequest, (ProviderEvent) -> Unit) -> ProviderResponse,
    ): AgentProviderClient = object : AgentProviderClient {
        override val id = "fixture"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, toolResultImages, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse =
            block(request, onEvent)
    }
}

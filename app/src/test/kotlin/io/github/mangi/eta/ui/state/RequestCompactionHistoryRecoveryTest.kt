package io.github.mangi.eta.ui.state

import android.app.Application
import android.content.Context
import io.github.mangi.eta.agent.model.AgentContextSnapshot
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentLoop
import io.github.mangi.eta.agent.model.AgentRequestCompactionPolicy
import io.github.mangi.eta.agent.model.format.AgentTraceFormatter
import io.github.mangi.eta.agent.model.provider.AgentModelClient
import io.github.mangi.eta.agent.model.provider.AgentModelRetry
import io.github.mangi.eta.agent.model.provider.AgentProviderClient
import io.github.mangi.eta.agent.model.provider.EndpointKind
import io.github.mangi.eta.agent.model.provider.ProviderCapabilities
import io.github.mangi.eta.agent.model.provider.ProviderEvent
import io.github.mangi.eta.agent.model.provider.ProviderRequest
import io.github.mangi.eta.agent.model.provider.ProviderRequestPurpose
import io.github.mangi.eta.agent.model.provider.ProviderResponse
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.archive.AgentRunCheckpointStore
import io.github.mangi.eta.agent.runtime.archive.AgentRuntimeResultStore
import io.github.mangi.eta.agent.runtime.ipc.AgentEvent
import io.github.mangi.eta.agent.runtime.ipc.AgentRuntimeWire
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.UserMessageUi
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Do not start EtaApp's background database warm-up while closing/reopening Room.
@Config(sdk = [36], application = Application::class)
class RequestCompactionHistoryRecoveryTest {
    private lateinit var context: Context
    private lateinit var conversationId: String
    private val defaults = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid", apiKey = "", model = "fixture",
        systemPrompt = "规则", autoCompactionEnabled = false,
    )
    private val enabledConfig get() = defaults.copy(requestCompactionEnabled = true)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // ResultStore also has a process-local ACK cache; never reuse another test's run IDs.
        conversationId = "request-compaction-${UUID.randomUUID()}"
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    @After
    fun tearDown() {
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    @Test
    fun saved94ResponsesCompactOnlyAfterThe95thChatAndCheckpointSurvivesReopen() {
        assertFalse(defaults.requestCompactionEnabled)
        assertEquals(95, defaults.requestCompactionInterval)
        val restored = saveAndReopen(historyState(94))
        assertEquals(94, successfulRequests(restored.history))
        val runId = "$conversationId-95"
        val pending = appendPrompt(restored, runId)
        val execution = runChat(pending, runId, onRequest = { request ->
            when (request.purpose) {
                ProviderRequestPurpose.CHAT -> assertEquals(94, successfulRequests(request.messages))
                ProviderRequestPurpose.COMPACTION -> assertEquals(95, successfulRequests(request.messages))
                else -> fail("Unexpected request purpose: ${request.purpose}")
            }
        })

        assertEquals(listOf(ProviderRequestPurpose.CHAT, ProviderRequestPurpose.COMPACTION), execution.purposes)
        assertEquals(0, successfulRequests(execution.messages))
        val snapshot = checkNotNull(execution.result.contextSnapshot)
        assertTrue(snapshot.messages.any { it.contextSummary })
        assertEquals(execution.result.transcript.size, checkNotNull(snapshot.consumedTranscriptMessages))
        reopenDatabase()
        val checkpoint = AgentRunCheckpointStore.list(context).single()
        assertEquals(runId, checkpoint.runId)
        assertEquals(snapshot, checkpoint.contextSnapshot)
        assertEquals(execution.result.transcript, checkpoint.transcript)
        assertEquals(0, successfulRequests(checkpoint.contextSnapshot!!.messages))
    }

    @Test
    fun compactedOutboxRecoveryAndConversationReopenDoNotCountTheOriginalJournalAgain() {
        val runId = "$conversationId-95"
        val pending = appendPrompt(saveAndReopen(historyState(94)), runId)
        val execution = runChat(pending, runId)
        assertEquals(listOf(ProviderRequestPurpose.CHAT, ProviderRequestPurpose.COMPACTION), execution.purposes)
        val result = outboxRoundTrip(execution.result)
        assertTrue(result.contextSnapshot!!.messages.any { it.contextSummary })
        val recovered = AgentRuntimeHistoryReducer.apply(pending, runId, result.transcript, result.contextSnapshot)
        assertFalse(recovered.alreadyApplied)
        assertEquals(pending.journal + result.transcript, recovered.state.journal)
        saveAndReopen(recovered.state)
        AgentRuntimeResultStore.remove(context, runId)
        reopenDatabase()
        assertTrue(AgentRuntimeResultStore.list(context).isEmpty())
        assertTrue(AgentRunCheckpointStore.list(context).isEmpty())
        val restored = AgentConversationStore.load(context).conversationsById.getValue(conversationId)
        assertEquals(recovered.state.history, restored.history)
        assertEquals(recovered.state.journal, restored.journal)
        assertEquals(listOf(runId), restored.appliedRuntimeRunIds)
        assertEquals(0, successfulRequests(restored.history))
        assertEquals(95, successfulRequests(restored.journal))

        val nextRunId = "$conversationId-96"
        val nextPending = appendPrompt(restored, nextRunId)
        val next = runChat(nextPending, nextRunId)
        assertEquals("A persisted summary must not trigger a second compaction", listOf(ProviderRequestPurpose.CHAT), next.purposes)
        val nextState = AgentRuntimeHistoryReducer.apply(nextPending, nextRunId, next.result.transcript, next.result.contextSnapshot).state
        assertEquals(1, successfulRequests(saveAndReopen(nextState).history))
        assertEquals(96, successfulRequests(nextState.journal))
    }

    @Test
    fun failedSnapshotCommitKeepsAll95ResponsesThroughOutboxAndConversationRecovery() {
        val runId = "$conversationId-failed-95"
        val pending = appendPrompt(saveAndReopen(historyState(94)), runId)
        var snapshotAttempts = 0
        val execution = runChat(pending, runId, onSnapshot = { candidate ->
            snapshotAttempts++
            assertTrue(candidate.messages.any { it.contextSummary })
            // Fault at the same commit callback used by the runtime, before any replacement is durable.
            throw IOException("fixture: context checkpoint write failed")
        })
        assertTrue(execution.result.ok)
        assertEquals(1, snapshotAttempts)
        assertEquals(listOf(ProviderRequestPurpose.CHAT, ProviderRequestPurpose.COMPACTION), execution.purposes)
        val expected = pending.history + execution.result.transcript
        assertEquals(expected, AgentConversationCodec.transcript(execution.messages, 1))
        assertEquals(95, successfulRequests(execution.messages))
        assertEquals(expected, execution.result.contextSnapshot!!.messages)
        assertTrue(execution.events.filterIsInstance<AgentEvent.ContextCompaction>().any { it.phase == "failed" })
        assertFalse(execution.events.filterIsInstance<AgentEvent.ContextCompaction>().any { it.phase == "completed" })
        reopenDatabase()
        val checkpoint = AgentRunCheckpointStore.list(context).single()
        assertNull(checkpoint.contextSnapshot)
        assertEquals(execution.result.transcript, checkpoint.transcript)

        val result = outboxRoundTrip(execution.result)
        val recovered = AgentRuntimeHistoryReducer.apply(pending, runId, result.transcript, result.contextSnapshot).state
        val restored = saveAndReopen(recovered)
        assertEquals(expected, restored.history)
        assertEquals(expected, restored.journal)
        assertFalse(restored.history.any { it.contextSummary })
        assertEquals(95, successfulRequests(restored.history))
    }

    @Test
    fun duplicateRunAfterConversationReopenCannotAdvance94ResponsesTo95() {
        val runId = "$conversationId-94"
        val pending = appendPrompt(saveAndReopen(historyState(93)), runId)
        val execution = runChat(pending, runId)
        assertEquals(listOf(ProviderRequestPurpose.CHAT), execution.purposes)
        assertNull(execution.result.contextSnapshot)
        val result = outboxRoundTrip(execution.result)
        val first = AgentRuntimeHistoryReducer.apply(pending, runId, result.transcript, result.contextSnapshot)
        assertFalse(first.alreadyApplied)
        val restored = saveAndReopen(first.state)
        assertEquals(94, successfulRequests(restored.history))

        // Simulate live delivery arriving after persisted outbox recovery, including another DB reopen.
        reopenDatabase()
        val replayedResult = AgentRuntimeResultStore.list(context).single().result
        val duplicate = AgentRuntimeHistoryReducer.apply(restored, runId, replayedResult.transcript, replayedResult.contextSnapshot)
        assertTrue(duplicate.alreadyApplied)
        assertEquals(restored, duplicate.state)
        val afterReplay = saveAndReopen(duplicate.state)
        assertEquals(94, successfulRequests(afterReplay.history))
        assertEquals(restored.journal, afterReplay.journal)
        assertEquals(listOf(runId), afterReplay.appliedRuntimeRunIds)
    }

    private fun historyState(requests: Int): AgentChatHomeUiState {
        val history = listOf(
            AgentModelClient.ConversationMessage("user", "保留历史任务的约束。", messageId = "user-original"),
            AgentModelClient.ConversationMessage("assistant", "本地开场白。", messageId = "greeting-$conversationId"),
        ) + List(requests) { index ->
            AgentModelClient.ConversationMessage(
                "assistant", "第 ${index + 1} 次请求已确认的事实与结果。".repeat(30), messageId = "assistant-prior-$index",
            )
        }
        return AgentChatHomeUiState(
            messages = emptyList(), history = history, journal = history,
            input = "", isStreaming = false, thinkingEnabled = false,
        )
    }

    private fun appendPrompt(state: AgentChatHomeUiState, runId: String): AgentChatHomeUiState {
        val prompt = AgentModelClient.ConversationMessage("user", "继续下一步。", messageId = "user-$runId")
        return state.copy(
            messages = state.messages + UserMessageUi(prompt.messageId, prompt.content),
            history = state.history + prompt, journal = state.journal + prompt,
        )
    }

    private fun saveAndReopen(state: AgentChatHomeUiState): AgentChatHomeUiState {
        runBlocking {
            AgentConversationStore.save(
                context, conversationId, mapOf(conversationId to state),
                mapOf(conversationId to "请求次数恢复"), mapOf(conversationId to 1L),
            )
        }
        reopenDatabase()
        return AgentConversationStore.load(context).conversationsById.getValue(conversationId).also {
            assertEquals(state.history, it.history)
            assertEquals(state.journal, it.journal)
            assertEquals(state.appliedRuntimeRunIds, it.appliedRuntimeRunIds)
        }
    }

    private fun reopenDatabase() {
        val previous = EtaDatabase.get(context)
        EtaDatabase.closeForTests()
        assertNotSame(previous, EtaDatabase.get(context))
    }

    private fun outboxRoundTrip(result: AgentRuntimeWire.RunResult): AgentRuntimeWire.RunResult {
        assertTrue(AgentRuntimeResultStore.add(context, AgentRuntimeWire.CompletedRun(handoff(result.runId), result, 1L)))
        reopenDatabase()
        return AgentRuntimeResultStore.list(context).single().result.also { assertEquals(result, it) }
    }

    private fun handoff(runId: String) = AgentRuntimeWire.EntryHandoff(
        runId, AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, conversationId,
    )

    private data class Execution(
        val result: AgentRuntimeWire.RunResult,
        val messages: JSONArray,
        val purposes: List<ProviderRequestPurpose>,
        val events: List<AgentEvent>,
    )

    private fun runChat(
        pending: AgentChatHomeUiState,
        runId: String,
        onRequest: (ProviderRequest) -> Unit = {},
        onSnapshot: (AgentContextSnapshot) -> Unit = { AgentRunCheckpointStore.saveContext(context, runId, it) },
    ): Execution {
        assertTrue(AgentRunCheckpointStore.start(context, AgentRuntimeWire.RunRequest(
            runId, pending.history.last().content, enabledConfig, emptyList(),
            history = pending.history.dropLast(1), handoff = handoff(runId),
        )))
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", defaults.systemPrompt))
        pending.history.forEach { messages.put(AgentConversationCodec.toJsonObject(it)) }
        val transcript = JSONArray()
        val purposes = mutableListOf<ProviderRequestPurpose>()
        val events = mutableListOf<AgentEvent>()
        // Only the provider boundary is fake; loop, compactor, codec, reducer and Room stores are real.
        val provider = object : AgentProviderClient {
            override val id = "request-compaction-persistence-fixture"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, false, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                purposes += request.purpose
                onRequest(request)
                val content = when (request.purpose) {
                    ProviderRequestPurpose.CHAT -> "本轮请求已完成。".repeat(30)
                    ProviderRequestPurpose.COMPACTION -> "前序任务已完成，继续保留原始约束。"
                    else -> error("Unexpected request purpose: ${request.purpose}")
                }
                return ProviderResponse(JSONObject().put("role", "assistant").put("content", content).put("finish_reason", "stop"))
            }
        }
        val loop = AgentLoop(
            config = enabledConfig, messages = messages, tools = JSONArray(), provider = provider,
            toolExecutor = AgentModelClient.ToolExecutor { error("No tools expected") },
            runController = AgentRunController(), traceFormatter = AgentTraceFormatter(), onEvent = events::add,
            modelRetry = AgentModelRetry { _, _ -> }, sessionId = conversationId,
            transcript = transcript, systemCount = 1, operationId = runId, onContextSnapshot = onSnapshot,
            onTranscript = { AgentRunCheckpointStore.saveTranscript(context, runId, it) },
        )
        val completed = loop.run()
        return Execution(
            AgentRuntimeWire.RunResult(
                runId, true, completed.content, transcript = AgentConversationCodec.transcript(transcript, 0),
                contextSnapshot = loop.contextSnapshot(),
            ), messages, purposes.toList(), events.toList(),
        )
    }

    private fun successfulRequests(history: List<AgentModelClient.ConversationMessage>): Int =
        successfulRequests(JSONArray().put(JSONObject().put("role", "system")).also { messages ->
            history.forEach { messages.put(AgentConversationCodec.toJsonObject(it)) }
        })

    private fun successfulRequests(messages: JSONArray): Int = AgentRequestCompactionPolicy.completedRequests(messages, 1)
}

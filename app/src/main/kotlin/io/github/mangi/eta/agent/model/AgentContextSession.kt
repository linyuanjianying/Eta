package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.ipc.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.config.Prefs
import java.util.concurrent.CancellationException
import org.json.JSONArray
import io.github.mangi.eta.agent.model.provider.AgentModelClient
import io.github.mangi.eta.agent.model.provider.AgentModelFailure
import io.github.mangi.eta.agent.model.provider.AgentProviderClient

/** 管理可替换的模型上下文；持久快照先提交，运行 transcript 始终追加。 */
internal class AgentContextSession(
    private val config: AgentModelClient.ModelConfig,
    private val messages: JSONArray,
    private val systemCount: Int,
    private val operationId: String,
    private val provider: AgentProviderClient,
    private val runController: AgentRunController,
    private val sensitiveIds: () -> Set<String>,
    private val onEvent: (AgentEvent) -> Unit,
    private val onContextSnapshot: (AgentContextSnapshot) -> Unit,
    private val transcriptSize: () -> Int = { 0 },
    private val roleplay: Boolean = false,
    private val mainToolsProvider: () -> JSONArray = { JSONArray() },
    private val mainSessionId: String = operationId,
    private val messageProjector: ((JSONArray) -> JSONArray)? = null,
    private val enableCacheReuse: Boolean = false,
) {
    private val contextWindow = config.knownContextWindow
    private var inputTokens: Int? = null
    private var compacted = false
    private var consumedSupplementCount = 0
    private var consumedUserTurns = (systemCount until messages.length()).sumOf {
        val message = messages.getJSONObject(it)
        message.optInt("_eta_compacted_users") + if (message.optString("role") == "user") 1 else 0
    }
    private var committedSnapshot: AgentContextSnapshot? = null
    // 次数压缩是尽力而为；失败不清零，仅等待新的成功 CHAT 响应后再尝试。
    private var lastFailedRequestCount: Int? = null

    fun snapshot(): AgentContextSnapshot? = committedSnapshot

    fun observeInputTokens(tokens: Int?) {
        inputTokens = tokens?.takeIf { it >= 0 }
    }

    fun userAppended() {
        consumedUserTurns++
        consumedSupplementCount++
    }

    private fun publishSnapshot(candidate: JSONArray = messages) {
        if (!compacted) return
        val snapshot = createSnapshot(candidate)
        snapshot.encode()
        onContextSnapshot(snapshot)
        committedSnapshot = snapshot
    }

    private fun createSnapshot(candidate: JSONArray): AgentContextSnapshot {
        val history = durableHistory(candidate)
        return AgentContextSnapshot(
            operationId = operationId,
            messages = history,
            coveredUserTurns = history.sumOf { it.compactedUserTurns },
            consumedUserTurns = consumedUserTurns,
            consumedSupplementCount = consumedSupplementCount,
            consumedTranscriptMessages = transcriptSize(),
        )
    }

    fun compact(force: Boolean = false, final: Boolean = false) {
        runController.throwIfCancelled()
        val before = inputTokens
        if (SignedToolContext.hasPendingResponse(messages)) {
            if (force) {
                throw SignedToolContext.compactionFailure(messages)
            }
            return
        }
        val tokenTriggered = config.autoCompactionEnabled && contextWindow != null && before != null &&
            before >= contextWindow * TRIGGER_RATIO
        val requestCount = if (config.requestCompactionEnabled) {
            AgentRequestCompactionPolicy.completedRequests(messages, systemCount)
        } else 0
        val interval = config.requestCompactionInterval.takeIf { it > 0 }
            ?: Prefs.DEFAULT_REQUEST_COMPACTION_INTERVAL
        val requestTriggered = config.requestCompactionEnabled && requestCount >= interval &&
            (lastFailedRequestCount?.let { requestCount > it } ?: true)
        if (!force && !tokenTriggered && !requestTriggered) {
            try {
                publishSnapshot()
                // 同一份历史刚尝试失败时不重复摘要；终态仍携带未经替换的完整上下文。
                if (final && lastFailedRequestCount != null) committedSnapshot = createSnapshot(messages)
            } catch (failure: Exception) {
                rethrowCancellation(failure)
                if (!final) throw failure
                committedSnapshot = createSnapshot(messages)
                onEvent(AgentEvent.ContextCompaction(operationId, "failed", before,
                    reasonCode = "CONTEXT_CHECKPOINT_FAILED"))
            }
            return
        }
        val operation = java.util.UUID.randomUUID().toString()
        onEvent(AgentEvent.ContextCompaction(operation, AgentEvent.ContextCompaction.PHASE_STARTED, before))
        try {
            val outcome = AgentContextCompactor(
                config = config,
                provider = provider,
                controller = runController,
                roleplay = roleplay,
                mainTools = mainToolsProvider(),
                mainSessionId = mainSessionId,
                messageProjector = messageProjector,
                enableCacheReuse = enableCacheReuse,
            ).compactWithOutcome(
                messages, systemCount, sensitiveIds(),
            )
            val candidate = outcome.messages
            runController.throwIfCancelled()
            val wasCompacted = compacted
            compacted = true
            try {
                publishSnapshot(candidate)
            } catch (failure: Exception) {
                compacted = wasCompacted
                throw failure
            }
            while (messages.length() > 0) messages.remove(messages.length() - 1)
            for (index in 0 until candidate.length()) messages.put(candidate.getJSONObject(index))
            inputTokens = null
            lastFailedRequestCount = null
            onEvent(
                AgentEvent.ContextCompaction(
                    operationId = operation,
                    phase = AgentEvent.ContextCompaction.PHASE_COMPLETED,
                    tokensBefore = before,
                    usage = outcome.usage,
                    archiveId = outcome.archiveId,
                ),
            )
        } catch (failure: Exception) {
            rethrowCancellation(failure)
            onEvent(AgentEvent.ContextCompaction(operation, "failed", before,
                reasonCode = (failure as? AgentModelFailure)?.code ?: "CONTEXT_SUMMARY_FAILED"))
            if (!force && !tokenTriggered) {
                lastFailedRequestCount = requestCount
                if (!final) return
            } else if (!final) throw failure
            // 已完成的回答仍成功交付；完整快照随终态 outbox 保存，不依赖先前检查点写入成功。
            committedSnapshot = createSnapshot(messages)
        }
    }

    private fun rethrowCancellation(failure: Exception) {
        runController.throwIfCancelled()
        if (failure is InterruptedException) {
            Thread.currentThread().interrupt()
            throw failure
        }
        if (failure is AgentRunCancelledException || failure is CancellationException ||
            Thread.currentThread().isInterrupted
        ) throw failure
    }

    private fun durableHistory(source: JSONArray): List<AgentModelClient.ConversationMessage> {
        val durable = JSONArray()
        for (index in systemCount until source.length()) {
            val message = source.getJSONObject(index)
            if (!message.optBoolean("_eta_observation")) durable.put(message)
        }
        return AgentConversationCodec.transcript(durable, 0, sensitiveIds())
    }

    private companion object {
        const val TRIGGER_RATIO = 0.85
    }
}

package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray

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
    private val compactionTarget: (() -> AgentCompactionTarget)? = null,
) {
    private val contextWindow = config.knownContextWindow

    /** 目录/服务商声明值：只用于提前触发，不作为硬上限（见 ModelConfig.declaredContextWindow）。 */
    private val declaredWindow = config.declaredContextWindow?.takeIf { it > 0 }
    private var inputTokens: Int? = null
    private var estimatedAtRequest: Int? = null
    private var compacted = false
    private var consumedSupplementCount = 0
    private var consumedUserTurns = (systemCount until messages.length()).sumOf {
        val message = messages.getJSONObject(it)
        message.optInt("_eta_compacted_users") + if (message.optString("role") == "user") 1 else 0
    }
    private var committedSnapshot: AgentContextSnapshot? = null

    fun snapshot(): AgentContextSnapshot? = committedSnapshot

    fun requestStarting() {
        estimatedAtRequest = estimatedTokens()
    }

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
        val before = effectiveInputTokens()
        if (config.signedToolRoundLocked(messages)) {
            if (force) {
                throw SignedToolContext.compactionFailure(messages)
            }
            return
        }
        if (!force && (!config.autoCompactionEnabled || before < triggerThreshold())) {
            try {
                publishSnapshot()
            } catch (failure: Exception) {
                runController.throwIfCancelled()
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
            val target = compactionTarget?.invoke() ?: AgentCompactionTarget(config, provider)
            val candidate = AgentContextCompactor(
                target.config, target.provider, runController,
                roleplay = roleplay, summaryPrompt = target.prompt,
            ).compact(
                messages, systemCount, sensitiveIds(),
            )
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
            estimatedAtRequest = null
            onEvent(AgentEvent.ContextCompaction(operation, AgentEvent.ContextCompaction.PHASE_COMPLETED, before))
        } catch (failure: Exception) {
            runController.throwIfCancelled()
            onEvent(AgentEvent.ContextCompaction(operation, "failed", before,
                reasonCode = (failure as? AgentModelFailure)?.code ?: "CONTEXT_SUMMARY_FAILED"))
            if (!final) throw failure
            // 已完成的回答仍成功交付；完整快照随终态 outbox 保存，不依赖先前检查点写入成功。
            committedSnapshot = createSnapshot(messages)
        }
    }

    private fun durableHistory(source: JSONArray): List<AgentModelClient.ConversationMessage> {
        val durable = JSONArray()
        for (index in systemCount until source.length()) {
            val message = source.getJSONObject(index)
            if (!message.optBoolean("_eta_observation")) durable.put(message)
        }
        return AgentConversationCodec.transcript(durable, 0, sensitiveIds())
    }

    /** Provider 输入用量加本次请求之后新增内容的估算，避免大工具结果落后一轮。 */
    private fun effectiveInputTokens(): Int {
        val estimate = estimatedTokens()
        val actual = inputTokens ?: return estimate
        val baseline = estimatedAtRequest ?: return actual
        return (actual.toLong() + (estimate - baseline).coerceAtLeast(0)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * 逐条累加估算，绝不 `messages.toString()`：
     * 后者每轮都会把整段对话物化成一个巨字符串（历史大时可达数百 MB），
     * 而这个估算有可能每一轮都跑一次——是「继续对话卡死」的直接来源之一。
     */
    private fun estimatedTokens(): Int {
        var chars = 0
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            chars += message.optString("content").length +
                message.optString("reasoning_content").length +
                message.optString("tool_calls").length +
                FIELD_OVERHEAD_CHARS
        }
        return chars / CHARS_PER_TOKEN_ESTIMATE
    }

    /** 人工窗口优先，其次模型声明窗口；都没有时直接使用用户设定的 token 阈值。 */
    internal fun triggerThreshold(): Int {
        val window = contextWindow ?: declaredWindow
        return if (window != null) (window * TRIGGER_RATIO).toInt().coerceAtLeast(1)
        else config.compactionThresholdTokens.takeIf {
            it in io.github.mangi.eta.data.model.CompactionSettings.MIN_THRESHOLD_TOKENS..
                io.github.mangi.eta.data.model.CompactionSettings.MAX_THRESHOLD_TOKENS
        } ?: io.github.mangi.eta.data.model.CompactionSettings.DEFAULT_THRESHOLD_TOKENS
    }

    private companion object {
        const val TRIGGER_RATIO = 0.85
        const val CHARS_PER_TOKEN_ESTIMATE = 4
        const val FIELD_OVERHEAD_CHARS = 64
    }
}

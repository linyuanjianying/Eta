package io.github.mangi.eta.agent.subagent

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject

@Serializable
internal data class SubagentStepSnapshot(
    val id: String,
    val kind: String,
    val title: String = "",
    val detail: String = "",
    val status: String = STATUS_RUNNING,
    val toolName: String = "",
) {
    companion object {
        const val KIND_THINKING = "thinking"
        const val KIND_TOOL = "tool"

        const val STATUS_RUNNING = "running"
        const val STATUS_SUCCESS = "success"
        const val STATUS_FAILED = "failed"

        /** 被中断的步骤：既不是成功也不是失败，UI 一律按静态节点展示，不能继续转圈。 */
        const val STATUS_CANCELLED = "cancelled"
    }
}

/**
 * Subagent 实时与持久化的结构化活动快照。
 *
 * 保存在父级 `subagent` 工具消息的 `command` 字段中，既支持运行中点击实时检视，
 * 也支持会话归档与重启后直接点开回看完整内部过程。
 *
 * 外层工具失败或被中断时，父级消息会被投影器直接改写终态，而快照里可能还残留
 * `running` 步骤与 `running` 状态。此时必须调用 [finishFailed] / [finishInterrupted]
 * 把快照一并收尾，否则详情面板会永远转圈；收尾只改写状态，不清空已经产生的部分
 * 输出与已完成步骤。
 */
@Serializable
internal data class SubagentActivitySnapshot(
    val subagentId: String = "",
    val subagentType: String = SubagentProfile.DEFAULT_TYPE,
    val description: String = "",
    val prompt: String = "",
    val model: String = "",
    val resumed: Boolean = false,
    val status: String = STATUS_RUNNING,
    val currentAction: String = "",
    val steps: List<SubagentStepSnapshot> = emptyList(),
    val output: String = "",
    val error: String? = null,
) {
    val toolStepCount: Int
        get() = steps.count { it.kind == SubagentStepSnapshot.KIND_TOOL }

    /** 仍在转圈的内部步骤数量；终态快照里应始终为 0。 */
    val runningStepCount: Int
        get() = steps.count { it.status == SubagentStepSnapshot.STATUS_RUNNING }

    val isRunning: Boolean
        get() = status == STATUS_RUNNING

    /**
     * 步骤是否可以播放运行动画：外层工具、快照、步骤自身三者都必须处于运行态。
     *
     * 界面单独拿 `step.status == running` 判断会在「外层已失败/中断但快照是旧数据」时
     * 继续转圈，所以把这条防御规则收敛到这里，供 UI 与回归测试共用。
     */
    fun isStepActive(step: SubagentStepSnapshot, outerRunning: Boolean): Boolean =
        outerRunning && isRunning && step.status == SubagentStepSnapshot.STATUS_RUNNING

    fun encode(): String = json.encodeToString(this)

    /**
     * 外层工具失败时的快照收尾：残留运行步骤标记为失败，保留部分输出与已完成步骤。
     * 已经是终态的快照不降级状态，但仍会兜底清掉任何游离的 `running` 步骤。
     */
    fun finishFailed(reason: String = ""): SubagentActivitySnapshot = settle(
        terminalStatus = STATUS_FAILED,
        runningStepStatus = SubagentStepSnapshot.STATUS_FAILED,
        reason = reason,
        fallbackAction = "执行失败",
    )

    /**
     * 外层工具被中断/取消时的快照收尾：残留运行步骤标记为已取消，
     * 保留部分输出与已完成步骤。中断不是失败，不用红色失败态覆盖。
     */
    fun finishInterrupted(reason: String = ""): SubagentActivitySnapshot = settle(
        terminalStatus = STATUS_CANCELLED,
        runningStepStatus = SubagentStepSnapshot.STATUS_CANCELLED,
        reason = reason,
        fallbackAction = "已中断",
    )

    /**
     * 共享的收尾实现：只改状态与文案，`output`、`steps` 的内容一律保留，
     * 让用户在失败/中断后仍能看到子代理已经做过什么、产出了什么。
     */
    private fun settle(
        terminalStatus: String,
        runningStepStatus: String,
        reason: String,
        fallbackAction: String,
    ): SubagentActivitySnapshot {
        val alreadyTerminal = status != STATUS_RUNNING
        val settledSteps = if (runningStepCount == 0) {
            steps
        } else {
            steps.map { step ->
                if (step.status == SubagentStepSnapshot.STATUS_RUNNING) {
                    step.copy(status = runningStepStatus)
                } else {
                    step
                }
            }
        }
        val detail = reason.trim().take(MAX_ERROR_CHARS)
        val settledError = error?.takeIf { it.isNotBlank() }
            ?: detail.takeIf { it.isNotBlank() }
        val settledAction = when {
            alreadyTerminal -> currentAction
            detail.isNotBlank() -> detail.lineSequence().first().trim().take(MAX_ACTION_CHARS)
            else -> fallbackAction
        }
        return copy(
            status = if (alreadyTerminal) status else terminalStatus,
            currentAction = settledAction,
            steps = settledSteps,
            error = settledError,
        )
    }

    companion object {
        const val STATUS_RUNNING = "running"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_FAILED = "failed"
        const val STATUS_CANCELLED = "cancelled"

        private const val MAX_ERROR_CHARS = 1_000
        private const val MAX_ACTION_CHARS = 80

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun decode(raw: String?): SubagentActivitySnapshot? {
            if (raw.isNullOrBlank() || !raw.trimStart().startsWith("{")) return null
            return runCatching { json.decodeFromString<SubagentActivitySnapshot>(raw) }
                .getOrNull()
                ?.takeIf { it.subagentType.isNotBlank() || it.description.isNotBlank() || it.prompt.isNotBlank() }
        }

        fun initialFromArguments(argumentsJson: String): SubagentActivitySnapshot? = runCatching {
            val args = JSONObject(argumentsJson)
            val resumeId = args.optString("resume").trim()
            val rawType = args.optString("subagent_type").trim().lowercase()
            val type = when {
                rawType.isNotEmpty() -> rawType
                resumeId.isNotEmpty() -> "subagent"
                else -> SubagentProfile.DEFAULT_TYPE
            }
            val description = args.optString("description").trim()
                .replace(Regex("\\s+"), " ")
                .take(80)
            val prompt = args.optString("prompt").trim().take(4_000)
            SubagentActivitySnapshot(
                subagentId = resumeId,
                subagentType = type,
                description = description,
                prompt = prompt,
                resumed = resumeId.isNotEmpty(),
                status = STATUS_RUNNING,
                currentAction = "正在启动…",
            )
        }.getOrNull()
    }
}

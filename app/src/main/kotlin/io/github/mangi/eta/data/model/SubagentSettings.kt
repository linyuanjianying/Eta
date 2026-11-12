package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

/**
 * Subagent 模型与思考配置。
 *
 * [modelId] 为空表示跟随主 Agent 当前生效的模型；指定具体 Model.id 且对应服务商与模型仍启用时，
 * Subagent 优先使用该模型运行。
 *
 * [budget] 是 Subagent 的运行预算（时间、调用次数与并发）。它和模型选择一起序列化进
 * `subagent_settings_v1`，因此旧版本 JSON 缺少该字段时会由 DataStore 的
 * `coerceInputValues`/默认值平滑补成默认预算，不需要额外的 prefs key。
 */
@Serializable
internal data class SubagentSettings(
    val modelId: String? = null,
    val reasoningEffort: String? = null,
    val budget: SubagentBudgetSettings = SubagentBudgetSettings(),
) {
    val resolvedReasoningEffort: ReasoningEffort?
        get() = ReasoningEffort.fromWireValue(reasoningEffort)

    /** 归一化后的运行预算：运行时一律读这里，不要直接读 [budget] 的原始字段。 */
    val normalizedBudget: SubagentBudgetSettings
        get() = budget.normalized
}

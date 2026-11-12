package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

/**
 * Subagent 运行预算：一次 Subagent 任务允许消耗的时间、模型/工具调用次数与并发数。
 *
 * 六个字段都带默认值与合法区间；写入 Datastore 前由设置页与
 * `RuntimeConfigRepository.setSubagentBudget` 归一化，读到旧数据或手改坏的 JSON 时
 * 调用方应使用 [normalized]（越界值按区间头尾夹取，并保证 idle ≤ request ≤ 运行总时长）。
 *
 * 字段含义：
 * - [maxRunMinutes]：单次 Subagent 任务的总运行时墙上限（分钟）；
 * - [maxRequestSeconds]：单次模型请求（一次 assemble + 流式返回）的超时（秒）；
 * - [maxIdleSeconds]：连续无任何 token/工具输出的空闲超时（秒），比请求超时更早触发；
 * - [maxModelCalls]：单次任务的模型调用轮数上限；
 * - [maxToolCalls]：单次任务的工具调用次数上限；
 * - [maxConcurrent]：同时运行的 Subagent 数量上限。
 */
@Serializable
internal data class SubagentBudgetSettings(
    val maxRunMinutes: Int = DEFAULT_MAX_RUN_MINUTES,
    val maxRequestSeconds: Int = DEFAULT_MAX_REQUEST_SECONDS,
    val maxIdleSeconds: Int = DEFAULT_MAX_IDLE_SECONDS,
    val maxModelCalls: Int = DEFAULT_MAX_MODEL_CALLS,
    val maxToolCalls: Int = DEFAULT_MAX_TOOL_CALLS,
    val maxConcurrent: Int = DEFAULT_MAX_CONCURRENT,
) {
    /** 是否仍是出厂默认预算（设置页据此禁用“恢复默认”）。 */
    val isDefault: Boolean
        get() = this == SubagentBudgetSettings()

    /**
     * 归一化后的预算快照：每个字段夹到自己的区间，再收敛跨字段约束。
     *
     * 跨字段约束只在“更严格”的方向收：请求超时不超过单次任务总时长，
     * 空闲超时不超过请求超时；反向不会放宽，避免手改配置意外拉长运行时间。
     * 各字段的区间下界已经互相兼容（1 分钟 = 60 秒 ≥ 请求下限 30 秒 ≥ 空闲下限 15 秒），
     * 因此夹取后仍落在合法区间内。
     */
    val normalized: SubagentBudgetSettings
        get() {
            val runMinutes = maxRunMinutes.coerceIn(MIN_MAX_RUN_MINUTES, MAX_MAX_RUN_MINUTES)
            val runSeconds = runMinutes * 60
            val requestSeconds = maxRequestSeconds
                .coerceIn(MIN_MAX_REQUEST_SECONDS, MAX_MAX_REQUEST_SECONDS)
                .coerceAtMost(runSeconds)
            val idleSeconds = maxIdleSeconds
                .coerceIn(MIN_MAX_IDLE_SECONDS, MAX_MAX_IDLE_SECONDS)
                .coerceAtMost(requestSeconds)
            return SubagentBudgetSettings(
                maxRunMinutes = runMinutes,
                maxRequestSeconds = requestSeconds,
                maxIdleSeconds = idleSeconds,
                maxModelCalls = maxModelCalls.coerceIn(MIN_MAX_MODEL_CALLS, MAX_MAX_MODEL_CALLS),
                maxToolCalls = maxToolCalls.coerceIn(MIN_MAX_TOOL_CALLS, MAX_MAX_TOOL_CALLS),
                maxConcurrent = maxConcurrent.coerceIn(MIN_MAX_CONCURRENT, MAX_MAX_CONCURRENT),
            )
        }

    /** 单次任务总时长的毫秒镜像，便于直接交给 `withTimeout` 一类的 API。 */
    val runTimeoutMillis: Long
        get() = normalized.maxRunMinutes * 60_000L

    /** 单次模型请求超时的毫秒镜像。 */
    val requestTimeoutMillis: Long
        get() = normalized.maxRequestSeconds * 1_000L

    /** 空闲超时的毫秒镜像。 */
    val idleTimeoutMillis: Long
        get() = normalized.maxIdleSeconds * 1_000L

    companion object {
        const val DEFAULT_MAX_RUN_MINUTES = 15
        const val MIN_MAX_RUN_MINUTES = 1
        const val MAX_MAX_RUN_MINUTES = 120

        const val DEFAULT_MAX_REQUEST_SECONDS = 300
        const val MIN_MAX_REQUEST_SECONDS = 30
        const val MAX_MAX_REQUEST_SECONDS = 1_800

        const val DEFAULT_MAX_IDLE_SECONDS = 180
        const val MIN_MAX_IDLE_SECONDS = 15
        const val MAX_MAX_IDLE_SECONDS = 900

        const val DEFAULT_MAX_MODEL_CALLS = 30
        const val MIN_MAX_MODEL_CALLS = 1
        const val MAX_MAX_MODEL_CALLS = 200

        const val DEFAULT_MAX_TOOL_CALLS = 100
        const val MIN_MAX_TOOL_CALLS = 1
        const val MAX_MAX_TOOL_CALLS = 1_000

        const val DEFAULT_MAX_CONCURRENT = 3
        const val MIN_MAX_CONCURRENT = 1
        const val MAX_MAX_CONCURRENT = 8
    }
}

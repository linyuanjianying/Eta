package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

@Serializable
internal data class CompactionSettings(
    val modelId: String? = null,
    val reasoningEffort: String? = null,
    val prompt: String = "",
    val fallbackThresholdTokens: Int = DEFAULT_THRESHOLD_TOKENS,
) {
    val effectiveThresholdTokens: Int
        get() = fallbackThresholdTokens.takeIf { it in MIN_THRESHOLD_TOKENS..MAX_THRESHOLD_TOKENS }
            ?: DEFAULT_THRESHOLD_TOKENS

    companion object {
        const val DEFAULT_THRESHOLD_TOKENS = 27_200
        const val MIN_THRESHOLD_TOKENS = 1_024
        const val MAX_THRESHOLD_TOKENS = 10_000_000
    }
}

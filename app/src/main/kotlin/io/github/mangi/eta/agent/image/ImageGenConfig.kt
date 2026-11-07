package io.github.mangi.eta.agent.image

import java.io.File

/**
 * 生图配置。
 *
 * 刻意不复用聊天模型的 key：生图服务常与对话服务不是同一个中转站（模型名、计费、限流都不同），
 * 共用会导致「改聊天 provider 顺手把生图打挂」，反过来也一样。
 */
internal data class ImageGenConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val enabled: Boolean,
) {
    val usable: Boolean get() = enabled && baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

    /**
     * data class 的默认 toString 会带上 apiKey；只要有人把它打进日志或错误信息就会泄漏密钥。
     * 这里固定成脱敏形式，任何路径都拿不到明文。
     */
    override fun toString(): String =
        "ImageGenConfig(baseUrl=$baseUrl, model=$model, enabled=$enabled, apiKey=" +
            (if (apiKey.isBlank()) "<empty>" else "<redacted:${apiKey.length}>") + ")"

    /** 拉模型列表用；与 generations 同一套 base_url 归一化。 */
    fun modelsUrl(): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        return when {
            trimmed.endsWith("/models") -> trimmed
            trimmed.endsWith("/v1") -> "$trimmed/models"
            trimmed.endsWith("/images/generations") -> trimmed.removeSuffix("/images/generations") + "/models"
            else -> "$trimmed/v1/models"
        }
    }

    /** 允许用户填 `https://host/v1`、`https://host/v1/` 或整条 generations 路径。 */
    fun generationsUrl(): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        return when {
            trimmed.endsWith("/images/generations") -> trimmed
            trimmed.endsWith("/v1") -> "$trimmed/images/generations"
            else -> "$trimmed/v1/images/generations"
        }
    }

    companion object {
        const val DEFAULT_MODEL = "gpt-image-1"
        const val DEFAULT_BASE_URL = ""

        fun fromSettings(baseUrl: String, apiKey: String, model: String, enabled: Boolean) =
            ImageGenConfig(
                baseUrl = baseUrl.trim(),
                apiKey = apiKey.trim(),
                model = model.trim().ifBlank { DEFAULT_MODEL },
                enabled = enabled,
            )
    }
}

internal data class ImageGenResult(
    val files: List<File>,
    val model: String,
    /** 参数被按模型规则修正时的说明（例如 dall-e-3 只出 1 张）；空表示完全按请求执行。 */
    val notes: List<String> = emptyList(),
)

internal sealed interface ImageGenOutcome {
    data class Success(val result: ImageGenResult) : ImageGenOutcome

    /** 失败带可展示的原因；调用方直接把它塞进 tool result，不抛异常中断整轮。 */
    data class Failure(val code: String, val message: String) : ImageGenOutcome
}

package io.github.mangi.eta.agent.image

/**
 * 按模型名修正请求参数的兼容层。
 *
 * 各家图像 API 的参数约束并不统一，而中转站通常只做透传：把 `n=3` 发给 dall-e-3、
 * 把 `response_format` 发给 gpt-image 系列，都会直接 400。这里在发请求前按模型名
 * 把参数掰回合法区间，并把「改了什么」原样回给模型，避免它以为拿到了 3 张。
 *
 * 刻意**不**按模型名拦截请求：很多中转站把异步任务制模型包装成了同步返回，
 * 硬拦会误伤。异步只在响应侧识别（见 [ImageGenClient.parseAndStore]）。
 */
internal object ImageGenModelRules {

    /** 单次请求的参数修正结果。 */
    internal data class Adjustments(
        val n: Int,
        val size: String,
        /** gpt-image 系列不接受 response_format，带了会 400。 */
        val omitResponseFormat: Boolean,
        /** 人话说明改了什么；非空时回传，让模型知道结果与预期不同。 */
        val notes: List<String>,
    )

    /** dall-e-3 只认这三档。 */
    private val DALLE3_SIZES = setOf("1024x1024", "1792x1024", "1024x1792")

    /** dall-e-2 只认方形三档。 */
    private val DALLE2_SIZES = setOf("256x256", "512x512", "1024x1024")

    /** 已知采用「异步任务 + 轮询」协议的模型族；仅用于给出可读的诊断。 */
    private val ASYNC_FAMILIES = listOf(
        "seedream", "seededit", "seedance",
        "kling", "kolors", "wanx", "wan2", "vidu",
        "hunyuan-image", "step-image", "cogview",
    )

    fun adjust(model: String, size: String, requested: Int): Adjustments {
        val id = model.lowercase()
        val notes = mutableListOf<String>()

        val (cap, capName) = when {
            id.contains("dall-e-3") || id.contains("dalle-3") -> 1 to "dall-e-3"
            id.contains("gpt-image") -> 10 to "gpt-image"
            id.contains("dall-e-2") || id.contains("dalle-2") -> 10 to "dall-e-2"
            else -> MAX_N to model
        }
        val n = requested.coerceIn(1, cap)
        if (n != requested) {
            notes += "$capName 只支持 n=$n（请求 $requested），已按 $n 张生成"
        }

        val normalizedSize = when {
            id.contains("dall-e-3") || id.contains("dalle-3") ->
                if (size in DALLE3_SIZES) size else {
                    notes += "dall-e-3 只支持 1024x1024 / 1792x1024 / 1024x1792，已改用 ${nearestDalle3(size)}"
                    nearestDalle3(size)
                }
            id.contains("dall-e-2") || id.contains("dalle-2") ->
                if (size in DALLE2_SIZES) size else {
                    notes += "dall-e-2 只支持 256x256 / 512x512 / 1024x1024，已改用 1024x1024"
                    "1024x1024"
                }
            else -> size
        }

        // gpt-image-1/2 把 response_format 当成未知参数直接拒绝；它本来也只回 b64_json。
        val omitResponseFormat = id.contains("gpt-image")

        return Adjustments(n = n, size = normalizedSize, omitResponseFormat = omitResponseFormat, notes = notes)
    }

    /** 响应里出现任务 id 而不是图片数据时，判断是不是这套模型族，好把原因说清楚。 */
    fun isAsyncFamily(model: String): Boolean {
        val id = model.lowercase()
        return ASYNC_FAMILIES.any { id.contains(it) }
    }

    private fun nearestDalle3(size: String): String {
        val parts = size.lowercase().split("x").mapNotNull { it.trim().toIntOrNull() }
        if (parts.size != 2) return "1024x1024"
        val (w, h) = parts
        return when {
            w > h -> "1792x1024"
            h > w -> "1024x1792"
            else -> "1024x1024"
        }
    }

    private const val MAX_N = 4
}

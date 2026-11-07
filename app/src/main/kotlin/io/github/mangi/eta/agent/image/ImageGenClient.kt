package io.github.mangi.eta.agent.image

import android.content.Context
import android.util.Base64
import io.github.mangi.eta.agent.model.AgentHttpClient
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI 兼容的 `/v1/images/generations` 客户端。
 *
 * 只做单次请求：国产服务的签名与异步任务轮询不在这里（它们的协议各不相同，
 * 塞进来会让这类「一个 base_url + 一个 key」的中转站走不通）。
 */
internal object ImageGenClient {
    private const val CONNECT_TIMEOUT_MS = 15_000L

    /** 生图比对话慢得多：实测中转站普遍 20–90s，给足 180s 但不无限等。 */
    private const val READ_TIMEOUT_MS = 180_000L

    private val client by lazy {
        AgentHttpClient.client.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(READ_TIMEOUT_MS + 20_000L, TimeUnit.MILLISECONDS)
            .build()
    }

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private data class PostResult(
        val ok: Boolean,
        val status: Int,
        val body: String,
        val networkError: String?,
    )

    /**
     * @param size 形如 `1024x1024`；留空则由服务端决定。
     * @return 已落盘的文件列表（失败时为空）与错误说明。
     */
    fun generate(
        context: Context,
        config: ImageGenConfig,
        prompt: String,
        size: String,
        count: Int,
    ): ImageGenOutcome {
        if (!config.usable) {
            return ImageGenOutcome.Failure(
                code = "IMAGE_GEN_NOT_CONFIGURED",
                message = "生图未配置：需要在设置里填写中转站 base_url、API Key 与模型名",
            )
        }
        if (prompt.isBlank()) {
            return ImageGenOutcome.Failure("IMAGE_GEN_EMPTY_PROMPT", "prompt 不能为空")
        }
        // 先按模型名把参数掰到合法区间：dall-e-3 只认 n=1、gpt-image 不接受 response_format。
        val rules = ImageGenModelRules.adjust(config.model, size.trim(), count)
        val payload = JSONObject()
            .put("model", config.model)
            .put("prompt", prompt)
            .put("n", rules.n)
        if (!rules.omitResponseFormat) payload.put("response_format", "b64_json")
        rules.size.takeIf { it.isNotEmpty() }?.let { payload.put("size", it) }

        val notes = rules.notes.toMutableList()

        fun post(p: JSONObject): PostResult {
            val request = Request.Builder()
                .url(config.generationsUrl())
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(p.toString().toRequestBody(jsonMedia))
                .build()
            return try {
                client.newCall(request).execute().use { response ->
                    PostResult(
                        ok = response.isSuccessful,
                        status = response.code,
                        body = response.body?.string().orEmpty(),
                        networkError = null,
                    )
                }
            } catch (e: IOException) {
                PostResult(false, 0, "", "${e.javaClass.simpleName}: ${e.message ?: "请求失败"}")
            }
        }

        var result = post(payload)
        // 400 且明确指向某个可选参数时，摘掉它再试一次：不同中转站的参数白名单差异很大，
        // 与其让用户去猜，不如自己降级并把降级原因回传。
        if (!result.ok && result.status == 400) {
            val text = result.body.lowercase()
            if (payload.has("response_format") &&
                ("response_format" in text || "unknown" in text || "unrecognized" in text)
            ) {
                payload.remove("response_format")
                notes += "服务端不接受 response_format，已省略该参数重试"
                result = post(payload)
            }
            if (!result.ok && result.status == 400 && payload.has("size") && "size" in text) {
                payload.remove("size")
                notes += "服务端不接受该 size，已省略让服务端决定"
                result = post(payload)
            }
        }

        if (result.networkError != null) {
            // 网络类错误单独给 code：超时和 DNS 失败在中转站场景很常见。
            return ImageGenOutcome.Failure("IMAGE_GEN_NETWORK", result.networkError)
        }
        if (!result.ok) {
            return ImageGenOutcome.Failure(
                code = "IMAGE_GEN_HTTP_${result.status}",
                message = result.body.take(600).ifBlank { "HTTP ${result.status}" },
            )
        }

        return parseAndStore(context, result.body, config.model, notes)
    }

    /**
     * 拉中转站的 `/v1/models`，供设置页直接选模型。
     *
     * suspend + 内部切 IO：这里常被 Compose 的 rememberCoroutineScope（Main）调用，
     * 直接 http 会 NetworkOnMainThreadException 闪退；把调度决定收在 client 内部，调用方不必当心。
     * 不校验 model 字段——用户往往先有 url+key 才去挑模型名。
     */
    suspend fun fetchModels(config: ImageGenConfig): Result<List<String>> = withContext(Dispatchers.IO) {
        if (config.baseUrl.isBlank() || config.apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("先填 base_url 和 API Key"))
        }
        val request = Request.Builder()
            .url(config.modelsUrl())
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("HTTP ${response.code}：${text.take(200)}"),
                    )
                }
                val data = JSONObject(text).optJSONArray("data")
                    ?: return@withContext Result.failure(
                        IllegalStateException("响应里没有 data 数组"),
                    )
                val ids = buildList {
                    for (i in 0 until data.length()) {
                        data.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }?.let(::add)
                    }
                }.distinct().sorted()
                if (ids.isEmpty()) Result.failure(IllegalStateException("模型列表为空"))
                else Result.success(ids)
            }
        } catch (e: IOException) {
            Result.failure(IllegalStateException("${e.javaClass.simpleName}: ${e.message ?: "请求失败"}"))
        } catch (e: org.json.JSONException) {
            Result.failure(IllegalStateException("返回不是合法 JSON：${e.message}"))
        }
    }

    private fun parseAndStore(
        context: Context,
        raw: String,
        model: String,
        notes: List<String>,
    ): ImageGenOutcome {
        val root = JSONObject(raw)
        val data = root.optJSONArray("data")
        if (data == null) {
            // 异步任务制服务会回 task id 而不是图：明确告诉模型这条路走不通，
            // 而不是丢一个 "BAD_RESPONSE" 让它反复重试。
            val taskId = root.optString("task_id").ifBlank { root.optString("id") }
                .ifBlank { root.optJSONObject("output")?.optString("task_id").orEmpty() }
            if (taskId.isNotBlank() || root.has("status") || root.has("task_status")) {
                val hint = if (ImageGenModelRules.isAsyncFamily(model)) {
                    "该模型是中转站上的异步任务制（seedream/可灵一类），当前只支持同步返回。"
                } else {
                    "服务端返回了任务 id 而非图片。"
                }
                return ImageGenOutcome.Failure(
                    code = "IMAGE_GEN_ASYNC_TASK",
                    message = hint + "需要轮询接口，当前实现不支持；换支持同步返回的模型" +
                        "（gpt-image / nano-banana / flux 系列）。原始响应：" + raw.take(300),
                )
            }
            return ImageGenOutcome.Failure("IMAGE_GEN_BAD_RESPONSE", raw.take(600))
        }
        if (data.length() == 0) {
            // 少数站直接返回 {"url": ...} 或 {"b64_json": ...}，不包 data 数组。
            val direct = JSONArray().put(root)
            return parseItems(context, direct, model, notes, raw)
        }
        return parseItems(context, data, model, notes, raw)
    }

    private fun parseItems(
        context: Context,
        data: JSONArray,
        model: String,
        notes: List<String>,
        raw: String,
    ): ImageGenOutcome {
        val dir = File(context.filesDir, "generated-images").apply { mkdirs() }
        val files = mutableListOf<File>()
        var lastReason: String? = null
        for (index in 0 until data.length()) {
            val item = data.optJSONObject(index) ?: continue
            // 三种返回形态都要接：b64_json / b64 / url。中转站之间差异很大，
            // 只认 b64_json 会把「其实已经生成成功」的结果判成失败。
            val b64 = listOf("b64_json", "b64", "image", "image_base64")
                .firstNotNullOfOrNull { key -> item.optString(key).takeIf { it.isNotBlank() } }
            var bytes: ByteArray? = null
            if (b64 != null) {
                bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
                if (bytes != null && !bytes.looksLikeImage()) {
                    // 有的站给的是去掉前缀的裸 base64，也有的是 data URL 前缀残留
                    val normalized = b64.substringAfter("base64,", b64)
                    bytes = runCatching { Base64.decode(normalized, Base64.DEFAULT) }.getOrNull()
                }
            }
            if (bytes == null || !bytes.looksLikeImage()) {
                val url = item.optString("url").takeIf { it.isNotBlank() }
                if (url == null) {
                    lastReason = "第 ${index + 1} 条既没有 b64_json 也没有 url"
                    continue
                }
                bytes = download(url)
                if (bytes == null) {
                    lastReason = "url 下载失败：${url.take(120)}"
                    continue
                }
            }
            // Base64.decode 对任意字符串都可能「成功」，不校验魔数会把乱码写成 .png，
            // 表现为文件存在但打不开、模型侧解码失败。这里必须验真。
            if (bytes.size < MIN_IMAGE_BYTES || !bytes.looksLikeImage()) {
                lastReason = "第 ${index + 1} 条解码后只有 ${bytes.size} 字节，不像图片"
                continue
            }
            val file = File(dir, "gen-${System.currentTimeMillis()}-$index.${extensionOf(bytes)}")
            runCatching { file.writeBytes(bytes) }.getOrElse { continue }
            files += file
        }
        return if (files.isEmpty()) {
            ImageGenOutcome.Failure(
                "IMAGE_GEN_DECODE_FAILED",
                (lastReason ?: "返回里没有可解析的图片数据") + "。原始响应：" + raw.take(300),
            )
        } else {
            ImageGenOutcome.Success(ImageGenResult(files = files, model = model, notes = notes))
        }
    }


    private fun download(url: String): ByteArray? = runCatching {
        // 部分站的图片 CDN 要求带 UA / Referer，不带会被 403；这里给一个浏览器 UA。
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/140 Mobile")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.bytes() else null
        }
    }.getOrNull()

    /** 魔数白名单：只有真正像图片的字节才允许落盘。 */
    private fun ByteArray.looksLikeImage(): Boolean = when {
        size >= 8 && this[0] == 0x89.toByte() && this[1] == 'P'.code.toByte() &&
            this[2] == 'N'.code.toByte() && this[3] == 'G'.code.toByte() -> true
        size >= 3 && this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte() &&
            this[2] == 0xFF.toByte() -> true
        size >= 12 && String(this, 0, 4) == "RIFF" && String(this, 8, 4) == "WEBP" -> true
        size >= 6 && (String(this, 0, 6) == "GIF89a" || String(this, 0, 6) == "GIF87a") -> true
        size >= 2 && String(this, 0, 2) == "BM" -> true
        else -> false
    }

    /** 再小的「图片」都不可能是真图；用它挡掉解码出的碎片。 */
    private const val MIN_IMAGE_BYTES = 512

    /** 按魔数判扩展名，别信 Content-Type：决定后续 BitmapFactory 能否识别。 */
    private fun extensionOf(bytes: ByteArray): String = when {
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "png"
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
        bytes.size >= 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WEBP" -> "webp"
        else -> "png"
    }

    private const val MAX_IMAGES = 4
}

package io.github.mangi.eta.agent.image

import android.content.Context
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentToolSchema
import org.json.JSONArray
import org.json.JSONObject

/**
 * 生图工具：让模型自己决定「用户要一张图」时调用。
 *
 * 不做成聊天页按钮，是因为现有架构里所有能力都是工具；做成按钮既要在 UI 层加状态机，
 * 又拿不到「先生成再检查」这类串联。工具化后模型天然会先看图再决定要不要重画。
 */
internal object AgentImageGenToolCatalog {
    const val GENERATE_IMAGE = "generate_image"

    private const val MAX_PROMPT = 2_000
    private const val MAX_IMAGES = 4

    fun appendSchemas(tools: JSONArray, enabled: Boolean) {
        if (!enabled) return
        tools.put(
            AgentToolSchema.function(
                name = GENERATE_IMAGE,
                description = "用已配置的生图中转站生成图片并保存到本机。返回保存路径；" +
                    "生成的图片会自动作为视觉输入进入本次结果，可以直接描述或继续加工。" +
                    "提示词请写清主体、风格、构图；需要多张时调 n。" +
                    "用户只要表达出画图意图（画/绘制/来一张/生成图片/插画/壁纸/封面/头像/海报/logo/" +
                    "示意图/配图/帮我配张图/改成某种风格）就应当直接调用本工具，不要先用文字问" +
                    "「要不要我画」或改成文字描述代答——本工具返回的图片会直接显示在对话里。",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "prompt",
                                JSONObject()
                                    .put("type", "string")
                                    .put("maxLength", MAX_PROMPT)
                                    .put("description", "图片描述，建议包含主体、风格、光线、画幅"),
                            )
                            .put(
                                "size",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "尺寸，如 1024x1024、1024x1536、1536x1024；留空由服务端决定"),
                            )
                            .put(
                                "n",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("minimum", 1)
                                    .put("maximum", MAX_IMAGES)
                                    .put("description", "生成张数，默认 1"),
                            ),
                    )
                    .put("required", JSONArray(listOf("prompt"))),
            ),
        )
    }

    fun execute(
        context: Context,
        config: ImageGenConfig,
        argumentsJson: String,
    ): AgentModelClient.ToolResult {
        val args = runCatching { JSONObject(argumentsJson.ifBlank { "{}" }) }
            .getOrElse {
                return fail("IMAGE_GEN_BAD_ARGS", "参数不是合法 JSON：${it.message}")
            }
        val prompt = args.optString("prompt").trim().take(MAX_PROMPT)
        val size = args.optString("size").trim()
        val count = args.optInt("n", 1).coerceIn(1, MAX_IMAGES)

        return when (val outcome = ImageGenClient.generate(context, config, prompt, size, count)) {
            is ImageGenOutcome.Failure -> fail(outcome.code, outcome.message)
            is ImageGenOutcome.Success -> {
                val images = outcome.result.files.mapNotNull { file ->
                    runCatching {
                        AgentImageCodec.fromBytes(
                            bytes = file.readBytes(),
                            source = file.absolutePath,
                            mimeHint = "image/png",
                        )
                    }.getOrNull()
                }
                val paths = JSONArray().also { array ->
                    outcome.result.files.forEach { array.put(it.absolutePath) }
                }
                val notes = JSONArray().also { array ->
                    outcome.result.notes.forEach { array.put(it) }
                }
                AgentModelClient.ToolResult(
                    content = JSONObject()
                        .put("ok", true)
                        .put("model", outcome.result.model)
                        .put("count", outcome.result.files.size)
                        .put("paths", paths)
                        .put("prompt", prompt)
                        // 参数被降级时明说，避免模型以为真的拿到了请求的张数/尺寸。
                        .put("adjustments", notes)
                        .toString(),
                    // 结果图直接带回视觉输入：模型无需再走一次 read_image 才能描述它生成的东西。
                    images = images,
                    // 与 read_image 的瞬时观察不同：生成的图要留在对话里给用户看。
                    persistImages = true,
                )
            }
        }
    }

    private fun fail(code: String, message: String) =
        AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", false)
                .put("code", code)
                .put("message", message)
                .toString(),
        )
}

package io.github.mangi.eta.agent.image

import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生图数据流的关键不变量（纯 JVM，不需要 Robolectric）：
 * 图片是会话资产要保留，但不能作为用户消息每轮重发给模型。
 */
class ImageGenDataFlowTest {

    private val dataUrl = "data:image/png;base64," + "A".repeat(2_000)

    private fun generatedMessage(): JSONObject =
        AgentConversationCodec.userMessage("Generated image(s) by tool(s): generate_image.", emptyList())
            .put(AgentConversationCodec.GENERATED_IMAGE_FLAG, true)
            .put("_eta_message_id", "generated-image-1")
            .also { message ->
                message.put(
                    "content",
                    JSONArray()
                        .put(JSONObject().put("type", "text").put("text", "generated"))
                        .put(
                            JSONObject().put("type", "image_url")
                                .put("image_url", JSONObject().put("url", dataUrl)),
                        ),
                )
            }

    @Test
    fun generatedMarkerSurvivesJsonRoundTrip() {
        val decoded = AgentConversationCodec.fromJsonObject(generatedMessage())
        assertTrue(decoded.generatedImages)
        assertEquals("generated-image-1", decoded.messageId)
    }

    @Test
    fun storedTranscriptKeepsGeneratedImageButSanitizesOrdinaryUserImages() {
        val generated = AgentConversationCodec.fromJsonObject(generatedMessage())
        // 普通用户消息：同一份 content 但没有 _eta_generated 标记。
        val ordinaryRaw = JSONObject(generatedMessage().toString()).apply {
            remove(AgentConversationCodec.GENERATED_IMAGE_FLAG)
            put("_eta_message_id", "user-1")
        }
        val ordinary = AgentConversationCodec.fromJsonObject(ordinaryRaw)
        assertFalse(ordinary.generatedImages)

        val stored = AgentConversationCodec.encodeTranscriptForStorage(listOf(generated, ordinary))
        assertTrue("生图消息的图片必须落库", stored.contains(dataUrl))
        val ordinaryPart = stored.substringAfter("\"user-1\"", "")
        assertFalse("普通用户消息的图片仍按原策略清理", ordinaryPart.contains(dataUrl))
    }

    @Test
    fun requestProjectionReplacesGeneratedImageWithShortReference() {
        val generated = AgentConversationCodec.fromJsonObject(generatedMessage())
        val projected = AgentConversationCodec.forRequest(listOf(generated)).single()
        assertTrue(projected.generatedImages)
        assertTrue(projected.contentJson.isEmpty())
        assertFalse("投影结果不能再带图片字节", projected.content.contains(dataUrl))
        assertTrue(projected.content.contains("read_image"))

        val json = AgentConversationCodec.toJsonObject(projected)
        val rendered = json.toString()
        assertFalse(rendered.contains(dataUrl))
        assertTrue(rendered.contains("本机已生成"))
    }

    @Test
    fun requestProjectionLeavesOrdinaryMessagesUntouched() {
        val plain = AgentModelClient.ConversationMessage(role = "user", content = "普通提问")
        val projected = AgentConversationCodec.forRequest(listOf(plain)).single()
        assertEquals(plain, projected)
    }

    @Test
    fun imageGenConfigNeverPrintsApiKey() {
        val config = ImageGenConfig.fromSettings(
            baseUrl = "https://relay.example/v1",
            apiKey = "sk-super-secret-value",
            model = "gpt-image-1",
            enabled = true,
        )
        val text = config.toString()
        assertFalse("toString 不能暴露密钥", text.contains("sk-super-secret-value"))
        assertTrue(text.contains("redacted"))
        assertTrue(config.usable)
    }

    @Test
    fun imageGenConfigUrlNormalizationAcceptsHostAndGenerationsPath() {
        fun cfg(baseUrl: String) = ImageGenConfig.fromSettings(baseUrl, "k", "m", true)
        assertEquals("https://a.example/v1/images/generations", cfg("https://a.example").generationsUrl())
        assertEquals("https://a.example/v1/images/generations", cfg("https://a.example/v1/").generationsUrl())
        assertEquals(
            "https://a.example/v1/images/generations",
            cfg("https://a.example/v1/images/generations").generationsUrl(),
        )
        assertEquals("https://a.example/v1/models", cfg("https://a.example/v1").modelsUrl())
    }

    @Test
    fun modelRulesClampDallE3AndDropResponseFormatForGptImage() {
        val dalle3 = ImageGenModelRules.adjust("dall-e-3", "512x512", 3)
        assertEquals(1, dalle3.n)
        assertTrue(dalle3.size in setOf("1024x1024", "1792x1024", "1024x1792"))
        assertFalse(dalle3.notes.isEmpty())

        val gptImage = ImageGenModelRules.adjust("gpt-image-1", "1024x1024", 2)
        assertTrue(gptImage.omitResponseFormat)
        assertEquals(2, gptImage.n)
    }
}

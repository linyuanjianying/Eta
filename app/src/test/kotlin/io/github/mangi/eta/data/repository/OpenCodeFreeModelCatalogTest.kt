package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.provider.BuiltinProviders
import io.github.mangi.eta.data.provider.ProviderSourceRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

class OpenCodeFreeModelCatalogTest {
    private val catalogue = """
        {"object":"list","data":[
            {"id":"beam-free","object":"model","owned_by":"opencode"},
            {"id":"deepseek-v4.1-flash-free","object":"model"},
            {"id":"jev-1.13-free","object":"model"},
            {"id":"zero-cost-model","cost":{"input":0,"output":0.0}},
            {"id":"explicitly-priced","cost":{"input":0,"output":1}},
            {"id":"missing-output-cost","cost":{"input":0}},
            {"id":"pricing-free","pricing":{"input":0,"output":0}},
            {"id":"archived-free","deprecated":true},
            {"id":"older-free","deprecated":true,"cost":{"input":0,"output":0}},
            {"id":"premium","object":"model"},
            {"id":"notfree","object":"model"}
        ]}
    """.trimIndent()

    @Test fun onlyExplicitlyFreeModelsAreDetected() {
        val ids = OpenCodeFreeModelCatalog.parseFreeModelIds(catalogue)
        assertEquals(
            setOf("beam-free","deepseek-v4.1-flash-free","jev-1.13-free","zero-cost-model","pricing-free"),
            ids,
        )
        val chat = RemoteModelFetcher.parseOpenAiModels(catalogue)
            .filter { it.modelId in ids && RemoteModelFetcher.isChatCapableModel(it) }
        // 免费条目里 beam-free 与 jev-1.13-free 也是对话模型（fork 断言为 4 条依赖了已变更的
        // 对话能力过滤），这里按当前 RemoteModelFetcher 规则统计：排除 deprecated 与非对话模型。
        assertEquals(5, chat.size)
        assertFalse(ids.contains("notfree"))
    }

    @Test fun malformedAndUnauthenticatedCatalogsDoNotCreateFreeModels() {
        assertThrows(IllegalArgumentException::class.java) {
            OpenCodeFreeModelCatalog.parseFreeModelIds("{}")
        }
        assertThrows(IllegalArgumentException::class.java) {
            OpenCodeFreeModelCatalog.parseFreeModelIds("""{"type":"error","message":"Unauthorized"}""")
        }
    }

    @Test fun directZenResolvesToOpenCodeWhenConfigured() {
        val builtin = BuiltinProviders.providerById(BuiltinProviders.OPENCODE_ID)!!
        assertEquals(ProviderSourceTypes.OPENCODE, ProviderSourceRegistry.resolve(builtin))
        assertTrue(OpenCodeFreeModelCatalog.identifyOfficialOpenCodeUrl(builtin.baseUrl))
        assertFalse(OpenCodeFreeModelCatalog.identifyOfficialOpenCodeUrl("https://not-opencode.ai/zen/v1"))
        assertFalse(OpenCodeFreeModelCatalog.identifyOfficialOpenCodeUrl("http://opencode.ai/zen/v1"))
        val gateway = OpenAiCompatibleProviderSetting(
            id = "gateway", name = "Local proxy",
            baseUrl = "https://eta-gateway.example/v1", sourceType = ProviderSourceTypes.OPENCODE,
        )
        assertEquals(ProviderSourceTypes.OPENCODE, ProviderSourceRegistry.resolve(gateway))
        assertFalse(OpenCodeFreeModelCatalog.identifyOfficialOpenCodeUrl(gateway.baseUrl))
    }

    @Test fun noFreeMetadataDoesNotTreatTheEntireCatalogAsFree() {
        val json = """{"data":[{"id":"gpt-paid"},{"id":"zero","cost":{"input":0.1,"output":0}}]}"""
        assertTrue(OpenCodeFreeModelCatalog.parseFreeModelIds(json).isEmpty())
    }

    @Test fun officialOpenCodeUrlIsIdentifiedAndCustomEndpointsAreNot() {
        // 免费目录只对官方 Zen 端点生效；第三方 OpenAI 兼容端点不得被误判为官方。
        val builtin = BuiltinProviders.providerById(BuiltinProviders.OPENCODE_ID)!!
        assertEquals("https://opencode.ai/zen/v1", builtin.baseUrl)
        assertTrue(OpenCodeFreeModelCatalog.identifyOfficialOpenCodeUrl(builtin.baseUrl))
        assertFalse(
            OpenCodeFreeModelCatalog.identifyOfficialOpenCodeUrl("https://other-provider.example/zen/v1"),
        )
    }

    @Test fun currentOfficialStyleModelListImportsFreeChatModelsOnly() {
        val models = """{"object":"list","data":[
           {"id":"gpt-5.6-sol","object":"model","owned_by":"opencode"},
           {"id":"deepseek-v4.1-flash-free","object":"model","owned_by":"opencode"},
           {"id":"mimo-v2.6-flash-free","object":"model","owned_by":"opencode"},
           {"id":"jev-1.13-free","object":"model","owned_by":"opencode"},
           {"id":"paid-model","object":"model","owned_by":"opencode"}
        ]}"""
        val freeIds = OpenCodeFreeModelCatalog.parseFreeModelIds(models)
        val accepted = RemoteModelFetcher.parseOpenAiModels(models)
            .filter { it.modelId in freeIds && RemoteModelFetcher.isChatCapableModel(it) }
        assertEquals(
            setOf("deepseek-v4.1-flash-free", "mimo-v2.6-flash-free", "jev-1.13-free"),
            accepted.map { it.modelId }.toSet(),
        )
    }
}

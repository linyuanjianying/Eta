package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.provider.AgentModelClient
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.CustomHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import io.github.mangi.eta.agent.runtime.archive.AgentExternalArchivePayload
import io.github.mangi.eta.agent.runtime.ipc.AgentRuntimeWire

class AgentRuntimeRequestConfigResolverTest {
    @Test
    fun onlyEtaProcessesWithSupportedEntryCanDeclareSearchPresentation() {
        val sources = listOf(AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
            AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE, "external", "unknown")
        for (source in sources) for (isEta in listOf(false, true)) {
            val original = request(source, modelConfig("fixture", ReasoningEffort.DEFAULT)
                .copy(supportsSearchSuggestions = true))
            val constrained = AgentRuntimeRequestConfigResolver.constrainPresentation(original, isEta)
            assertEquals(isEta && source in sources.take(2), constrained.config.supportsSearchSuggestions)
        }
    }

    @Test
    fun voiceConfigResolutionPreservesOnlyTheEntryPresentationDeclaration() {
        val local = modelConfig("fixture", ReasoningEffort.DEFAULT)
        val original = request(AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE,
            local.copy(supportsSearchSuggestions = true))
        assertTrue(AgentRuntimeRequestConfigResolver.applyRuntimeConfig(original, local)
            .config.supportsSearchSuggestions)
        assertFalse(AgentRuntimeRequestConfigResolver.applyRuntimeConfig(
            original.copy(config = local), local.copy(supportsSearchSuggestions = true),
        ).config.supportsSearchSuggestions)
    }

    @Test
    fun oauthRequestUsesLocalConnectionAndPreservesSupportedTransientChoices() {
        val local = modelConfig("", ReasoningEffort.DEFAULT).copy(
            providerId = "account-provider",
            authMode = ProviderAuthModes.OPENAI_CHATGPT,
            baseUrl = "https://api.openai.com/v1",
            customHeaders = listOf(CustomHeader("x-local", "fixture")),
            reasoningCapabilities = ModelReasoningCapabilities(
                supportedEfforts = listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH), canDisable = true,
            ),
        )
        val entry = local.copy(
            apiKey = "fixture-untrusted", baseUrl = "https://untrusted.invalid",
            customHeaders = listOf(CustomHeader("x-untrusted", "fixture")),
            reasoningEffort = ReasoningEffort.HIGH,
            terminalTools = true, deviceSensitiveReadTools = true,
            autoCompactionEnabled = false,
        )
        val request = request("external", entry)
        assertTrue(AgentRuntimeRequestConfigResolver.requiresRuntimeConfig(request))
        val result = AgentRuntimeRequestConfigResolver.applyRuntimeConfig(request, local).config

        assertEquals(local.baseUrl, result.baseUrl)
        assertEquals("", result.apiKey)
        assertEquals(local.customHeaders, result.customHeaders)
        assertEquals(ReasoningEffort.HIGH, result.reasoningEffort)
        assertTrue(result.terminalTools)
        assertTrue(result.deviceSensitiveReadTools)
        assertFalse(result.autoCompactionEnabled)
    }

    @Test
    fun oauthRequestCannotChangeLocalAccountOrInjectUnsupportedReasoning() {
        val local = modelConfig("", ReasoningEffort.DEFAULT).copy(
            providerId = "account-provider", authMode = ProviderAuthModes.XAI_GROK,
            reasoningCapabilities = ModelReasoningCapabilities(supportedEfforts = listOf(ReasoningEffort.LOW)),
        )
        val request = request("external", local.copy(reasoningEffort = ReasoningEffort.MAX))
        assertEquals(ReasoningEffort.LOW,
            AgentRuntimeRequestConfigResolver.applyRuntimeConfig(request, local).config.reasoningEffort)
        assertThrows(IllegalArgumentException::class.java) {
            AgentRuntimeRequestConfigResolver.applyRuntimeConfig(request, local.copy(providerId = "other-provider"))
        }
    }

    @Test
    fun etaVoiceRequestUsesRuntimeConfigAndUpdatesArchiveMetadata() {
        val runtimeConfig = modelConfig("runtime-key", ReasoningEffort.HIGH)
        val request = request(
            source = AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE,
            config = modelConfig("entry-key", ReasoningEffort.OFF),
        )

        val resolved = AgentRuntimeRequestConfigResolver.applyRuntimeConfig(request, runtimeConfig)
        val payload = AgentExternalArchivePayload.from(requireNotNull(resolved.handoff).payload)

        assertTrue(AgentRuntimeRequestConfigResolver.requiresRuntimeConfig(request))
        assertEquals("runtime-key", resolved.config.apiKey)
        requireNotNull(payload)
        assertTrue(requireNotNull(payload.thinkingEnabled))
        assertEquals(ReasoningEffort.HIGH, payload.reasoningEffort)
    }

    @Test
    fun etaVoiceRequestKeepsEntryToolSwitchesWhileTakingRuntimeModel() {
        val entry = modelConfig("entry-key", ReasoningEffort.OFF).copy(
            terminalTools = true,
            deviceSensitiveReadTools = true,
            deviceSensitiveActionTools = true,
            browserTools = false,
        )
        val request = request(source = AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE, config = entry)

        val resolved = AgentRuntimeRequestConfigResolver.applyRuntimeConfig(
            request,
            modelConfig("runtime-key", ReasoningEffort.HIGH),
        ).config

        assertEquals("runtime-key", resolved.apiKey)
        assertTrue(resolved.terminalTools)
        assertTrue(resolved.deviceSensitiveReadTools)
        assertTrue(resolved.deviceSensitiveActionTools)
        assertFalse(resolved.browserTools)
    }

    @Test
    fun voiceAndOauthPreserveRequestCompactionSelectionUseLocalIntervalAndCannotEscalatePermission() {
        for (source in listOf(AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE, "external")) {
            for (localEnabled in listOf(false, true)) for (requested in listOf(false, true)) {
                val local = modelConfig("", ReasoningEffort.DEFAULT).copy(
                    providerId = "local-provider",
                    authMode = if (source == "external") ProviderAuthModes.OPENAI_CHATGPT else ProviderAuthModes.API_KEY,
                    autoCompactionEnabled = false,
                    requestCompactionEnabled = localEnabled,
                    requestCompactionInterval = 151,
                )
                val entry = local.copy(requestCompactionEnabled = requested, requestCompactionInterval = 1)
                val resolved = AgentRuntimeRequestConfigResolver.applyRuntimeConfig(request(source, entry), local).config
                assertEquals(localEnabled && requested, resolved.requestCompactionEnabled)
                assertEquals(151, resolved.requestCompactionInterval)
                assertFalse(resolved.autoCompactionEnabled)
                for (permitted in listOf(false, true)) {
                    val constrained = AgentRuntimePolicy.constrain(
                        resolved,
                        AgentRuntimePolicy.Permissions(
                            terminalTools = false, browserTools = false,
                            requestCompaction = permitted, requestCompactionInterval = 777,
                        ),
                    )
                    assertEquals(localEnabled && requested && permitted, constrained.requestCompactionEnabled)
                    assertEquals(777, constrained.requestCompactionInterval)
                }
            }
        }
    }

    @Test
    fun voiceAndOauthCannotTurnRequestCompactionOnJustBecauseTokenCompactionIsEnabled() {
        for (source in listOf(AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE, "external")) {
            val config = modelConfig("", ReasoningEffort.DEFAULT).copy(
                providerId = "local-provider",
                authMode = if (source == "external") ProviderAuthModes.OPENAI_CHATGPT else ProviderAuthModes.API_KEY,
                autoCompactionEnabled = true,
                requestCompactionEnabled = false,
            )
            val resolved = AgentRuntimeRequestConfigResolver.applyRuntimeConfig(request(source, config), config).config
            assertTrue(resolved.autoCompactionEnabled)
            assertFalse(resolved.requestCompactionEnabled)
            assertEquals(95, resolved.requestCompactionInterval)
        }
    }

    @Test
    fun externalAssistantRequestKeepsEntryConfig() {
        val entryConfig = modelConfig("entry-key", ReasoningEffort.OFF)
        val request = request(source = "external", config = entryConfig)

        val resolved = AgentRuntimeRequestConfigResolver.applyRuntimeConfig(
            request,
            modelConfig("runtime-key", ReasoningEffort.HIGH),
        )

        assertFalse(AgentRuntimeRequestConfigResolver.requiresRuntimeConfig(request))
        assertSame(request, resolved)
        assertSame(entryConfig, resolved.config)
    }

    private fun request(
        source: String,
        config: AgentModelClient.ModelConfig,
    ): AgentRuntimeWire.RunRequest = AgentRuntimeWire.RunRequest(
        runId = "run-1",
        prompt = "测试",
        config = config,
        images = emptyList(),
        handoff = AgentRuntimeWire.EntryHandoff(
            id = "handoff-1",
            source = source,
            payload = AgentExternalArchivePayload(
                userText = "测试",
                conversationKey = "conversation-1",
                title = "测试",
            ).toJson(),
        ),
    )

    private fun modelConfig(
        apiKey: String,
        reasoningEffort: ReasoningEffort,
    ): AgentModelClient.ModelConfig = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid/v1",
        apiKey = apiKey,
        model = "test-model",
        systemPrompt = "",
        thinkingEnabled = reasoningEffort.enablesReasoning,
        reasoningEffort = reasoningEffort,
    )
}

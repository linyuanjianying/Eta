package io.github.mangi.eta.data.repository

import android.content.Context
import android.content.SharedPreferences
import io.github.mangi.eta.agent.model.provider.AgentModelClient
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.AssistantOverlaySettings
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.SubagentSettings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RuntimeConfigRepositoryRequestCompactionTest {
    private lateinit var preferences: SharedPreferences
    private var savedLocalPreferences: Any? = null

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication<android.app.Application>()
        preferences = context.getSharedPreferences("runtime-request-compaction-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        savedLocalPreferences = localPreferencesField().get(Prefs)
        localPreferencesField().set(Prefs, preferences)
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        SettingsDataStore.init(context)
        ProviderRepository.init(context)
        runBlocking { SettingsDataStore.setSubagentSettings(SubagentSettings()) }
    }

    @After
    fun tearDown() {
        localPreferencesField().set(Prefs, savedLocalPreferences)
        EtaDatabase.closeForTests()
    }

    @Test
    fun localSettingsReachMainOverlayAndOauthRuntimeConfigurations() = runBlocking {
        val provider = OpenAiCompatibleProviderSetting(
            id = "request-count-main", name = "fixture", baseUrl = "https://example.invalid/v1", apiKey = "fixture",
            models = listOf(Model(id = "main-model", modelId = "fixture-model", displayName = "fixture")),
        )
        val oauth = provider.copy(id = "request-count-oauth", authMode = ProviderAuthModes.OPENAI_CHATGPT,
            models = listOf(Model(id = "oauth-model", modelId = "fixture-oauth", displayName = "fixture")))
        ProviderRepository.addProvider(provider)
        ProviderRepository.addProvider(oauth)
        SettingsDataStore.setSelection(provider.id, provider.models.single().id)
        for (enabled in listOf(false, true)) for (automatic in listOf(false, true)) {
            assertTrue(Prefs.setRequestCompactionSettings(enabled, Int.MAX_VALUE))
            preferences.edit().putBoolean(Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED, automatic).commit()
            val main = requireNotNull(RuntimeConfigRepository.currentRuntimeConfig())
            val bound = requireNotNull(RuntimeConfigRepository.runtimeConfigFor(provider.models.single().id))
            val overlay = RuntimeConfigRepository.applyAssistantOverlaySettings(main, AssistantOverlaySettings())
            val account = requireNotNull(RuntimeConfigRepository.oauthRuntimeConfigFor(
                oauth.id, oauth.authMode, oauth.models.single().modelId,
            ))
            for (config in listOf(main, bound, overlay, account)) {
                assertEquals(enabled, config.requestCompactionEnabled)
                assertEquals(Int.MAX_VALUE, config.requestCompactionInterval)
                assertEquals(automatic, config.autoCompactionEnabled)
            }
        }
    }

    @Test
    fun subagentsExplicitlyDisableRequestCompactionForPrimaryAndSecondaryModels() = runBlocking {
        val secondary = OpenAiCompatibleProviderSetting(
            id = "request-count-secondary", name = "fixture", baseUrl = "https://example.invalid/v1", apiKey = "fixture",
            models = listOf(Model(id = "secondary-model", modelId = "secondary", displayName = "fixture")),
        )
        ProviderRepository.addProvider(secondary)
        assertTrue(Prefs.setRequestCompactionSettings(true, 1))
        for (modelId in listOf(null, secondary.models.single().id)) {
            SettingsDataStore.setSubagentSettings(SubagentSettings(modelId = modelId))
            for (automatic in listOf(false, true)) {
                val primary = AgentModelClient.ModelConfig(
                    baseUrl = "https://example.invalid/v1", apiKey = "fixture", model = "primary", systemPrompt = "",
                    requestCompactionEnabled = true, requestCompactionInterval = 1,
                    autoCompactionEnabled = automatic,
                )
                val subagent = RuntimeConfigRepository.subagentRuntimeConfig(primary)
                assertFalse(subagent.requestCompactionEnabled)
                assertFalse(subagent.subagentTools)
                assertEquals(automatic, subagent.autoCompactionEnabled)
                assertEquals(if (modelId == null) "primary" else "secondary", subagent.model)
            }
        }
    }

    private fun localPreferencesField() = Prefs::class.java.getDeclaredField("localAgent")
        .apply { isAccessible = true }
}

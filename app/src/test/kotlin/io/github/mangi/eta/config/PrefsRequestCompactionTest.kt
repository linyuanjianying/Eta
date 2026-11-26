package io.github.mangi.eta.config

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import io.github.mangi.eta.agent.model.provider.AgentModelClient
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import java.lang.reflect.Proxy
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
class PrefsRequestCompactionTest {
    private lateinit var local: SharedPreferences
    private lateinit var remote: SharedPreferences
    private lateinit var savedFields: Map<String, Any?>

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        local = context.getSharedPreferences("request-compaction-local-test", Context.MODE_PRIVATE)
        remote = context.getSharedPreferences("request-compaction-remote-test", Context.MODE_PRIVATE)
        local.edit().clear().commit()
        remote.edit().clear().commit()
        savedFields = listOf("localAgent", "remote", "cachedRemoteService", "cachedRemotePrefs")
            .associateWith { name -> field(name).get(Prefs) }
        field("localAgent").set(Prefs, local)
        field("remote").set(Prefs, remote)
        field("cachedRemoteService").set(Prefs, null)
        field("cachedRemotePrefs").set(Prefs, null)
    }

    @After
    fun tearDown() {
        savedFields.forEach { (name, value) -> field(name).set(Prefs, value) }
    }

    @Test
    fun settingsRoundTripThroughSharedPreferencesAndAcceptEveryPositiveInt() {
        assertFalse(Prefs.isEnabled(Prefs.Keys.AGENT_REQUEST_COMPACTION_ENABLED))
        assertEquals(95, Prefs.requestCompactionInterval())
        for (enabled in listOf(false, true)) for (interval in listOf(1, 95, Int.MAX_VALUE)) {
            assertTrue(Prefs.setRequestCompactionSettings(enabled, interval))
            assertEquals(enabled, local.getBoolean(Prefs.Keys.AGENT_REQUEST_COMPACTION_ENABLED, !enabled))
            assertEquals(interval, local.getInt(Prefs.Keys.AGENT_REQUEST_COMPACTION_INTERVAL, 0))
            assertEquals(enabled, Prefs.isEnabled(Prefs.Keys.AGENT_REQUEST_COMPACTION_ENABLED))
            assertEquals(interval, Prefs.requestCompactionInterval())
        }
    }

    @Test
    fun invalidIntervalsNeverWriteEitherSetting() {
        assertTrue(Prefs.setRequestCompactionSettings(true, 123))
        val saved = local.all.toMap()
        for (invalid in listOf(0, -1, Int.MIN_VALUE)) {
            assertFalse(Prefs.setRequestCompactionSettings(false, invalid))
            assertEquals(saved, local.all)
        }
    }

    @Test
    fun corruptAndWrongTypeIntervalsFallBackToDefault() {
        for (value in listOf(0, -1, Int.MIN_VALUE)) {
            local.edit().putInt(Prefs.Keys.AGENT_REQUEST_COMPACTION_INTERVAL, value).commit()
            assertEquals(95, Prefs.requestCompactionInterval())
        }
        local.edit().putString(Prefs.Keys.AGENT_REQUEST_COMPACTION_INTERVAL, "123").commit()
        assertEquals(95, Prefs.requestCompactionInterval())
        local.edit().putLong(Prefs.Keys.AGENT_REQUEST_COMPACTION_INTERVAL, 123L).commit()
        assertEquals(95, Prefs.requestCompactionInterval())
        remote.edit().putInt(Prefs.Keys.AGENT_REQUEST_COMPACTION_INTERVAL, 321).commit()
        field("localAgent").set(Prefs, null)
        assertEquals(321, Prefs.requestCompactionInterval())
        assertFalse(Prefs.setRequestCompactionSettings(true, 10))
    }

    @Test
    fun localCommitIsRequiredAndRemoteSynchronizationIsBestEffort() {
        val service = serviceUsing(remote)
        assertTrue(Prefs.setRequestCompactionSettings(true, Int.MAX_VALUE, service))
        assertTrue(remote.getBoolean(Prefs.Keys.AGENT_REQUEST_COMPACTION_ENABLED, false))
        assertEquals(Int.MAX_VALUE, remote.getInt(Prefs.Keys.AGENT_REQUEST_COMPACTION_INTERVAL, 0))
        val savedRemote = remote.all.toMap()

        field("localAgent").set(Prefs, preferencesWithCommit(local) { false })
        assertFalse(Prefs.setRequestCompactionSettings(false, 2, service))
        assertEquals(savedRemote, remote.all)
        field("localAgent").set(Prefs, preferencesWithCommit(local) { error("local commit failed") })
        assertFalse(Prefs.setRequestCompactionSettings(false, 2, service))
        assertEquals(savedRemote, remote.all)

        field("localAgent").set(Prefs, local)
        for (throws in listOf(false, true)) {
            field("cachedRemotePrefs").set(Prefs, preferencesWithCommit(remote) {
                if (throws) error("remote unavailable") else false
            })
            assertTrue(Prefs.setRequestCompactionSettings(false, 3, service))
            assertFalse(Prefs.isEnabled(Prefs.Keys.AGENT_REQUEST_COMPACTION_ENABLED))
            assertEquals(3, Prefs.requestCompactionInterval())
        }
    }

    @Test
    fun reconciliationMigratesIntSeparatelyAndThenKeepsLocalAsSourceOfTruth() {
        val service = serviceUsing(remote)
        remote.edit()
            .putBoolean(Prefs.Keys.AGENT_REQUEST_COMPACTION_ENABLED, true)
            .putInt(Prefs.Keys.AGENT_REQUEST_COMPACTION_INTERVAL, 456)
            .commit()
        Prefs.reconcileAgentPreferences(service)
        assertTrue(local.getBoolean(Prefs.Keys.AGENT_REQUEST_COMPACTION_ENABLED, false))
        assertEquals(456, local.getInt(Prefs.Keys.AGENT_REQUEST_COMPACTION_INTERVAL, 0))
        local.edit()
            .putBoolean(Prefs.Keys.AGENT_REQUEST_COMPACTION_ENABLED, false)
            .putInt(Prefs.Keys.AGENT_REQUEST_COMPACTION_INTERVAL, Int.MAX_VALUE)
            .commit()
        Prefs.reconcileAgentPreferences(service)
        assertFalse(remote.getBoolean(Prefs.Keys.AGENT_REQUEST_COMPACTION_ENABLED, true))
        assertEquals(Int.MAX_VALUE, remote.getInt(Prefs.Keys.AGENT_REQUEST_COMPACTION_INTERVAL, 0))

        local.edit().remove(Prefs.Keys.AGENT_REQUEST_COMPACTION_INTERVAL).commit()
        remote.edit().putString(Prefs.Keys.AGENT_REQUEST_COMPACTION_INTERVAL, "corrupt").commit()
        Prefs.reconcileAgentPreferences(service)
        assertEquals(95, local.getInt(Prefs.Keys.AGENT_REQUEST_COMPACTION_INTERVAL, 0))
        Prefs.reconcileAgentPreferences(service)
        assertEquals(95, remote.getInt(Prefs.Keys.AGENT_REQUEST_COMPACTION_INTERVAL, 0))
    }

    @Test
    fun loadConfigAppliesLocalSettingsToJsonAndLegacyFallbackIndependentlyOfTokenCompaction() {
        val config = AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid/v1", apiKey = "fixture", model = "fixture", systemPrompt = "",
            requestCompactionEnabled = false, requestCompactionInterval = 1,
        )
        for (runtimeJson in listOf("", "invalid-json", RuntimeConfigRepository.runtimeConfigJson(config))) {
            remote.edit().putString(Prefs.Keys.AGENT_RUNTIME_CONFIG_JSON, runtimeJson).commit()
            for (enabled in listOf(false, true)) for (automatic in listOf(false, true)) {
                assertTrue(Prefs.setRequestCompactionSettings(enabled, 753))
                local.edit().putBoolean(Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED, automatic).commit()
                val loaded = AgentModelClient.loadConfig()
                assertEquals(enabled, loaded.requestCompactionEnabled)
                assertEquals(753, loaded.requestCompactionInterval)
                assertEquals(automatic, loaded.autoCompactionEnabled)
            }
        }
    }

    private fun field(name: String) = Prefs::class.java.getDeclaredField(name).apply { isAccessible = true }

    // Supply cached preferences without connecting to LSPosed or a real Binder service.
    private fun serviceUsing(preferences: SharedPreferences): XposedService {
        val constructor = XposedService::class.java.declaredConstructors.single().apply { isAccessible = true }
        val service = constructor.newInstance(null) as XposedService
        field("cachedRemoteService").set(Prefs, service)
        field("cachedRemotePrefs").set(Prefs, preferences)
        return service
    }

    private fun preferencesWithCommit(preferences: SharedPreferences, commit: () -> Boolean): SharedPreferences =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            if (method.name != "edit") return@newProxyInstance method.invoke(preferences, *(args ?: emptyArray()))
            val editor = preferences.edit()
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, operation, values ->
                when {
                    operation.name == "commit" -> commit()
                    operation.returnType == SharedPreferences.Editor::class.java -> {
                        operation.invoke(editor, *(values ?: emptyArray()))
                        proxy
                    }
                    else -> operation.invoke(editor, *(values ?: emptyArray()))
                }
            }
        } as SharedPreferences
}

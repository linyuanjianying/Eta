package io.github.mangi.eta.config

import android.content.SharedPreferences
import io.github.mangi.eta.data.model.ReasoningEffort
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PrefsDefaultsTest {
    @Test
    fun defaultsMatchRecommendedInitialSettings() {
        assertEquals(
            mapOf(
                Prefs.Keys.POWER_KEY_TAKEOVER to false,
                Prefs.Keys.ASSISTANT_AUTO_CONFIG to false,
                Prefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH to true,
                Prefs.Keys.DOUBLE_FINGER_CIRCLE_TO_SEARCH to false,
                Prefs.Keys.AGENT_TERMINAL_TOOLS to true,
                Prefs.Keys.AGENT_BROWSER_TOOLS to true,
                Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS to true,
                Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS to true,
                Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS to true,
                Prefs.Keys.AGENT_THINKING_ENABLED to true,
                Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED to true,
                Prefs.Keys.AGENT_REQUEST_COMPACTION_ENABLED to false,
                Prefs.Keys.AGENT_SUBAGENT_ENABLED to true,
                Prefs.Keys.AGENT_VIRTUAL_DISPLAY_ENABLED to true,
                Prefs.Keys.AGENT_KEEP_SCREEN_ON_ENABLED to true,
            ),
            Prefs.Keys.BOOLEAN_DEFAULTS,
        )
        assertFalse(
            Prefs.Keys.BOOLEAN_DEFAULTS.containsKey(Prefs.Keys.POWER_KEY_ASSISTANT_TARGET),
        )
    }

    @Test
    fun localAgentKeysMatchRuntimeOwnedSettings() {
        assertEquals(
            setOf(
                Prefs.Keys.AGENT_TERMINAL_TOOLS,
                Prefs.Keys.AGENT_BROWSER_TOOLS,
                Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS,
                Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS,
                Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS,
                Prefs.Keys.AGENT_THINKING_ENABLED,
                Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED,
                Prefs.Keys.AGENT_REQUEST_COMPACTION_ENABLED,
                Prefs.Keys.AGENT_SUBAGENT_ENABLED,
                Prefs.Keys.AGENT_VIRTUAL_DISPLAY_ENABLED,
                Prefs.Keys.AGENT_KEEP_SCREEN_ON_ENABLED,
            ),
            Prefs.Keys.LOCAL_AGENT_KEYS,
        )
    }

    @Test
    fun requestCompactionIntervalDefaultsAndNeverEntersBooleanKeys() {
        assertEquals(95, Prefs.DEFAULT_REQUEST_COMPACTION_INTERVAL)
        assertEquals(95, Prefs.requestCompactionInterval(null))
        assertFalse(Prefs.Keys.BOOLEAN_DEFAULTS.containsKey(Prefs.Keys.AGENT_REQUEST_COMPACTION_INTERVAL))
        assertFalse(Prefs.Keys.LOCAL_AGENT_KEYS.contains(Prefs.Keys.AGENT_REQUEST_COMPACTION_INTERVAL))
    }

    @Test
    fun requestCompactionTargetDefaultsToNullAndNeverEntersBooleanKeys() {
        assertEquals("agent_request_compaction_target_json", Prefs.Keys.AGENT_REQUEST_COMPACTION_TARGET_JSON)
        assertNull(Prefs.requestCompactionTarget(null))
        assertFalse(Prefs.requestCompactionTargetMatches("provider-instance", "api-model-id", null))
        assertFalse(Prefs.Keys.BOOLEAN_DEFAULTS.containsKey(Prefs.Keys.AGENT_REQUEST_COMPACTION_TARGET_JSON))
        assertFalse(Prefs.Keys.LOCAL_AGENT_KEYS.contains(Prefs.Keys.AGENT_REQUEST_COMPACTION_TARGET_JSON))
    }

    @Test
    fun requestCompactionIntervalRejectsCorruptWrongTypeAndNonPositivePreferences() {
        for (value in listOf(null, "95", 95L, true, 0, -1, Int.MIN_VALUE)) {
            val preferences = Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { _, method, arguments ->
                check(method.name == "getInt")
                assertEquals(Prefs.Keys.AGENT_REQUEST_COMPACTION_INTERVAL, arguments?.get(0))
                when (value) {
                    null -> arguments?.get(1)
                    is Int -> value
                    else -> throw ClassCastException("wrong preference type")
                }
            } as SharedPreferences
            assertEquals(95, Prefs.requestCompactionInterval(preferences))
        }
        for (value in listOf(1, 95, Int.MAX_VALUE)) {
            val preferences = Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { _, method, _ ->
                check(method.name == "getInt")
                value
            } as SharedPreferences
            assertEquals(value, Prefs.requestCompactionInterval(preferences))
        }
    }

    @Test
    fun defaultReasoningEffortFallsBackFromMissingOffOrUnknownValues() {
        assertEquals(ReasoningEffort.DEFAULT, Prefs.defaultReasoningEffort(null))
        assertEquals(ReasoningEffort.DEFAULT, Prefs.defaultReasoningEffort(stringPreferences(null)))
        assertEquals(ReasoningEffort.DEFAULT, Prefs.defaultReasoningEffort(stringPreferences("off")))
        assertEquals(ReasoningEffort.DEFAULT, Prefs.defaultReasoningEffort(stringPreferences("none")))
        assertEquals(ReasoningEffort.DEFAULT, Prefs.defaultReasoningEffort(stringPreferences("unknown")))
        assertEquals(ReasoningEffort.LOW, Prefs.defaultReasoningEffort(stringPreferences("low")))
        assertEquals(ReasoningEffort.HIGH, Prefs.defaultReasoningEffort(stringPreferences("HIGH")))
        assertEquals(ReasoningEffort.MAX, Prefs.defaultReasoningEffort(stringPreferences("max")))
    }

    private fun stringPreferences(value: String?): SharedPreferences =
        Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "getString" -> {
                    val key = arguments?.get(0) as String
                    if (key == Prefs.Keys.AGENT_DEFAULT_REASONING_EFFORT) value else arguments[1]
                }
                "equals" -> proxy === arguments?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "StringSharedPreferences"
                else -> error("Unexpected SharedPreferences method: ${method.name}")
            }
        } as SharedPreferences
}

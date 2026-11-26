package io.github.mangi.eta.config

import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** No Android runtime or filesystem: commit deliberately changes memory before reporting disk failure. */
class PrefsRequestCompactionRollbackTest {
    private lateinit var savedFields: Map<String, Any?>
    private lateinit var service: XposedService
    private lateinit var remote: MemoryPreferences

    @Before
    fun setUp() {
        savedFields = listOf("localAgent", "remote", "cachedRemoteService", "cachedRemotePrefs")
            .associateWith { field(it).get(Prefs) }
        remote = MemoryPreferences(mapOf(ENABLED to true, INTERVAL to 777))
        // Use only the cached preference path, never a real Binder service.
        val constructor = XposedService::class.java.declaredConstructors.single().apply { isAccessible = true }
        service = constructor.newInstance(null) as XposedService
        field("cachedRemoteService").set(Prefs, service)
        field("cachedRemotePrefs").set(Prefs, remote.preferences)
    }

    @After
    fun tearDown() {
        savedFields.forEach { (name, value) -> field(name).set(Prefs, value) }
    }

    @Test
    fun falseOrExceptionAfterMemoryUpdateRestoresAllThreeOldValuesAndNeverSyncsRemote() {
        for (failure in listOf(Commit.FAIL_AFTER_MEMORY, Commit.THROW_AFTER_MEMORY, Commit.THROW_BEFORE_MEMORY)) {
            val initial = mapOf(ENABLED to true, INTERVAL to 123, TARGET to RAW_TARGET, OTHER to "leave untouched")
            val local = install(initial, failure)
            assertFalse(Prefs.setRequestCompactionSettings(false, 456, service, NEXT_TARGET))
            if (failure != Commit.THROW_BEFORE_MEMORY) {
                assertEquals(false, local.memoryAfterCommits.first()[ENABLED])
                assertEquals(456, local.memoryAfterCommits.first()[INTERVAL])
                assertTrue(local.memoryAfterCommits.first()[TARGET] is String)
                assertFalse(RAW_TARGET == local.memoryAfterCommits.first()[TARGET])
            }
            assertEquals(initial, local.memory)
            assertEquals(initial, local.disk)
            assertEquals(2, local.commitCount)
            assertEquals(0, remote.editCount)
            assertOnlyTheseKeysAccessed(local)
        }
    }

    @Test
    fun absentKeysAreRemovedAgainInsteadOfWritingTheirDefaults() {
        val original = mapOf(ENABLED to false, INTERVAL to 951, TARGET to RAW_TARGET)
        for (mask in 0 until 8) {
            val initial = original.entries.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
                .associate { it.toPair() } + (OTHER to "leave untouched")
            val local = install(initial, Commit.FAIL_AFTER_MEMORY)
            assertFalse(Prefs.setRequestCompactionSettings(true, 456, service, NEXT_TARGET))
            assertEquals(initial, local.memory)
            assertEquals(initial, local.disk)
            assertEquals(0, remote.editCount)
            assertOnlyTheseKeysAccessed(local)
        }
    }

    @Test
    fun corruptTypesArePreservedForAllThreeKeysWithoutReadingOtherPreferences() {
        val values = listOf(true, 17, 19L, 1.5f, "corrupt", setOf("corrupt", "value"))
        for (enabled in values) for (interval in values) for (target in values) {
            val initial = mapOf(ENABLED to enabled, INTERVAL to interval, TARGET to target, OTHER to "leave untouched")
            val local = install(initial, Commit.FAIL_AFTER_MEMORY)
            assertFalse(Prefs.setRequestCompactionSettings(false, Int.MAX_VALUE, service, NEXT_TARGET))
            assertEquals(initial, local.memory)
            assertEquals(initial, local.disk)
            assertEquals(0, remote.editCount)
            assertOnlyTheseKeysAccessed(local)
        }
    }

    @Test
    fun rollbackDiskFailureStillReturnsFalseAndKeepsRestoredMemory() {
        for (rollbackFailure in listOf(Commit.FAIL_AFTER_MEMORY, Commit.THROW_AFTER_MEMORY, Commit.THROW_BEFORE_MEMORY)) {
            val initial = mapOf(ENABLED to true, INTERVAL to 123, TARGET to RAW_TARGET, OTHER to "leave untouched")
            val local = install(initial, Commit.FAIL_AFTER_MEMORY, rollbackFailure)
            assertFalse(Prefs.setRequestCompactionSettings(false, 456, service, target = null))
            assertEquals(NO_TARGET_JSON, local.memoryAfterCommits.first()[TARGET])
            assertEquals(initial, local.memory)
            assertEquals(initial, local.disk)
            assertEquals(2, local.commitCount)
            assertEquals(if (rollbackFailure == Commit.FAIL_AFTER_MEMORY) 0 else 1, local.applyCount)
            assertEquals(0, remote.editCount)
            assertOnlyTheseKeysAccessed(local)
        }
    }

    @Test
    fun unresolvedDiskStateAfterRollbackFailureIsNeverReportedAsASuccessfulSave() {
        val initial = mapOf(ENABLED to true, INTERVAL to 123, TARGET to RAW_TARGET, OTHER to "leave untouched")
        val local = install(initial, Commit.FAIL_AFTER_DISK, Commit.FAIL_AFTER_MEMORY)
        assertFalse(Prefs.setRequestCompactionSettings(false, 456, service, target = null))
        assertEquals(initial, local.memory)
        // A failed write does not prove what reached disk; a failed rollback cannot confirm durability.
        assertEquals(initial + mapOf(ENABLED to false, INTERVAL to 456, TARGET to NO_TARGET_JSON), local.disk)
        assertEquals(2, local.commitCount)
        assertEquals(0, remote.editCount)
        assertOnlyTheseKeysAccessed(local)
    }

    @Test
    fun failedSnapshotOrUnsupportedTypeNeverStartsAWrite() {
        for (key in listOf(ENABLED, INTERVAL, TARGET)) for (failReading in listOf(false, true)) {
            val initial = mapOf(ENABLED to true, INTERVAL to 123, TARGET to RAW_TARGET, OTHER to "private") +
                (key to if (failReading) "unreadable" else Any())
            val local = install(initial)
            local.failReadingKey = key.takeIf { failReading }
            assertFalse(Prefs.setRequestCompactionSettings(false, 456, service, NEXT_TARGET))
            assertEquals(initial, local.memory)
            assertEquals(initial, local.disk)
            assertEquals(0, local.editCount)
            assertEquals(0, remote.editCount)
            assertOnlyTheseKeysAccessed(local)
        }
    }

    @Test
    fun successfulRetryAfterTwoDiskFailuresSavesAndSyncsOnlyTheNewTriple() {
        val initial = mapOf(ENABLED to false, INTERVAL to 123, TARGET to RAW_TARGET, OTHER to "leave untouched")
        val local = install(initial, Commit.FAIL_AFTER_MEMORY, Commit.FAIL_AFTER_MEMORY)
        assertFalse(Prefs.setRequestCompactionSettings(true, 456, service, NEXT_TARGET))
        assertEquals(initial, local.memory)
        assertEquals(0, remote.editCount)
        assertTrue(Prefs.setRequestCompactionSettings(true, Int.MAX_VALUE, service, NEXT_TARGET))
        assertEquals(NEXT_TARGET, Prefs.requestCompactionTarget())
        val saved = initial + mapOf(ENABLED to true, INTERVAL to Int.MAX_VALUE, TARGET to local.memory.getValue(TARGET))
        assertEquals(saved, local.memory)
        assertEquals(saved, local.disk)
        assertEquals(3, local.commitCount)
        assertEquals(saved - OTHER, remote.memory)
        assertEquals(remote.memory, remote.disk)
        assertEquals(1, remote.editCount)
        assertOnlyTheseKeysAccessed(local)
    }

    @Test
    fun invalidIntervalNeverReadsOrWritesPreferences() {
        val local = install(mapOf(ENABLED to true, INTERVAL to 123))
        for (interval in listOf(0, -1, Int.MIN_VALUE)) {
            assertFalse(Prefs.setRequestCompactionSettings(false, interval, service, target = null))
        }
        assertTrue(local.readKeys.isEmpty())
        assertEquals(0, local.editCount)
        assertEquals(0, remote.editCount)
    }

    @Test
    fun absentCorruptAndWrongTypeTargetsAlwaysFailClosed() {
        val absent = install(emptyMap())
        assertNull(Prefs.requestCompactionTarget())
        assertFalse(Prefs.requestCompactionTargetMatches("", ""))
        assertOnlyTheseKeysAccessed(absent)
        val invalid = listOf(
            true, 123, 123L, 1.5f, setOf("provider", "model"),
            "", "  ", "{", "null", "[]", "42", "true", "\"model\"", "{}",
            """{"providerId":"p"}""", """{"modelId":"m"}""",
            """{"providerId":null,"modelId":"m"}""", """{"providerId":"p","modelId":null}""",
            """{"providerId":123,"modelId":"m"}""", """{"providerId":"p","modelId":123}""",
            """{"providerId":true,"modelId":"m"}""", """{"providerId":"p","modelId":false}""",
            """{"providerId":[],"modelId":"m"}""", """{"providerId":"p","modelId":{}}""",
            """{"providerId":"","modelId":"m"}""", """{"providerId":"p","modelId":""}""",
            """{"providerId":" ","modelId":"m"}""", """{"providerId":"p","modelId":"\t"}""",
            """{"providerId":"p","displayName":"m"}""",
            """{"providerId":"p","modelId":"m"} trailing""",
            """{"providerId":"p","modelId":"m"}{}""",
            """{providerId:"p",modelId:"m"}""", """{'providerId':'p','modelId':'m'}""",
            """{"providerId":"p","modelId":"m",}""",
            """{"providerId":"${"p".repeat(4097)}","modelId":"m"}""",
            """{"providerId":"p","modelId":"${"m".repeat(4097)}"}""",
            " ".repeat(50000) + RAW_TARGET,
        )
        for (value in invalid) {
            val local = install(mapOf(TARGET to value))
            assertNull("Rejected stored target: $value", Prefs.requestCompactionTarget())
            assertFalse(Prefs.requestCompactionTargetMatches("p", "m"))
            assertFalse(Prefs.requestCompactionTargetMatches("", ""))
            assertOnlyTheseKeysAccessed(local)
        }
        val unreadable = install(mapOf(TARGET to RAW_TARGET))
        unreadable.failReadingKey = TARGET
        assertNull(Prefs.requestCompactionTarget())
    }

    @Test
    fun jsonRoundTripPreservesExactProviderInstanceAndApiModelIds() {
        val local = install(emptyMap())
        val targets = listOf(
            NEXT_TARGET,
            RequestCompactionTarget("provider-instance-A", NEXT_TARGET.modelId),
            RequestCompactionTarget(NEXT_TARGET.providerId, "different/api-id"),
            RequestCompactionTarget(" 提供商/\"\\\n ", " model/API:\"\\\t\n模型 "),
            RequestCompactionTarget("p".repeat(4096), "m".repeat(4096)),
            RequestCompactionTarget("\u0001".repeat(4096), "\u0002".repeat(4096)),
        )
        for (target in targets) {
            assertTrue(Prefs.setRequestCompactionSettings(true, 95, target = target))
            assertEquals(target, Prefs.requestCompactionTarget())
            assertTrue(Prefs.requestCompactionTargetMatches(target.providerId, target.modelId))
            assertFalse(Prefs.requestCompactionTargetMatches(target.providerId + " ", target.modelId))
            assertFalse(Prefs.requestCompactionTargetMatches(target.providerId, target.modelId + " "))
            if (target.providerId.uppercase() != target.providerId || target.modelId.uppercase() != target.modelId) {
                assertFalse(Prefs.requestCompactionTargetMatches(target.providerId.uppercase(), target.modelId.uppercase()))
            }
            assertFalse(Prefs.requestCompactionTargetMatches(target.providerId + "-another-instance", target.modelId))
            // Two- and three-positional-argument callers retain the existing target by default.
            assertTrue(Prefs.setRequestCompactionSettings(false, 123))
            // A no-service save deliberately clears the remote cache; restore the fake Binder path.
            cacheRemotePreferences()
            assertTrue(Prefs.setRequestCompactionSettings(true, 456, service))
            assertEquals(target, Prefs.requestCompactionTarget())
            assertEquals(target, Prefs.requestCompactionTarget(remote.preferences))
            assertEquals(local.memory, local.disk)
        }
        assertFalse(Prefs.requestCompactionTargetMatches(NEXT_TARGET.providerId, "same display name"))
        assertOnlyTheseKeysAccessed(local)
        assertOnlyTheseKeysAccessed(remote)
    }

    @Test
    fun explicitNullPersistsFailClosedTombstoneLocallyAndRemotelyInOneCommitEach() {
        val local = install(mapOf(ENABLED to true, INTERVAL to 123, TARGET to RAW_TARGET, OTHER to "private"))
        remote.memory[TARGET] = RAW_TARGET
        assertTrue(Prefs.setRequestCompactionSettings(false, 456, service, target = null))
        assertEquals(NO_TARGET_JSON, local.memory[TARGET])
        assertEquals(local.memory, local.disk)
        assertEquals(mapOf(ENABLED to false, INTERVAL to 456, TARGET to NO_TARGET_JSON), remote.memory)
        assertEquals(remote.memory, remote.disk)
        assertNull(Prefs.requestCompactionTarget())
        assertEquals(1, local.commitCount)
        assertEquals(1, remote.commitCount)
        assertOnlyTheseKeysAccessed(local)
        assertOnlyTheseKeysAccessed(remote)
    }

    @Test
    fun invalidTargetsAreRejectedBeforeReadingOrEditingAnyPreference() {
        val initial = mapOf(ENABLED to true, INTERVAL to 123, TARGET to RAW_TARGET)
        val local = install(initial)
        val invalid = listOf(
            RequestCompactionTarget("", "m"), RequestCompactionTarget("p", ""),
            RequestCompactionTarget(" \t\n", "m"), RequestCompactionTarget("p", " \t\n"),
            RequestCompactionTarget("p".repeat(4097), "m"), RequestCompactionTarget("p", "m".repeat(4097)),
        )
        for (target in invalid) for (enabled in listOf(false, true)) {
            assertFalse(Prefs.setRequestCompactionSettings(enabled, 95, service, target))
            assertEquals(initial, local.memory)
            assertEquals(initial, local.disk)
        }
        assertTrue(local.readKeys.isEmpty())
        assertEquals(0, local.editCount)
        assertEquals(0, remote.editCount)
    }

    @Test
    fun remoteFailureDoesNotRollBackSuccessfulLocalTriple() {
        for (failure in listOf(Commit.FAIL_AFTER_MEMORY, Commit.THROW_AFTER_MEMORY, Commit.THROW_BEFORE_MEMORY)) {
            remote = MemoryPreferences(mapOf(TARGET to RAW_TARGET), listOf(failure))
            field("cachedRemotePrefs").set(Prefs, remote.preferences)
            val local = install(mapOf(TARGET to RAW_TARGET))
            assertTrue(Prefs.setRequestCompactionSettings(true, 95, service, NEXT_TARGET))
            assertEquals(NEXT_TARGET, Prefs.requestCompactionTarget())
            assertEquals(local.memory, local.disk)
            assertEquals(1, local.commitCount)
            assertEquals(1, remote.commitCount)
            assertOnlyTheseKeysAccessed(local)
            assertOnlyTheseKeysAccessed(remote)
        }
    }

    @Test
    fun remoteFallbackOnlyAppliesWhenLocalPreferencesAreUnavailable() {
        field("remote").set(Prefs, remote.preferences)
        remote.memory[TARGET] = RAW_TARGET
        val local = install(emptyMap())
        assertNull(Prefs.requestCompactionTarget())
        field("localAgent").set(Prefs, null)
        assertEquals(RequestCompactionTarget("provider-instance-A", "model/api-id"), Prefs.requestCompactionTarget())
        assertTrue(Prefs.requestCompactionTargetMatches("provider-instance-A", "model/api-id"))
        assertFalse(Prefs.setRequestCompactionSettings(true, 95, service, NEXT_TARGET))
        assertEquals(0, local.editCount)
        assertEquals(0, remote.editCount)
        field("remote").set(Prefs, null)
        assertNull(Prefs.requestCompactionTarget())
        assertFalse(Prefs.requestCompactionTargetMatches("provider-instance-A", "model/api-id"))
    }

    @Test
    fun reconciliationMigratesTargetSeparatelyAndThenUsesTheLocalTarget() {
        val local = install(emptyMap())
        remote.memory[TARGET] = RAW_TARGET
        Prefs.reconcileAgentPreferences(service)
        assertEquals(RequestCompactionTarget("provider-instance-A", "model/api-id"), Prefs.requestCompactionTarget())
        assertEquals(local.memory, local.disk)
        assertEquals(1, local.commitCount)
        assertTrue(local.memory[TARGET] is String)
        assertTrue(Prefs.setRequestCompactionSettings(false, 123, target = NEXT_TARGET))
        cacheRemotePreferences()
        Prefs.reconcileAgentPreferences(service)
        assertEquals(NEXT_TARGET, Prefs.requestCompactionTarget(remote.preferences))
        assertEquals(mapOf(ENABLED to false, INTERVAL to 123, TARGET to local.memory.getValue(TARGET)), remote.memory)
        assertEquals(remote.memory, remote.disk)
    }

    @Test
    fun reconciliationNeverCoercesCorruptTargetsOrLetsThemMatch() {
        for (corrupt in listOf(true, 1, 2L, 3f, setOf("bad"), "broken", "{}", "[]", "")) {
            val localFromRemote = install(emptyMap())
            remote.memory[TARGET] = corrupt
            Prefs.reconcileAgentPreferences(service)
            assertNull(Prefs.requestCompactionTarget())
            assertEquals(NO_TARGET_JSON, localFromRemote.memory[TARGET])
            val local = install(mapOf(TARGET to corrupt))
            remote.memory[TARGET] = RAW_TARGET
            Prefs.reconcileAgentPreferences(service)
            assertEquals(NO_TARGET_JSON, remote.memory[TARGET])
            assertNull(Prefs.requestCompactionTarget())
            assertEquals(corrupt, local.memory[TARGET])
        }
    }

    @Test
    fun localClearTombstoneWinsAfterRemoteFailureAndReconcileRetry() {
        val local = install(emptyMap())
        Prefs.reconcileAgentPreferences(service)
        assertFalse(local.memory.containsKey(TARGET))
        assertFalse(local.disk.containsKey(TARGET))
        assertFalse(remote.memory.containsKey(TARGET))
        assertFalse(remote.disk.containsKey(TARGET))

        remote = MemoryPreferences(mapOf(TARGET to RAW_TARGET), listOf(Commit.THROW_BEFORE_MEMORY, Commit.SUCCESS))
        cacheRemotePreferences()

        assertTrue(Prefs.setRequestCompactionSettings(true, 95, service, target = null))
        assertEquals(NO_TARGET_JSON, local.memory[TARGET])
        assertEquals(NO_TARGET_JSON, local.disk[TARGET])
        assertEquals(RAW_TARGET, remote.memory[TARGET])
        assertEquals(RAW_TARGET, remote.disk[TARGET])
        assertNull(Prefs.requestCompactionTarget(local.preferences))
        assertEquals(RequestCompactionTarget("provider-instance-A", "model/api-id"), Prefs.requestCompactionTarget(remote.preferences))

        Prefs.reconcileAgentPreferences(service)
        assertEquals(NO_TARGET_JSON, remote.memory[TARGET])
        assertEquals(NO_TARGET_JSON, remote.disk[TARGET])
        assertNull(Prefs.requestCompactionTarget(local.preferences))
        assertNull(Prefs.requestCompactionTarget(remote.preferences))
        assertEquals(2, remote.commitCount)
    }

    private fun install(initial: Map<String, Any>, vararg outcomes: Commit): MemoryPreferences =
        MemoryPreferences(initial, outcomes.toList()).also { field("localAgent").set(Prefs, it.preferences) }

    private fun assertOnlyTheseKeysAccessed(preferences: MemoryPreferences) {
        assertTrue("unexpected reads: ${preferences.readKeys}", preferences.readKeys.all { it in setOf(ENABLED, INTERVAL, TARGET) })
        assertTrue("unexpected writes: ${preferences.writeKeys}", preferences.writeKeys.all { it in setOf(ENABLED, INTERVAL, TARGET) })
    }

    private fun cacheRemotePreferences() {
        field("cachedRemoteService").set(Prefs, service)
        field("cachedRemotePrefs").set(Prefs, remote.preferences)
    }

    private fun field(name: String) = Prefs::class.java.getDeclaredField(name).apply { isAccessible = true }

    private enum class Commit { SUCCESS, FAIL_AFTER_MEMORY, FAIL_AFTER_DISK, THROW_AFTER_MEMORY, THROW_BEFORE_MEMORY }

    /** Implements only keyed reads and editors; getAll/clear fail to guard unrelated/private data. */
    private class MemoryPreferences(initial: Map<String, Any>, outcomes: List<Commit> = emptyList()) {
        val memory = initial.toMutableMap()
        val disk = initial.toMutableMap()
        val readKeys = mutableListOf<String>()
        val writeKeys = mutableListOf<String>()
        val memoryAfterCommits = mutableListOf<Map<String, Any>>()
        var failReadingKey: String? = null
        var editCount = 0
        var commitCount = 0
        var applyCount = 0
        private val commits = ArrayDeque(outcomes)
        val preferences = Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(SharedPreferences::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "edit" -> editor().also { editCount++ }
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "MemoryPreferences"
                "contains", "getBoolean", "getInt", "getLong", "getFloat", "getString", "getStringSet" -> {
                    val key = args!![0] as String
                    readKeys += key
                    check(key != failReadingKey) { "preference read failed" }
                    if (method.name == "contains") memory.containsKey(key)
                    else if (!memory.containsKey(key)) args[1]
                    else when (method.name) {
                        "getBoolean" -> memory[key] as Boolean
                        "getInt" -> memory[key] as Int
                        "getLong" -> memory[key] as Long
                        "getFloat" -> memory[key] as Float
                        "getString" -> memory[key] as String
                        else -> memory[key] as Set<*>
                    }
                }
                else -> error("Unexpected preference access: ${method.name}")
            }
        } as SharedPreferences

        private fun editor(): SharedPreferences.Editor {
            val pending = linkedMapOf<String, Any?>()
            fun updateMemory() {
                pending.forEach { (key, value) ->
                    if (value == null) memory.remove(key) else memory[key] = value
                }
            }
            return Proxy.newProxyInstance(
                javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "putBoolean", "putInt", "putLong", "putFloat", "putString", "putStringSet", "remove" -> {
                        val key = args!![0] as String
                        writeKeys += key
                        pending[key] = if (method.name == "remove") null else args[1]
                        proxy
                    }
                    "commit" -> {
                        commitCount++
                        val outcome = commits.removeFirstOrNull() ?: Commit.SUCCESS
                        check(outcome != Commit.THROW_BEFORE_MEMORY) { "commit failed before memory update" }
                        updateMemory()
                        memoryAfterCommits += memory.toMap()
                        check(outcome != Commit.THROW_AFTER_MEMORY) { "commit failed after memory update" }
                        if (outcome == Commit.SUCCESS || outcome == Commit.FAIL_AFTER_DISK) {
                            disk.clear()
                            disk.putAll(memory)
                        }
                        outcome == Commit.SUCCESS
                    }
                    "apply" -> {
                        applyCount++
                        updateMemory()
                        // Async disk result is deliberately unknown, never model it as confirmed success.
                        null
                    }
                    else -> error("Unexpected preference edit: ${method.name}")
                }
            } as SharedPreferences.Editor
        }
    }

    private companion object {
        const val ENABLED = Prefs.Keys.AGENT_REQUEST_COMPACTION_ENABLED
        const val INTERVAL = Prefs.Keys.AGENT_REQUEST_COMPACTION_INTERVAL
        const val TARGET = Prefs.Keys.AGENT_REQUEST_COMPACTION_TARGET_JSON
        const val OTHER = "unrelated-private-preference"
        const val RAW_TARGET = """{ "modelId": "model/api-id", "providerId": "provider-instance-A" }"""
        const val NO_TARGET_JSON = "{\"version\":1,\"target\":null}"
        val NEXT_TARGET = RequestCompactionTarget("provider-instance-B", "model/api-id")
    }
}

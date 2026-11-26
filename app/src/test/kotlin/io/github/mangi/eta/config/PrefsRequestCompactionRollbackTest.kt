package io.github.mangi.eta.config

import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun falseOrExceptionAfterMemoryUpdateRestoresBothOldValuesAndNeverSyncsRemote() {
        for (failure in listOf(Commit.FAIL_AFTER_MEMORY, Commit.THROW_AFTER_MEMORY)) {
            val initial = mapOf(ENABLED to true, INTERVAL to 123, OTHER to "leave untouched")
            val local = install(initial, failure)
            assertFalse(Prefs.setRequestCompactionSettings(false, 456, service))
            assertEquals(false, local.memoryAfterCommits.first()[ENABLED])
            assertEquals(456, local.memoryAfterCommits.first()[INTERVAL])
            assertEquals(initial, local.memory)
            assertEquals(initial, local.disk)
            assertEquals(2, local.commitCount)
            assertEquals(0, remote.editCount)
            assertOnlyTheseKeysAccessed(local)
        }
    }

    @Test
    fun absentKeysAreRemovedAgainInsteadOfWritingTheirDefaults() {
        for (initial in listOf(
            mapOf(OTHER to "leave untouched"),
            mapOf(ENABLED to false, OTHER to "leave untouched"),
            mapOf(INTERVAL to 951, OTHER to "leave untouched"),
        )) {
            val local = install(initial, Commit.FAIL_AFTER_MEMORY)
            assertFalse(Prefs.setRequestCompactionSettings(true, 456, service))
            assertEquals(initial, local.memory)
            assertEquals(initial, local.disk)
            assertEquals(0, remote.editCount)
            assertOnlyTheseKeysAccessed(local)
        }
    }

    @Test
    fun corruptTypesArePreservedForBothKeysWithoutReadingOtherPreferences() {
        val values = listOf(true, 17, 19L, 1.5f, "corrupt", setOf("corrupt", "value"))
        for (enabled in values) for (interval in values) {
            val initial = mapOf(ENABLED to enabled, INTERVAL to interval, OTHER to "leave untouched")
            val local = install(initial, Commit.FAIL_AFTER_MEMORY)
            assertFalse(Prefs.setRequestCompactionSettings(false, Int.MAX_VALUE, service))
            assertEquals(initial, local.memory)
            assertEquals(initial, local.disk)
            assertEquals(0, remote.editCount)
            assertOnlyTheseKeysAccessed(local)
        }
    }

    @Test
    fun rollbackDiskFailureStillReturnsFalseAndKeepsRestoredMemory() {
        for (rollbackFailure in listOf(Commit.FAIL_AFTER_MEMORY, Commit.THROW_AFTER_MEMORY, Commit.THROW_BEFORE_MEMORY)) {
            val initial = mapOf(ENABLED to true, INTERVAL to 123, OTHER to "leave untouched")
            val local = install(initial, Commit.FAIL_AFTER_MEMORY, rollbackFailure)
            assertFalse(Prefs.setRequestCompactionSettings(false, 456, service))
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
        val initial = mapOf(ENABLED to true, INTERVAL to 123, OTHER to "leave untouched")
        val local = install(initial, Commit.FAIL_AFTER_DISK, Commit.FAIL_AFTER_MEMORY)
        assertFalse(Prefs.setRequestCompactionSettings(false, 456, service))
        assertEquals(initial, local.memory)
        // A failed write does not prove what reached disk; a failed rollback cannot confirm durability.
        assertEquals(initial + mapOf(ENABLED to false, INTERVAL to 456), local.disk)
        assertEquals(2, local.commitCount)
        assertEquals(0, remote.editCount)
        assertOnlyTheseKeysAccessed(local)
    }

    @Test
    fun failedSnapshotOrUnsupportedTypeNeverStartsAWrite() {
        for (failReading in listOf(false, true)) {
            val initial = mapOf(ENABLED to if (failReading) true else Any(), INTERVAL to 123, OTHER to "private")
            val local = install(initial)
            local.failReading = failReading
            assertFalse(Prefs.setRequestCompactionSettings(false, 456, service))
            assertEquals(initial, local.memory)
            assertEquals(initial, local.disk)
            assertEquals(0, local.editCount)
            assertEquals(0, remote.editCount)
            assertOnlyTheseKeysAccessed(local)
        }
    }

    @Test
    fun successfulRetryAfterTwoDiskFailuresSavesAndSyncsOnlyTheNewPair() {
        val initial = mapOf(ENABLED to false, INTERVAL to 123, OTHER to "leave untouched")
        val local = install(initial, Commit.FAIL_AFTER_MEMORY, Commit.FAIL_AFTER_MEMORY)
        assertFalse(Prefs.setRequestCompactionSettings(true, 456, service))
        assertEquals(initial, local.memory)
        assertEquals(0, remote.editCount)
        assertTrue(Prefs.setRequestCompactionSettings(true, Int.MAX_VALUE, service))
        val saved = initial + mapOf(ENABLED to true, INTERVAL to Int.MAX_VALUE)
        assertEquals(saved, local.memory)
        assertEquals(saved, local.disk)
        assertEquals(3, local.commitCount)
        assertEquals(mapOf(ENABLED to true, INTERVAL to Int.MAX_VALUE), remote.memory)
        assertEquals(remote.memory, remote.disk)
        assertEquals(1, remote.editCount)
        assertOnlyTheseKeysAccessed(local)
    }

    @Test
    fun invalidIntervalNeverReadsOrWritesPreferences() {
        val local = install(mapOf(ENABLED to true, INTERVAL to 123))
        for (interval in listOf(0, -1, Int.MIN_VALUE)) {
            assertFalse(Prefs.setRequestCompactionSettings(false, interval, service))
        }
        assertTrue(local.readKeys.isEmpty())
        assertEquals(0, local.editCount)
        assertEquals(0, remote.editCount)
    }

    private fun install(initial: Map<String, Any>, vararg outcomes: Commit): MemoryPreferences =
        MemoryPreferences(initial, outcomes.toList()).also { field("localAgent").set(Prefs, it.preferences) }

    private fun assertOnlyTheseKeysAccessed(preferences: MemoryPreferences) {
        assertTrue(preferences.readKeys.all { it in setOf(ENABLED, INTERVAL) })
        assertTrue(preferences.writeKeys.all { it in setOf(ENABLED, INTERVAL) })
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
        var failReading = false
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
                    check(!failReading) { "preference read failed" }
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
        const val OTHER = "unrelated-private-preference"
    }
}

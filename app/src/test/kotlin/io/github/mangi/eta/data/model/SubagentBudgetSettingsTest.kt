package io.github.mangi.eta.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentBudgetSettingsTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    @Test
    fun defaultBudgetMatchesDocumentedValues() {
        val budget = SubagentBudgetSettings()
        assertEquals(15, budget.maxRunMinutes)
        assertEquals(300, budget.maxRequestSeconds)
        assertEquals(180, budget.maxIdleSeconds)
        assertEquals(30, budget.maxModelCalls)
        assertEquals(100, budget.maxToolCalls)
        assertEquals(3, budget.maxConcurrent)
        assertTrue(budget.isDefault)
        assertEquals(budget, budget.normalized)
    }

    @Test
    fun normalizedClampsEveryFieldIntoItsRange() {
        val budget = SubagentBudgetSettings(
            maxRunMinutes = 0,
            maxRequestSeconds = 5,
            maxIdleSeconds = 0,
            maxModelCalls = 0,
            maxToolCalls = 99_999,
            maxConcurrent = 99,
        ).normalized
        assertEquals(1, budget.maxRunMinutes)
        assertEquals(30, budget.maxRequestSeconds)
        assertEquals(15, budget.maxIdleSeconds)
        assertEquals(1, budget.maxModelCalls)
        assertEquals(1_000, budget.maxToolCalls)
        assertEquals(8, budget.maxConcurrent)

        val upper = SubagentBudgetSettings(
            maxRunMinutes = 999,
            maxRequestSeconds = 99_999,
            maxIdleSeconds = 99_999,
            maxModelCalls = 9_999,
            maxToolCalls = 9_999,
            maxConcurrent = 999,
        ).normalized
        assertEquals(120, upper.maxRunMinutes)
        // 1800 秒仍在 120 分钟（7200 秒）任务上限之内，因此按字段区间封顶。
        assertEquals(1_800, upper.maxRequestSeconds)
        assertEquals(900, upper.maxIdleSeconds)
        assertEquals(200, upper.maxModelCalls)
        assertEquals(1_000, upper.maxToolCalls)
        assertEquals(8, upper.maxConcurrent)
    }

    @Test
    fun normalizedKeepsIdleWithinRequestWithinRun() {
        // 任务只有 1 分钟时，1800 秒的请求超时被收到 60 秒，空闲超时也随之收到 60 秒。
        val budget = SubagentBudgetSettings(
            maxRunMinutes = 1,
            maxRequestSeconds = 1_800,
            maxIdleSeconds = 900,
        ).normalized
        assertEquals(1, budget.maxRunMinutes)
        assertEquals(60, budget.maxRequestSeconds)
        assertEquals(60, budget.maxIdleSeconds)

        val tightRequest = SubagentBudgetSettings(
            maxRunMinutes = 60,
            maxRequestSeconds = 60,
            maxIdleSeconds = 900,
        ).normalized
        assertEquals(60, tightRequest.maxRequestSeconds)
        assertEquals(60, tightRequest.maxIdleSeconds)
    }

    @Test
    fun timeoutsAreDerivedFromNormalizedValues() {
        val budget = SubagentBudgetSettings(maxRunMinutes = 2, maxRequestSeconds = 70, maxIdleSeconds = 20)
        assertEquals(120_000L, budget.runTimeoutMillis)
        assertEquals(70_000L, budget.requestTimeoutMillis)
        assertEquals(20_000L, budget.idleTimeoutMillis)

        val clamped = SubagentBudgetSettings(maxRunMinutes = 0, maxRequestSeconds = 1_800)
        assertEquals(60_000L, clamped.runTimeoutMillis)
        assertEquals(60_000L, clamped.requestTimeoutMillis)
    }

    @Test
    fun serializedSubagentSettingsRoundTripKeepsBudget() {
        val settings = SubagentSettings(
            modelId = "model-x",
            reasoningEffort = "HIGH",
            budget = SubagentBudgetSettings(maxRunMinutes = 42, maxConcurrent = 5),
        )
        val decoded = json.decodeFromString<SubagentSettings>(json.encodeToString(settings))
        assertEquals(settings, decoded)
        assertEquals(42, decoded.normalizedBudget.maxRunMinutes)
        assertEquals(5, decoded.normalizedBudget.maxConcurrent)
    }

    @Test
    fun legacySubagentJsonWithoutBudgetFallsBackToDefaults() {
        val legacy = json.decodeFromString<SubagentSettings>("""{"modelId":"m1"}""")
        assertEquals("m1", legacy.modelId)
        assertEquals(SubagentBudgetSettings(), legacy.budget)
        assertTrue(legacy.normalizedBudget.isDefault)
    }

    @Test
    fun isDefaultIgnoresUnnormalizedCopiesOnlyWhenValuesDiffer() {
        assertFalse(SubagentBudgetSettings(maxToolCalls = 101).isDefault)
        // 越界值先被算作非默认，归一化只是夹到区间端点，不会悄悄变回默认。
        val outOfRange = SubagentBudgetSettings(maxToolCalls = 5_000)
        assertFalse(outOfRange.isDefault)
        assertEquals(SubagentBudgetSettings(maxToolCalls = 1_000), outOfRange.normalized)
        assertFalse(outOfRange.normalized.isDefault)
    }
}

package io.github.mangi.eta.agent.skill

import io.github.mangi.eta.data.repository.AgentCombatProtocolStore
import io.github.mangi.eta.data.repository.ReverseCapabilitySettings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReverseCapabilityRegressionTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun securitySkillsAreFilteredButGenericSkillsRemain() {
        for (id in listOf("apk-reverse", "eni-apk-reverse", "eni-redteam-xss-detail-pack")) {
            assertTrue(id, ReverseSkillCatalog.isReverse(id))
        }
        for (id in listOf("skill-creator", "eni-docs", "eni-diagram", "user-writing-helper")) {
            assertFalse(id, ReverseSkillCatalog.isReverse(id))
        }
        assertTrue(ReverseSkillCatalog.isReverse("custom-auditor", mapOf("family" to "security")))
    }

    @Test fun disabledBundleHidesSecurityOnly() {
        fun entry(id: String) = SkillIndexEntry(id, id, "", rootPath = "/tmp/$id",
            skillFilePath = "/tmp/$id/SKILL.md", hasScripts = false, hasReferences = false,
            hasAssets = false, hasEvals = false)
        val entries = listOf(entry("apk-reverse"), entry("skill-creator"), entry("user-writing-helper"))
        assertEquals(listOf("skill-creator", "user-writing-helper"),
            ReverseCapabilityRuntime.filterVisible(entries, false).map { it.id })
        assertEquals(entries, ReverseCapabilityRuntime.filterVisible(entries, true))
    }

    @Test fun toggleReadsExternalChangesRatherThanCachedPreference() {
        val dir = tmp.newFolder()
        ReverseCapabilitySettings.init(dir)
        assertTrue(ReverseCapabilitySettings.isEnabled())
        assertTrue(ReverseCapabilitySettings.setEnabled(null, false))
        assertFalse(ReverseCapabilitySettings.isEnabled())
        java.io.File(dir, "reverse_capability.enabled").writeText("true")
        assertTrue(ReverseCapabilitySettings.isEnabled())
    }

    @Test fun protocolIsReadOnlyAndStillLoadedFromDisk() {
        val dir = tmp.newFolder()
        val store = AgentCombatProtocolStore(dir)
        assertFalse(store.snapshot().available)
        // 应用内不再提供写入/清空入口；用户自行放置的 .md 仍被只读装载并原样注入。
        val prompts = java.io.File(dir, "prompts").apply { mkdirs() }
        java.io.File(prompts, "README.md").writeText("ignored")
        java.io.File(prompts, "笔记.md").writeText("   ")
        java.io.File(prompts, "作战协议.md").writeText("Prefer short responses.")
        val loaded = AgentCombatProtocolStore(dir).snapshot()
        assertTrue(loaded.available)
        assertEquals("Prefer short responses.", loaded.content)
        assertEquals("作战协议.md", loaded.fileName)
        assertEquals(loaded.revision, AgentCombatProtocolStore(dir).snapshot().revision)
    }

    @Test fun promptStillInjectsExistingProtocolButNotWhenDisabled() {
        val snapshot = io.github.mangi.eta.data.repository.AgentCombatProtocolSnapshot(
            content = "Prefer short responses.",
            revision = "abc12345",
            byteSize = 22,
            charCount = 22,
            fileName = "作战协议.md",
            truncated = false,
        )
        val enabled = io.github.mangi.eta.agent.model.AgentPromptBuilder.buildSystemMessages(
            config = promptConfig(),
            skillContext = SkillContext.EMPTY,
            memoryContext = io.github.mangi.eta.agent.memory.AgentMemoryContext.DISABLED,
            rootAvailable = false,
            combatProtocol = snapshot,
        ).toString()
        assertTrue(enabled.contains("<combat_protocol>"))
        assertTrue(enabled.contains("Prefer short responses."))
        val disabled = io.github.mangi.eta.agent.model.AgentPromptBuilder.buildSystemMessages(
            config = promptConfig(),
            skillContext = SkillContext.EMPTY,
            memoryContext = io.github.mangi.eta.agent.memory.AgentMemoryContext.DISABLED,
            rootAvailable = false,
            combatProtocol = io.github.mangi.eta.data.repository.AgentCombatProtocolSnapshot.EMPTY,
        ).toString()
        assertFalse(disabled.contains("<combat_protocol>"))
    }

    private fun promptConfig() = io.github.mangi.eta.agent.model.AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid/v1",
        apiKey = "test",
        model = "test-model",
        systemPrompt = "",
    )
}

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

    @Test fun protocolRoundTripAndClearKeepsBackup() {
        val dir = tmp.newFolder()
        val store = AgentCombatProtocolStore(dir)
        assertFalse(store.snapshot().available)
        val saved = store.write("Prefer short responses.")
        assertEquals("Prefer short responses.", saved.content)
        assertEquals(saved.revision, AgentCombatProtocolStore(dir).snapshot().revision)
        assertFalse(store.clear().available)
        assertTrue(java.io.File(dir, "prompts/.backup").listFiles()!!.isNotEmpty())
    }
}

package io.github.mangi.eta.agent.prompt

import io.github.mangi.eta.agent.subagent.AgentSubagentToolCatalog
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具白名单的请求侧过滤 + 执行侧判定的纯逻辑（无需 Robolectric）。
 *
 * 关键不变量：白名单候选列表必须覆盖所有真实可调用的工具，
 * 否则用户在界面上「找不到」某项，等于该工具被静默禁用。
 */
class AgentToolPolicyTest {

    private fun schema(name: String) =
        JSONObject().put("type", "function")
            .put("function", JSONObject().put("name", name))

    @Test
    fun allowAllKeepsEveryTool() {
        val policy = AgentToolPolicy()
        val tools = JSONArray().put(schema("tap")).put(schema("generate_image"))
        assertEquals(2, policy.filterTools(tools).length())
        assertTrue(policy.allows("anything_at_all"))
    }

    @Test
    fun allowListFiltersRequestAndExecutionSide() {
        val policy = AgentToolPolicy(mode = ToolPolicyMode.ALLOW_LIST, allowedTools = setOf("tap"))
        val tools = JSONArray().put(schema("tap")).put(schema("generate_image")).put(schema("task_write"))
        val filtered = policy.filterTools(tools)
        assertEquals(1, filtered.length())
        assertEquals("tap", filtered.getJSONObject(0).getJSONObject("function").getString("name"))
        assertTrue(policy.allows("tap"))
        // 执行侧必须同样拒绝，否则模型能从历史里拼出被禁的调用。
        assertFalse(policy.allows("generate_image"))
        assertFalse(policy.allows("task_write"))
    }

    @Test
    fun policyRoundTripsThroughJsonAndSurvivesCorruption() {
        val policy = AgentToolPolicy(
            mode = ToolPolicyMode.ALLOW_LIST,
            allowedTools = setOf("knowledge_search", "subagent"),
            version = 3,
        )
        val decoded = AgentToolPolicy.decode(policy.toJson().toString())
        assertEquals(ToolPolicyMode.ALLOW_LIST, decoded.mode)
        assertEquals(setOf("knowledge_search", "subagent"), decoded.allowedTools)
        assertEquals(3, decoded.version)

        // 损坏的配置回落到「全部开放」，不能因此把用户挡在所有工具之外。
        assertEquals(ToolPolicyMode.ALLOW_ALL, AgentToolPolicy.decode("{not json}").mode)
        assertEquals(ToolPolicyMode.ALLOW_ALL, AgentToolPolicy.decode(null).mode)
    }

    @Test
    fun withModeAndAllowedBumpVersion() {
        val base = AgentToolPolicy(version = 5)
        assertEquals(6, base.withMode(ToolPolicyMode.ALLOW_LIST).version)
        assertEquals(6, base.withAllowed(setOf("tap")).version)
    }

    @Test
    fun knowledgeToolsAreRegisteredAndNeedNoRoot() {
        listOf("knowledge_search", "knowledge_get", "knowledge_save", "knowledge_delete").forEach { name ->
            assertTrue("$name 未登记到 AgentToolRequirements", AgentToolRequirements.find(name) != null)
            assertFalse(
                "$name 不应依赖 root",
                AgentToolRequirements.rootDenied(name, JSONObject(), rootAvailable = false),
            )
        }
    }

    @Test
    fun runtimeConditionalToolsAreNotInRequirementsButDoReachTheWhitelist() {
        // 生图有独立开关，但仍登记能力要求；任务清单按会话注入。
        assertTrue(AgentToolRequirements.find("generate_image") != null)
        assertTrue(AgentToolRequirements.find("task_write") == null)
        val conditional = setOf(
            io.github.mangi.eta.agent.tool.AgentTaskTool.TASK_WRITE,
            io.github.mangi.eta.agent.image.AgentImageGenToolCatalog.GENERATE_IMAGE,
        )
        assertEquals(setOf("task_write", "generate_image"), conditional)
    }

    @Test
    fun whitelistCandidatesIncludeRuntimeInjectedTools() {
        val known = AgentToolRequirements.knownToolNames()
        // subagent、会话历史、任务清单由运行时另行拼装；生图已登记要求。
        // 界面把它们并进候选，避免「白名单里找不到它们」。
        assertTrue(AgentSubagentToolCatalog.names.contains("subagent"))
        assertFalse(known.contains("subagent"))
        assertFalse(known.contains("task_write"))
        assertTrue(known.contains("generate_image"))
    }
}

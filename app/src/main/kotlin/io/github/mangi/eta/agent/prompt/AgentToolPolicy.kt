package io.github.mangi.eta.agent.prompt

import io.github.mangi.eta.config.Prefs
import org.json.JSONArray
import org.json.JSONObject

/** 工具开放范围：全部开放，或只开放白名单里的工具。 */
internal enum class ToolPolicyMode {
    ALLOW_ALL,
    ALLOW_LIST,
}

/**
 * 工具白名单与能力开关的正式配置。
 *
 * 与"界面隐藏"不同，这里同时作用于两处：请求里的 tools 数组按白名单过滤，
 * 执行侧再用 [allows] 兜底拒绝，避免模型从历史或复述里拼出未授权工具调用。
 */
internal data class AgentToolPolicy(
    val mode: ToolPolicyMode = ToolPolicyMode.ALLOW_ALL,
    val allowedTools: Set<String> = emptySet(),
    val version: Int = 0,
) {
    fun allows(toolName: String): Boolean =
        mode == ToolPolicyMode.ALLOW_ALL || toolName in allowedTools

    fun filterTools(tools: JSONArray): JSONArray {
        if (mode == ToolPolicyMode.ALLOW_ALL) return tools
        val filtered = JSONArray()
        for (index in 0 until tools.length()) {
            val schema = tools.optJSONObject(index) ?: continue
            val name = schema.optJSONObject("function")?.optString("name").orEmpty()
            if (name.isNotEmpty() && allows(name)) filtered.put(schema)
        }
        return filtered
    }

    fun withMode(mode: ToolPolicyMode): AgentToolPolicy = copy(mode = mode, version = version + 1)

    fun withAllowed(allowedTools: Set<String>): AgentToolPolicy =
        copy(allowedTools = allowedTools, version = version + 1)

    fun toJson(): JSONObject = JSONObject()
        .put("mode", mode.name)
        .put("allowed", JSONArray().also { array -> allowedTools.sorted().forEach(array::put) })
        .put("version", version)

    companion object {
        fun decode(raw: String?): AgentToolPolicy =
            if (raw.isNullOrBlank()) {
                AgentToolPolicy()
            } else {
                runCatching { fromJson(JSONObject(raw)) }.getOrElse { AgentToolPolicy() }
            }

        fun fromJson(json: JSONObject): AgentToolPolicy {
            val allowed = json.optJSONArray("allowed")?.let { array ->
                (0 until array.length()).mapNotNull { index -> array.optString(index).takeIf(String::isNotBlank) }
                    .toSet()
            }.orEmpty()
            return AgentToolPolicy(
                mode = runCatching { ToolPolicyMode.valueOf(json.optString("mode")) }
                    .getOrDefault(ToolPolicyMode.ALLOW_ALL),
                allowedTools = allowed,
                version = json.optInt("version", 0),
            )
        }
    }
}

internal object AgentToolPolicyStore {
    private const val KEY = "agent_tool_policy_json"

    @Volatile
    private var cachedRaw: String? = null

    @Volatile
    private var cachedValue: AgentToolPolicy = AgentToolPolicy()

    fun load(): AgentToolPolicy {
        val preferences = Prefs.localAgentPreferences() ?: return AgentToolPolicy()
        val raw = runCatching { preferences.getString(KEY, null) }.getOrNull()
        if (raw == cachedRaw) return cachedValue
        val value = AgentToolPolicy.decode(raw)
        cachedRaw = raw
        cachedValue = value
        return value
    }

    fun save(value: AgentToolPolicy): Boolean {
        val preferences = Prefs.localAgentPreferences() ?: return false
        val raw = value.toJson().toString()
        val written = runCatching { preferences.edit().putString(KEY, raw).commit() }.getOrDefault(false)
        if (written) {
            cachedRaw = raw
            cachedValue = value
        }
        return written
    }
}

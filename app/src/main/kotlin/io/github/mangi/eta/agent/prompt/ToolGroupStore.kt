package io.github.mangi.eta.agent.prompt

import io.github.mangi.eta.config.Prefs
import org.json.JSONArray
import org.json.JSONObject

/** 用户自定义的工具组合（与内置"常用组合"并列展示，可随时套用/删除）。 */
internal data class NamedToolGroup(val name: String, val tools: Set<String>)

internal object ToolGroupStore {
    private const val KEY = "agent_tool_groups_json"

    fun load(): List<NamedToolGroup> {
        val raw = runCatching {
            Prefs.localAgentPreferences()?.getString(KEY, null)
        }.getOrNull() ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val name = item.optString("name").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val tools = item.optJSONArray("tools")?.let { list ->
                (0 until list.length()).mapNotNull { i -> list.optString(i).takeIf(String::isNotBlank) }.toSet()
            }.orEmpty()
            NamedToolGroup(name, tools)
        }
    }

    fun saveGroup(name: String, tools: Set<String>): Boolean {
        val trimmed = name.trim().take(24)
        if (trimmed.isEmpty()) return false
        val next = (listOf(NamedToolGroup(trimmed, tools)) + load().filterNot { it.name == trimmed }).take(MAX_GROUPS)
        return persist(next)
    }

    fun deleteGroup(name: String): Boolean = persist(load().filterNot { it.name == name })

    fun renameGroup(from: String, to: String): Boolean {
        val target = to.trim().take(24)
        if (target.isEmpty()) return false
        val groups = load()
        val source = groups.firstOrNull { it.name == from } ?: return false
        val next = buildList {
            add(source.copy(name = target))
            groups.forEach { group ->
                if (group.name != from && group.name != target) add(group)
            }
        }
        return persist(next)
    }

    private fun persist(groups: List<NamedToolGroup>): Boolean {
        val array = JSONArray()
        groups.forEach { group ->
            array.put(
                JSONObject()
                    .put("name", group.name)
                    .put("tools", JSONArray().also { list -> group.tools.sorted().forEach(list::put) }),
            )
        }
        return runCatching {
            Prefs.localAgentPreferences()?.edit()?.putString(KEY, array.toString())?.commit()
        }.getOrDefault(false) == true
    }

    private const val MAX_GROUPS = 12
}

package io.github.mangi.eta.agent.model

import org.json.JSONArray

/** 从活跃上下文恢复已接纳的 CHAT 请求数，不统计原始 journal、工具结果或摘要请求。 */
internal object AgentRequestCompactionPolicy {
    fun completedRequests(messages: JSONArray, systemCount: Int): Int {
        var count = 0
        for (index in messages.length() - 1 downTo systemCount) {
            val message = messages.optJSONObject(index) ?: continue
            if (message.optBoolean("_eta_context_summary")) break
            // 角色开场白由 App 本地创建，并非成功 CHAT；旧历史没有 ID 时仍应正常累计。
            if (message.optString("role") == "assistant" &&
                !message.optString("_eta_message_id").startsWith("greeting-")
            ) count++
        }
        return count
    }
}

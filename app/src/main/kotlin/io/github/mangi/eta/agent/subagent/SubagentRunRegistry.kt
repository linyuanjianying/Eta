package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.runtime.AgentRunController
import java.util.concurrent.ConcurrentHashMap

/**
 * 进程级子代理登记表：记录仍在跑的子代理及其控制器。
 *
 * 用途是把「暂停/继续/定向补充指令」落到正在干活的子代理上，而不是一律塞给父 Agent
 * （父 Agent 收到时子代理还在跑，等于没说）。同时给界面提供会话维度的存活列表。
 */
internal object SubagentRunRegistry {

    private val controllers = ConcurrentHashMap<String, AgentRunController>()

    /** 子智能体所属会话：输入框的目标胶囊必须按会话过滤，否则会串到别的对话里。 */
    private val conversationOf = ConcurrentHashMap<String, String>()

    /** 处于暂停态的子智能体 id（只用于界面回显，真正的挂起由控制器内部完成）。 */
    private val pausedIds: MutableSet<String> =
        java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    fun register(conversationKey: String, subagentId: String, controller: AgentRunController) {
        controllers[subagentId] = controller
        conversationOf[subagentId] = conversationKey
    }

    fun unregister(subagentId: String) {
        controllers.remove(subagentId)
        conversationOf.remove(subagentId)
        pausedIds.remove(subagentId)
    }

    /** 暂停指定子智能体：它在下一个检查点挂起，进度不丢。@return false 表示它已经结束。 */
    fun pause(subagentId: String): Boolean {
        val controller = controllers[subagentId] ?: return false
        controller.pause()
        pausedIds.add(subagentId)
        return true
    }

    fun resume(subagentId: String): Boolean {
        val controller = controllers[subagentId] ?: return false
        controller.resume()
        pausedIds.remove(subagentId)
        return true
    }

    fun isPaused(subagentId: String): Boolean = pausedIds.contains(subagentId)

    fun liveIds(conversationKey: String?): List<String> {
        val key = conversationKey?.takeIf { it.isNotBlank() } ?: return emptyList()
        return controllers.keys.filter { id -> conversationOf[id] == key }
    }

    fun liveIds(): List<String> = controllers.keys.toList()

    /** 向指定子智能体投递补充指令；在下一个回合边界注入，不打断正在执行的工具批次。 */
    fun steer(subagentId: String, text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        return controllers[subagentId]?.steer(trimmed) ?: false
    }
}

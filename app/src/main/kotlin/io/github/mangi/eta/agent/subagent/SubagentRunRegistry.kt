package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.runtime.AgentRunController
import java.util.concurrent.ConcurrentHashMap

/**
 * 进程级子代理登记表：记录仍在跑的子代理及其控制器。
 *
 * 用途是把「暂停/继续/定向补充指令」落到正在干活的子代理上，而不是一律塞给父 Agent
 * （父 Agent 收到时子代理还在跑，等于没说）。同时给界面提供会话维度的存活列表。
 *
 * 登记键必须是（会话, 子代理）复合键：`SubagentSessionStore` 的 id 历史上有按会话
 * 递增的形态，老归档里的 `sub_1` 在两个会话里同时存在时，只按 id 登记会互相覆盖，
 * 一次 unregister 就会把另一个会话里仍活着的子代理抹掉。
 *
 * 单 id 的 pause/resume/steer 保留下来兼容既有调用方，但只在 id 全局唯一、
 * 能无歧义落到某一个（会话, 子代理）时才执行；有歧义一律返回 false，让调用方
 * 改用带 conversationKey 的重载，而不是赌一个随机对象。
 */
internal object SubagentRunRegistry {

    private data class RunKey(
        val conversationKey: String,
        val subagentId: String,
    )

    private val controllers = ConcurrentHashMap<RunKey, AgentRunController>()

    /** 处于暂停态的子代理（只用于界面回显，真正的挂起由控制器内部完成）。 */
    private val pausedKeys: MutableSet<RunKey> =
        java.util.Collections.newSetFromMap(ConcurrentHashMap<RunKey, Boolean>())

    fun register(conversationKey: String, subagentId: String, controller: AgentRunController) {
        val key = RunKey(conversationKey, subagentId)
        controllers[key] = controller
        pausedKeys.remove(key)
    }

    /** 只注销当前会话的这一个子代理；同 id 的其他会话登记保持不变。 */
    fun unregister(conversationKey: String, subagentId: String): Boolean =
        remove(RunKey(conversationKey, subagentId))

    /**
     * 兼容旧调用的单 id 注销：子代理结束时同 id 的所有残留登记都应消失，
     * 所以这里一次性清掉全部匹配项，不做唯一性判断。
     */
    fun unregister(subagentId: String) {
        keysOf(subagentId).forEach { key -> remove(key) }
    }

    fun isRegistered(conversationKey: String, subagentId: String): Boolean =
        controllers.containsKey(RunKey(conversationKey, subagentId))

    /** 暂停指定子智能体：它在下一个检查点挂起，进度不丢。@return false 表示它已经结束或 id 有歧义。 */
    fun pause(subagentId: String): Boolean {
        val key = uniqueKeyOf(subagentId) ?: return false
        return pause(key)
    }

    fun pause(conversationKey: String, subagentId: String): Boolean =
        pause(RunKey(conversationKey, subagentId))

    fun resume(subagentId: String): Boolean {
        val key = uniqueKeyOf(subagentId) ?: return false
        return resume(key)
    }

    fun resume(conversationKey: String, subagentId: String): Boolean =
        resume(RunKey(conversationKey, subagentId))

    fun isPaused(subagentId: String): Boolean {
        val key = uniqueKeyOf(subagentId) ?: return false
        return pausedKeys.contains(key)
    }

    fun isPaused(conversationKey: String, subagentId: String): Boolean =
        pausedKeys.contains(RunKey(conversationKey, subagentId))

    fun liveIds(conversationKey: String?): List<String> {
        val key = conversationKey?.takeIf { it.isNotBlank() } ?: return emptyList()
        return controllers.keys
            .filter { it.conversationKey == key }
            .map { it.subagentId }
    }

    fun liveIds(): List<String> = controllers.keys.map { it.subagentId }.distinct()

    /** 向指定子智能体投递补充指令；在下一个回合边界注入，不打断正在执行的工具批次。 */
    fun steer(subagentId: String, text: String): Boolean {
        val key = uniqueKeyOf(subagentId) ?: return false
        return steer(key, text)
    }

    fun steer(conversationKey: String, subagentId: String, text: String): Boolean =
        steer(RunKey(conversationKey, subagentId), text)

    private fun pause(key: RunKey): Boolean {
        val controller = controllers[key] ?: return false
        controller.pause()
        pausedKeys.add(key)
        return true
    }

    private fun resume(key: RunKey): Boolean {
        val controller = controllers[key] ?: return false
        controller.resume()
        pausedKeys.remove(key)
        return true
    }

    private fun steer(key: RunKey, text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        return controllers[key]?.steer(trimmed) ?: false
    }

    private fun remove(key: RunKey): Boolean {
        pausedKeys.remove(key)
        return controllers.remove(key) != null
    }

    private fun keysOf(subagentId: String): List<RunKey> =
        controllers.keys.filter { it.subagentId == subagentId }

    /** id 在全部会话里只对应一个登记时返回它；0 个或多个都视为不可无歧义操作。 */
    private fun uniqueKeyOf(subagentId: String): RunKey? {
        val matches = keysOf(subagentId)
        return matches.singleOrNull()
    }
}

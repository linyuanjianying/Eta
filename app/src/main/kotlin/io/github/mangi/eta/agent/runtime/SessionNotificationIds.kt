package io.github.mangi.eta.agent.runtime

/**
 * 会话卡的通知 ID 分配器。
 *
 * 一张流体云卡对应一个会话，所以每条会话要有稳定的通知 ID：
 * 同一个会话反复发布永远是同一个 ID（就地更新，不会堆卡）；会话结束后回收。
 * 空闲常驻卡固定 [IDLE_NOTIFICATION_ID]，与 3 位数历史 ID（无障碍、中断提醒）岔开。
 */
internal object SessionNotificationIds {

    /** 空闲常驻卡：没任务时「点开继续」那一条。 */
    const val IDLE_NOTIFICATION_ID = 4772

    private const val BASE = 4800
    private const val RANGE = 400

    private val lock = Any()
    private val keyToId = LinkedHashMap<String, Int>()
    private val idToKey = HashMap<Int, String>()

    /** 任务结束通知用另一段 ID，和运行中/空闲卡岔开，避免互相覆盖。 */
    private const val COMPLETION_BASE = 5300
    private val completionKeyToId = LinkedHashMap<String, Int>()
    private val completionIdToKey = HashMap<Int, String>()

    fun idFor(key: String): Int = synchronized(lock) {
        keyToId[key]?.let { return it }
        for (offset in 0 until RANGE) {
            val candidate = BASE + offset
            if (candidate !in idToKey) {
                keyToId[key] = candidate
                idToKey[candidate] = key
                return candidate
            }
        }
        // 极端情况（并发会话超过 400）回收最早的一张，保证不出负数 ID。
        val oldest = keyToId.entries.firstOrNull()
        if (oldest != null) {
            keyToId.remove(oldest.key)
            idToKey.remove(oldest.value)
        }
        keyToId[key] = BASE
        idToKey[BASE] = key
        BASE
    }

    /**
     * 任务结束通知的稳定 ID。
     *
     * 与 [idFor] 同样的分配策略，但落在 [COMPLETION_BASE] 起的独立区间：
     * 一条会话跑完只在原位更新自己那一条，不会盖掉别的会话的卡。
     */
    fun completionIdFor(key: String): Int = synchronized(lock) {
        if (key.isBlank()) return COMPLETION_BASE
        completionKeyToId[key]?.let { return it }
        for (offset in 0 until RANGE) {
            val candidate = COMPLETION_BASE + offset
            if (candidate !in completionIdToKey) {
                completionKeyToId[key] = candidate
                completionIdToKey[candidate] = key
                return candidate
            }
        }
        val oldest = completionKeyToId.entries.firstOrNull()
        if (oldest != null) {
            completionKeyToId.remove(oldest.key)
            completionIdToKey.remove(oldest.value)
        }
        completionKeyToId[key] = COMPLETION_BASE
        completionIdToKey[COMPLETION_BASE] = key
        COMPLETION_BASE
    }

    fun release(key: String) = synchronized(lock) {
        keyToId.remove(key)?.let { id ->
            idToKey.remove(id)
            Unit
        }
        Unit
    }

    /** 是否是我们为会话卡分配的 ID（通知被划掉时用）。 */
    fun isSessionCard(id: Int): Boolean = synchronized(lock) { id in idToKey }

    fun keyOf(id: Int): String? = synchronized(lock) { idToKey[id] }
}

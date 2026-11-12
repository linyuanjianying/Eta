package io.github.mangi.eta.agent.subagent

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话缓存回归：id 必须跨会话全局唯一，否则暂停/中断会落到别的会话的子代理上。
 */
class SubagentSessionStoreTest {

    private fun conversation(): String = "conv-${UUID.randomUUID()}"

    @Test
    fun createdAtSubagentIdsAreGloballyUniqueAcrossConversations() {
        val conversationA = conversation()
        val conversationB = conversation()

        val first = SubagentSessionStore.create(conversationA, SubagentProfile.EXPLORE)
        val second = SubagentSessionStore.create(conversationA, SubagentProfile.CODER)
        val otherConversation = SubagentSessionStore.create(conversationB, SubagentProfile.EXPLORE)

        assertTrue(first.subagentId.startsWith("sub_1-"))
        assertTrue(second.subagentId.startsWith("sub_2-"))
        assertTrue(otherConversation.subagentId.startsWith("sub_1-"))
        assertNotEquals(first.subagentId, second.subagentId)
        // 决定性断言：不同会话里各自的第一个子代理也不能撞 id。
        assertNotEquals(first.subagentId, otherConversation.subagentId)
        assertTrue(first.running.get())

        // 按会话查只能命中自己的那一个。
        assertEquals(first, SubagentSessionStore.get(conversationA, first.subagentId))
        assertNull(SubagentSessionStore.get(conversationA, otherConversation.subagentId))
        assertNotNull(SubagentSessionStore.get(conversationB, otherConversation.subagentId))
        assertNull(SubagentSessionStore.get(conversationB, first.subagentId))
    }

    @Test
    fun resumeTargetAfterCompletionKeepsHistoryAndReleasesRunningFlag() {
        val conversationKey = conversation()
        val created = SubagentSessionStore.create(conversationKey, SubagentProfile.EXPLORE)

        SubagentSessionStore.complete(conversationKey, created.subagentId, emptyList())
        val completed = SubagentSessionStore.get(conversationKey, created.subagentId)
        assertNotNull(completed)
        assertFalse(completed!!.running.get())

        // 续接前必须能原子地把 running 从 false 抢回 true。
        assertTrue(completed.running.compareAndSet(false, true))
        SubagentSessionStore.releaseRunning(conversationKey, created.subagentId)
        assertFalse(completed.running.get())
        assertNull(SubagentSessionStore.get(conversationKey, "sub_missing"))
    }
}

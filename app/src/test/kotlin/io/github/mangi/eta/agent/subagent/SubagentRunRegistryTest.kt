package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.runtime.AgentRunController
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 登记表回归：复合键隔离同名子代理，单 id 控制在有歧义时必须拒绝执行。
 */
class SubagentRunRegistryTest {

    private fun conversation(): String = "conv-${UUID.randomUUID()}"

    @Test
    fun sameSubagentIdInDifferentConversationsIsIsolated() {
        val conversationA = conversation()
        val conversationB = conversation()
        val controllerA = AgentRunController()
        val controllerB = AgentRunController()

        SubagentRunRegistry.register(conversationA, "sub_1", controllerA)
        SubagentRunRegistry.register(conversationB, "sub_1", controllerB)
        try {
            assertEquals(listOf("sub_1"), SubagentRunRegistry.liveIds(conversationA))
            assertEquals(listOf("sub_1"), SubagentRunRegistry.liveIds(conversationB))
            assertTrue(SubagentRunRegistry.isRegistered(conversationA, "sub_1"))
            assertTrue(SubagentRunRegistry.isRegistered(conversationB, "sub_1"))

            // 单 id 在两个会话里同时存在 → 有歧义，任何控制操作都不能赌一个随机对象。
            assertFalse(SubagentRunRegistry.pause("sub_1"))
            assertFalse(controllerA.isPaused)
            assertFalse(controllerB.isPaused)
            assertFalse(SubagentRunRegistry.steer("sub_1", "补充指令"))

            // 会话内精确操作照常生效，且不影响另一个会话。
            assertTrue(SubagentRunRegistry.pause(conversationB, "sub_1"))
            assertTrue(controllerB.isPaused)
            assertFalse(controllerA.isPaused)
            assertTrue(SubagentRunRegistry.isPaused(conversationB, "sub_1"))
            assertFalse(SubagentRunRegistry.isPaused(conversationA, "sub_1"))
            assertTrue(SubagentRunRegistry.resume(conversationB, "sub_1"))
            assertFalse(controllerB.isPaused)

            assertTrue(SubagentRunRegistry.steer(conversationA, "sub_1", "先看 A 会话"))
            assertEquals("先看 A 会话", controllerA.pollSteeringMessage())
            assertEquals(null, controllerB.pollSteeringMessage())

            // 会话内注销只摘掉自己那一条，另一个会话的子代理继续存活。
            assertTrue(SubagentRunRegistry.unregister(conversationA, "sub_1"))
            assertFalse(SubagentRunRegistry.isRegistered(conversationA, "sub_1"))
            assertTrue(SubagentRunRegistry.isRegistered(conversationB, "sub_1"))
            assertFalse(SubagentRunRegistry.unregister(conversationA, "sub_1"))
        } finally {
            SubagentRunRegistry.unregister("sub_1")
        }
        assertTrue(SubagentRunRegistry.liveIds(conversationB).isEmpty())
    }

    @Test
    fun uniqueSubagentIdKeepsLegacySingleIdApiWorking() {
        val conversationKey = conversation()
        val controller = AgentRunController()
        SubagentRunRegistry.register(conversationKey, "sub_legacy", controller)
        try {
            assertTrue(SubagentRunRegistry.pause("sub_legacy"))
            assertTrue(controller.isPaused)
            assertTrue(SubagentRunRegistry.isPaused("sub_legacy"))
            assertTrue(SubagentRunRegistry.resume("sub_legacy"))
            assertFalse(controller.isPaused)
            assertTrue(SubagentRunRegistry.steer("sub_legacy", "继续深挖"))
            assertEquals("继续深挖", controller.pollSteeringMessage())
        } finally {
            // 旧调用点仍只传一个 id；结束时必须把同 id 的所有残留都清掉。
            SubagentRunRegistry.unregister("sub_legacy")
        }
        assertFalse(SubagentRunRegistry.liveIds().contains("sub_legacy"))
        assertFalse(SubagentRunRegistry.isPaused("sub_legacy"))
        assertFalse(SubagentRunRegistry.pause("sub_legacy"))
    }

    @Test
    fun liveIdsListIsScopedAndDistinctAcrossConversations() {
        val conversationA = conversation()
        val conversationB = conversation()
        try {
            SubagentRunRegistry.register(conversationA, "sub_a1", AgentRunController())
            SubagentRunRegistry.register(conversationA, "sub_a2", AgentRunController())
            SubagentRunRegistry.register(conversationB, "sub_b1", AgentRunController())

            assertEquals(
                setOf("sub_a1", "sub_a2"),
                SubagentRunRegistry.liveIds(conversationA).toSet(),
            )
            assertEquals(listOf("sub_b1"), SubagentRunRegistry.liveIds(conversationB))
            assertTrue(SubagentRunRegistry.liveIds("missing").isEmpty())
            assertTrue(SubagentRunRegistry.liveIds(null).isEmpty())
            assertTrue(SubagentRunRegistry.liveIds(" ").isEmpty())
            val allLive = SubagentRunRegistry.liveIds().toSet()
            assertTrue(allLive.containsAll(setOf("sub_a1", "sub_a2", "sub_b1")))
        } finally {
            SubagentRunRegistry.unregister(conversationA, "sub_a1")
            SubagentRunRegistry.unregister(conversationA, "sub_a2")
            SubagentRunRegistry.unregister(conversationB, "sub_b1")
        }
    }
}

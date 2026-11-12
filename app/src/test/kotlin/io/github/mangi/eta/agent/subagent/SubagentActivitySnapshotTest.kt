package io.github.mangi.eta.agent.subagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快照收尾回归：外层失败/中断必须把内部 running 步骤一并终结，
 * 同时保留已经跑完的步骤与部分输出，避免详情面板永远转圈或丢掉工作成果。
 */
class SubagentActivitySnapshotTest {

    @Test
    fun finishFailedTerminalizesRunningStepsAndPreservesWork() {
        val settled = runningSnapshot().finishFailed("模型请求失败：429 Too Many Requests")

        assertEquals(SubagentActivitySnapshot.STATUS_FAILED, settled.status)
        assertTrue(!settled.isRunning)
        assertEquals(0, settled.runningStepCount)
        assertEquals(
            listOf(SubagentStepSnapshot.STATUS_SUCCESS, SubagentStepSnapshot.STATUS_FAILED),
            settled.steps.map { it.status },
        )
        assertEquals("已找到 3 条 ERROR", settled.output)
        assertEquals("模型请求失败：429 Too Many Requests", settled.error)
        assertEquals("模型请求失败：429 Too Many Requests", settled.currentAction)
        // 步骤明细不能被收尾清空，用户要继续看子代理做到哪儿。
        assertEquals("先定位日志文件", settled.steps[0].detail)
        assertEquals("grep ERROR", settled.steps[1].detail)
    }

    @Test
    fun finishInterruptedMarksStepsCancelledInsteadOfFailed() {
        val settled = runningSnapshot().finishInterrupted("任务中断")

        assertEquals(SubagentActivitySnapshot.STATUS_CANCELLED, settled.status)
        assertEquals(0, settled.runningStepCount)
        assertEquals(
            listOf(
                SubagentStepSnapshot.STATUS_SUCCESS,
                SubagentStepSnapshot.STATUS_CANCELLED,
            ),
            settled.steps.map { it.status },
        )
        assertEquals("已找到 3 条 ERROR", settled.output)
        assertEquals("任务中断", settled.currentAction)
        assertEquals("任务中断", settled.error)
    }

    @Test
    fun settledSnapshotIsIdempotentAndNeverDowngradesTerminalStatus() {
        val cancelled = runningSnapshot().finishInterrupted("任务中断")
        val again = cancelled.finishFailed("迟到的失败事件")

        assertEquals(SubagentActivitySnapshot.STATUS_CANCELLED, again.status)
        assertEquals(
            listOf(SubagentStepSnapshot.STATUS_SUCCESS, SubagentStepSnapshot.STATUS_CANCELLED),
            again.steps.map { it.status },
        )
        assertEquals("任务中断", again.currentAction)
        assertEquals(again, again.finishInterrupted("再来一次"))
    }

    @Test
    fun completedSnapshotKeepsStatusButStillClearsStrayRunningStep() {
        val completed = runningSnapshot().copy(
            status = SubagentActivitySnapshot.STATUS_COMPLETED,
            currentAction = "完成 · 1 次工具调用",
        )

        val settled = completed.finishFailed("外层收尾失败")
        assertEquals(SubagentActivitySnapshot.STATUS_COMPLETED, settled.status)
        assertEquals(0, settled.runningStepCount)
        assertEquals("完成 · 1 次工具调用", settled.currentAction)
        assertEquals("外层收尾失败", settled.error)
    }

    @Test
    fun settlementSurvivesEncodeDecodeRoundTrip() {
        val encoded = runningSnapshot().finishFailed("网络中断").encode()

        val decoded = SubagentActivitySnapshot.decode(encoded)
        assertEquals(SubagentActivitySnapshot.STATUS_FAILED, decoded?.status)
        assertEquals(0, decoded?.runningStepCount)
        assertEquals("已找到 3 条 ERROR", decoded?.output)
        assertEquals(
            listOf(SubagentStepSnapshot.STATUS_SUCCESS, SubagentStepSnapshot.STATUS_FAILED),
            decoded?.steps?.map { it.status },
        )
    }

    @Test
    fun settlingSnapshotWithoutDetailFallsBackToReadableAction() {
        val bare = SubagentActivitySnapshot(
            subagentType = "explore",
            description = "空跑",
            status = SubagentActivitySnapshot.STATUS_RUNNING,
        )

        val failed = bare.finishFailed("")
        assertEquals(SubagentActivitySnapshot.STATUS_FAILED, failed.status)
        assertEquals("执行失败", failed.currentAction)
        assertNull(failed.error)

        val interrupted = bare.finishInterrupted("   ")
        assertEquals(SubagentActivitySnapshot.STATUS_CANCELLED, interrupted.status)
        assertEquals("已中断", interrupted.currentAction)
        assertNull(interrupted.error)
    }

    @Test
    fun stepAnimationRequiresOuterSnapshotAndStepToAllBeRunning() {
        val running = runningSnapshot()
        val runningStep = running.steps.last()
        val doneStep = running.steps.first()

        assertTrue(running.isStepActive(runningStep, outerRunning = true))
        // 外层工具已经收尾（失败/中断/完成）时，即使快照是旧的 running，也不能再动。
        assertFalse(running.isStepActive(runningStep, outerRunning = false))
        assertFalse(running.isStepActive(runningStep.copy(status = SubagentStepSnapshot.STATUS_CANCELLED), true))
        assertFalse(running.isStepActive(doneStep, outerRunning = true))

        val settled = running.finishInterrupted("任务中断")
        assertFalse(settled.isStepActive(settled.steps.last(), outerRunning = true))
        assertFalse(settled.isStepActive(settled.steps.last(), outerRunning = false))
    }

    private fun runningSnapshot(): SubagentActivitySnapshot = SubagentActivitySnapshot(
        subagentId = "sub_1-abcd1234",
        subagentType = "explore",
        description = "检索错误日志",
        prompt = "分析错误日志里的异常堆栈",
        model = "test-model",
        status = SubagentActivitySnapshot.STATUS_RUNNING,
        currentAction = "正在调用 grep_files",
        steps = listOf(
            SubagentStepSnapshot(
                id = "thinking-1-0",
                kind = SubagentStepSnapshot.KIND_THINKING,
                title = "思考",
                detail = "先定位日志文件",
                status = SubagentStepSnapshot.STATUS_SUCCESS,
            ),
            SubagentStepSnapshot(
                id = "tool-1-tc1",
                kind = SubagentStepSnapshot.KIND_TOOL,
                title = "grep_files",
                detail = "grep ERROR",
                status = SubagentStepSnapshot.STATUS_RUNNING,
                toolName = "grep_files",
            ),
        ),
        output = "已找到 3 条 ERROR",
    )
}

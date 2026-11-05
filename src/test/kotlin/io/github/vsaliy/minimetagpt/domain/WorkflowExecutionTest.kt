package io.github.vsaliy.minimetagpt.domain

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkflowExecutionTest {
    private fun created() = WorkflowExecution.create("req", 2)

    @Test
    fun `new workflow is CREATED at PENDING`() {
        val w = created()
        assertEquals(WorkflowStatus.CREATED, w.status)
        assertEquals(WorkflowStage.PENDING, w.stage)
        assertNull(w.startedAt)
    }

    @Test
    fun `happy path transitions set timestamps`() {
        val running = created().transition(WorkflowStatus.RUNNING)
        assertNotNull(running.startedAt)
        val waiting = running.transition(WorkflowStatus.WAITING_FOR_APPROVAL).transition(WorkflowStatus.RUNNING)
        val done = waiting.transition(WorkflowStatus.COMPLETED)
        assertNotNull(done.finishedAt)
        assertEquals(running.startedAt, done.startedAt)
    }

    @Test
    fun `terminal states cannot transition`() {
        for (terminal in listOf(WorkflowStatus.COMPLETED, WorkflowStatus.FAILED, WorkflowStatus.CANCELLED)) {
            WorkflowStatus.entries.forEach { next ->
                assertTrue(!terminal.canTransitionTo(next), "$terminal -> $next must be illegal")
            }
        }
    }

    @Test
    fun `illegal transitions throw`() {
        assertThrows<IllegalWorkflowTransitionException> { created().transition(WorkflowStatus.COMPLETED) }
        assertThrows<IllegalWorkflowTransitionException> { created().transition(WorkflowStatus.WAITING_FOR_APPROVAL) }
    }

    @Test
    fun `failure keeps error info`() {
        val error = ErrorInfo(ErrorCategory.BUILD_FAILED, "boom")
        val failed = created().transition(WorkflowStatus.RUNNING).transition(WorkflowStatus.FAILED, error = error)
        assertEquals(error, failed.error)
    }

    @Test
    fun `correction attempts are counted`() {
        assertEquals(1, created().withCorrectionAttempt().correctionAttempts)
    }
}

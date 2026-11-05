package io.github.vsaliy.minimetagpt.domain

import java.time.Instant
import java.util.UUID

enum class WorkflowStatus {
    CREATED, RUNNING, WAITING_FOR_APPROVAL, COMPLETED, FAILED, CANCELLED;

    val terminal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELLED

    fun canTransitionTo(next: WorkflowStatus): Boolean = next in allowed()

    private fun allowed(): Set<WorkflowStatus> = when (this) {
        CREATED -> setOf(RUNNING, CANCELLED, FAILED)
        RUNNING -> setOf(WAITING_FOR_APPROVAL, COMPLETED, FAILED, CANCELLED)
        WAITING_FOR_APPROVAL -> setOf(RUNNING, FAILED, CANCELLED)
        COMPLETED, FAILED, CANCELLED -> emptySet()
    }
}

enum class WorkflowStage {
    PENDING, REQUIREMENTS, ARCHITECTURE, DEVELOPMENT, TEST_GENERATION, APPROVAL, BUILD_AND_TEST, CORRECTION, DONE
}

class IllegalWorkflowTransitionException(from: WorkflowStatus, to: WorkflowStatus) :
    IllegalStateException("Illegal workflow transition $from -> $to")

data class WorkflowExecution(
    val id: UUID,
    val request: String,
    val status: WorkflowStatus,
    val stage: WorkflowStage,
    val maxCorrectionAttempts: Int,
    val correctionAttempts: Int = 0,
    val error: ErrorInfo? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null,
) {
    fun transition(next: WorkflowStatus, now: Instant = Instant.now(), error: ErrorInfo? = null): WorkflowExecution {
        if (!status.canTransitionTo(next)) throw IllegalWorkflowTransitionException(status, next)
        return copy(
            status = next,
            updatedAt = now,
            startedAt = startedAt ?: now.takeIf { next == WorkflowStatus.RUNNING },
            finishedAt = now.takeIf { next.terminal },
            error = error ?: this.error.takeUnless { next == WorkflowStatus.RUNNING },
        )
    }

    fun atStage(next: WorkflowStage, now: Instant = Instant.now()) = copy(stage = next, updatedAt = now)

    fun withCorrectionAttempt(now: Instant = Instant.now()) =
        copy(correctionAttempts = correctionAttempts + 1, updatedAt = now)

    companion object {
        fun create(request: String, maxCorrectionAttempts: Int, now: Instant = Instant.now()) = WorkflowExecution(
            id = UUID.randomUUID(),
            request = request,
            status = WorkflowStatus.CREATED,
            stage = WorkflowStage.PENDING,
            maxCorrectionAttempts = maxCorrectionAttempts,
            createdAt = now,
            updatedAt = now,
        )
    }
}

data class TokenUsage(val inputTokens: Int?, val outputTokens: Int?, val totalTokens: Int?)

data class AgentTask(
    val id: UUID = UUID.randomUUID(),
    val workflowId: UUID,
    val role: AgentRole,
    val stage: WorkflowStage,
    val inputs: List<ArtifactReference>,
    val attempt: Int = 1,
    val instructions: String? = null,
)

enum class ExecutionOutcome { SUCCESS, INVALID_OUTPUT, FAILED, CANCELLED }

data class AgentExecutionResult(
    val task: AgentTask,
    val outcome: ExecutionOutcome,
    val artifact: ArtifactReference?,
    val validation: ValidationResult,
    val error: ErrorInfo?,
    val startedAt: Instant,
    val finishedAt: Instant,
    val tokenUsage: TokenUsage? = null,
) {
    val durationMs: Long get() = finishedAt.toEpochMilli() - startedAt.toEpochMilli()
}

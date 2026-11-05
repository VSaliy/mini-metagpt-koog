package io.github.vsaliy.minimetagpt.api

import io.github.vsaliy.minimetagpt.domain.Artifact
import io.github.vsaliy.minimetagpt.domain.ErrorInfo
import io.github.vsaliy.minimetagpt.domain.WorkflowExecution
import io.github.vsaliy.minimetagpt.domain.WorkflowStage
import io.github.vsaliy.minimetagpt.domain.WorkflowStatus
import io.github.vsaliy.minimetagpt.orchestration.AgentExecutionRecord
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class CreateWorkflowRequest(
    @field:NotBlank(message = "requirement must not be blank")
    @field:Size(max = 8000, message = "requirement must be at most 8000 characters")
    val requirement: String?,
)

data class WorkflowResponse(
    val id: UUID,
    val status: WorkflowStatus,
    val stage: WorkflowStage,
    val requirement: String,
    val correctionAttempts: Int,
    val maxCorrectionAttempts: Int,
    val error: ErrorInfo?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val executions: List<AgentExecutionRecord> = emptyList(),
) {
    companion object {
        fun of(w: WorkflowExecution, executions: List<AgentExecutionRecord> = emptyList()) = WorkflowResponse(
            w.id, w.status, w.stage, w.request, w.correctionAttempts, w.maxCorrectionAttempts, w.error,
            w.createdAt, w.updatedAt, w.startedAt, w.finishedAt, executions,
        )
    }
}

data class ArtifactsResponse(val workflowId: UUID, val artifacts: List<Artifact>)

data class ErrorResponse(
    val code: String,
    val message: String,
    val details: List<String> = emptyList(),
    val path: String,
    val timestamp: Instant = Instant.now(),
)

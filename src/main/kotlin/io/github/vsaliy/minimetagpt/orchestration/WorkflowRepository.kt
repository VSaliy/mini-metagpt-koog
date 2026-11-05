package io.github.vsaliy.minimetagpt.orchestration

import io.github.vsaliy.minimetagpt.domain.AgentExecutionResult
import io.github.vsaliy.minimetagpt.domain.AgentRole
import io.github.vsaliy.minimetagpt.domain.Artifact
import io.github.vsaliy.minimetagpt.domain.ArtifactType
import io.github.vsaliy.minimetagpt.domain.ErrorInfo
import io.github.vsaliy.minimetagpt.domain.ExecutionOutcome
import io.github.vsaliy.minimetagpt.domain.TokenUsage
import io.github.vsaliy.minimetagpt.domain.ValidationResult
import io.github.vsaliy.minimetagpt.domain.WorkflowExecution
import io.github.vsaliy.minimetagpt.domain.WorkflowStage
import java.time.Instant
import java.util.UUID

/** Execution-history record as read back from storage. */
data class AgentExecutionRecord(
    val id: UUID,
    val workflowId: UUID,
    val role: AgentRole,
    val stage: WorkflowStage,
    val attempt: Int,
    val outcome: ExecutionOutcome,
    val artifactId: UUID?,
    val error: ErrorInfo?,
    val validation: ValidationResult?,
    val tokenUsage: TokenUsage?,
    val startedAt: Instant,
    val finishedAt: Instant,
    val durationMs: Long,
)

interface WorkflowRepository {
    fun save(execution: WorkflowExecution)
    fun find(id: UUID): WorkflowExecution?
    fun saveArtifact(artifact: Artifact)
    fun artifacts(workflowId: UUID): List<Artifact>
    fun latestArtifact(workflowId: UUID, type: ArtifactType): Artifact?
    fun saveAgentExecution(result: AgentExecutionResult)
    fun agentExecutions(workflowId: UUID): List<AgentExecutionRecord>

    /** Marks workflows left RUNNING/WAITING by a previous process as FAILED. Returns how many were changed. */
    fun failInterrupted(error: ErrorInfo, now: Instant): Int
}

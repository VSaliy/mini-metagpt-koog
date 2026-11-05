package io.github.vsaliy.minimetagpt.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.vsaliy.minimetagpt.domain.AgentExecutionResult
import io.github.vsaliy.minimetagpt.domain.AgentRole
import io.github.vsaliy.minimetagpt.domain.ArchitectureArtifact
import io.github.vsaliy.minimetagpt.domain.Artifact
import io.github.vsaliy.minimetagpt.domain.ArtifactType
import io.github.vsaliy.minimetagpt.domain.ErrorInfo
import io.github.vsaliy.minimetagpt.domain.ExecutionOutcome
import io.github.vsaliy.minimetagpt.domain.ImplementationArtifact
import io.github.vsaliy.minimetagpt.domain.QualityReportArtifact
import io.github.vsaliy.minimetagpt.domain.RequirementsArtifact
import io.github.vsaliy.minimetagpt.domain.TokenUsage
import io.github.vsaliy.minimetagpt.domain.ValidationResult
import io.github.vsaliy.minimetagpt.domain.WorkflowExecution
import io.github.vsaliy.minimetagpt.domain.WorkflowStage
import io.github.vsaliy.minimetagpt.domain.WorkflowStatus
import io.github.vsaliy.minimetagpt.orchestration.AgentExecutionRecord
import io.github.vsaliy.minimetagpt.orchestration.WorkflowRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class JdbcWorkflowRepository(
    private val jdbc: JdbcTemplate,
    private val json: ObjectMapper,
) : WorkflowRepository {

    override fun save(execution: WorkflowExecution) {
        val updated = jdbc.update(
            """UPDATE workflow_execution SET status=?, stage=?, correction_attempts=?, error_json=?, updated_at=?,
               started_at=?, finished_at=? WHERE id=?""",
            execution.status.name, execution.stage.name, execution.correctionAttempts,
            execution.error?.let(json::writeValueAsString), ts(execution.updatedAt),
            execution.startedAt?.let(::ts), execution.finishedAt?.let(::ts), execution.id,
        )
        if (updated == 0) {
            jdbc.update(
                """INSERT INTO workflow_execution(id, request, status, stage, max_correction_attempts, correction_attempts,
                   error_json, created_at, updated_at, started_at, finished_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
                execution.id, execution.request, execution.status.name, execution.stage.name,
                execution.maxCorrectionAttempts, execution.correctionAttempts,
                execution.error?.let(json::writeValueAsString), ts(execution.createdAt), ts(execution.updatedAt),
                execution.startedAt?.let(::ts), execution.finishedAt?.let(::ts),
            )
        }
    }

    override fun find(id: UUID): WorkflowExecution? =
        jdbc.query("SELECT * FROM workflow_execution WHERE id=?", workflowMapper, id).firstOrNull()

    override fun saveArtifact(artifact: Artifact) {
        jdbc.update(
            "INSERT INTO artifact(id, workflow_id, type, schema_version, content_json, created_at) VALUES (?,?,?,?,?,?)",
            artifact.meta.id, artifact.meta.workflowId, artifact.type.name, artifact.meta.schemaVersion,
            json.writeValueAsString(artifact), ts(artifact.meta.createdAt),
        )
    }

    override fun artifacts(workflowId: UUID): List<Artifact> =
        jdbc.query("SELECT type, content_json FROM artifact WHERE workflow_id=? ORDER BY created_at, id", artifactMapper, workflowId)

    override fun latestArtifact(workflowId: UUID, type: ArtifactType): Artifact? =
        jdbc.query(
            "SELECT type, content_json FROM artifact WHERE workflow_id=? AND type=? ORDER BY created_at DESC, id DESC LIMIT 1",
            artifactMapper, workflowId, type.name,
        ).firstOrNull()

    override fun saveAgentExecution(result: AgentExecutionResult) {
        jdbc.update(
            """INSERT INTO agent_execution(id, workflow_id, role, stage, attempt, outcome, artifact_id, error_json,
               validation_json, input_tokens, output_tokens, total_tokens, started_at, finished_at, duration_ms)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            result.task.id, result.task.workflowId, result.task.role.name, result.task.stage.name, result.task.attempt,
            result.outcome.name, result.artifact?.id, result.error?.let(json::writeValueAsString),
            json.writeValueAsString(result.validation), result.tokenUsage?.inputTokens, result.tokenUsage?.outputTokens,
            result.tokenUsage?.totalTokens, ts(result.startedAt), ts(result.finishedAt), result.durationMs,
        )
    }

    override fun agentExecutions(workflowId: UUID): List<AgentExecutionRecord> =
        jdbc.query("SELECT * FROM agent_execution WHERE workflow_id=? ORDER BY started_at, id", { rs, _ ->
            val input = rs.getObject("input_tokens") as Number?
            val output = rs.getObject("output_tokens") as Number?
            val total = rs.getObject("total_tokens") as Number?
            AgentExecutionRecord(
                id = rs.getObject("id", UUID::class.java),
                workflowId = rs.getObject("workflow_id", UUID::class.java),
                role = AgentRole.valueOf(rs.getString("role")),
                stage = WorkflowStage.valueOf(rs.getString("stage")),
                attempt = rs.getInt("attempt"),
                outcome = ExecutionOutcome.valueOf(rs.getString("outcome")),
                artifactId = rs.getObject("artifact_id", UUID::class.java),
                error = rs.getString("error_json")?.let { json.readValue(it, ErrorInfo::class.java) },
                validation = rs.getString("validation_json")?.let { json.readValue(it, ValidationResult::class.java) },
                tokenUsage = if (input == null && output == null && total == null) null
                else TokenUsage(input?.toInt(), output?.toInt(), total?.toInt()),
                startedAt = instant(rs, "started_at")!!,
                finishedAt = instant(rs, "finished_at")!!,
                durationMs = rs.getLong("duration_ms"),
            )
        }, workflowId)

    override fun failInterrupted(error: ErrorInfo, now: Instant): Int =
        jdbc.update(
            """UPDATE workflow_execution SET status=?, error_json=?, updated_at=?, finished_at=?
               WHERE status IN ('RUNNING','WAITING_FOR_APPROVAL','CREATED')""",
            WorkflowStatus.FAILED.name, json.writeValueAsString(error), ts(now), ts(now),
        )

    private val workflowMapper = RowMapper { rs, _ ->
        WorkflowExecution(
            id = rs.getObject("id", UUID::class.java),
            request = rs.getString("request"),
            status = WorkflowStatus.valueOf(rs.getString("status")),
            stage = WorkflowStage.valueOf(rs.getString("stage")),
            maxCorrectionAttempts = rs.getInt("max_correction_attempts"),
            correctionAttempts = rs.getInt("correction_attempts"),
            error = rs.getString("error_json")?.let { json.readValue(it, ErrorInfo::class.java) },
            createdAt = instant(rs, "created_at")!!,
            updatedAt = instant(rs, "updated_at")!!,
            startedAt = instant(rs, "started_at"),
            finishedAt = instant(rs, "finished_at"),
        )
    }

    private val artifactMapper = RowMapper { rs, _ ->
        val body = rs.getString("content_json")
        when (ArtifactType.valueOf(rs.getString("type"))) {
            ArtifactType.REQUIREMENTS -> json.readValue(body, RequirementsArtifact::class.java)
            ArtifactType.ARCHITECTURE -> json.readValue(body, ArchitectureArtifact::class.java)
            ArtifactType.IMPLEMENTATION -> json.readValue(body, ImplementationArtifact::class.java)
            ArtifactType.QUALITY_REPORT -> json.readValue(body, QualityReportArtifact::class.java)
        }
    }

    private fun ts(instant: Instant): OffsetDateTime = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)

    private fun instant(rs: ResultSet, column: String): Instant? =
        rs.getObject(column, OffsetDateTime::class.java)?.toInstant()
}

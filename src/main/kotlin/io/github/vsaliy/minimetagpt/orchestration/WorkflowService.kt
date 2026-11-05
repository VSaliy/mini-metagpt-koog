package io.github.vsaliy.minimetagpt.orchestration

import io.github.vsaliy.minimetagpt.domain.Artifact
import io.github.vsaliy.minimetagpt.domain.ArtifactType
import io.github.vsaliy.minimetagpt.domain.ErrorCategory
import io.github.vsaliy.minimetagpt.domain.ErrorInfo
import io.github.vsaliy.minimetagpt.domain.QualityReportArtifact
import io.github.vsaliy.minimetagpt.domain.WorkflowExecution
import io.github.vsaliy.minimetagpt.domain.WorkflowStatus
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class WorkflowNotFoundException(id: UUID) : RuntimeException("Workflow $id not found")

class WorkflowConflictException(val code: String, message: String) : RuntimeException(message)

/** Application service behind the REST API: starts workflows asynchronously and exposes their state. */
class WorkflowService(
    private val repository: WorkflowRepository,
    private val state: WorkflowStateStore,
    private val engine: WorkflowEngine,
    private val approvals: ApprovalGate,
    private val maxCorrectionAttempts: Int,
) {
    private val log = LoggerFactory.getLogger(WorkflowService::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = ConcurrentHashMap<UUID, Job>()

    @PostConstruct
    fun recoverInterrupted() {
        val n = repository.failInterrupted(
            ErrorInfo(ErrorCategory.INTERRUPTED, "Workflow was interrupted by an application restart"), Instant.now(),
        )
        if (n > 0) log.warn("marked {} interrupted workflow(s) as FAILED", n)
    }

    @PreDestroy
    fun shutdown() {
        scope.cancel()
    }

    fun create(request: String): WorkflowExecution {
        val execution = WorkflowExecution.create(request, maxCorrectionAttempts)
        state.create(execution)
        val job = scope.launch(start = CoroutineStart.LAZY) { engine.run(execution.id) }
        jobs[execution.id] = job
        job.invokeOnCompletion { jobs.remove(execution.id) }
        job.start()
        return execution
    }

    fun get(id: UUID): WorkflowExecution = repository.find(id) ?: throw WorkflowNotFoundException(id)

    fun artifacts(id: UUID): List<Artifact> {
        get(id)
        return repository.artifacts(id)
    }

    fun history(id: UUID): List<AgentExecutionRecord> {
        get(id)
        return repository.agentExecutions(id)
    }

    fun report(id: UUID): QualityReportArtifact {
        val workflow = get(id)
        return repository.latestArtifact(id, ArtifactType.QUALITY_REPORT) as QualityReportArtifact?
            ?: throw WorkflowConflictException("REPORT_NOT_AVAILABLE", "No quality report yet; workflow is ${workflow.status}")
    }

    fun cancel(id: UUID): WorkflowExecution {
        val current = get(id)
        if (current.status.terminal && current.status != WorkflowStatus.CANCELLED) {
            throw WorkflowConflictException("WORKFLOW_FINISHED", "Workflow already ${current.status}")
        }
        val updated = state.update(id) { it.transition(WorkflowStatus.CANCELLED) } ?: throw WorkflowNotFoundException(id)
        if (updated.status != WorkflowStatus.CANCELLED) {
            throw WorkflowConflictException("WORKFLOW_FINISHED", "Workflow already ${updated.status}")
        }
        jobs[id]?.cancel()
        return updated
    }

    fun decide(id: UUID, approved: Boolean): WorkflowExecution {
        val current = get(id)
        if (current.status != WorkflowStatus.WAITING_FOR_APPROVAL || !approvals.decide(id, approved)) {
            throw WorkflowConflictException("NOT_WAITING_FOR_APPROVAL", "Workflow is ${current.status}, not waiting for approval")
        }
        return get(id)
    }
}

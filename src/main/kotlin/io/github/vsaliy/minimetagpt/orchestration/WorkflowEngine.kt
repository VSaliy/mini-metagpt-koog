package io.github.vsaliy.minimetagpt.orchestration

import io.github.vsaliy.minimetagpt.agents.AgentFailure
import io.github.vsaliy.minimetagpt.agents.AgentOutput
import io.github.vsaliy.minimetagpt.agents.ArchitectAgent
import io.github.vsaliy.minimetagpt.agents.DeveloperAgent
import io.github.vsaliy.minimetagpt.agents.GeneratedTests
import io.github.vsaliy.minimetagpt.agents.QaAgent
import io.github.vsaliy.minimetagpt.agents.RequirementsAgent
import io.github.vsaliy.minimetagpt.agents.Verification
import io.github.vsaliy.minimetagpt.domain.AgentExecutionResult
import io.github.vsaliy.minimetagpt.domain.AgentRole
import io.github.vsaliy.minimetagpt.domain.AgentTask
import io.github.vsaliy.minimetagpt.domain.Artifact
import io.github.vsaliy.minimetagpt.domain.ArtifactReference
import io.github.vsaliy.minimetagpt.domain.ErrorCategory
import io.github.vsaliy.minimetagpt.domain.ErrorInfo
import io.github.vsaliy.minimetagpt.domain.ExecutionOutcome
import io.github.vsaliy.minimetagpt.domain.FailureAnalysis
import io.github.vsaliy.minimetagpt.domain.FailureCause
import io.github.vsaliy.minimetagpt.domain.ImplementationArtifact
import io.github.vsaliy.minimetagpt.domain.QualityReportArtifact
import io.github.vsaliy.minimetagpt.domain.ValidationResult
import io.github.vsaliy.minimetagpt.domain.Verdict
import io.github.vsaliy.minimetagpt.domain.WorkflowStage
import io.github.vsaliy.minimetagpt.domain.WorkflowStatus
import io.github.vsaliy.minimetagpt.infrastructure.config.MiniMetagptProperties
import io.github.vsaliy.minimetagpt.infrastructure.observability.StageObserver
import io.github.vsaliy.minimetagpt.infrastructure.tools.GitTool
import io.github.vsaliy.minimetagpt.infrastructure.tools.ToolExecutionException
import io.github.vsaliy.minimetagpt.infrastructure.tools.ToolRejectedException
import io.github.vsaliy.minimetagpt.infrastructure.tools.Workspace
import io.github.vsaliy.minimetagpt.infrastructure.tools.WorkspaceManager
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/**
 * Explicit SOP: requirements -> architecture -> development -> test generation -> [approval] -> build/test
 * -> (analysis -> correction -> re-test)* -> report. The correction loop is bounded by the workflow's
 * `maxCorrectionAttempts`; each stage's output is validated by its agent before it is handed on.
 */
class WorkflowEngine(
    private val requirementsAgent: RequirementsAgent,
    private val architectAgent: ArchitectAgent,
    private val developerAgent: DeveloperAgent,
    private val qaAgent: QaAgent,
    private val repository: WorkflowRepository,
    private val state: WorkflowStateStore,
    private val workspaces: WorkspaceManager,
    private val git: GitTool,
    private val approvals: ApprovalGate,
    private val observer: StageObserver,
    private val execution: MiniMetagptProperties.Execution,
) {
    private val log = LoggerFactory.getLogger(WorkflowEngine::class.java)

    suspend fun run(id: UUID) {
        val started = state.update(id) { it.transition(WorkflowStatus.RUNNING) }
        if (started == null || started.status != WorkflowStatus.RUNNING) return
        try {
            pipeline(started.id, started.request, started.maxCorrectionAttempts)
        } catch (e: CancellationException) {
            state.update(id) { it.transition(WorkflowStatus.CANCELLED) }
            throw e
        } catch (e: AgentFailure) {
            log.warn("workflowId={} failed category={} message={}", id, e.error.category, e.error.message)
            state.update(id) { it.transition(WorkflowStatus.FAILED, error = e.error) }
        } catch (e: Exception) {
            log.error("workflowId={} failed unexpectedly", id, e)
            val error = ErrorInfo(ErrorCategory.INTERNAL, e.message ?: e.javaClass.simpleName, detail = e.javaClass.name)
            state.update(id) { it.transition(WorkflowStatus.FAILED, error = error) }
        }
    }

    private suspend fun pipeline(id: UUID, request: String, maxCorrections: Int) {
        val requirements = stage(id, AgentRole.REQUIREMENTS, WorkflowStage.REQUIREMENTS, emptyList(), artifactOf = { it }) { task ->
            requirementsAgent.execute(task, request)
        }.value
        val architecture = stage(id, AgentRole.ARCHITECT, WorkflowStage.ARCHITECTURE, listOf(requirements.reference()), artifactOf = { it }) { task ->
            architectAgent.execute(task, requirements)
        }.value

        val workspace = workspaces.create(id)
        val baseInputs = listOf(requirements.reference(), architecture.reference())
        var implementation: ImplementationArtifact =
            stage(id, AgentRole.DEVELOPER, WorkflowStage.DEVELOPMENT, baseInputs, artifactOf = { it }) { task ->
                developerAgent.execute(task, requirements, architecture, workspace)
            }.value
        var tests: GeneratedTests =
            stage<GeneratedTests>(id, AgentRole.QA, WorkflowStage.TEST_GENERATION, baseInputs + implementation.reference()) { task ->
                qaAgent.generateTests(task, requirements, architecture, implementation, workspace)
            }.value
        var commit = snapshot(workspace, "Generated project for workflow $id")

        val skipReason = executionBlockedReason(id) ?: approvalDecision(id)
        var verification: Verification? = null
        val analyses = mutableListOf<FailureAnalysis>()
        var corrections = 0
        if (skipReason == null) {
            while (true) {
                val current = verify(id, workspace, baseInputs + implementation.reference(), corrections + 1)
                verification = current
                if (current.passed || corrections >= maxCorrections) break
                corrections++
                val inputs = baseInputs + implementation.reference()
                val analysis = stage<FailureAnalysis>(id, AgentRole.QA, WorkflowStage.CORRECTION, inputs, attempt = corrections) { task ->
                    qaAgent.analyzeFailure(task, current, workspace)
                }.value
                analyses += analysis
                state.update(id) { it.withCorrectionAttempt() }
                if (analysis.cause == FailureCause.TEST) {
                    tests = stage<GeneratedTests>(id, AgentRole.QA, WorkflowStage.CORRECTION, inputs, attempt = corrections + 1) { task ->
                        qaAgent.generateTests(task, requirements, architecture, implementation, workspace, analysis)
                    }.value
                } else {
                    val previous = implementation
                    implementation = stage(id, AgentRole.DEVELOPER, WorkflowStage.CORRECTION, inputs, attempt = corrections + 1, artifactOf = { it }) { task ->
                        developerAgent.execute(task, requirements, architecture, workspace, previous, analysis)
                    }.value
                }
                commit = snapshot(workspace, "Correction $corrections for workflow $id") ?: commit
            }
        }

        val reportTask = AgentTask(workflowId = id, role = AgentRole.QA, stage = WorkflowStage.DONE,
            inputs = baseInputs + implementation.reference(), attempt = corrections + 1)
        val report = qaAgent.report(reportTask, tests, verification, analyses, corrections, skipReason, null, commit)
        repository.saveArtifact(report)
        finish(id, report, verification, maxCorrections)
    }

    private fun executionBlockedReason(id: UUID): String? = when {
        !execution.enabled -> "execution is disabled by configuration (minimetagpt.execution.enabled=false)"
        else -> null
    }.also { if (it != null) log.info("workflowId={} skipping build and test: {}", id, it) }

    /** Awaits human approval when required. Returns a skip reason when the build must not run. */
    private suspend fun approvalDecision(id: UUID): String? {
        if (!execution.requireApproval) return null
        val decision = approvals.open(id)
        state.update(id) { it.atStage(WorkflowStage.APPROVAL).transition(WorkflowStatus.WAITING_FOR_APPROVAL) }
        val approved = approvals.await(id, decision)
        state.update(id) { it.transition(WorkflowStatus.RUNNING) }
        return if (approved) null else "execution of generated code was rejected by a reviewer"
    }

    private suspend fun verify(id: UUID, workspace: Workspace, inputs: List<ArtifactReference>, attempt: Int): Verification {
        return stage<Verification>(
            id, AgentRole.QA, WorkflowStage.BUILD_AND_TEST, inputs, attempt = attempt,
            judge = { v -> if (v.passed) null else ErrorInfo(ErrorCategory.BUILD_FAILED, "Build or tests failed", WorkflowStage.BUILD_AND_TEST, AgentRole.QA) },
        ) { _ -> AgentOutput(qaAgent.verify(workspace), null, null, ValidationResult.OK) }.value
    }

    private suspend fun finish(id: UUID, report: QualityReportArtifact, verification: Verification?, maxCorrections: Int) {
        val verdict = report.content.verdict
        state.update(id) { it.atStage(WorkflowStage.DONE) }
        if (verdict == Verdict.FAILED) {
            val last = verification?.builds?.lastOrNull { !it.success } ?: verification?.builds?.lastOrNull()
            val category = if (maxCorrections > 0 && report.content.correctionAttempts >= maxCorrections) ErrorCategory.RETRIES_EXHAUSTED else ErrorCategory.BUILD_FAILED
            val error = ErrorInfo(category, report.content.summary, WorkflowStage.BUILD_AND_TEST, AgentRole.QA, last?.outputTail?.takeLast(4000))
            state.update(id) { it.transition(WorkflowStatus.FAILED, error = error) }
        } else {
            state.update(id) { it.transition(WorkflowStatus.COMPLETED) }
        }
    }

    private suspend fun snapshot(workspace: Workspace, message: String): String? =
        try {
            git.commitAll(workspace, message)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("git snapshot skipped: {}", e.message)
            null
        }

    private suspend fun <T> stage(
        id: UUID,
        role: AgentRole,
        stage: WorkflowStage,
        inputs: List<ArtifactReference>,
        attempt: Int = 1,
        artifactOf: (T) -> Artifact? = { null },
        judge: (T) -> ErrorInfo? = { null },
        block: suspend (AgentTask) -> AgentOutput<T>,
    ): AgentOutput<T> {
        state.update(id) { it.atStage(stage) }
        val task = AgentTask(workflowId = id, role = role, stage = stage, inputs = inputs, attempt = attempt)
        val started = Instant.now()
        try {
            val out = observer.observe(id, role, stage) { block(task) }
            val artifact = artifactOf(out.value)?.also(repository::saveArtifact)
            val judged = judge(out.value)
            record(task, if (judged == null) ExecutionOutcome.SUCCESS else ExecutionOutcome.FAILED, artifact?.reference(), out.validation, judged, started, out)
            return out
        } catch (e: CancellationException) {
            record(task, ExecutionOutcome.CANCELLED, null, ValidationResult.OK, null, started, null)
            throw e
        } catch (e: Exception) {
            val failure = toFailure(e, role, stage)
            val outcome = if (failure.error.category in INVALID) ExecutionOutcome.INVALID_OUTPUT else ExecutionOutcome.FAILED
            record(task, outcome, null, ValidationResult.OK, failure.error, started, null)
            throw failure
        }
    }

    private fun toFailure(e: Exception, role: AgentRole, stage: WorkflowStage): AgentFailure = when (e) {
        is AgentFailure -> AgentFailure(e.error.copy(stage = stage, role = role), e)
        is ToolRejectedException -> AgentFailure(ErrorInfo(ErrorCategory.UNSAFE_OUTPUT, e.message ?: "Tool rejected request", stage, role), e)
        is ToolExecutionException -> AgentFailure(ErrorInfo(ErrorCategory.TOOL_FAILURE, e.message ?: "Tool failed", stage, role), e)
        else -> AgentFailure(ErrorInfo(ErrorCategory.INTERNAL, e.message ?: e.javaClass.simpleName, stage, role, e.javaClass.name), e)
    }

    private fun record(
        task: AgentTask,
        outcome: ExecutionOutcome,
        artifact: ArtifactReference?,
        validation: ValidationResult,
        error: ErrorInfo?,
        started: Instant,
        output: AgentOutput<*>?,
    ) {
        repository.saveAgentExecution(
            AgentExecutionResult(task, outcome, artifact, validation, error, started, Instant.now(), output?.usage),
        )
    }

    private companion object {
        val INVALID = setOf(ErrorCategory.INVALID_OUTPUT, ErrorCategory.VALIDATION_FAILED, ErrorCategory.UNSAFE_OUTPUT)
    }
}

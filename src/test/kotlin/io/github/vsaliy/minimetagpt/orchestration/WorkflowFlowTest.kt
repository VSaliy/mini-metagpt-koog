package io.github.vsaliy.minimetagpt.orchestration

import io.github.vsaliy.minimetagpt.agents.ArchitectAgent
import io.github.vsaliy.minimetagpt.agents.DeveloperAgent
import io.github.vsaliy.minimetagpt.agents.QaAgent
import io.github.vsaliy.minimetagpt.agents.RequirementsAgent
import io.github.vsaliy.minimetagpt.agents.StructuredGenerator
import io.github.vsaliy.minimetagpt.domain.ArtifactType
import io.github.vsaliy.minimetagpt.domain.BuildStep
import io.github.vsaliy.minimetagpt.domain.ErrorCategory
import io.github.vsaliy.minimetagpt.domain.ExecutionOutcome
import io.github.vsaliy.minimetagpt.domain.FailureAnalysis
import io.github.vsaliy.minimetagpt.domain.FailureCause
import io.github.vsaliy.minimetagpt.domain.GeneratedFile
import io.github.vsaliy.minimetagpt.domain.ImplementationDraft
import io.github.vsaliy.minimetagpt.domain.QualityReportArtifact
import io.github.vsaliy.minimetagpt.domain.Verdict
import io.github.vsaliy.minimetagpt.domain.WorkflowStage
import io.github.vsaliy.minimetagpt.domain.WorkflowStatus
import io.github.vsaliy.minimetagpt.infrastructure.config.MiniMetagptProperties
import io.github.vsaliy.minimetagpt.infrastructure.observability.StageObserver
import io.github.vsaliy.minimetagpt.infrastructure.tools.GitTool
import io.github.vsaliy.minimetagpt.infrastructure.tools.ProcessRunner
import io.github.vsaliy.minimetagpt.infrastructure.tools.WorkspaceManager
import io.github.vsaliy.minimetagpt.support.InMemoryWorkflowRepository
import io.github.vsaliy.minimetagpt.support.ScriptedBuildTool
import io.github.vsaliy.minimetagpt.support.ScriptedLlm
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkflowFlowTest {
    @TempDir
    lateinit var root: Path

    private val llm = ScriptedLlm()
    private val repository = InMemoryWorkflowRepository()
    private var service: WorkflowService? = null

    private fun build(
        buildOutcomes: List<Boolean> = emptyList(),
        requireApproval: Boolean = false,
        enabled: Boolean = true,
        maxCorrections: Int = 2,
        maxOutputRetries: Int = 0,
    ): Pair<WorkflowService, ScriptedBuildTool> {
        val execution = MiniMetagptProperties.Execution(enabled = enabled, requireApproval = requireApproval, gitCommand = "git")
        val generator = StructuredGenerator(maxOutputRetries)
        val buildTool = ScriptedBuildTool(buildOutcomes.toMutableList())
        val state = WorkflowStateStore(repository)
        val approvals = ApprovalGate()
        val engine = WorkflowEngine(
            RequirementsAgent(llm, generator), ArchitectAgent(llm, generator), DeveloperAgent(llm, generator),
            QaAgent(llm, generator, buildTool), repository, state, WorkspaceManager(root),
            GitTool(ProcessRunner(10_000), "git", Duration.ofSeconds(30)), approvals, StageObserver(null), execution,
        )
        return WorkflowService(repository, state, engine, approvals, maxCorrections).also { service = it } to buildTool
    }

    @AfterEach
    fun tearDown() {
        service?.shutdown()
    }

    private fun await(id: UUID, vararg statuses: WorkflowStatus): io.github.vsaliy.minimetagpt.domain.WorkflowExecution {
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (System.nanoTime() < deadline) {
            val w = service!!.get(id)
            if (w.status in statuses) { if (w.status == WorkflowStatus.FAILED && WorkflowStatus.FAILED !in statuses) error("failed: ${w.error}"); return w }
            Thread.sleep(25)
        }
        error("Timed out; workflow is ${service!!.get(id).status}")
    }

    private val terminal = arrayOf(WorkflowStatus.COMPLETED, WorkflowStatus.FAILED, WorkflowStatus.CANCELLED)

    @Test
    fun `happy path produces four artifact types and a PASSED report`() {
        val (svc, buildTool) = build(listOf(true))
        val id = svc.create("Create a tasks API").id
        val done = await(id, *terminal)

        assertEquals(WorkflowStatus.COMPLETED, done.status)
        assertEquals(WorkflowStage.DONE, done.stage)
        assertEquals(0, done.correctionAttempts)
        assertEquals(
            listOf(ArtifactType.REQUIREMENTS, ArtifactType.ARCHITECTURE, ArtifactType.IMPLEMENTATION, ArtifactType.QUALITY_REPORT),
            svc.artifacts(id).map { it.type },
        )
        val report = svc.report(id)
        assertEquals(Verdict.PASSED, report.content.verdict)
        assertEquals(3, report.content.testSummary?.total)
        assertEquals(listOf(BuildStep.COMPILE, BuildStep.TEST), buildTool.steps)
        assertNotNull(report.content.gitCommit)
        assertTrue(svc.history(id).all { it.outcome == ExecutionOutcome.SUCCESS })
    }

    @Test
    fun `failed tests trigger developer correction and a retest`() {
        llm.analyses += FailureAnalysis(FailureCause.IMPLEMENTATION, "controller returns 200", listOf("TaskController.java"), "return 201")
        val (svc, buildTool) = build(listOf(false, true))
        val id = svc.create("Create a tasks API").id
        val done = await(id, *terminal)

        assertEquals(WorkflowStatus.COMPLETED, done.status)
        assertEquals(1, done.correctionAttempts)
        assertEquals(2, llm.implementationCalls.size)
        assertTrue(llm.implementationCalls[1].userPrompt.contains("controller returns 200"), "diagnostics must reach the developer")
        assertEquals(1, llm.analysisCalls.size)
        assertEquals(2, buildTool.steps.count { it == BuildStep.TEST })
        val report = svc.report(id)
        assertEquals(Verdict.PASSED, report.content.verdict)
        assertEquals(1, report.content.failureAnalyses.size)
        assertEquals(2, svc.artifacts(id).count { it.type == ArtifactType.IMPLEMENTATION })
    }

    @Test
    fun `test defects make QA regenerate tests instead of changing the implementation`() {
        llm.analyses += FailureAnalysis(FailureCause.TEST, "test asserts the wrong status", emptyList(), "assert 201")
        val (svc, _) = build(listOf(false, true))
        val id = svc.create("Create a tasks API").id
        assertEquals(WorkflowStatus.COMPLETED, await(id, *terminal).status)
        assertEquals(1, llm.implementationCalls.size)
        assertEquals(2, llm.testCalls.size)
    }

    @Test
    fun `correction loop is bounded and failure diagnostics are preserved`() {
        val (svc, buildTool) = build(List(10) { false }, maxCorrections = 2)
        val id = svc.create("Create a tasks API").id
        val done = await(id, *terminal)

        assertEquals(WorkflowStatus.FAILED, done.status)
        assertEquals(ErrorCategory.RETRIES_EXHAUSTED, done.error?.category)
        assertTrue(done.error?.detail?.contains("FAILURE") == true)
        assertEquals(2, done.correctionAttempts)
        assertEquals(3, buildTool.steps.count { it == BuildStep.TEST }, "initial run + 2 retests, never more")
        assertEquals(Verdict.FAILED, svc.report(id).content.verdict)
    }

    @Test
    fun `zero corrections means a failure ends the workflow immediately`() {
        val (svc, buildTool) = build(listOf(false), maxCorrections = 0)
        val id = svc.create("Create a tasks API").id
        val done = await(id, *terminal)
        assertEquals(WorkflowStatus.FAILED, done.status)
        assertEquals(ErrorCategory.BUILD_FAILED, done.error?.category)
        assertEquals(1, buildTool.steps.count { it == BuildStep.TEST })
        assertEquals(0, llm.analysisCalls.size)
    }

    @Test
    fun `disabled execution never builds and reports NOT_EXECUTED`() {
        val (svc, buildTool) = build(enabled = false)
        val id = svc.create("Create a tasks API").id
        assertEquals(WorkflowStatus.COMPLETED, await(id, *terminal).status)
        assertTrue(buildTool.steps.isEmpty())
        val report = svc.report(id)
        assertEquals(Verdict.NOT_EXECUTED, report.content.verdict)
        assertNotNull(report.content.executionSkippedReason)
        assertNull(report.content.testSummary)
    }

    @Test
    fun `approval gate pauses before running generated code and approve resumes`() {
        val (svc, buildTool) = build(listOf(true), requireApproval = true)
        val id = svc.create("Create a tasks API").id
        val waiting = await(id, WorkflowStatus.WAITING_FOR_APPROVAL)
        assertEquals(WorkflowStage.APPROVAL, waiting.stage)
        assertTrue(buildTool.steps.isEmpty(), "nothing may run before approval")

        svc.decide(id, true)
        assertEquals(WorkflowStatus.COMPLETED, await(id, *terminal).status)
        assertEquals(Verdict.PASSED, svc.report(id).content.verdict)
    }

    @Test
    fun `rejecting approval skips execution`() {
        val (svc, buildTool) = build(requireApproval = true)
        val id = svc.create("Create a tasks API").id
        await(id, WorkflowStatus.WAITING_FOR_APPROVAL)
        svc.decide(id, false)
        assertEquals(WorkflowStatus.COMPLETED, await(id, *terminal).status)
        assertTrue(buildTool.steps.isEmpty())
        assertEquals(Verdict.NOT_EXECUTED, svc.report(id).content.verdict)
    }

    @Test
    fun `deciding when nothing waits is a conflict`() {
        val (svc, _) = build(enabled = false)
        val id = svc.create("x").id
        await(id, *terminal)
        assertThrows<WorkflowConflictException> { svc.decide(id, true) }
    }

    @Test
    fun `cancellation while waiting for approval ends in CANCELLED`() {
        val (svc, buildTool) = build(requireApproval = true)
        val id = svc.create("Create a tasks API").id
        await(id, WorkflowStatus.WAITING_FOR_APPROVAL)
        assertEquals(WorkflowStatus.CANCELLED, svc.cancel(id).status)
        Thread.sleep(200)
        assertEquals(WorkflowStatus.CANCELLED, svc.get(id).status)
        assertTrue(buildTool.steps.isEmpty())
        assertThrows<WorkflowConflictException> { svc.report(id) }
    }

    @Test
    fun `cancelling a finished workflow is a conflict`() {
        val (svc, _) = build(enabled = false)
        val id = svc.create("x").id
        await(id, *terminal)
        assertThrows<WorkflowConflictException> { svc.cancel(id) }
    }

    @Test
    fun `unsafe generated paths fail the workflow without writing anything`() {
        llm.implementations += ImplementationDraft(listOf(GeneratedFile("../../etc/evil", "x"), GeneratedFile("pom.xml", "x")))
        val (svc, buildTool) = build()
        val id = svc.create("x").id
        val done = await(id, *terminal)
        assertEquals(WorkflowStatus.FAILED, done.status)
        assertEquals(ErrorCategory.VALIDATION_FAILED, done.error?.category)
        assertEquals(WorkflowStage.DEVELOPMENT, done.error?.stage)
        assertTrue(buildTool.steps.isEmpty())
        assertTrue(svc.history(id).any { it.outcome == ExecutionOutcome.INVALID_OUTPUT })
    }

    @Test
    fun `invalid model output is retried within the configured limit`() {
        llm.implementations += ImplementationDraft(listOf(GeneratedFile("README.md", "no pom")))
        val (svc, _) = build(enabled = false, maxOutputRetries = 1)
        val id = svc.create("x").id
        assertEquals(WorkflowStatus.COMPLETED, await(id, *terminal).status)
        assertEquals(2, llm.implementationCalls.size)
        assertTrue(llm.implementationCalls[1].userPrompt.contains("rejected"))
    }

    @Test
    fun `llm failure is recorded as LLM_FAILURE at the failing stage`() {
        llm.failRequirements = java.io.IOException("connection refused")
        val (svc, _) = build()
        val id = svc.create("x").id
        val done = await(id, *terminal)
        assertEquals(WorkflowStatus.FAILED, done.status)
        assertEquals(ErrorCategory.LLM_FAILURE, done.error?.category)
        assertEquals(WorkflowStage.REQUIREMENTS, done.error?.stage)
        assertTrue(svc.artifacts(id).isEmpty())
    }

    @Test
    fun `interrupted workflows are failed on startup`() {
        val (svc, _) = build(requireApproval = true)
        val id = svc.create("x").id
        await(id, WorkflowStatus.WAITING_FOR_APPROVAL)
        val (restarted, _) = build()
        restarted.recoverInterrupted()
        svc.shutdown()
        assertEquals(ErrorCategory.INTERRUPTED, restarted.get(id).error?.category)
        assertEquals(WorkflowStatus.FAILED, restarted.get(id).status)
    }
}

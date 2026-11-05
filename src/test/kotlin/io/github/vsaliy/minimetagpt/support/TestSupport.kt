package io.github.vsaliy.minimetagpt.support

import io.github.vsaliy.minimetagpt.agents.Generated
import io.github.vsaliy.minimetagpt.agents.LlmCall
import io.github.vsaliy.minimetagpt.agents.StructuredLlm
import io.github.vsaliy.minimetagpt.domain.AgentExecutionResult
import io.github.vsaliy.minimetagpt.domain.Artifact
import io.github.vsaliy.minimetagpt.domain.ArtifactType
import io.github.vsaliy.minimetagpt.domain.BuildResult
import io.github.vsaliy.minimetagpt.domain.BuildStep
import io.github.vsaliy.minimetagpt.domain.ErrorInfo
import io.github.vsaliy.minimetagpt.domain.FailureAnalysis
import io.github.vsaliy.minimetagpt.domain.ImplementationDraft
import io.github.vsaliy.minimetagpt.domain.TestSuiteDraft
import io.github.vsaliy.minimetagpt.domain.TestSummary
import io.github.vsaliy.minimetagpt.domain.WorkflowExecution
import io.github.vsaliy.minimetagpt.domain.WorkflowStatus
import io.github.vsaliy.minimetagpt.infrastructure.llm.TaskApiFakeLlm
import io.github.vsaliy.minimetagpt.infrastructure.tools.BuildTool
import io.github.vsaliy.minimetagpt.infrastructure.tools.Workspace
import io.github.vsaliy.minimetagpt.orchestration.AgentExecutionRecord
import io.github.vsaliy.minimetagpt.orchestration.WorkflowRepository
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Fake LLM with scriptable behaviour per call; defaults to the deterministic task-API answers. */
class ScriptedLlm(private val base: StructuredLlm = TaskApiFakeLlm()) : StructuredLlm by base {
    val implementationCalls = CopyOnWriteArrayList<LlmCall>()
    val testCalls = CopyOnWriteArrayList<LlmCall>()
    val analysisCalls = CopyOnWriteArrayList<LlmCall>()
    val implementations = ArrayDeque<ImplementationDraft>()
    val tests = ArrayDeque<TestSuiteDraft>()
    val analyses = ArrayDeque<FailureAnalysis>()
    var failRequirements: Exception? = null

    override suspend fun requirements(call: LlmCall) = failRequirements?.let { throw it } ?: base.requirements(call)

    override suspend fun implementation(call: LlmCall): Generated<ImplementationDraft> {
        implementationCalls += call
        return implementations.removeFirstOrNull()?.let { Generated(it, "scripted", null) } ?: base.implementation(call)
    }

    override suspend fun tests(call: LlmCall): Generated<TestSuiteDraft> {
        testCalls += call
        return tests.removeFirstOrNull()?.let { Generated(it, "scripted", null) } ?: base.tests(call)
    }

    override suspend fun failureAnalysis(call: LlmCall): Generated<FailureAnalysis> {
        analysisCalls += call
        return analyses.removeFirstOrNull()?.let { Generated(it, "scripted", null) } ?: base.failureAnalysis(call)
    }
}

/** Build tool that returns scripted pass/fail results without running anything. */
class ScriptedBuildTool(private val outcomes: MutableList<Boolean>) : BuildTool {
    val steps = CopyOnWriteArrayList<BuildStep>()
    private var lastPassed = true

    override suspend fun run(workspace: Workspace, step: BuildStep): BuildResult {
        steps += step
        val pass = if (step == BuildStep.COMPILE) true.also { /* compile always ok */ } else outcomes.removeFirstOrNull() ?: true
        if (step == BuildStep.TEST) lastPassed = pass
        return BuildResult(step, listOf("mvn", step.name), if (pass) 0 else 1, false, 5, if (pass) "BUILD SUCCESS" else "FAILURE: test failed", Instant.now())
    }

    override fun testSummary(workspace: Workspace) = if (lastPassed) TestSummary(3, 0, 0, 0) else TestSummary(3, 1, 0, 0)
}

class InMemoryWorkflowRepository : WorkflowRepository {
    private val workflows = ConcurrentHashMap<UUID, WorkflowExecution>()
    val allArtifacts = CopyOnWriteArrayList<Artifact>()
    val executions = CopyOnWriteArrayList<AgentExecutionResult>()

    override fun save(execution: WorkflowExecution) { workflows[execution.id] = execution }
    override fun find(id: UUID) = workflows[id]
    override fun saveArtifact(artifact: Artifact) { allArtifacts += artifact }
    override fun artifacts(workflowId: UUID) = allArtifacts.filter { it.meta.workflowId == workflowId }
    override fun latestArtifact(workflowId: UUID, type: ArtifactType) = artifacts(workflowId).lastOrNull { it.type == type }
    override fun saveAgentExecution(result: AgentExecutionResult) { executions += result }
    override fun agentExecutions(workflowId: UUID) = executions.filter { it.task.workflowId == workflowId }.map {
        AgentExecutionRecord(it.task.id, workflowId, it.task.role, it.task.stage, it.task.attempt, it.outcome, it.artifact?.id,
            it.error, it.validation, it.tokenUsage, it.startedAt, it.finishedAt, it.durationMs)
    }
    override fun failInterrupted(error: ErrorInfo, now: Instant): Int {
        var n = 0
        workflows.replaceAll { _, w ->
            if (!w.status.terminal) { n++; w.copy(status = WorkflowStatus.FAILED, error = error, finishedAt = now) } else w
        }
        return n
    }
}

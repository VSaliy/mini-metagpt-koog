package io.github.vsaliy.minimetagpt.agents

import io.github.vsaliy.minimetagpt.domain.AgentRole
import io.github.vsaliy.minimetagpt.domain.AgentTask
import io.github.vsaliy.minimetagpt.domain.ErrorCategory
import io.github.vsaliy.minimetagpt.domain.GeneratedFile
import io.github.vsaliy.minimetagpt.domain.ImplementationDraft
import io.github.vsaliy.minimetagpt.domain.WorkflowStage
import io.github.vsaliy.minimetagpt.infrastructure.tools.WorkspaceManager
import io.github.vsaliy.minimetagpt.support.ScriptedBuildTool
import io.github.vsaliy.minimetagpt.support.ScriptedLlm
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Each agent stage is tested in isolation, without the workflow engine. */
class AgentsTest {
    @TempDir
    lateinit var root: Path

    private val workflowId = UUID.randomUUID()
    private val llm = ScriptedLlm()
    private val generator = StructuredGenerator(0)

    private fun task(role: AgentRole, stage: WorkflowStage, inputs: List<io.github.vsaliy.minimetagpt.domain.ArtifactReference> = emptyList()) =
        AgentTask(workflowId = workflowId, role = role, stage = stage, inputs = inputs)

    @Test
    fun `requirements architect developer and qa chain with provenance`() = runBlocking {
        val req = RequirementsAgent(llm, generator).execute(task(AgentRole.REQUIREMENTS, WorkflowStage.REQUIREMENTS), "Create a tasks API").value
        assertEquals(workflowId, req.meta.workflowId)
        assertEquals(AgentRole.REQUIREMENTS, req.meta.provenance.producedBy)
        assertTrue(req.meta.validation.valid)

        val arch = ArchitectAgent(llm, generator).execute(task(AgentRole.ARCHITECT, WorkflowStage.ARCHITECTURE, listOf(req.reference())), req).value
        assertEquals(listOf(req.reference()), arch.meta.provenance.inputs)

        val ws = WorkspaceManager(root).create(workflowId)
        val impl = DeveloperAgent(llm, generator).execute(task(AgentRole.DEVELOPER, WorkflowStage.DEVELOPMENT, listOf(req.reference(), arch.reference())), req, arch, ws).value
        assertEquals(1, impl.content.revision)
        assertTrue(impl.content.files.any { it.path == "pom.xml" })
        assertTrue(ws.exists("src/main/java/com/example/tasks/TaskController.java"))

        val tests = QaAgent(llm, generator, ScriptedBuildTool(mutableListOf()))
            .generateTests(task(AgentRole.QA, WorkflowStage.TEST_GENERATION), req, arch, impl, ws).value
        assertTrue(tests.files.all { it.path.startsWith("src/test/java/") })
        assertTrue(ws.exists("src/test/java/com/example/tasks/TaskControllerTest.java"))
    }

    @Test
    fun `developer never writes outside the workspace`() = runBlocking {
        val req = RequirementsAgent(llm, generator).execute(task(AgentRole.REQUIREMENTS, WorkflowStage.REQUIREMENTS), "x").value
        val arch = ArchitectAgent(llm, generator).execute(task(AgentRole.ARCHITECT, WorkflowStage.ARCHITECTURE), req).value
        llm.implementations += ImplementationDraft(listOf(GeneratedFile("../../escape.txt", "pwned"), GeneratedFile("pom.xml", "x")))
        val ws = WorkspaceManager(root).create(workflowId)

        val failure = assertThrows<AgentFailure> {
            DeveloperAgent(llm, generator).execute(task(AgentRole.DEVELOPER, WorkflowStage.DEVELOPMENT), req, arch, ws)
        }
        assertEquals(ErrorCategory.VALIDATION_FAILED, failure.error.category)
        assertTrue(ws.list().isEmpty())
        assertTrue(!Files.exists(root.resolve("escape.txt")) && !Files.exists(root.parent.resolve("escape.txt")))
    }

    @Test
    fun `qa report never claims a pass without execution`() = runBlocking {
        val qa = QaAgent(llm, generator, ScriptedBuildTool(mutableListOf()))
        val req = RequirementsAgent(llm, generator).execute(task(AgentRole.REQUIREMENTS, WorkflowStage.REQUIREMENTS), "x").value
        val arch = ArchitectAgent(llm, generator).execute(task(AgentRole.ARCHITECT, WorkflowStage.ARCHITECTURE), req).value
        val ws = WorkspaceManager(root).create(workflowId)
        val impl = DeveloperAgent(llm, generator).execute(task(AgentRole.DEVELOPER, WorkflowStage.DEVELOPMENT), req, arch, ws).value
        val tests = qa.generateTests(task(AgentRole.QA, WorkflowStage.TEST_GENERATION), req, arch, impl, ws).value

        val report = qa.report(task(AgentRole.QA, WorkflowStage.DONE), tests, null, emptyList(), 0, "not approved", null, null)
        assertEquals(io.github.vsaliy.minimetagpt.domain.Verdict.NOT_EXECUTED, report.content.verdict)
        assertTrue(report.content.buildResults.isEmpty())
        assertTrue(report.content.summary.contains("NOT executed"))
    }
}

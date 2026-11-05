package io.github.vsaliy.minimetagpt.agents

import io.github.vsaliy.minimetagpt.domain.AgentRole
import io.github.vsaliy.minimetagpt.domain.AgentTask
import io.github.vsaliy.minimetagpt.domain.ArchitectureArtifact
import io.github.vsaliy.minimetagpt.domain.ArchitectureContent
import io.github.vsaliy.minimetagpt.domain.ArtifactMeta
import io.github.vsaliy.minimetagpt.domain.ArtifactValidators
import io.github.vsaliy.minimetagpt.domain.FailureAnalysis
import io.github.vsaliy.minimetagpt.domain.ImplementationArtifact
import io.github.vsaliy.minimetagpt.domain.ImplementationContent
import io.github.vsaliy.minimetagpt.domain.Provenance
import io.github.vsaliy.minimetagpt.domain.RequirementsArtifact
import io.github.vsaliy.minimetagpt.domain.RequirementsContent
import io.github.vsaliy.minimetagpt.infrastructure.tools.Workspace

class DeveloperAgent(private val llm: StructuredLlm, private val generator: StructuredGenerator) {

    /**
     * Generates the project (or, when [previous] and [analysis] are given, a correction) and writes it into
     * [workspace]. Files the model proposes are validated, then written only through the workspace tool.
     */
    suspend fun execute(
        task: AgentTask,
        requirements: RequirementsArtifact,
        architecture: ArchitectureArtifact,
        workspace: Workspace,
        previous: ImplementationArtifact? = null,
        analysis: FailureAnalysis? = null,
    ): AgentOutput<ImplementationArtifact> {
        val existing = if (previous != null) workspace.list().toSet() else emptySet()
        val call = LlmCall(task.workflowId, AgentRole.DEVELOPER, SYSTEM, userPrompt(requirements, architecture, workspace, previous, analysis))
        val out = generator.generate(call, llm::implementation) { ArtifactValidators.implementation(it, existing) }

        val purposes = out.value.files.associate { it.path to it.purpose }
        out.value.files.forEach { workspace.write(it.path, it.content, it.purpose) }
        val projectFiles = workspace.list().filter { !it.startsWith("src/test/") }
        val revision = (previous?.content?.revision ?: 0) + 1
        val meta = ArtifactMeta(
            workflowId = task.workflowId,
            provenance = Provenance(AgentRole.DEVELOPER, out.model, task.inputs, task.attempt),
            validation = out.validation,
        )
        val content = ImplementationContent(
            workspaceId = workspace.id,
            files = workspace.manifest(projectFiles, purposes),
            notes = out.value.notes,
            revision = revision,
        )
        return AgentOutput(ImplementationArtifact(meta, content), out.model, out.usage, out.validation)
    }

    private fun userPrompt(
        requirements: RequirementsArtifact,
        architecture: ArchitectureArtifact,
        workspace: Workspace,
        previous: ImplementationArtifact?,
        analysis: FailureAnalysis?,
    ): String = buildString {
        appendLine("Requirements (JSON):")
        appendLine(promptJson.encodeToString(RequirementsContent.serializer(), requirements.content))
        appendLine("Approved architecture (JSON):")
        appendLine(promptJson.encodeToString(ArchitectureContent.serializer(), architecture.content))
        if (previous != null && analysis != null) {
            appendLine("\nThis is a CORRECTION round (revision ${previous.content.revision + 1}). Current project files:")
            previous.content.files.filter { it.path.endsWith(".java") || it.path == "pom.xml" }.forEach {
                appendLine("--- ${it.path}\n${workspace.read(it.path).truncate(12_000)}")
            }
            appendLine("\nQA failure analysis (cause=${analysis.cause}): ${analysis.summary}")
            if (analysis.suspectedFiles.isNotEmpty()) appendLine("Suspected files: ${analysis.suspectedFiles.joinToString()}")
            if (analysis.suggestedFix.isNotBlank()) appendLine("Suggested fix: ${analysis.suggestedFix}")
            appendLine("Return ONLY the files that must be created or replaced (full content for each).")
        }
    }

    companion object {
        const val SYSTEM = """You are the Developer Agent of a software factory.
Implement the approved architecture as a complete Maven project for Java 21 and Spring Boot $GENERATED_SPRING_BOOT_VERSION.
Rules:
- Return files as JSON: relative POSIX paths, full file contents. Never use absolute paths, '..' or backslashes.
- Include pom.xml (parent spring-boot-starter-parent $GENERATED_SPRING_BOOT_VERSION, java.version 21,
  spring-boot-starter-web, spring-boot-starter-validation and spring-boot-starter-test in test scope),
  the @SpringBootApplication class, application.properties, controllers, services, repositories, entities and DTOs
  under src/main/java and src/main/resources, following the architecture exactly.
- Do NOT write tests: files under src/test are owned by the QA agent.
- Do not add plugins that execute arbitrary code or download scripts. Do not embed secrets.
Answer only with the requested JSON."""
    }
}

package io.github.vsaliy.minimetagpt.agents

import io.github.vsaliy.minimetagpt.domain.AgentRole
import io.github.vsaliy.minimetagpt.domain.AgentTask
import io.github.vsaliy.minimetagpt.domain.ArchitectureArtifact
import io.github.vsaliy.minimetagpt.domain.ArtifactMeta
import io.github.vsaliy.minimetagpt.domain.ArtifactValidators
import io.github.vsaliy.minimetagpt.domain.Provenance
import io.github.vsaliy.minimetagpt.domain.RequirementsArtifact
import io.github.vsaliy.minimetagpt.domain.RequirementsContent

class ArchitectAgent(private val llm: StructuredLlm, private val generator: StructuredGenerator) {

    suspend fun execute(task: AgentTask, requirements: RequirementsArtifact): AgentOutput<ArchitectureArtifact> {
        val user = "Requirements (JSON):\n" + promptJson.encodeToString(RequirementsContent.serializer(), requirements.content)
        val call = LlmCall(task.workflowId, AgentRole.ARCHITECT, SYSTEM, user)
        val out = generator.generate(call, llm::architecture, ArtifactValidators::architecture)
        val meta = ArtifactMeta(
            workflowId = task.workflowId,
            provenance = Provenance(AgentRole.ARCHITECT, out.model, task.inputs, task.attempt),
            validation = out.validation,
        )
        return AgentOutput(ArchitectureArtifact(meta, out.value), out.model, out.usage, out.validation)
    }

    companion object {
        const val SYSTEM = """You are the Software Architect Agent of a software factory.
Design a small Java 21 Spring Boot $GENERATED_SPRING_BOOT_VERSION REST service that satisfies the given requirements.
Define: projectName (lowercase-with-dashes, used as Maven artifactId), basePackage (lowercase Java package),
components (controller/service/repository/etc.), REST endpoints (method, path, request/response types, success status),
domain entities and DTOs with fields and Jakarta Bean Validation constraints, a persistence strategy,
Maven dependencies (groupId/artifactId/scope) and significant architectural decisions with rationale.
Prefer the simplest design that satisfies the requirements. Answer only with the requested JSON."""
    }
}

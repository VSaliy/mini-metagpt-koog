package io.github.vsaliy.minimetagpt.agents

import io.github.vsaliy.minimetagpt.domain.AgentRole
import io.github.vsaliy.minimetagpt.domain.ArchitectureContent
import io.github.vsaliy.minimetagpt.domain.FailureAnalysis
import io.github.vsaliy.minimetagpt.domain.ImplementationDraft
import io.github.vsaliy.minimetagpt.domain.RequirementsContent
import io.github.vsaliy.minimetagpt.domain.TestSuiteDraft
import io.github.vsaliy.minimetagpt.domain.TokenUsage
import java.util.UUID

data class LlmCall(val workflowId: UUID, val role: AgentRole, val systemPrompt: String, val userPrompt: String)

/** A structured model answer. [usage] is only set when the provider reported it; it is never estimated. */
data class Generated<T>(val value: T, val model: String?, val usage: TokenUsage?)

/**
 * Provider-neutral port between agents and the LLM runtime. Agents depend only on this interface, so the
 * Koog/OpenAI integration can be swapped (Ollama, fakes) without touching domain or orchestration code.
 */
interface StructuredLlm {
    suspend fun requirements(call: LlmCall): Generated<RequirementsContent>
    suspend fun architecture(call: LlmCall): Generated<ArchitectureContent>
    suspend fun implementation(call: LlmCall): Generated<ImplementationDraft>
    suspend fun tests(call: LlmCall): Generated<TestSuiteDraft>
    suspend fun failureAnalysis(call: LlmCall): Generated<FailureAnalysis>
}

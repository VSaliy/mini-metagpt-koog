package io.github.vsaliy.minimetagpt.infrastructure.llm

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.structure.LLMStructuredParsingError
import io.github.vsaliy.minimetagpt.agents.Generated
import io.github.vsaliy.minimetagpt.agents.InvalidModelOutputException
import io.github.vsaliy.minimetagpt.agents.LlmCall
import io.github.vsaliy.minimetagpt.agents.StructuredLlm
import io.github.vsaliy.minimetagpt.domain.ArchitectureContent
import io.github.vsaliy.minimetagpt.domain.FailureAnalysis
import io.github.vsaliy.minimetagpt.domain.ImplementationDraft
import io.github.vsaliy.minimetagpt.domain.RequirementsContent
import io.github.vsaliy.minimetagpt.domain.TestSuiteDraft
import io.github.vsaliy.minimetagpt.domain.TokenUsage
import kotlinx.serialization.SerializationException

/**
 * The only place that talks to JetBrains Koog. Each call runs a one-node Koog graph agent that asks the model
 * for structured output (JSON schema derived from the `@Serializable` contract type) and parses it.
 */
class KoogStructuredLlm(
    private val executor: PromptExecutor,
    private val model: LLModel,
    private val temperature: Double?,
    private val maxIterations: Int,
) : StructuredLlm {

    override suspend fun requirements(call: LlmCall) = run<RequirementsContent>(call)
    override suspend fun architecture(call: LlmCall) = run<ArchitectureContent>(call)
    override suspend fun implementation(call: LlmCall) = run<ImplementationDraft>(call)
    override suspend fun tests(call: LlmCall) = run<TestSuiteDraft>(call)
    override suspend fun failureAnalysis(call: LlmCall) = run<FailureAnalysis>(call)

    private suspend inline fun <reified T : Any> run(call: LlmCall): Generated<T> {
        val structured = strategy<String, Generated<T>>("structured-${call.role.name.lowercase()}") {
            val request by node<String, Generated<T>>("request") { message ->
                llm.writeSession {
                    appendPrompt { user(message) }
                    val response = requestLLMStructured<T>().getOrThrow()
                    val meta = response.message.metaInfo
                    val usage = if (meta.inputTokensCount != null || meta.outputTokensCount != null || meta.totalTokensCount != null) {
                        TokenUsage(meta.inputTokensCount, meta.outputTokensCount, meta.totalTokensCount)
                    } else {
                        null
                    }
                    Generated(response.data, meta.modelId ?: model.id, usage)
                }
            }
            edge(nodeStart forwardTo request)
            edge(request forwardTo nodeFinish)
        }
        val agent = AIAgent(
            promptExecutor = executor,
            llmModel = model,
            strategy = structured,
            systemPrompt = call.systemPrompt,
            temperature = temperature,
            maxIterations = maxIterations,
        )
        return try {
            agent.run(call.userPrompt)
        } catch (e: LLMStructuredParsingError) {
            throw InvalidModelOutputException("Response is not valid ${T::class.simpleName} JSON: ${e.message}", e)
        } catch (e: SerializationException) {
            throw InvalidModelOutputException("Response is not valid ${T::class.simpleName} JSON: ${e.message}", e)
        }
    }
}

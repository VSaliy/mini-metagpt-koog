package io.github.vsaliy.minimetagpt.infrastructure.llm

import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import io.github.vsaliy.minimetagpt.agents.StructuredLlm
import io.github.vsaliy.minimetagpt.infrastructure.config.MiniMetagptProperties.Llm
import io.github.vsaliy.minimetagpt.infrastructure.config.MiniMetagptProperties.Provider

/** Selects the provider. Adding a provider only touches this file; agents and orchestration are unaffected. */
object LlmFactory {
    fun create(config: Llm): StructuredLlm = when (config.provider) {
        Provider.FAKE -> TaskApiFakeLlm()
        Provider.OPENAI -> {
            check(config.apiKey.isNotBlank()) { "minimetagpt.llm.api-key (OPENAI_API_KEY) is required for provider OPENAI" }
            val settings = if (config.baseUrl.isBlank()) OpenAIClientSettings() else OpenAIClientSettings(baseUrl = config.baseUrl)
            val model = OpenAIModels.Chat.GPT4o.let { if (config.model.isBlank()) it else it.copy(id = config.model) }
            koog(MultiLLMPromptExecutor(OpenAILLMClient(config.apiKey, settings)), model, config)
        }
        Provider.OLLAMA -> {
            check(config.model.isNotBlank()) { "minimetagpt.llm.model is required for provider OLLAMA" }
            val client = if (config.baseUrl.isBlank()) OllamaClient() else OllamaClient(baseUrl = config.baseUrl)
            // No JSON-schema capability is declared, so Koog falls back to prompt-embedded ("manual") structured output.
            val model = LLModel(LLMProvider.Ollama, config.model, listOf(LLMCapability.Temperature))
            koog(MultiLLMPromptExecutor(client), model, config)
        }
    }

    private fun koog(executor: MultiLLMPromptExecutor, model: LLModel, config: Llm) =
        KoogStructuredLlm(executor, model, config.temperature, config.maxIterations)
}

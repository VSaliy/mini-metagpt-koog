package io.github.vsaliy.minimetagpt.infrastructure

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import io.github.vsaliy.minimetagpt.agents.InvalidModelOutputException
import io.github.vsaliy.minimetagpt.agents.LlmCall
import io.github.vsaliy.minimetagpt.domain.AgentRole
import io.github.vsaliy.minimetagpt.domain.RequirementsContent
import io.github.vsaliy.minimetagpt.domain.TokenUsage
import io.github.vsaliy.minimetagpt.infrastructure.llm.KoogStructuredLlm
import io.github.vsaliy.minimetagpt.infrastructure.llm.TaskApiFakeLlm
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Clock
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exercises the real Koog agent/strategy/structured-output path against a scripted PromptExecutor (no network). */
class KoogStructuredLlmTest {

    private class ScriptedExecutor(private val reply: String, private val tokens: Boolean) : PromptExecutor() {
        val prompts = mutableListOf<Prompt>()

        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
            prompts += prompt
            val meta = if (tokens) ResponseMetaInfo(Clock.System.now(), 30, 10, 20, model.id) else ResponseMetaInfo(Clock.System.now())
            return Message.Assistant(reply, meta)
        }

        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
            throw UnsupportedOperationException()

        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = throw UnsupportedOperationException()

        override fun close() {}
    }

    private val model = LLModel(LLMProvider.Ollama, "test-model", listOf(LLMCapability.Temperature))
    private val call = LlmCall(UUID.randomUUID(), AgentRole.REQUIREMENTS, "You are the requirements agent.", "Build a task API")

    @Test
    fun `parses structured output through a Koog agent and reports provider usage`() = runBlocking {
        val executor = ScriptedExecutor(Json.encodeToString(TaskApiFakeLlm.REQUIREMENTS), tokens = true)
        val llm = KoogStructuredLlm(executor, model, 0.1, 10)

        val result = llm.requirements(call)

        assertEquals(TaskApiFakeLlm.REQUIREMENTS, result.value)
        assertEquals(TokenUsage(10, 20, 30), result.usage)
        assertEquals("test-model", result.model)
        val rendered = executor.prompts.single().toString()
        assertTrue(rendered.contains("You are the requirements agent."))
        assertTrue(rendered.contains("Build a task API"))
        assertTrue(rendered.contains("functionalRequirements"), "JSON schema instructions must be part of the prompt")
    }

    @Test
    fun `never invents token usage`() = runBlocking {
        val executor = ScriptedExecutor(Json.encodeToString(TaskApiFakeLlm.REQUIREMENTS), tokens = false)
        assertNull(KoogStructuredLlm(executor, model, null, 10).requirements(call).usage)
    }

    @Test
    fun `unparseable output becomes InvalidModelOutputException`() {
        val llm = KoogStructuredLlm(ScriptedExecutor("this is not json", false), model, null, 10)
        assertThrows<InvalidModelOutputException> { runBlocking { llm.requirements(call) } }
    }

    @Test
    fun `wrong shape becomes InvalidModelOutputException`() {
        val llm = KoogStructuredLlm(ScriptedExecutor("""{"title":"only a title"}""", false), model, null, 10)
        assertThrows<InvalidModelOutputException> { runBlocking<RequirementsContent> { llm.requirements(call).value } }
    }
}

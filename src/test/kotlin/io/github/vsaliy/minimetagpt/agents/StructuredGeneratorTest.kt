package io.github.vsaliy.minimetagpt.agents

import io.github.vsaliy.minimetagpt.domain.AgentRole
import io.github.vsaliy.minimetagpt.domain.ErrorCategory
import io.github.vsaliy.minimetagpt.domain.TokenUsage
import io.github.vsaliy.minimetagpt.domain.ValidationCollector
import io.github.vsaliy.minimetagpt.domain.ValidationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StructuredGeneratorTest {
    private val call = LlmCall(UUID.randomUUID(), AgentRole.REQUIREMENTS, "sys", "user")
    private val ok: (String) -> ValidationResult = { ValidationResult.OK }

    @Test
    fun `retries invalid output with feedback and then succeeds`() = runBlocking {
        val prompts = mutableListOf<String>()
        var n = 0
        val out = StructuredGenerator(2).generate(call, { c ->
            prompts += c.userPrompt
            if (++n < 3) throw InvalidModelOutputException("bad json") else Generated("good", "m", TokenUsage(1, 2, 3))
        }, ok)
        assertEquals("good", out.value)
        assertEquals(3, n)
        assertTrue(prompts[1].contains("bad json"))
    }

    @Test
    fun `retries when validation fails and reports feedback`() = runBlocking {
        val prompts = mutableListOf<String>()
        val validate = { v: String ->
            ValidationCollector().apply { require(v == "fixed", "field", "must be fixed") }.result()
        }
        val out = StructuredGenerator(1).generate(call, { c ->
            prompts += c.userPrompt
            Generated(if (prompts.size == 1) "broken" else "fixed", null, null)
        }, validate)
        assertEquals("fixed", out.value)
        assertTrue(prompts[1].contains("field: must be fixed"))
    }

    @Test
    fun `gives up after the retry limit with INVALID_OUTPUT`() {
        var n = 0
        val failure = assertThrows<AgentFailure> {
            runBlocking { StructuredGenerator(1).generate<String>(call, { n++; throw InvalidModelOutputException("nope") }, ok) }
        }
        assertEquals(2, n)
        assertEquals(ErrorCategory.INVALID_OUTPUT, failure.error.category)
    }

    @Test
    fun `persistent validation failure is VALIDATION_FAILED`() {
        val failure = assertThrows<AgentFailure> {
            runBlocking {
                StructuredGenerator(0).generate(call, { Generated("x", null, null) }, { ValidationCollector().apply { error("p", "bad") }.result() })
            }
        }
        assertEquals(ErrorCategory.VALIDATION_FAILED, failure.error.category)
    }

    @Test
    fun `provider errors are not retried`() {
        var n = 0
        val failure = assertThrows<AgentFailure> {
            runBlocking { StructuredGenerator(5).generate<String>(call, { n++; throw java.io.IOException("503") }, ok) }
        }
        assertEquals(1, n)
        assertEquals(ErrorCategory.LLM_FAILURE, failure.error.category)
    }

    @Test
    fun `cancellation propagates untouched`() {
        assertThrows<CancellationException> {
            runBlocking { StructuredGenerator(5).generate<String>(call, { throw CancellationException("stop") }, ok) }
        }
    }

    @Test
    fun `usage is summed only from reported values`() = runBlocking {
        var n = 0
        val out = StructuredGenerator(1).generate(call, {
            if (n++ == 0) Generated("a", null, TokenUsage(10, 5, 15)) else Generated("b", null, null)
        }, { v -> if (v == "b") ValidationResult.OK else ValidationCollector().apply { error("x", "y") }.result() })
        assertEquals(TokenUsage(10, 5, 15), out.usage)
    }
}

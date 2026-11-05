package io.github.vsaliy.minimetagpt.agents

import io.github.vsaliy.minimetagpt.domain.ErrorCategory
import io.github.vsaliy.minimetagpt.domain.ErrorInfo
import io.github.vsaliy.minimetagpt.domain.TokenUsage
import io.github.vsaliy.minimetagpt.domain.ValidationResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/** The model answered, but not with a parseable instance of the requested contract. */
class InvalidModelOutputException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** A stage cannot continue; carries the structured diagnostic that is persisted with the workflow. */
class AgentFailure(val error: ErrorInfo, cause: Throwable? = null) : RuntimeException(error.message, cause)

/** Result of an agent stage. [validation] is the outcome of the contract check that gated the value. */
data class AgentOutput<T>(val value: T, val model: String?, val usage: TokenUsage?, val validation: ValidationResult)

/**
 * Calls the model, validates the result against its contract and retries (bounded by [maxOutputRetries])
 * with the validation feedback appended to the prompt. Invalid output never leaves this function.
 */
class StructuredGenerator(private val maxOutputRetries: Int) {

    suspend fun <T> generate(
        call: LlmCall,
        produce: suspend (LlmCall) -> Generated<T>,
        validate: (T) -> ValidationResult,
    ): AgentOutput<T> {
        var feedback: String? = null
        var lastCategory = ErrorCategory.INVALID_OUTPUT
        var usage: TokenUsage? = null
        for (attempt in 0..maxOutputRetries.coerceAtLeast(0)) {
            val current = feedback?.let {
                call.copy(userPrompt = call.userPrompt + "\n\nYour previous answer was rejected. Fix these problems and answer again:\n" + it)
            } ?: call
            try {
                val generated = produce(current)
                usage = usage.plus(generated.usage)
                val validation = validate(generated.value)
                if (validation.valid) return AgentOutput(generated.value, generated.model, usage, validation)
                lastCategory = ErrorCategory.VALIDATION_FAILED
                feedback = validation.summary()
            } catch (e: CancellationException) {
                throw e
            } catch (e: InvalidModelOutputException) {
                lastCategory = ErrorCategory.INVALID_OUTPUT
                feedback = e.message
            } catch (e: Exception) {
                throw AgentFailure(
                    ErrorInfo(ErrorCategory.LLM_FAILURE, "LLM call failed: ${e.message}", role = call.role, detail = e.javaClass.name),
                    e,
                )
            }
        }
        throw AgentFailure(
            ErrorInfo(lastCategory, "Model output rejected after ${maxOutputRetries + 1} attempt(s)", role = call.role, detail = feedback),
        )
    }

    private fun TokenUsage?.plus(other: TokenUsage?): TokenUsage? = when {
        this == null -> other
        other == null -> this
        else -> TokenUsage(sum(inputTokens, other.inputTokens), sum(outputTokens, other.outputTokens), sum(totalTokens, other.totalTokens))
    }

    private fun sum(a: Int?, b: Int?): Int? = if (a == null && b == null) null else (a ?: 0) + (b ?: 0)
}

internal const val GENERATED_SPRING_BOOT_VERSION = "3.5.16"

internal fun String.truncate(max: Int): String = if (length <= max) this else take(max) + "\n...[truncated ${length - max} chars]"

internal val promptJson = Json { prettyPrint = true }

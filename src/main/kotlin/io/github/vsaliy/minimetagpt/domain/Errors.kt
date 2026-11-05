package io.github.vsaliy.minimetagpt.domain

enum class ErrorCategory {
    LLM_FAILURE,
    INVALID_OUTPUT,
    VALIDATION_FAILED,
    UNSAFE_OUTPUT,
    BUILD_FAILED,
    TOOL_FAILURE,
    APPROVAL_REJECTED,
    RETRIES_EXHAUSTED,
    INTERRUPTED,
    INTERNAL,
}

data class ErrorInfo(
    val category: ErrorCategory,
    val message: String,
    val stage: WorkflowStage? = null,
    val role: AgentRole? = null,
    val detail: String? = null,
)

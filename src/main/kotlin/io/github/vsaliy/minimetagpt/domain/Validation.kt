package io.github.vsaliy.minimetagpt.domain

enum class Severity { ERROR, WARNING }

data class ValidationIssue(val path: String, val message: String, val severity: Severity = Severity.ERROR)

data class ValidationResult(val issues: List<ValidationIssue> = emptyList()) {
    val valid: Boolean get() = issues.none { it.severity == Severity.ERROR }

    fun summary(): String = issues.joinToString("; ") { "${it.path}: ${it.message}" }

    companion object {
        val OK = ValidationResult()
    }
}

class ValidationCollector {
    private val issues = mutableListOf<ValidationIssue>()

    fun error(path: String, message: String) {
        issues += ValidationIssue(path, message)
    }

    fun warn(path: String, message: String) {
        issues += ValidationIssue(path, message, Severity.WARNING)
    }

    fun require(condition: Boolean, path: String, message: String) {
        if (!condition) error(path, message)
    }

    fun result() = ValidationResult(issues.toList())
}

/** Thrown when an artifact produced by a stage does not satisfy its contract. */
class ArtifactValidationException(val validation: ValidationResult) :
    RuntimeException("Artifact validation failed: ${validation.summary()}")

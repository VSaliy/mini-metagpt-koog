package io.github.vsaliy.minimetagpt.domain

import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

const val CONTRACT_VERSION = "1.0"

enum class ArtifactType { REQUIREMENTS, ARCHITECTURE, IMPLEMENTATION, QUALITY_REPORT }

/** Stable pointer to a stored artifact. */
data class ArtifactReference(
    val id: UUID,
    val type: ArtifactType,
    val schemaVersion: String = CONTRACT_VERSION,
)

/** Where an artifact came from: producing role, model and the artifacts it was derived from. */
data class Provenance(
    val producedBy: AgentRole,
    val model: String?,
    val inputs: List<ArtifactReference>,
    val attempt: Int = 1,
)

data class ArtifactMeta(
    val id: UUID = UUID.randomUUID(),
    val workflowId: UUID,
    val schemaVersion: String = CONTRACT_VERSION,
    val createdAt: Instant = Instant.now(),
    val provenance: Provenance,
    val validation: ValidationResult = ValidationResult.OK,
)

sealed interface Artifact {
    val meta: ArtifactMeta
    val type: ArtifactType
    fun reference() = ArtifactReference(meta.id, type, meta.schemaVersion)
}

// ---- Requirements -------------------------------------------------------------------------

@Serializable
data class Requirement(val id: String, val title: String, val description: String, val priority: String = "MUST")

@Serializable
data class AcceptanceCriterion(val id: String, val requirementId: String, val description: String)

@Serializable
data class RequirementsContent(
    val title: String,
    val summary: String,
    val functionalRequirements: List<Requirement>,
    val nonFunctionalRequirements: List<Requirement>,
    val acceptanceCriteria: List<AcceptanceCriterion>,
    val assumptions: List<String> = emptyList(),
    val openQuestions: List<String> = emptyList(),
)

data class RequirementsArtifact(override val meta: ArtifactMeta, val content: RequirementsContent) : Artifact {
    override val type get() = ArtifactType.REQUIREMENTS
}

// ---- Architecture -------------------------------------------------------------------------

@Serializable
data class ComponentSpec(val name: String, val kind: String, val responsibility: String)

@Serializable
data class EndpointSpec(
    val method: String,
    val path: String,
    val description: String,
    val requestType: String? = null,
    val responseType: String? = null,
    val successStatus: Int = 200,
)

@Serializable
data class FieldSpec(val name: String, val type: String, val constraints: List<String> = emptyList())

@Serializable
data class TypeSpec(val name: String, val description: String, val fields: List<FieldSpec>)

@Serializable
data class PersistenceSpec(val strategy: String, val description: String)

@Serializable
data class DependencySpec(val groupId: String, val artifactId: String, val scope: String = "compile")

@Serializable
data class DecisionRecord(val title: String, val decision: String, val rationale: String)

@Serializable
data class ArchitectureContent(
    val projectName: String,
    val basePackage: String,
    val components: List<ComponentSpec>,
    val endpoints: List<EndpointSpec>,
    val entities: List<TypeSpec>,
    val dtos: List<TypeSpec>,
    val persistence: PersistenceSpec,
    val dependencies: List<DependencySpec>,
    val decisions: List<DecisionRecord>,
)

data class ArchitectureArtifact(override val meta: ArtifactMeta, val content: ArchitectureContent) : Artifact {
    override val type get() = ArtifactType.ARCHITECTURE
}

// ---- Implementation -----------------------------------------------------------------------

/** A file as proposed by the LLM. Untrusted until validated and written through the workspace tool. */
@Serializable
data class GeneratedFile(val path: String, val content: String, val purpose: String = "")

@Serializable
data class ImplementationDraft(val files: List<GeneratedFile>, val notes: String = "")

data class FileManifestEntry(val path: String, val sha256: String, val bytes: Long, val purpose: String)

data class ImplementationContent(
    val workspaceId: String,
    val files: List<FileManifestEntry>,
    val notes: String,
    val revision: Int,
)

data class ImplementationArtifact(override val meta: ArtifactMeta, val content: ImplementationContent) : Artifact {
    override val type get() = ArtifactType.IMPLEMENTATION
}

// ---- QA -----------------------------------------------------------------------------------

@Serializable
data class AcceptanceCoverage(val criterionId: String, val testReferences: List<String>, val notes: String = "")

@Serializable
data class ReviewFinding(val severity: String, val file: String, val description: String)

@Serializable
data class TestSuiteDraft(
    val testFiles: List<GeneratedFile>,
    val acceptanceCoverage: List<AcceptanceCoverage>,
    val reviewFindings: List<ReviewFinding> = emptyList(),
)

@Serializable
enum class FailureCause { IMPLEMENTATION, TEST, BUILD_CONFIG, UNKNOWN }

@Serializable
data class FailureAnalysis(
    val cause: FailureCause,
    val summary: String,
    val suspectedFiles: List<String> = emptyList(),
    val suggestedFix: String = "",
)

enum class BuildStep { COMPILE, TEST }

enum class Verdict { PASSED, FAILED, NOT_EXECUTED }

data class BuildResult(
    val step: BuildStep,
    val command: List<String>,
    val exitCode: Int?,
    val timedOut: Boolean,
    val durationMs: Long,
    val outputTail: String,
    val startedAt: Instant,
) {
    val success: Boolean get() = !timedOut && exitCode == 0
}

data class TestSummary(val total: Int, val failures: Int, val errors: Int, val skipped: Int) {
    val passed: Int get() = total - failures - errors - skipped
}

data class QualityReportContent(
    val verdict: Verdict,
    val summary: String,
    val testFiles: List<FileManifestEntry>,
    val acceptanceCoverage: List<AcceptanceCoverage>,
    val reviewFindings: List<ReviewFinding>,
    val buildResults: List<BuildResult>,
    val testSummary: TestSummary?,
    val failureAnalyses: List<FailureAnalysis>,
    val correctionAttempts: Int,
    val executionSkippedReason: String? = null,
    val gitCommit: String? = null,
)

data class QualityReportArtifact(override val meta: ArtifactMeta, val content: QualityReportContent) : Artifact {
    override val type get() = ArtifactType.QUALITY_REPORT
}

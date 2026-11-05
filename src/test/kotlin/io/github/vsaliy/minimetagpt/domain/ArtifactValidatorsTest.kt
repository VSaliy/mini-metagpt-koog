package io.github.vsaliy.minimetagpt.domain

import io.github.vsaliy.minimetagpt.infrastructure.llm.TaskApiFakeLlm
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtifactValidatorsTest {
    private val requirements = TaskApiFakeLlm.REQUIREMENTS
    private val architecture = TaskApiFakeLlm.ARCHITECTURE

    @Test
    fun `valid requirements and architecture pass`() {
        assertTrue(ArtifactValidators.requirements(requirements).valid)
        assertTrue(ArtifactValidators.architecture(architecture).valid)
    }

    @Test
    fun `requirements need functional requirements and criteria that reference real requirements`() {
        val bad = requirements.copy(
            functionalRequirements = emptyList(),
            acceptanceCriteria = listOf(AcceptanceCriterion("AC-1", "FR-404", "x")),
        )
        val result = ArtifactValidators.requirements(bad)
        assertFalse(result.valid)
        assertTrue(result.issues.any { it.path == "functionalRequirements" })
        assertTrue(result.issues.any { it.message.contains("unknown requirement") })
    }

    @Test
    fun `duplicate requirement ids are rejected`() {
        val dup = requirements.copy(nonFunctionalRequirements = listOf(requirements.functionalRequirements.first()))
        assertFalse(ArtifactValidators.requirements(dup).valid)
    }

    @Test
    fun `architecture rejects bad names methods and paths`() {
        val bad = architecture.copy(
            projectName = "Bad Name",
            basePackage = "NotAPackage",
            endpoints = listOf(EndpointSpec("FETCH", "no-slash", "x", successStatus = 500)),
        )
        val paths = ArtifactValidators.architecture(bad).issues.map { it.path }
        assertTrue("projectName" in paths && "basePackage" in paths)
        assertTrue(paths.any { it == "endpoints[0].method" } && paths.any { it == "endpoints[0].path" } && paths.any { it == "endpoints[0].successStatus" })
    }

    @Test
    fun `undeclared endpoint types only produce warnings`() {
        val warn = architecture.copy(endpoints = listOf(EndpointSpec("GET", "/x", "x", responseType = "Mystery")))
        val result = ArtifactValidators.architecture(warn)
        assertTrue(result.valid)
        assertEquals(1, result.issues.count { it.severity == Severity.WARNING })
    }

    @ParameterizedTest
    @ValueSource(strings = ["../etc/passwd", "/abs/path", "a/../../b", "C:/win", "a\\b", "", ".git/config", "a//b", "./a"])
    fun `unsafe paths are rejected`(path: String) {
        assertNotNull(ArtifactValidators.pathProblem(path))
    }

    @Test
    fun `safe paths are accepted`() {
        assertNull(ArtifactValidators.pathProblem("src/main/java/com/example/App.java"))
    }

    @Test
    fun `implementation requires pom and sources and forbids tests`() {
        assertFalse(ArtifactValidators.implementation(ImplementationDraft(listOf(GeneratedFile("README.md", "x")))).valid)
        val withTest = ImplementationDraft(listOf(GeneratedFile("pom.xml", "<p/>"), GeneratedFile("src/main/java/A.java", "class A{}"), GeneratedFile("src/test/java/T.java", "class T{}")))
        assertTrue(ArtifactValidators.implementation(withTest).issues.any { it.message.contains("owned by the QA agent") })
    }

    @Test
    fun `implementation corrections may omit pom when project already exists`() {
        val draft = ImplementationDraft(listOf(GeneratedFile("src/main/java/A.java", "class A{}")))
        assertTrue(ArtifactValidators.implementation(draft, existing = setOf("pom.xml")).valid)
    }

    @Test
    fun `implementation rejects traversal duplicates and oversized files`() {
        val big = "x".repeat(ArtifactValidators.MAX_FILE_BYTES + 1)
        val draft = ImplementationDraft(listOf(GeneratedFile("../evil", "x"), GeneratedFile("pom.xml", big), GeneratedFile("pom.xml", "y")))
        val messages = ArtifactValidators.implementation(draft).issues.map { it.message }
        assertTrue(messages.any { it.contains("..") })
        assertTrue(messages.any { it.contains("exceeds") })
        assertTrue(messages.any { it.contains("duplicate") })
    }

    @Test
    fun `test drafts must live under src test java and map known criteria`() {
        val draft = TestSuiteDraft(
            testFiles = listOf(GeneratedFile("src/main/java/T.java", "x")),
            acceptanceCoverage = listOf(AcceptanceCoverage("AC-99", listOf("t"))),
        )
        val result = ArtifactValidators.tests(draft, requirements)
        assertFalse(result.valid)
        assertTrue(result.issues.any { it.message.contains("src/test/java") })
        assertTrue(result.issues.any { it.message.contains("unknown criterion") })
    }
}

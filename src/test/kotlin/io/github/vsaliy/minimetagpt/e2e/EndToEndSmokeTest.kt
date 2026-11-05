package io.github.vsaliy.minimetagpt.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end smoke test: fake LLM generates the Task API, then the REAL Maven compiles and tests the generated
 * project in an isolated workspace. Requires `mvn` on the PATH and access to Maven Central; skipped otherwise.
 */
@Tag("e2e")
@SpringBootTest
@AutoConfigureMockMvc
class EndToEndSmokeTest {
    companion object {
        private val workspaceRoot: Path = Files.createTempDirectory("mmk-e2e-ws")

        @JvmStatic
        @DynamicPropertySource
        fun props(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url") { "jdbc:h2:mem:e2e-${UUID.randomUUID()};DB_CLOSE_DELAY=-1" }
            r.add("minimetagpt.workspace.root") { workspaceRoot.toString() }
            r.add("minimetagpt.execution.enabled") { "true" }
            r.add("minimetagpt.execution.require-approval") { "false" }
        }

        private fun mavenAvailable() = runCatching { ProcessBuilder("mvn", "-v").redirectErrorStream(true).start().waitFor() == 0 }.getOrDefault(false)
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper

    @Test
    fun `task api requirement yields a generated project that really builds and passes its tests`() {
        assumeTrue(mavenAvailable(), "mvn not available")
        val created = mvc.post("/api/v1/workflows") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"requirement":"Create a Spring Boot REST API for managing tasks with CRUD operations and validation."}"""
        }.andReturn().response
        assertEquals(202, created.status)
        val id = mapper.readTree(created.contentAsString)["id"].asText()

        val deadline = System.nanoTime() + 600_000_000_000L
        var status = ""
        while (System.nanoTime() < deadline) {
            status = mapper.readTree(mvc.get("/api/v1/workflows/$id").andReturn().response.contentAsString)["status"].asText()
            if (status in setOf("COMPLETED", "FAILED", "CANCELLED")) break
            Thread.sleep(500)
        }
        val report: JsonNode = mapper.readTree(mvc.get("/api/v1/workflows/$id/report").andReturn().response.contentAsString)
        assertEquals("COMPLETED", status, report.toString())

        val content = report["content"]
        assertEquals("PASSED", content["verdict"].asText())
        assertTrue(content["testSummary"]["total"].asInt() >= 1)
        assertEquals(0, content["testSummary"]["failures"].asInt() + content["testSummary"]["errors"].asInt())
        assertTrue(content["buildResults"].all { it["success"].asBoolean() })
        assertNotNull(content["gitCommit"])

        val ws = workspaceRoot.resolve(id)
        for (f in listOf("pom.xml", "src/main/java/com/example/tasks/TaskController.java", "src/test/java/com/example/tasks/TaskControllerTest.java", ".git/HEAD")) {
            assertTrue(Files.exists(ws.resolve(f)), "missing $f")
        }
        assertTrue(Files.exists(ws.resolve("target/surefire-reports")), "real surefire reports must exist")
    }
}

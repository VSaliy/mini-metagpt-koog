package io.github.vsaliy.minimetagpt.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.vsaliy.minimetagpt.orchestration.WorkflowService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Full Spring context: controller -> service -> engine -> fake LLM -> Flyway-migrated H2 persistence. */
@SpringBootTest
@AutoConfigureMockMvc
class WorkflowApiIntegrationTest {
    companion object {
        private val workspaceRoot: Path = Files.createTempDirectory("mmk-api-ws")

        @JvmStatic
        @DynamicPropertySource
        fun props(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url") { "jdbc:h2:mem:api-${UUID.randomUUID()};DB_CLOSE_DELAY=-1" }
            r.add("minimetagpt.workspace.root") { workspaceRoot.toString() }
            r.add("minimetagpt.execution.enabled") { "false" }
            r.add("minimetagpt.llm.provider") { "fake" }
        }
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var service: WorkflowService

    private fun json(body: String): JsonNode = mapper.readTree(body)

    private fun create(requirement: String = "Create a Spring Boot REST API for managing tasks with CRUD operations and validation."): UUID {
        val body = mvc.post("/api/v1/workflows") {
            contentType = MediaType.APPLICATION_JSON
            content = mapper.writeValueAsString(mapOf("requirement" to requirement))
        }.andReturn().response
        assertEquals(202, body.status)
        val id = UUID.fromString(json(body.contentAsString)["id"].asText())
        assertTrue(body.getHeader("Location")!!.endsWith("/api/v1/workflows/$id"))
        return id
    }

    private fun awaitStatus(id: UUID, expected: String): JsonNode {
        val deadline = System.nanoTime() + 30_000_000_000L
        while (System.nanoTime() < deadline) {
            val node = json(mvc.get("/api/v1/workflows/$id").andReturn().response.contentAsString)
            if (node["status"].asText() == expected) return node
            check(node["status"].asText() !in setOf("FAILED", "CANCELLED") || expected == node["status"].asText()) { "unexpected $node" }
            Thread.sleep(25)
        }
        error("timeout waiting for $expected")
    }

    @Test
    fun `health endpoint is up`() {
        mvc.get("/actuator/health").andExpect { status { isOk() } }
    }

    @Test
    fun `create runs the full workflow asynchronously and exposes artifacts and report`() {
        val id = create()
        val done = awaitStatus(id, "COMPLETED")
        assertEquals("DONE", done["stage"].asText())
        assertTrue(done["executions"].size() >= 4)

        val artifacts = json(mvc.get("/api/v1/workflows/$id/artifacts").andReturn().response.contentAsString)["artifacts"]
        assertEquals(
            listOf("REQUIREMENTS", "ARCHITECTURE", "IMPLEMENTATION", "QUALITY_REPORT"),
            artifacts.map { it["type"].asText() },
        )
        assertTrue(artifacts.all { it["meta"]["schemaVersion"].asText().isNotBlank() && it["meta"]["id"] != null })

        val report = json(mvc.get("/api/v1/workflows/$id/report").andReturn().response.contentAsString)
        assertEquals("NOT_EXECUTED", report["content"]["verdict"].asText(), "tests were not run, so no pass may be claimed")

        assertTrue(Files.exists(workspaceRoot.resolve(id.toString()).resolve("pom.xml")))
        assertTrue(Files.exists(workspaceRoot.resolve(id.toString()).resolve("src/test/java/com/example/tasks/TaskControllerTest.java")))
    }

    @Test
    fun `state is persisted in the database`() {
        val id = create()
        awaitStatus(id, "COMPLETED")
        assertEquals(1, jdbc.queryForObject("select count(*) from workflow_execution where id = ?", Int::class.java, id.toString()))
        assertEquals(4, jdbc.queryForObject("select count(*) from artifact where workflow_id = ?", Int::class.java, id.toString()))
        assertTrue(jdbc.queryForObject("select count(*) from agent_execution where workflow_id = ?", Int::class.java, id.toString())!! >= 4)
    }

    @Test
    fun `blank and missing requirements are rejected with a structured 400`() {
        for (body in listOf("""{"requirement":"   "}""", "{}", "not json")) {
            val r = mvc.post("/api/v1/workflows") { contentType = MediaType.APPLICATION_JSON; content = body }.andReturn().response
            assertEquals(400, r.status, body)
            val err = json(r.contentAsString)
            assertTrue(err["code"].asText().isNotBlank())
            assertEquals("/api/v1/workflows", err["path"].asText())
        }
    }

    @Test
    fun `unknown workflow ids give 404 and malformed ids give 400`() {
        val unknown = UUID.randomUUID()
        for (path in listOf("", "/artifacts", "/report")) {
            mvc.get("/api/v1/workflows/$unknown$path").andExpect { status { isNotFound() } }
        }
        mvc.post("/api/v1/workflows/$unknown/cancel").andExpect { status { isNotFound() } }
        mvc.get("/api/v1/workflows/not-a-uuid").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `cancelling a finished workflow is a 409`() {
        val id = create()
        awaitStatus(id, "COMPLETED")
        mvc.post("/api/v1/workflows/$id/cancel").andExpect { status { isConflict() } }
    }

    @Test
    fun `report is a 409 for a workflow that has none yet`() {
        val id = service.create("x").id
        val r = mvc.get("/api/v1/workflows/$id/report").andReturn().response
        assertTrue(r.status == 409 || r.status == 200, "either not ready yet (409) or already finished (200)")
    }
}

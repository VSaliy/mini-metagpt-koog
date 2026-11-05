package io.github.vsaliy.minimetagpt.infrastructure.llm

import io.github.vsaliy.minimetagpt.agents.Generated
import io.github.vsaliy.minimetagpt.agents.LlmCall
import io.github.vsaliy.minimetagpt.agents.StructuredLlm
import io.github.vsaliy.minimetagpt.domain.AcceptanceCoverage
import io.github.vsaliy.minimetagpt.domain.AcceptanceCriterion
import io.github.vsaliy.minimetagpt.domain.ArchitectureContent
import io.github.vsaliy.minimetagpt.domain.ComponentSpec
import io.github.vsaliy.minimetagpt.domain.DecisionRecord
import io.github.vsaliy.minimetagpt.domain.DependencySpec
import io.github.vsaliy.minimetagpt.domain.EndpointSpec
import io.github.vsaliy.minimetagpt.domain.FailureAnalysis
import io.github.vsaliy.minimetagpt.domain.FailureCause
import io.github.vsaliy.minimetagpt.domain.FieldSpec
import io.github.vsaliy.minimetagpt.domain.GeneratedFile
import io.github.vsaliy.minimetagpt.domain.ImplementationDraft
import io.github.vsaliy.minimetagpt.domain.PersistenceSpec
import io.github.vsaliy.minimetagpt.domain.Requirement
import io.github.vsaliy.minimetagpt.domain.RequirementsContent
import io.github.vsaliy.minimetagpt.domain.ReviewFinding
import io.github.vsaliy.minimetagpt.domain.TestSuiteDraft
import io.github.vsaliy.minimetagpt.domain.TypeSpec

/**
 * Deterministic, offline stand-in for a real model. It always answers with the same "task management REST API"
 * solution whose Java sources live under `fake/task-api/` on the classpath. It never reports token usage.
 * It exists so tests, demos and CI need no paid LLM access; it is selected with `minimetagpt.llm.provider=fake`.
 */
class TaskApiFakeLlm : StructuredLlm {

    override suspend fun requirements(call: LlmCall) = generated(REQUIREMENTS)
    override suspend fun architecture(call: LlmCall) = generated(ARCHITECTURE)

    override suspend fun implementation(call: LlmCall) = generated(
        ImplementationDraft(
            files = MAIN_FILES.map { GeneratedFile(it, resource(it), purposeOf(it)) },
            notes = "Deterministic fake-LLM implementation of the task management API (in-memory repository).",
        ),
    )

    override suspend fun tests(call: LlmCall) = generated(
        TestSuiteDraft(
            testFiles = listOf(GeneratedFile(TEST_FILE, resource(TEST_FILE), "MockMvc tests for the task CRUD API")),
            acceptanceCoverage = listOf(
                AcceptanceCoverage("AC-1", listOf("TaskControllerTest.createReturns201WithLocationAndBody")),
                AcceptanceCoverage("AC-2", listOf("TaskControllerTest.getReturnsCreatedTask", "TaskControllerTest.listContainsCreatedTasks")),
                AcceptanceCoverage("AC-3", listOf("TaskControllerTest.updateChangesFields")),
                AcceptanceCoverage("AC-4", listOf("TaskControllerTest.deleteRemovesTask")),
                AcceptanceCoverage("AC-5", listOf("TaskControllerTest.createWithBlankTitleReturns400", "TaskControllerTest.createWithTooLongTitleReturns400")),
                AcceptanceCoverage("AC-6", listOf("TaskControllerTest.getUnknownReturns404", "TaskControllerTest.deleteUnknownReturns404")),
            ),
            reviewFindings = listOf(
                ReviewFinding("LOW", "src/main/java/com/example/tasks/InMemoryTaskRepository.java", "Data is lost on restart; acceptable for the MVP."),
                ReviewFinding("LOW", "src/main/java/com/example/tasks/TaskController.java", "No authentication or rate limiting; add before exposing publicly."),
            ),
        ),
    )

    override suspend fun failureAnalysis(call: LlmCall) = generated(
        FailureAnalysis(FailureCause.UNKNOWN, "The fake LLM cannot analyse failures.", emptyList(), ""),
    )

    private fun <T> generated(value: T) = Generated(value, "fake-task-api", null)

    private fun purposeOf(path: String) = when {
        path == "pom.xml" -> "Maven build"
        path.endsWith("application.properties") -> "Spring Boot configuration"
        else -> "Application source"
    }

    private fun resource(path: String): String {
        val name = "/fake/task-api/$path.tmpl"
        return TaskApiFakeLlm::class.java.getResourceAsStream(name)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("Missing fake resource $name")
    }

    companion object {
        private const val PKG = "src/main/java/com/example/tasks"
        const val TEST_FILE = "src/test/java/com/example/tasks/TaskControllerTest.java"
        val MAIN_FILES = listOf(
            "pom.xml",
            "src/main/resources/application.properties",
            "$PKG/TaskManagerApplication.java",
            "$PKG/TaskStatus.java",
            "$PKG/Task.java",
            "$PKG/TaskRequest.java",
            "$PKG/TaskResponse.java",
            "$PKG/TaskRepository.java",
            "$PKG/InMemoryTaskRepository.java",
            "$PKG/TaskNotFoundException.java",
            "$PKG/TaskService.java",
            "$PKG/TaskController.java",
            "$PKG/ApiExceptionHandler.java",
        )

        val REQUIREMENTS = RequirementsContent(
            title = "Task Management REST API",
            summary = "A Spring Boot REST API for managing tasks with CRUD operations and input validation.",
            functionalRequirements = listOf(
                Requirement("FR-1", "Create task", "Clients can create a task with a title, optional description and status."),
                Requirement("FR-2", "Read tasks", "Clients can fetch a single task by id and list all tasks."),
                Requirement("FR-3", "Update task", "Clients can update the title, description and status of an existing task."),
                Requirement("FR-4", "Delete task", "Clients can delete an existing task."),
                Requirement("FR-5", "Validate input", "Invalid task payloads are rejected with HTTP 400."),
            ),
            nonFunctionalRequirements = listOf(
                Requirement("NFR-1", "Java 21 / Spring Boot", "Implemented with Java 21 and Spring Boot, built with Maven.", "MUST"),
                Requirement("NFR-2", "Testability", "All endpoints are covered by automated JUnit 5 tests.", "SHOULD"),
            ),
            acceptanceCriteria = listOf(
                AcceptanceCriterion("AC-1", "FR-1", "POST /api/tasks with a valid body returns 201, a Location header and the created task."),
                AcceptanceCriterion("AC-2", "FR-2", "GET /api/tasks/{id} returns the task and GET /api/tasks lists created tasks."),
                AcceptanceCriterion("AC-3", "FR-3", "PUT /api/tasks/{id} updates the task and returns it."),
                AcceptanceCriterion("AC-4", "FR-4", "DELETE /api/tasks/{id} returns 204 and the task is no longer retrievable."),
                AcceptanceCriterion("AC-5", "FR-5", "A blank or longer than 120 characters title is rejected with 400."),
                AcceptanceCriterion("AC-6", "FR-2", "Unknown ids return 404 for get and delete."),
            ),
            assumptions = listOf("Single-user service without authentication.", "Tasks are stored in memory."),
            openQuestions = listOf("Is persistence to a database required?", "Are due dates or assignees needed?"),
        )

        val ARCHITECTURE = ArchitectureContent(
            projectName = "task-manager-api",
            basePackage = "com.example.tasks",
            components = listOf(
                ComponentSpec("TaskController", "REST controller", "HTTP mapping, request validation"),
                ComponentSpec("TaskService", "service", "Business logic for task CRUD"),
                ComponentSpec("TaskRepository", "repository", "Storage abstraction with an in-memory implementation"),
                ComponentSpec("ApiExceptionHandler", "advice", "Maps TaskNotFoundException to 404"),
            ),
            endpoints = listOf(
                EndpointSpec("POST", "/api/tasks", "Create a task", "TaskRequest", "TaskResponse", 201),
                EndpointSpec("GET", "/api/tasks", "List tasks", null, "List<TaskResponse>", 200),
                EndpointSpec("GET", "/api/tasks/{id}", "Get a task", null, "TaskResponse", 200),
                EndpointSpec("PUT", "/api/tasks/{id}", "Update a task", "TaskRequest", "TaskResponse", 200),
                EndpointSpec("DELETE", "/api/tasks/{id}", "Delete a task", null, null, 204),
            ),
            entities = listOf(
                TypeSpec(
                    "Task", "A unit of work",
                    listOf(
                        FieldSpec("id", "Long"), FieldSpec("title", "String", listOf("NotBlank", "Size(max=120)")),
                        FieldSpec("description", "String", listOf("Size(max=1000)")), FieldSpec("status", "TaskStatus"),
                        FieldSpec("createdAt", "Instant"),
                    ),
                ),
            ),
            dtos = listOf(
                TypeSpec("TaskRequest", "Create/update payload", listOf(FieldSpec("title", "String", listOf("NotBlank", "Size(max=120)")), FieldSpec("description", "String"), FieldSpec("status", "TaskStatus"))),
                TypeSpec("TaskResponse", "Task representation", listOf(FieldSpec("id", "Long"), FieldSpec("title", "String"), FieldSpec("description", "String"), FieldSpec("status", "TaskStatus"), FieldSpec("createdAt", "Instant"))),
            ),
            persistence = PersistenceSpec("in-memory", "ConcurrentHashMap behind a TaskRepository interface so a JPA implementation can replace it."),
            dependencies = listOf(
                DependencySpec("org.springframework.boot", "spring-boot-starter-web"),
                DependencySpec("org.springframework.boot", "spring-boot-starter-validation"),
                DependencySpec("org.springframework.boot", "spring-boot-starter-test", "test"),
            ),
            decisions = listOf(
                DecisionRecord("In-memory persistence", "Use a ConcurrentHashMap repository for the MVP.", "Keeps the generated project dependency-light and fast to build; the repository interface allows a later swap to JPA."),
            ),
        )
    }
}

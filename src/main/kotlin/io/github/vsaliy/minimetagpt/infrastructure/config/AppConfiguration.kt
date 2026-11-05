package io.github.vsaliy.minimetagpt.infrastructure.config

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.vsaliy.minimetagpt.agents.ArchitectAgent
import io.github.vsaliy.minimetagpt.agents.DeveloperAgent
import io.github.vsaliy.minimetagpt.agents.QaAgent
import io.github.vsaliy.minimetagpt.agents.RequirementsAgent
import io.github.vsaliy.minimetagpt.agents.StructuredGenerator
import io.github.vsaliy.minimetagpt.agents.StructuredLlm
import io.github.vsaliy.minimetagpt.infrastructure.llm.LlmFactory
import io.github.vsaliy.minimetagpt.infrastructure.observability.StageObserver
import io.github.vsaliy.minimetagpt.infrastructure.persistence.JdbcWorkflowRepository
import io.github.vsaliy.minimetagpt.infrastructure.tools.BuildTool
import io.github.vsaliy.minimetagpt.infrastructure.tools.GitTool
import io.github.vsaliy.minimetagpt.infrastructure.tools.MavenTool
import io.github.vsaliy.minimetagpt.infrastructure.tools.ProcessRunner
import io.github.vsaliy.minimetagpt.infrastructure.tools.WorkspaceManager
import io.github.vsaliy.minimetagpt.orchestration.ApprovalGate
import io.github.vsaliy.minimetagpt.orchestration.WorkflowEngine
import io.github.vsaliy.minimetagpt.orchestration.WorkflowRepository
import io.github.vsaliy.minimetagpt.orchestration.WorkflowService
import io.github.vsaliy.minimetagpt.orchestration.WorkflowStateStore
import io.micrometer.tracing.Tracer
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate

@Configuration
@EnableConfigurationProperties(MiniMetagptProperties::class)
class AppConfiguration {

    @Bean
    fun structuredLlm(props: MiniMetagptProperties): StructuredLlm = LlmFactory.create(props.llm)

    @Bean
    fun structuredGenerator(props: MiniMetagptProperties) = StructuredGenerator(props.llm.maxOutputRetries)

    @Bean
    fun workspaceManager(props: MiniMetagptProperties) = WorkspaceManager(props.workspace.root)

    @Bean
    fun processRunner(props: MiniMetagptProperties) = ProcessRunner(props.execution.maxOutputBytes)

    @Bean
    fun buildTool(runner: ProcessRunner, props: MiniMetagptProperties): BuildTool = MavenTool(runner, props.execution)

    @Bean
    fun gitTool(runner: ProcessRunner, props: MiniMetagptProperties) =
        GitTool(runner, props.execution.gitCommand, props.execution.gitTimeout)

    @Bean
    fun requirementsAgent(llm: StructuredLlm, generator: StructuredGenerator) = RequirementsAgent(llm, generator)

    @Bean
    fun architectAgent(llm: StructuredLlm, generator: StructuredGenerator) = ArchitectAgent(llm, generator)

    @Bean
    fun developerAgent(llm: StructuredLlm, generator: StructuredGenerator) = DeveloperAgent(llm, generator)

    @Bean
    fun qaAgent(llm: StructuredLlm, generator: StructuredGenerator, maven: BuildTool) = QaAgent(llm, generator, maven)

    @Bean
    fun workflowRepository(jdbc: JdbcTemplate, json: ObjectMapper): WorkflowRepository = JdbcWorkflowRepository(jdbc, json)

    @Bean
    fun workflowStateStore(repository: WorkflowRepository) = WorkflowStateStore(repository)

    @Bean
    fun approvalGate() = ApprovalGate()

    @Bean
    fun stageObserver(tracer: ObjectProvider<Tracer>) = StageObserver(tracer.ifAvailable)

    @Bean
    fun workflowEngine(
        requirements: RequirementsAgent,
        architect: ArchitectAgent,
        developer: DeveloperAgent,
        qa: QaAgent,
        repository: WorkflowRepository,
        state: WorkflowStateStore,
        workspaces: WorkspaceManager,
        git: GitTool,
        approvals: ApprovalGate,
        observer: StageObserver,
        props: MiniMetagptProperties,
    ) = WorkflowEngine(requirements, architect, developer, qa, repository, state, workspaces, git, approvals, observer, props.execution)

    @Bean
    fun workflowService(
        repository: WorkflowRepository,
        state: WorkflowStateStore,
        engine: WorkflowEngine,
        approvals: ApprovalGate,
        props: MiniMetagptProperties,
    ) = WorkflowService(repository, state, engine, approvals, props.workflow.maxCorrectionAttempts)
}

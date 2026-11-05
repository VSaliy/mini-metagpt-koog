package io.github.vsaliy.minimetagpt.infrastructure.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.NestedConfigurationProperty
import java.nio.file.Path
import java.time.Duration

@ConfigurationProperties("minimetagpt")
data class MiniMetagptProperties(
    @NestedConfigurationProperty val llm: Llm = Llm(),
    @NestedConfigurationProperty val workflow: Workflow = Workflow(),
    @NestedConfigurationProperty val workspace: Workspace = Workspace(),
    @NestedConfigurationProperty val execution: Execution = Execution(),
) {
    enum class Provider { FAKE, OPENAI, OLLAMA }

    data class Llm(
        val provider: Provider = Provider.FAKE,
        val model: String = "",
        val baseUrl: String = "",
        val apiKey: String = "",
        val temperature: Double = 0.2,
        /** Extra attempts when a model response cannot be parsed or fails contract validation. */
        val maxOutputRetries: Int = 1,
        val maxIterations: Int = 10,
    ) {
        override fun toString() = "Llm(provider=$provider, model=$model, baseUrl=$baseUrl, apiKey=***)"
    }

    data class Workflow(
        val maxCorrectionAttempts: Int = 2,
        val maxRequestLength: Int = 8000,
    )

    data class Workspace(val root: Path = Path.of("workspaces"))

    data class Execution(
        /** When false, generated projects are written but never compiled or tested. */
        val enabled: Boolean = true,
        /** Generated code is untrusted; require a human to approve before it is executed. */
        val requireApproval: Boolean = true,
        val mavenCommand: String = "mvn",
        val gitCommand: String = "git",
        val compileTimeout: Duration = Duration.ofMinutes(5),
        val testTimeout: Duration = Duration.ofMinutes(10),
        val gitTimeout: Duration = Duration.ofSeconds(30),
        val maxOutputBytes: Int = 200_000,
        val mavenOpts: String = "-Xmx512m",
    )
}

package io.github.vsaliy.minimetagpt.infrastructure.tools

import io.github.vsaliy.minimetagpt.domain.BuildResult
import io.github.vsaliy.minimetagpt.domain.BuildStep
import io.github.vsaliy.minimetagpt.domain.TestSummary
import io.github.vsaliy.minimetagpt.infrastructure.config.MiniMetagptProperties
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Deterministic build/test execution for a workspace. Callers choose a [BuildStep], never raw arguments. */
interface BuildTool {
    suspend fun run(workspace: Workspace, step: BuildStep): BuildResult

    /** Aggregated test results of the last run, or null when no test report exists. */
    fun testSummary(workspace: Workspace): TestSummary?
}

/** Runs a fixed, allowlisted set of Maven invocations. */
class MavenTool(
    private val runner: ProcessRunner,
    private val properties: MiniMetagptProperties.Execution,
) : BuildTool {
    override suspend fun run(workspace: Workspace, step: BuildStep): BuildResult {
        if (!workspace.exists("pom.xml")) throw ToolRejectedException("Workspace has no pom.xml")
        if (step == BuildStep.TEST) workspace.deleteGenerated("target/surefire-reports")
        val args = ARGS.getValue(step)
        val result = runner.run(
            CommandSpec(
                executable = properties.mavenCommand,
                args = args,
                workingDir = workspace.root,
                timeout = if (step == BuildStep.COMPILE) properties.compileTimeout else properties.testTimeout,
                extraEnv = mapOf("MAVEN_OPTS" to properties.mavenOpts),
            ),
        )
        return BuildResult(
            step = step,
            command = listOf(properties.mavenCommand) + args,
            exitCode = result.exitCode,
            timedOut = result.timedOut,
            durationMs = result.durationMs,
            outputTail = result.output,
            startedAt = result.startedAt,
        )
    }

    override fun testSummary(workspace: Workspace): TestSummary? {
        val reports = listSurefire(workspace).filter {
            it.startsWith("target/surefire-reports/TEST-") && it.endsWith(".xml")
        }
        if (reports.isEmpty()) return null
        var total = 0; var failures = 0; var errors = 0; var skipped = 0
        for (report in reports) {
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                isXIncludeAware = false
                isExpandEntityReferences = false
            }
            val root = factory.newDocumentBuilder().parse(workspace.resolve(report).toFile()).documentElement
            fun attr(name: String) = root.getAttribute(name).toIntOrNull() ?: 0
            total += attr("tests"); failures += attr("failures"); errors += attr("errors"); skipped += attr("skipped")
        }
        return TestSummary(total, failures, errors, skipped)
    }

    private fun listSurefire(workspace: Workspace): List<String> {
        val dir = workspace.root.resolve("target/surefire-reports")
        if (!java.nio.file.Files.isDirectory(dir)) return emptyList()
        return java.nio.file.Files.list(dir).use { s -> s.map { "target/surefire-reports/" + it.fileName }.toList() }
    }

    companion object {
        private val ARGS = mapOf(
            BuildStep.COMPILE to listOf("-B", "-ntp", "-Dstyle.color=never", "test-compile"),
            BuildStep.TEST to listOf("-B", "-ntp", "-Dstyle.color=never", "test"),
        )
    }
}

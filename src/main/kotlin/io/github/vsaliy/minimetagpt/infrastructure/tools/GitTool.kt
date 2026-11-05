package io.github.vsaliy.minimetagpt.infrastructure.tools

import java.time.Duration

/**
 * Local-only git operations on a workspace. Only `init`, `add` and `commit` are exposed; there is deliberately
 * no remote, push or pull support: publishing code requires explicit human authorization outside this tool.
 */
class GitTool(
    private val runner: ProcessRunner,
    private val gitCommand: String,
    private val timeout: Duration,
) {
    suspend fun commitAll(workspace: Workspace, message: String): String? {
        require(message.isNotBlank() && message.length <= 200 && '\u0000' !in message) { "Invalid commit message" }
        if (!workspace.exists(".gitignore")) workspace.write(".gitignore", "target/\n")
        if (!java.nio.file.Files.exists(workspace.root.resolve(".git"))) git(workspace, "init", "-q")
        git(workspace, "add", "-A")
        val status = git(workspace, "status", "--porcelain")
        if (status.output.isBlank()) return head(workspace)
        git(workspace, *IDENTITY, "commit", "-q", "--no-verify", "-m", message)
        return head(workspace)
    }

    private suspend fun head(workspace: Workspace): String? =
        runner.run(spec(workspace, listOf("rev-parse", "HEAD"))).takeIf { it.exitCode == 0 }?.output?.trim()

    private suspend fun git(workspace: Workspace, vararg args: String): ProcessResult {
        val sub = args.first { !it.startsWith("-") && it !in IDENTITY }
        if (sub !in ALLOWED) throw ToolRejectedException("git subcommand not allowed: $sub")
        val result = runner.run(spec(workspace, args.toList()))
        if (result.exitCode != 0) throw ToolExecutionException("git $sub failed (exit ${result.exitCode}): ${result.output.take(500)}")
        return result
    }

    private fun spec(workspace: Workspace, args: List<String>) = CommandSpec(
        executable = gitCommand,
        args = args,
        workingDir = workspace.root,
        timeout = timeout,
        extraEnv = mapOf("GIT_CONFIG_GLOBAL" to "/dev/null", "GIT_CONFIG_SYSTEM" to "/dev/null", "GIT_TERMINAL_PROMPT" to "0"),
    )

    companion object {
        private val ALLOWED = setOf("init", "add", "status", "commit", "rev-parse")
        private val IDENTITY = arrayOf(
            "-c", "user.name=mini-metagpt", "-c", "user.email=mini-metagpt@localhost",
            "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null",
        )
    }
}

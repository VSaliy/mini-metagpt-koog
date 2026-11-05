package io.github.vsaliy.minimetagpt.infrastructure

import io.github.vsaliy.minimetagpt.infrastructure.tools.GitTool
import io.github.vsaliy.minimetagpt.infrastructure.tools.ProcessRunner
import io.github.vsaliy.minimetagpt.infrastructure.tools.WorkspaceManager
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GitToolTest {
    @TempDir
    lateinit var root: Path

    private fun gitAvailable() = runCatching { ProcessBuilder("git", "--version").start().waitFor() == 0 }.getOrDefault(false)

    @Test
    fun `commits workspace content locally and ignores target`() = runBlocking {
        assumeTrue(gitAvailable())
        val ws = WorkspaceManager(root).create(UUID.randomUUID())
        val git = GitTool(ProcessRunner(10_000), "git", Duration.ofSeconds(30))
        ws.write("pom.xml", "<project/>")
        val first = git.commitAll(ws, "first")
        assertNotNull(first)
        assertTrue(ws.read(".gitignore").contains("target/"))
        assertEquals(first, git.commitAll(ws, "no changes"), "no new commit when nothing changed")
        ws.write("A.java", "class A {}")
        assertNotEquals(first, git.commitAll(ws, "second"))
    }
}

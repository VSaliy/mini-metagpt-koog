package io.github.vsaliy.minimetagpt.infrastructure

import io.github.vsaliy.minimetagpt.infrastructure.tools.ToolRejectedException
import io.github.vsaliy.minimetagpt.infrastructure.tools.WorkspaceManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkspaceTest {
    @TempDir
    lateinit var root: Path

    private fun workspace() = WorkspaceManager(root).create(UUID.randomUUID())

    @Test
    fun `writes and reads files and produces a manifest`() {
        val ws = workspace()
        val entry = ws.write("src/main/java/A.java", "class A {}", "source")
        assertEquals("class A {}", ws.read("src/main/java/A.java"))
        assertEquals(10, entry.bytes)
        assertEquals(64, entry.sha256.length)
        assertEquals(listOf("src/main/java/A.java"), ws.list())
    }

    @Test
    fun `rejects traversal absolute paths and git internals`() {
        val ws = workspace()
        for (bad in listOf("../outside.txt", "a/../../outside.txt", "/tmp/x", ".git/hooks/pre-commit", "a\\b")) {
            assertThrows<ToolRejectedException>(bad) { ws.write(bad, "x") }
        }
        assertTrue(Files.list(root).use { it.count() } == 1L, "nothing may be written outside the workspace")
    }

    @Test
    fun `rejects writes through symbolic links`() {
        val ws = workspace()
        val outside = Files.createDirectory(root.resolve("outside"))
        Files.createSymbolicLink(ws.root.resolve("link"), outside)
        assertThrows<ToolRejectedException> { ws.write("link/evil.txt", "x") }
        assertTrue(Files.list(outside).use { it.count() } == 0L)
    }

    @Test
    fun `only build output and generated tests may be deleted`() {
        val ws = workspace()
        ws.write("target/surefire-reports/a.xml", "x")
        ws.write("src/test/java/T.java", "x")
        ws.write("pom.xml", "x")
        ws.deleteGenerated("target/surefire-reports")
        ws.deleteGenerated("src/test")
        assertEquals(listOf("pom.xml"), ws.list())
        assertThrows<ToolRejectedException> { ws.deleteGenerated("pom.xml") }
        assertThrows<ToolRejectedException> { ws.deleteGenerated("src/main") }
    }

    @Test
    fun `workspaces are isolated per workflow`() {
        val manager = WorkspaceManager(root)
        val a = manager.create(UUID.randomUUID())
        val b = manager.create(UUID.randomUUID())
        a.write("f.txt", "a")
        assertTrue(b.list().isEmpty())
    }
}

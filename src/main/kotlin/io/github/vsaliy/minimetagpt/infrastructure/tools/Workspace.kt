package io.github.vsaliy.minimetagpt.infrastructure.tools

import io.github.vsaliy.minimetagpt.domain.ArtifactValidators
import io.github.vsaliy.minimetagpt.domain.FileManifestEntry
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.relativeTo

/** Allocates one isolated directory per workflow. */
class WorkspaceManager(rootDir: Path) {
    private val root: Path = rootDir.toAbsolutePath().normalize().also { Files.createDirectories(it) }.toRealPath()

    fun create(workflowId: UUID): Workspace {
        val dir = root.resolve(workflowId.toString())
        Files.createDirectories(dir)
        return Workspace(workflowId.toString(), dir.toRealPath())
    }

    fun open(workflowId: UUID): Workspace? {
        val dir = root.resolve(workflowId.toString())
        return if (dir.isDirectory()) Workspace(workflowId.toString(), dir.toRealPath()) else null
    }
}

/**
 * All filesystem access for generated code goes through this class. Paths are validated, normalized, kept
 * inside [root] and may not traverse symbolic links.
 */
class Workspace(val id: String, val root: Path) {

    fun resolve(relativePath: String): Path {
        ArtifactValidators.pathProblem(relativePath)?.let { throw ToolRejectedException("Rejected path '$relativePath': $it") }
        val target = root.resolve(relativePath).normalize()
        if (!target.startsWith(root)) throw ToolRejectedException("Path escapes workspace: $relativePath")
        var current = root
        for (segment in target.relativeTo(root)) {
            current = current.resolve(segment.toString())
            if (Files.isSymbolicLink(current)) throw ToolRejectedException("Symbolic links are not allowed: $relativePath")
        }
        return target
    }

    fun write(relativePath: String, content: String, purpose: String = ""): FileManifestEntry {
        val target = resolve(relativePath)
        if (target.isDirectory()) throw ToolRejectedException("Path is a directory: $relativePath")
        Files.createDirectories(target.parent)
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        Files.write(target, bytes)
        return FileManifestEntry(relativePath, sha256(bytes), bytes.size.toLong(), purpose)
    }

    fun read(relativePath: String): String {
        val target = resolve(relativePath)
        if (!target.exists()) throw ToolRejectedException("No such file: $relativePath")
        return Files.readString(target)
    }

    fun exists(relativePath: String): Boolean = resolve(relativePath).exists()

    /** Relative paths of all regular files, excluding build output and git internals. */
    fun list(): List<String> {
        if (!root.exists()) return emptyList()
        Files.walk(root).use { stream ->
            return stream
                .filter { Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) }
                .map { it.relativeTo(root).joinToString("/") }
                .filter { !it.startsWith("target/") && !it.startsWith(".git/") }
                .sorted()
                .toList()
        }
    }

    fun manifest(paths: Collection<String>, purposes: Map<String, String> = emptyMap()): List<FileManifestEntry> =
        paths.sorted().map {
            val bytes = Files.readAllBytes(resolve(it))
            FileManifestEntry(it, sha256(bytes), bytes.size.toLong(), purposes[it].orEmpty())
        }

    /** Removes generated directories inside the workspace. Only `target/...` and `src/test/...` are permitted. */
    fun deleteGenerated(relativePath: String) {
        if (DELETABLE_ROOTS.none { relativePath == it || relativePath.startsWith("$it/") }) {
            throw ToolRejectedException("Only build output (target/) and generated tests (src/test/) may be deleted")
        }
        val dir = resolve(relativePath)
        if (dir.exists()) Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        val DELETABLE_ROOTS = listOf("target", "src/test")
    }
}

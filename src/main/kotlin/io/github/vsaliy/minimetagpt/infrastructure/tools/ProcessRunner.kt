package io.github.vsaliy.minimetagpt.infrastructure.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

data class CommandSpec(
    val executable: String,
    val args: List<String>,
    val workingDir: Path,
    val timeout: Duration,
    val extraEnv: Map<String, String> = emptyMap(),
)

data class ProcessResult(
    val exitCode: Int?,
    val timedOut: Boolean,
    val durationMs: Long,
    val output: String,
    val startedAt: Instant,
)

/**
 * Runs an already-validated command without a shell, with a scrubbed environment (no API keys),
 * a hard timeout, bounded captured output and process-tree termination on timeout or cancellation.
 */
class ProcessRunner(private val maxOutputBytes: Int) {

    suspend fun run(spec: CommandSpec): ProcessResult = withContext(Dispatchers.IO) {
        val started = Instant.now()
        val builder = ProcessBuilder(listOf(spec.executable) + spec.args)
            .directory(spec.workingDir.toFile())
            .redirectErrorStream(true)
        builder.environment().apply {
            clear()
            ALLOWED_ENV.forEach { name -> System.getenv(name)?.let { put(name, it) } }
            putAll(spec.extraEnv)
        }
        val process = try {
            builder.start()
        } catch (e: java.io.IOException) {
            throw ToolExecutionException("Cannot start '${spec.executable}': ${e.message}", e)
        }
        val tail = BoundedTail(maxOutputBytes)
        val reader = Thread {
            process.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    tail.append(buffer, n)
                }
            }
        }.apply { isDaemon = true; start() }

        val deadline = System.nanoTime() + spec.timeout.toNanos()
        var timedOut = false
        try {
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                currentCoroutineContext().ensureActive()
                if (System.nanoTime() > deadline) {
                    timedOut = true
                    break
                }
            }
        } finally {
            if (process.isAlive) kill(process)
        }
        reader.join(2000)
        ProcessResult(
            exitCode = if (timedOut) null else process.exitValue(),
            timedOut = timedOut,
            durationMs = Duration.between(started, Instant.now()).toMillis(),
            output = tail.text(),
            startedAt = started,
        )
    }

    private fun kill(process: Process) {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
        process.waitFor(5, TimeUnit.SECONDS)
    }

    private class BoundedTail(private val limit: Int) {
        private var data = ByteArray(0)

        @Synchronized
        fun append(buffer: ByteArray, length: Int) {
            val combined = data + buffer.copyOf(length)
            data = if (combined.size > limit) combined.copyOfRange(combined.size - limit, combined.size) else combined
        }

        @Synchronized
        fun text() = String(data, Charsets.UTF_8)
    }

    companion object {
        private val ALLOWED_ENV = listOf("PATH", "JAVA_HOME", "HOME", "LANG", "LC_ALL", "TMPDIR", "USER", "M2_HOME")
    }
}

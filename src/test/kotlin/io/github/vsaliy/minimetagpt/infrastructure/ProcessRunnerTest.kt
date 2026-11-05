package io.github.vsaliy.minimetagpt.infrastructure

import io.github.vsaliy.minimetagpt.infrastructure.tools.CommandSpec
import io.github.vsaliy.minimetagpt.infrastructure.tools.ProcessRunner
import io.github.vsaliy.minimetagpt.infrastructure.tools.ToolExecutionException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProcessRunnerTest {
    @TempDir
    lateinit var dir: Path

    private fun spec(vararg args: String, timeout: Duration = Duration.ofSeconds(10), env: Map<String, String> = emptyMap()) =
        CommandSpec("sh", listOf("-c", *args), dir, timeout, env)

    @Test
    fun `captures output and exit code`() = runBlocking {
        val r = ProcessRunner(10_000).run(spec("echo hello; exit 3"))
        assertEquals(3, r.exitCode)
        assertFalse(r.timedOut)
        assertTrue(r.output.contains("hello"))
    }

    @Test
    fun `kills processes that exceed the timeout`() = runBlocking {
        val started = System.nanoTime()
        val r = ProcessRunner(10_000).run(spec("sleep 30", timeout = Duration.ofMillis(500)))
        assertTrue(r.timedOut)
        assertEquals(null, r.exitCode)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 10_000)
    }

    @Test
    fun `does not leak secrets from the parent environment`() = runBlocking {
        val r = ProcessRunner(10_000).run(spec("echo \"key=[\$OPENAI_API_KEY] path=[\$PATH] extra=[\$EXTRA]\"", env = mapOf("EXTRA" to "yes")))
        assertTrue(r.output.contains("key=[]"))
        assertTrue(r.output.contains("extra=[yes]"))
    }

    @Test
    fun `output is bounded to the tail`() = runBlocking {
        val r = ProcessRunner(100).run(spec("i=0; while [ \$i -lt 2000 ]; do echo line-\$i; i=\$((i+1)); done"))
        assertTrue(r.output.length <= 100)
        assertTrue(r.output.contains("line-1999"))
    }

    @Test
    fun `missing executable is a tool failure`() {
        assertThrows<ToolExecutionException> {
            runBlocking { ProcessRunner(100).run(CommandSpec("definitely-not-a-binary", emptyList(), dir, Duration.ofSeconds(1))) }
        }
    }

    @Test
    fun `cancelling the coroutine terminates the process`() = runBlocking {
        val marker = dir.resolve("marker")
        val job = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            ProcessRunner(100).run(spec("sleep 3; touch ${marker}", timeout = Duration.ofSeconds(30)))
        }
        delay(300)
        job.cancel()
        delay(4000)
        assertFalse(java.nio.file.Files.exists(marker))
    }
}

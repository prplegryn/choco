package app.chocola

import app.chocola.agent.ProcessRunner
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

class ProcessRunnerTest {
    private class QuietProcess(private val forever: Boolean, output: String = "", private val code: Int = 0) : Process() {
        private val alive = AtomicBoolean(forever)
        private val input = ByteArrayInputStream(output.toByteArray(Charsets.UTF_8))
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun isAlive() = alive.get()
        // A regression to blocking waitFor must fail this test, not hang the test worker.
        override fun waitFor(): Int = error("waitFor must not be used")
        override fun exitValue(): Int { if (alive.get()) throw IllegalThreadStateException(); return code }
        override fun destroy() { alive.set(false) }
        override fun destroyForcibly(): Process { alive.set(false); return this }
    }
    @Test fun quietCommandTimesOutWithoutCancellingTheParentTask() = runBlocking {
        val process = QuietProcess(true)
        val result = withTimeout(3000) { ProcessRunner.collect(process, 1) {} }
        assertEquals(124, result.exitCode)
        assertTrue(result.timedOut)
        assertFalse(process.isAlive)
        assertTrue(currentCoroutineContext().isActive)
    }
    @Test fun userCancellationTerminatesAnIdleBackgroundProcess() = runBlocking {
        val process = QuietProcess(true)
        val task = launch { ProcessRunner.collect(process, null) {} }
        delay(80)
        withTimeout(1500) { task.cancelAndJoin() }
        assertFalse(process.isAlive)
    }
    @Test fun completedProcessDoesNotWaitForDescendantPipeClosure() = runBlocking {
        val result = withTimeout(1000) { ProcessRunner.collect(QuietProcess(false, "中文输出\n", 7), 10) {} }
        assertEquals("中文输出\n", result.output)
        assertEquals(7, result.exitCode)
        assertFalse(result.timedOut)
    }
    @Test fun outputKeepsRecentDataWithinItsBound() = runBlocking {
        val result = ProcessRunner.collect(QuietProcess(false, "a".repeat(280_000) + "LATEST"), 10) {}
        assertTrue(result.output.length <= 200_000)
        assertTrue(result.output.endsWith("LATEST"))
    }
}

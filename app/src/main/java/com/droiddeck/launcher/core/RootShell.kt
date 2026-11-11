package com.droiddeck.launcher.core

import android.util.Log
import java.io.IOException
import java.util.concurrent.TimeUnit

object RootShell {
    private const val TAG = "RootShell"

    class Result(val exit: Int, val output: String) {
        val ok: Boolean get() = exit == 0
        val lastLine: String get() = output.lines().lastOrNull { it.isNotBlank() }?.trim().orEmpty()
    }

    @Volatile
    private var granted: Boolean? = null

    /** True only once su has already answered as uid 0 in this process; never spawns su. */
    fun known(): Boolean = granted == true

    /** May block while the root manager asks the user, so never call it on the main thread. */
    fun available(): Boolean {
        granted?.let { return it }
        synchronized(this) {
            granted?.let { return it }
            val result = run(listOf("id -u"), timeoutSeconds = 30, probe = true)
            val ok = result.ok && result.lastLine == "0"
            granted = ok
            Log.i(TAG, if (ok) "root granted" else "no root (exit ${result.exit})")
            return ok
        }
    }

    fun run(commands: List<String>, timeoutSeconds: Long = 15): Result = run(commands, timeoutSeconds, probe = false)

    private var shell: Process? = null
    private val shellReader = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "root-shell-query").apply { isDaemon = true }
    }

    /**
     * Runs a short read-only command in one long-lived root shell and returns its output, or null when root is
     * missing or the shell does not answer. Periodic sensor reads go through here: starting a new su for each
     * read costs a process and a root-manager round trip every few seconds for the whole session.
     */
    @Synchronized
    fun query(command: String, timeoutMs: Long = 3000): String? {
        if (granted != true) return null
        return try {
            val process = shell?.takeIf { it.isAlive } ?: ProcessBuilder("su").redirectErrorStream(true).start().also { shell = it }
            val marker = "__dd${System.nanoTime()}__"
            process.outputStream.write("$command\necho $marker\n".toByteArray())
            process.outputStream.flush()
            val input = process.inputStream
            val answer = shellReader.submit(java.util.concurrent.Callable {
                val text = StringBuilder()
                val buffer = ByteArray(4096)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) return@Callable null
                    text.append(String(buffer, 0, n))
                    val end = text.indexOf(marker)
                    if (end >= 0) return@Callable text.substring(0, end)
                }
                @Suppress("UNREACHABLE_CODE") null
            })
            answer.get(timeoutMs, TimeUnit.MILLISECONDS) ?: run { closeShell(); null }
        } catch (e: Exception) {
            closeShell()
            null
        }
    }

    private fun closeShell() {
        runCatching { shell?.destroyForcibly() }
        shell = null
    }

    private fun run(commands: List<String>, timeoutSeconds: Long, probe: Boolean): Result {
        if (!probe && granted == false) return Result(-1, "")
        val process = try {
            ProcessBuilder("su").redirectErrorStream(true).start()
        } catch (e: IOException) {
            return Result(-1, "")
        }
        val output = StringBuilder()
        val reader = Thread({
            try {
                process.inputStream.bufferedReader().forEachLine { synchronized(output) { output.append(it).append('\n') } }
            } catch (_: IOException) {
            }
        }, "root-shell-out").apply { isDaemon = true; start() }
        try {
            process.outputStream.bufferedWriter().use { w ->
                commands.forEach { w.write(it); w.write("\n") }
                w.write("exit\n")
            }
        } catch (_: IOException) {
        }
        val finished = try { process.waitFor(timeoutSeconds, TimeUnit.SECONDS) } catch (_: InterruptedException) { false }
        if (!finished) {
            process.destroyForcibly()
            Log.w(TAG, "su timed out after ${timeoutSeconds}s")
            return Result(-1, synchronized(output) { output.toString() })
        }
        reader.join(1000)
        return Result(process.exitValue(), synchronized(output) { output.toString() })
    }
}

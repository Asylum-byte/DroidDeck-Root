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

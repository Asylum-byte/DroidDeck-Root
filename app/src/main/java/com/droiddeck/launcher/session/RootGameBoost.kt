package com.droiddeck.launcher.session

import android.content.Context
import android.os.Process
import android.system.Os
import android.util.Log
import com.droiddeck.launcher.core.RootShell
import java.io.File

/**
 * Moves the session's Linux processes into their own cpu cgroup with a uclamp floor, so the scheduler
 * keeps them on fast cores and ramps clocks early without pinning the whole phone at full speed.
 */
object RootGameBoost {
    private const val TAG = "RootGameBoost"
    val LEVELS = listOf(0, 20, 40, 60)

    private const val CPUCTL = "/dev/cpuctl"
    private const val GROUP = "$CPUCTL/droiddeck"
    private const val HOME = "$CPUCTL/top-app"
    private const val MARKER = "root-game-boost"
    private const val SWEEP_MS = 3000L

    @Volatile private var sweeper: Thread? = null
    private val moved = HashSet<Int>()

    @Synchronized
    fun start(context: Context): String {
        val level = SessionPrefs.rootGameBoost(context)
        if (level <= 0) return "game boost: off"
        if (!RootShell.available()) return "game boost: no root, left alone"
        cleanup(context)
        val setup = RootShell.run(listOf(
            "[ -f $HOME/cpu.uclamp.min ] || exit 3",
            "mkdir -p $GROUP || exit 4",
            "echo $level > $GROUP/cpu.uclamp.min || exit 5",
            "[ -f $GROUP/cpu.uclamp.latency_sensitive ] && echo 1 > $GROUP/cpu.uclamp.latency_sensitive",
            "cat $GROUP/cpu.uclamp.min",
        ))
        if (!setup.ok) return "game boost: this kernel has no cpu uclamp groups (exit ${setup.exit})"
        File(context.filesDir, MARKER).writeText(level.toString())
        synchronized(moved) { moved.clear() }
        val app = context.applicationContext
        sweeper = Thread({ sweep(app) }, "root-game-boost").apply { isDaemon = true; start() }
        return "game boost: uclamp.min ${setup.lastLine}"
    }

    @Synchronized
    fun stop(context: Context) {
        sweeper?.interrupt()
        sweeper = null
        cleanup(context)
    }

    @Synchronized
    fun clearLeftover(context: Context) {
        if (File(context.filesDir, MARKER).exists() && RootShell.available()) cleanup(context)
    }

    private fun cleanup(context: Context) {
        val marker = File(context.filesDir, MARKER)
        if (!marker.exists()) return
        val result = RootShell.run(listOf(
            "[ -d $GROUP ] || exit 0",
            "for p in $(cat $GROUP/cgroup.procs 2>/dev/null); do echo \$p > $HOME/cgroup.procs 2>/dev/null; done",
            "rmdir $GROUP",
        ))
        Log.i(TAG, "boost group removed (exit ${result.exit})")
        marker.delete()
    }

    private fun sweep(context: Context) {
        val me = Process.myPid()
        val uid = Process.myUid()
        val pkg = context.packageName
        while (!Thread.currentThread().isInterrupted) {
            val fresh = File("/proc").list().orEmpty().mapNotNull { it.toIntOrNull() }.filter { pid ->
                pid != me && synchronized(moved) { pid !in moved } && ownedBy(pid, uid) && !isAppProcess(pid, pkg)
            }
            if (fresh.isNotEmpty()) {
                RootShell.run(listOf("for p in ${fresh.joinToString(" ")}; do echo \$p > $GROUP/cgroup.procs; done 2>/dev/null; true"))
                synchronized(moved) { moved.addAll(fresh) }
            }
            try { Thread.sleep(SWEEP_MS) } catch (_: InterruptedException) { return }
        }
    }

    private fun ownedBy(pid: Int, uid: Int): Boolean = try { Os.stat("/proc/$pid").st_uid == uid } catch (_: Exception) { false }

    /** Android's own processes for this package are placed by ActivityManager and stay where it puts them. */
    private fun isAppProcess(pid: Int, pkg: String): Boolean = try {
        File("/proc/$pid/cmdline").readText().substringBefore('\u0000').let { it == pkg || it.startsWith("$pkg:") }
    } catch (_: Exception) { true }
}

package com.droiddeck.launcher.session

import android.content.Context
import android.os.Process
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

    /** Changes the floor of a running session; 0 removes the boost group. */
    @Synchronized
    fun setLevel(context: Context, level: Int) {
        if (level <= 0) { stop(context); return }
        if (sweeper != null && File(context.filesDir, MARKER).exists()) {
            RootShell.run(listOf("echo $level > $GROUP/cpu.uclamp.min"))
            File(context.filesDir, MARKER).writeText(level.toString())
            Log.i(TAG, "boost now $level")
        } else Log.i(TAG, start(context, level))
    }

    @Synchronized
    fun start(context: Context, level: Int = SessionPrefs.rootGameBoost(context)): String {
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
            // Under chroot the guest runs in su's SELinux domain, so only root can see its /proc entries.
            val listing = RootShell.query("ps -A -o PID,UID,NAME", 8000).orEmpty()
            val fresh = listing.lines().mapNotNull { line ->
                val cols = line.trim().split(Regex("\\s+"))
                val pid = cols.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                val owner = cols.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                val name = cols.getOrNull(2).orEmpty()
                pid.takeIf { owner == uid && it != me && name != pkg && !name.startsWith("$pkg:") }
            }.filter { pid -> synchronized(moved) { pid !in moved } }
            if (fresh.isNotEmpty()) {
                RootShell.query("for p in ${fresh.joinToString(" ")}; do echo \$p > $GROUP/cgroup.procs; done 2>/dev/null; true", 8000)
                synchronized(moved) {
                    moved.addAll(fresh)
                    if (moved.size > 4096) moved.clear()
                }
            }
            try { Thread.sleep(SWEEP_MS) } catch (_: InterruptedException) { return }
        }
    }
}

package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.core.RootShell

/**
 * While a game runs, Steam's web helper (the Chromium processes behind the Steam interface) keeps working in
 * the background. This puts those processes into their own cpu cgroup with a small CPU share and a low clock
 * ceiling for as long as a game runs, and lifts both when the game exits. They are slowed, not stopped: a
 * stopped helper could not open the Steam menu or the overlay any more.
 */
object RootSteamThrottle {
    private const val TAG = "RootSteamThrottle"
    private const val GROUP = "/dev/cpuctl/droiddeck-steam"
    private const val HOME = "/dev/cpuctl/top-app"
    /** Clock ceiling for the group in percent of the fastest core. */
    private const val UCLAMP_MAX = 30
    /** CPU time per 100 ms period, all helper processes together: 40 % of one core. */
    private const val QUOTA_US = 40_000
    private const val SWEEP_MS = 5000L

    @Volatile private var thread: Thread? = null
    @Volatile private var made = false

    /** True while the limits are in place. */
    @Volatile var active: Boolean = false
        private set

    /** Called when a game starts or exits. */
    @Synchronized
    fun game(context: Context, running: Boolean) {
        if (!running || !SessionPrefs.rootSteamThrottle(context)) { release(); return }
        if (thread != null) return
        thread = Thread({ loop() }, "root-steam-throttle").apply { isDaemon = true; start() }
    }

    /** Game over: the helpers stay in the group, without limits. */
    @Synchronized
    private fun release() {
        thread?.interrupt()
        thread = null
        if (!active) return
        active = false
        RootShell.query("[ -d $GROUP ] && { echo max > $GROUP/cpu.uclamp.max; " +
            "[ -f $GROUP/cpu.cfs_quota_us ] && echo -1 > $GROUP/cpu.cfs_quota_us; }; true", 8000)
        Log.i(TAG, "steam helpers: limits lifted")
    }

    /** Session over: the group goes away. */
    @Synchronized
    fun stop() {
        release()
        if (!made) return
        made = false
        RootShell.query("[ -d $GROUP ] && { for p in $(cat $GROUP/cgroup.procs 2>/dev/null); do " +
            "echo \$p > $HOME/cgroup.procs 2>/dev/null; done; rmdir $GROUP; }; true", 8000)
    }

    private fun loop() {
        if (!RootShell.available()) return
        val setup = RootShell.query("mkdir -p $GROUP && echo $UCLAMP_MAX > $GROUP/cpu.uclamp.max && " +
            "{ [ -f $GROUP/cpu.cfs_quota_us ] && echo $QUOTA_US > $GROUP/cpu.cfs_quota_us; echo ok; }", 8000)
        if (setup?.trim()?.endsWith("ok") != true) {
            Log.i(TAG, "steam helpers: this kernel has no cpu uclamp groups, left alone")
            return
        }
        made = true
        active = true
        Log.i(TAG, "steam helpers: at most $UCLAMP_MAX % clock and ${QUOTA_US / 1000} % of one core while the game runs")
        while (!Thread.currentThread().isInterrupted) {
            RootShell.query("for p in $(pgrep steamwebhelper); do echo \$p > $GROUP/cgroup.procs; done 2>/dev/null; true", 8000)
            try { Thread.sleep(SWEEP_MS) } catch (_: InterruptedException) { return }
        }
    }
}

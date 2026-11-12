package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.agent.AgentGuest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Frame limit that takes effect while the game runs: gamescope's own limiter, set through the same root-window
 * property Steam's quick menu uses. Unlike DXVK_FRAME_RATE/VKD3D_FRAME_RATE it needs no game restart, which is
 * what lets the governor and the tuner step the frame rate. Only gamescope sessions have it.
 */
object LiveFpsLimit {
    private const val TAG = "LiveFpsLimit"
    private const val CHECK_AFTER_MS = 9000L
    private const val MIN_FPS = 24

    /** What was last set (0 = none). */
    @Volatile var current: Int = 0
        private set

    /** Whether a limit was seen holding the frame rate down: null until one could be checked. */
    @Volatile var works: Boolean? = null
        private set

    /**
     * The rates gamescope's limiter can really hold. It only skips whole refreshes, so a limit has to divide
     * the refresh rate evenly: at 120 Hz that is 60, 40 and 30, and a limit of 55 or 50 stays at 60. With the
     * display following the game, every mode of the panel counts (144 Hz adds 72, 48 and 36, 90 Hz adds 45).
     */
    fun achievable(context: Context): List<Int> {
        val rates = ArrayList<Float>()
        SessionState.refreshHz.takeIf { it > 1f }?.let { rates += it }
        if (SessionPrefs.matchRefreshToGame(context)) runCatching {
            val display = context.getSystemService(android.hardware.display.DisplayManager::class.java)
                ?.getDisplay(android.view.Display.DEFAULT_DISPLAY) ?: return@runCatching
            val now = display.mode
            val cap = PerfMode.peakRefreshCap(context)
            display.supportedModes.filter { it.physicalWidth == now.physicalWidth && it.physicalHeight == now.physicalHeight &&
                it.refreshRate <= cap + 0.5f }.forEach { rates += it.refreshRate }
        }
        return rates.flatMap { hz -> (1..6).map { Math.round(hz / it) }.filter { it >= MIN_FPS && PerfMode.cadenceFits(hz, it) } }
            .distinct().sortedDescending()
    }

    /** The nearest rate the limiter can hold that does not exceed [fps]; the lowest one when all are higher. */
    fun snap(context: Context, fps: Int): Int {
        if (fps <= 0) return 0
        val rates = achievable(context)
        return rates.filter { it <= fps + 1 }.maxOrNull() ?: rates.minOrNull() ?: fps
    }

    @Synchronized
    fun set(context: Context, fps: Int): Boolean {
        val value = snap(context, fps.coerceIn(0, 240))
        if (value == current) return true
        if (!SessionState.running) return false
        val before = fpsNow()
        val script = "ok=1; for d in \$DISPLAY \$STEAM_GAME_DISPLAY_0; do " +
            "DISPLAY=\$d xprop -root -f GAMESCOPE_FPS_LIMIT 32c -set GAMESCOPE_FPS_LIMIT $value || ok=0; done; [ \$ok = 1 ]"
        val answer = runCatching {
            AgentGuest.call(context, JSONObject().put("kind", "exec").put("argv", JSONArray(listOf("sh", "-c", script))).put("timeout", 8),
                10_000, trusted = true)
        }.onFailure { Log.i(TAG, "limit $value not set: ${it.message}") }.getOrNull() ?: return false
        if (answer.optInt("exitCode", -1) != 0) {
            Log.i(TAG, "limit $value not set: $answer")
            return false
        }
        current = value
        Log.i(TAG, "limit $value fps" + if (value != fps) " (asked for $fps)" else "")
        // The display switches to a mode this limit divides, and gamescope's refresh follows it.
        if (SessionState.liveFpsLimit != value) {
            SessionState.liveFpsLimit = value
            SessionState.onGameFpsLimit?.invoke()
        }
        if (value > 0 && before != null && before > value + 3) verifyLater(value)
        return true
    }

    /** Session over: gamescope is gone, so only the bookkeeping resets. */
    fun reset() {
        current = 0
        SessionState.liveFpsLimit = 0
    }

    private fun fpsNow(): Float? {
        val stats = com.droiddeck.launcher.hud.LiveFrames.stats ?: return null
        val fresh = (System.nanoTime() - com.droiddeck.launcher.hud.LiveFrames.updatedNs) < 3_000_000_000L
        return if (fresh) stats.fps else null
    }

    private fun verifyLater(limit: Int) {
        Thread({
            try { Thread.sleep(CHECK_AFTER_MS) } catch (_: InterruptedException) { return@Thread }
            if (current != limit) return@Thread
            val fps = fpsNow() ?: return@Thread
            works = fps <= limit + 3
            Log.i(TAG, "limit $limit fps ${if (works == true) "holds" else "has no effect"} (now ${"%.1f".format(fps)} fps)")
        }, "live-fps-check").apply { isDaemon = true; start() }
    }
}

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

    /** What was last set (0 = none). */
    @Volatile var current: Int = 0
        private set

    /** Whether a limit was seen holding the frame rate down: null until one could be checked. */
    @Volatile var works: Boolean? = null
        private set

    @Synchronized
    fun set(context: Context, fps: Int): Boolean {
        val value = fps.coerceIn(0, 240)
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
        Log.i(TAG, "limit $value fps")
        if (value > 0 && before != null && before > value + 3) verifyLater(value)
        return true
    }

    /** Session over: gamescope is gone, so only the bookkeeping resets. */
    fun reset() {
        current = 0
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

package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.agent.AgentGuest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Frame limit that takes effect while the game runs: gamescope's own limiter. In a Steam session it is set
 * through Steam's Frame Limit (the slider in its quick menu), elsewhere through gamescope's root-window
 * property. Unlike DXVK_FRAME_RATE/VKD3D_FRAME_RATE it needs no game restart, which is what lets the governor
 * and the tuner step the frame rate. Only gamescope sessions have it.
 */
object LiveFpsLimit {
    private const val TAG = "LiveFpsLimit"
    private const val CHECK_AFTER_MS = 9000L
    private const val MIN_FPS = 24
    private const val REFRESH_WAIT_MS = 3000L

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

    /**
     * Sets Steam's own Frame Limit from inside the Steam client, the way its quick menu does. Steam owns
     * gamescope's limit in a Steam session: a value written past it straight to gamescope did not hold a game.
     * Steam only takes the rates gamescope lists for the current refresh rate; the script picks the highest of
     * them that does not exceed the wanted one, and the highest of all for "off".
     */
    private const val STEAM_JS = """
(async () => {
  const want = __WANT__;
  const settings = window.settingsStore && window.settingsStore.clientSettings;
  if (!settings || !window.SteamClient || !SteamClient.Settings || typeof SteamClient.Settings.SetSetting !== "function") return "unavailable";
  let ctl = window.__droiddeckLimitCtl;
  if (!ctl) {
    ctl = { service: null, ids: {} };
    try {
      let require;
      window.webpackChunksteamui.push([[Symbol("droiddeck-limit-ctl")], {}, r => { require = r; }]);
      for (const id of Object.keys(require.m || {})) {
        const source = String(require.m[id]);
        for (const name of ["gamescope_disable_framelimit", "gamescope_enable_app_target_framerate"]) {
          if (ctl.ids[name]) continue;
          const m = source.match(new RegExp(name + "\\s*:\\s*\\{\\s*n\\s*:\\s*(\\d+)"));
          if (m) ctl.ids[name] = Number(m[1]);
        }
        if (ctl.service || !source.includes("SetBlurParams") || !source.includes("RegisterForNotifyStateChanged")) continue;
        let exports;
        try { exports = require(id); } catch (e) { continue; }
        if (!exports || typeof exports !== "object") continue;
        ctl.service = Object.values(exports).find(v => v && typeof v.GetState === "function" &&
          typeof v.RegisterForNotifyStateChanged === "function" && typeof v.SetBlurParams === "function") || null;
      }
    } catch (e) {
    }
    window.__droiddeckLimitCtl = ctl;
  }
  if (!ctl.service) return "unavailable";
  const write = (field, value) => {
    const bytes = [];
    for (let n of [field * 8, value]) {
      while (n > 127) { bytes.push((n & 127) | 128); n = Math.floor(n / 128); }
      bytes.push(n);
    }
    return SteamClient.Settings.SetSetting(btoa(String.fromCharCode(...bytes)));
  };
  let rates;
  try {
    const reply = await ctl.service.GetState({});
    if (reply.BSuccess()) rates = reply.Body().toObject().state?.active_display_info?.supported_frame_rates;
  } catch (e) {
  }
  if (!Array.isArray(rates) || rates.length === 0) return "norates";
  for (const [name, on] of [["gamescope_disable_framelimit", false], ["gamescope_enable_app_target_framerate", true]]) {
    if (settings[name] !== !on) continue;
    if (!ctl.ids[name]) return "blocked:" + name;
    await write(ctl.ids[name], on ? 1 : 0);
  }
  const top = Math.max(...rates);
  const fit = rates.filter(r => r <= want + 1);
  const value = want <= 0 ? top : (fit.length ? Math.max(...fit) : Math.min(...rates));
  if (settings.gamescope_app_target_framerate !== value) await write(21002, value);
  return "set:" + value;
})()
"""

    /** How the limit in place was set: "steam" (Steam's Frame Limit) or "gamescope" (its property); null = none. */
    @Volatile var via: String? = null
        private set

    /** Why Steam's Frame Limit could not be used, when Steam itself said so. */
    @Volatile var note: String? = null
        private set

    @Volatile private var settleAfter = 0L

    /** The rate Steam took, or null when Steam's Frame Limit cannot be reached in this session. */
    private fun viaSteam(context: Context, value: Int): Int? {
        val answer = runCatching { AgentGuest.steamEval(context, STEAM_JS.replace("__WANT__", value.toString()), 12_000) }
            .onFailure { Log.i(TAG, "steam frame limit: ${it.message}") }.getOrNull() ?: return null
        val text = answer.optString("value")
        if (!text.startsWith("set:")) {
            Log.i(TAG, "steam frame limit: $text")
            // "blocked:<setting>": Steam has its Frame Limit switched off and the switch could not be found.
            note = text.takeIf { it.startsWith("blocked:") }
            return null
        }
        note = null
        return text.removePrefix("set:").toIntOrNull()
    }

    /** Sessions without the Steam client (or without its DevTools): gamescope's root-window property. */
    private fun viaProperty(context: Context, value: Int): Boolean {
        val script = "n=0; for d in \$DISPLAY \$STEAM_GAME_DISPLAY_0; do " +
            "DISPLAY=\$d xprop -root -f GAMESCOPE_FPS_LIMIT 32c -set GAMESCOPE_FPS_LIMIT $value && n=\$((n+1)); done; [ \$n -gt 0 ]"
        val answer = runCatching {
            AgentGuest.call(context, JSONObject().put("kind", "exec").put("argv", JSONArray(listOf("sh", "-c", script))).put("timeout", 8),
                10_000, trusted = true)
        }.onFailure { Log.i(TAG, "limit $value not set: ${it.message}") }.getOrNull() ?: return false
        if (answer.optInt("exitCode", -1) != 0) Log.i(TAG, "limit $value not set: $answer")
        return answer.optInt("exitCode", -1) == 0
    }

    /** The display needs a moment to switch to a mode the limit divides; Steam only offers the rate after that. */
    private fun awaitRefresh(context: Context, value: Int) {
        if (value <= 0 || !SessionPrefs.matchRefreshToGame(context)) return
        val end = System.currentTimeMillis() + REFRESH_WAIT_MS
        while (System.currentTimeMillis() < end && !PerfMode.cadenceFits(SessionState.refreshHz, value)) {
            try { Thread.sleep(150) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return }
        }
        // gamescope follows the host's refresh rate a few frames later, and Steam reads it from gamescope.
        try { Thread.sleep(600) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    @Synchronized
    fun set(context: Context, fps: Int): Boolean {
        val value = snap(context, fps.coerceIn(0, 240))
        if (value == current) return true
        if (!SessionState.running) return false
        val before = fpsNow()
        val shown = SessionState.liveFpsLimit
        // The display switches to a mode this limit divides first, and gamescope's refresh follows it.
        if (shown != value) {
            SessionState.liveFpsLimit = value
            SessionState.onGameFpsLimit?.invoke()
        }
        awaitRefresh(context, value)
        val taken = viaSteam(context, value)
        val how = when {
            taken != null -> "steam"
            viaProperty(context, value) -> "gamescope"
            else -> null
        }
        if (how == null) {
            if (shown != value) {
                SessionState.liveFpsLimit = shown
                SessionState.onGameFpsLimit?.invoke()
            }
            return false
        }
        current = value
        via = how.takeIf { value > 0 }
        // Steam keeps its Frame Limit across sessions; remember that one of ours is in it.
        if (how == "steam") SessionPrefs.setLiveLimitLeft(context, value > 0)
        Log.i(TAG, "limit $value fps through $how" + (if (value != fps) " (asked for $fps)" else "") +
            (if (taken != null && value > 0 && taken != value) ", Steam took $taken" else ""))
        // Checked unless the game was already below the limit: then nothing can be told from the frame rate.
        if (value > 0 && (before == null || before > value + 3)) verifyLater(value)
        return true
    }

    /**
     * A session that ended with a limit in place left it in Steam's settings. Called while a session runs:
     * takes it out again as soon as the Steam client answers.
     */
    fun settle(context: Context) {
        if (current != 0 || !SessionPrefs.liveLimitLeft(context)) return
        val now = System.currentTimeMillis()
        if (now < settleAfter) return
        settleAfter = now + 15_000
        synchronized(this) {
            if (current == 0 && viaSteam(context, 0) != null) {
                SessionPrefs.setLiveLimitLeft(context, false)
                Log.i(TAG, "took the last session's frame limit out of Steam")
            }
        }
    }

    /** Session over: gamescope is gone, so only the bookkeeping resets. */
    fun reset() {
        current = 0
        via = null
        works = null
        SessionState.liveFpsLimit = 0
    }

    private fun fpsNow(maxAgeMs: Long = 3000): Float? {
        val stats = com.droiddeck.launcher.hud.LiveFrames.stats ?: return null
        val fresh = (System.nanoTime() - com.droiddeck.launcher.hud.LiveFrames.updatedNs) < maxAgeMs * 1_000_000L
        return if (fresh) stats.fps else null
    }

    private fun verifyLater(limit: Int) {
        Thread({
            try { Thread.sleep(CHECK_AFTER_MS) } catch (_: InterruptedException) { return@Thread }
            if (current != limit) return@Thread
            val fps = fpsNow(6000) ?: return@Thread
            works = fps <= limit + 3
            Log.i(TAG, "limit $limit fps ${if (works == true) "holds" else "has no effect"} (now ${"%.1f".format(fps)} fps)")
        }, "live-fps-check").apply { isDaemon = true; start() }
    }
}

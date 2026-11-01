package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import java.io.File

/** Per-game overrides for the root clock limits and CPU boost; every field can fall back to the global setting. */
object RootGameProfiles {
    private const val TAG = "RootGameProfiles"
    private const val PREFS = "root-game-profiles"
    const val INHERIT = -1
    /** Written by droiddeck-game-env in the guest's XDG_RUNTIME_DIR while a game runs. */
    private const val GAME_FILE = "droiddeck-game"
    private const val POLL_MS = 2000L

    data class Profile(val gpuCapMhz: Int = INHERIT, val cpuCapPercent: Int = INHERIT, val boost: Int = INHERIT) {
        val empty: Boolean get() = gpuCapMhz == INHERIT && cpuCapPercent == INHERIT && boost == INHERIT
    }

    data class Effective(val perf: RootPerfProfile.Settings, val boost: Int)

    fun get(context: Context, scope: String): Profile {
        val raw = prefs(context).getString(scope, null) ?: return Profile()
        val parts = raw.split(',').map { it.trim().toIntOrNull() ?: INHERIT }
        return Profile(parts.getOrElse(0) { INHERIT }, parts.getOrElse(1) { INHERIT }, parts.getOrElse(2) { INHERIT })
    }

    fun set(context: Context, scope: String, profile: Profile) {
        prefs(context).edit().apply {
            if (profile.empty) remove(scope) else putString(scope, "${profile.gpuCapMhz},${profile.cpuCapPercent},${profile.boost}")
        }.apply()
    }

    fun effective(context: Context, scope: String?): Effective {
        val global = RootPerfProfile.globalSettings(context)
        val game = scope?.let { get(context, it) } ?: Profile()
        return Effective(
            global.copy(
                gpuCapMhz = game.gpuCapMhz.takeIf { it != INHERIT } ?: global.gpuCapMhz,
                cpuCapPercent = game.cpuCapPercent.takeIf { it != INHERIT } ?: global.cpuCapPercent,
            ),
            game.boost.takeIf { it != INHERIT } ?: SessionPrefs.rootGameBoost(context),
        )
    }

    @Volatile private var watcher: Thread? = null
    @Volatile private var gameFile: File? = null

    /** What the running session currently has applied; trials change it without saving a profile. */
    @Volatile var applied: Effective? = null
        private set

    @Synchronized
    fun applyNow(context: Context, next: Effective): String {
        val before = applied ?: effective(context, null)
        val perf = if (next.perf != before.perf) RootPerfProfile.switchTo(context, next.perf) else "clocks unchanged"
        if (next.boost != before.boost) RootGameBoost.setLevel(context, next.boost)
        applied = next
        return "$perf, boost ${next.boost}"
    }

    fun runningScope(context: Context): String? =
        if (!SessionState.running) null else runCatching { File(context.filesDir, ".wayland-rt/$GAME_FILE").readText().trim() }.getOrNull()
            ?.takeIf { it.matches(Regex("[1-9][0-9]{0,9}")) }

    /** Follows the running game and switches the root settings to its profile, and back when it exits. */
    @Synchronized
    fun watch(context: Context, runtimeDir: File, report: (String) -> Unit) {
        unwatch()
        val app = context.applicationContext
        val file = File(runtimeDir, GAME_FILE)
        file.delete()
        gameFile = file
        applied = effective(app, null)
        watcher = Thread({
            var current: String? = null
            while (!Thread.currentThread().isInterrupted) {
                val scope = runCatching { file.readText().trim() }.getOrNull()?.takeIf { it.matches(Regex("[1-9][0-9]{0,9}")) }
                if (scope != current) {
                    current = scope
                    val next = effective(app, scope)
                    if (next != applied) report("game profile ${scope ?: "none (global)"}: ${applyNow(app, next)}")
                    else Log.i(TAG, "game ${scope ?: "-"} uses the global root settings")
                }
                try { Thread.sleep(POLL_MS) } catch (_: InterruptedException) { break }
            }
        }, "root-game-profiles").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun unwatch() {
        watcher?.interrupt()
        watcher = null
        applied = null
        // The guest's cleanup child dies with the session, so the marker is removed here too.
        gameFile?.delete()
        gameFile = null
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

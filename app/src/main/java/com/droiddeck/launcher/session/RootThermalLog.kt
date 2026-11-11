package com.droiddeck.launcher.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.RootShell
import java.io.File

/**
 * Records skin/GPU/battery temperature, power draw, FPS and GPU clock every few seconds for the whole session,
 * one CSV per session, so a long run shows whether a setup holds without a cooler. Also notifies once when the
 * skin reaches the temperature where Android starts cutting the GPU clock.
 */
object RootThermalLog {
    private const val TAG = "RootThermalLog"
    private const val DIR = "thermal"
    private const val KEEP = 30
    private const val INTERVAL_MS = 10_000L
    const val HEADER = "t,skin,gpu_c,battery_c,watt,fps,gpu_mhz,thermal_cap,game,fps_limit"

    /** Android cut the GPU at about 43-44 °C skin in measurements on the RedMagic 11 Pro. */
    private const val SKIN_ALERT_C = 43.0
    private const val SKIN_REARM_C = 40.0
    private const val CHANNEL = "thermal"
    private const val NOTIFICATION_ID = 4301
    private const val SUMMARY_CHANNEL = "session-summary"
    private const val SUMMARY_ID = 4302
    /** Ten minutes in a game; shorter runs say little about heat. */
    private const val SUMMARY_MIN_SAMPLES = 60

    @Volatile private var thread: Thread? = null
    @Volatile var currentFile: File? = null
        private set

    fun dir(context: Context) = File(context.filesDir, DIR)

    @Synchronized
    fun start(context: Context) {
        stop()
        if (!RootShell.available()) return
        val app = context.applicationContext
        val dir = dir(app).apply { mkdirs() }
        dir.listFiles { f -> f.name.endsWith(".csv") }?.sortedByDescending { it.name }?.drop(KEEP - 1)?.forEach { it.delete() }
        val file = File(dir, "${System.currentTimeMillis()}.csv").apply { writeText(HEADER + "\n") }
        currentFile = file
        thread = Thread({ loop(app, file) }, "root-thermal-log").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        thread?.interrupt()
        thread = null
        currentFile = null
    }

    private fun loop(context: Context, file: File) {
        val start = System.currentTimeMillis()
        var alerted = false
        var hotAtS: Long? = null
        var samples = 0
        var wattSum = 0.0; var wattN = 0
        var fpsSum = 0.0; var fpsN = 0
        var skinMax: Double? = null
        var lastGame = ""
        while (!Thread.currentThread().isInterrupted) {
            runCatching {
                val r = RootSensors.read(context) ?: return@runCatching
                val frames = com.droiddeck.launcher.hud.LiveFrames.stats
                val fresh = (System.nanoTime() - com.droiddeck.launcher.hud.LiveFrames.updatedNs) < 3_000_000_000L
                val fps = if (frames != null && fresh) frames.fps.toDouble() else null
                val game = RootGameProfiles.runningScope(context).orEmpty()
                val t = (System.currentTimeMillis() - start) / 1000
                file.appendText(listOf(t, f(r.skin), f(r.gpuC), f(r.batteryC), f(r.watt, 2), f(fps), r.gpuMhz ?: "",
                    RootGameProfiles.thermalCapMhz.takeIf { it > 0 } ?: "", game, LiveFpsLimit.current.takeIf { it > 0 } ?: "").joinToString(",") + "\n")
                if (game.isNotEmpty()) {
                    // The summary covers time in a game, not the Steam menus around it.
                    samples++
                    lastGame = game
                    r.watt?.let { wattSum += it; wattN++ }
                    fps?.let { fpsSum += it; fpsN++ }
                    r.skin?.let { s -> if (skinMax == null || s > skinMax!!) skinMax = s }
                }
                if (r.skin != null) {
                    if (!alerted && r.skin >= SKIN_ALERT_C) {
                        alerted = true
                        if (hotAtS == null) hotAtS = t
                        notifyHot(context, r.skin, r.watt, game)
                    } else if (alerted && r.skin <= SKIN_REARM_C) alerted = false
                }
            }.onFailure { Log.w(TAG, "sample failed", it) }
            try { Thread.sleep(INTERVAL_MS) } catch (_: InterruptedException) { break }
        }
        if (samples >= SUMMARY_MIN_SAMPLES) runCatching {
            notifySummary(context, lastGame, samples * INTERVAL_MS / 60_000.0, if (wattN > 0) wattSum / wattN else null,
                if (fpsN > 0) fpsSum / fpsN else null, skinMax, hotAtS)
        }
    }

    /** After a session with a real stretch of play: how it went, so a setup that ran too hot gets noticed. */
    private fun notifySummary(context: Context, game: String, minutes: Double, watt: Double?, fps: Double?, skinMax: Double?, hotAtS: Long?) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(SUMMARY_CHANNEL, context.getString(R.string.thermal_summary_channel),
            NotificationManager.IMPORTANCE_LOW))
        val name = gameName(context, game)
        fun n(v: Double?, digits: Int = 1) = v?.let { "%.${digits}f".format(it) } ?: "?"
        val verdict = context.getString(when {
            hotAtS != null -> R.string.thermal_summary_hot
            watt != null && watt > 6.0 -> R.string.thermal_summary_heavy
            else -> R.string.thermal_summary_fine
        }, hotAtS?.div(60) ?: 0)
        val text = context.getString(R.string.thermal_summary_text, n(minutes, 0), n(fps, 0), n(watt), n(skinMax)) + " " + verdict
        manager.notify(SUMMARY_ID, Notification.Builder(context, SUMMARY_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_session)
            .setContentTitle(context.getString(R.string.thermal_summary_title) + (name?.let { " – $it" } ?: ""))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build())
    }

    private fun gameName(context: Context, scope: String): String? = scope.takeIf { it.isNotEmpty() }?.let { s ->
        runCatching { com.droiddeck.launcher.frontend.LibraryCache.snapshot(context).first.firstOrNull { it.profileKey == s }?.name }.getOrNull()
    }

    private fun notifyHot(context: Context, skin: Double, watt: Double?, game: String) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.thermal_channel),
                NotificationManager.IMPORTANCE_DEFAULT))
            val name = gameName(context, game)
            val text = context.getString(R.string.thermal_hot_text, "%.1f".format(skin), watt?.let { "%.1f".format(it) } ?: "?")
            val notification = Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_session)
                .setContentTitle(context.getString(R.string.thermal_hot_title) + (name?.let { " – $it" } ?: ""))
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        }.onFailure { Log.w(TAG, "notify failed", it) }
    }

    private fun f(v: Double?, digits: Int = 1): String = v?.let { "%.${digits}f".format(java.util.Locale.ROOT, it) } ?: ""
}

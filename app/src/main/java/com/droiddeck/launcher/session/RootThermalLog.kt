package com.droiddeck.launcher.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
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
    private const val KGSL = "/sys/class/kgsl/kgsl-3d0"
    private const val BAT = "/sys/class/power_supply/battery"
    const val HEADER = "t,skin,gpu_c,battery_c,watt,fps,gpu_mhz,thermal_cap,game"

    /** Android cut the GPU at about 43-44 °C skin in measurements on the RedMagic 11 Pro. */
    private const val SKIN_ALERT_C = 43.0
    private const val SKIN_REARM_C = 40.0
    private const val CHANNEL = "thermal"
    private const val NOTIFICATION_ID = 4301

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
        val skinZone = runCatching { RootSoftThrottle.findSkinZone() }.getOrNull()
        val start = System.currentTimeMillis()
        var alerted = false
        while (!Thread.currentThread().isInterrupted) {
            runCatching {
                val out = RootShell.run(listOf(
                    "echo $(cat ${skinZone ?: "/nonexistent"}/temp 2>/dev/null || echo x) $(cat $KGSL/temp 2>/dev/null || echo x) " +
                        "$(cat $KGSL/clock_mhz 2>/dev/null || echo x) $(cat $BAT/current_now 2>/dev/null || echo x) " +
                        "$(cat $BAT/voltage_now 2>/dev/null || echo x)",
                ), 5).output.trim().lines().lastOrNull().orEmpty().split(' ')
                val skin = out.getOrNull(0)?.let(::degrees)
                val gpu = out.getOrNull(1)?.let(::degrees)
                val mhz = out.getOrNull(2)?.toLongOrNull()
                val ua = out.getOrNull(3)?.toDoubleOrNull()
                val uv = out.getOrNull(4)?.toDoubleOrNull()
                val watt = if (ua != null && uv != null) kotlin.math.abs(ua * uv) / 1e12 else null
                val battery = batteryTemp(context)
                val frames = com.droiddeck.launcher.hud.LiveFrames.stats
                val fresh = (System.nanoTime() - com.droiddeck.launcher.hud.LiveFrames.updatedNs) < 3_000_000_000L
                val fps = if (frames != null && fresh) frames.fps.toDouble() else null
                val game = RootGameProfiles.runningScope(context).orEmpty()
                val t = (System.currentTimeMillis() - start) / 1000
                file.appendText(listOf(t, f(skin), f(gpu), f(battery), f(watt, 2), f(fps), mhz ?: "",
                    RootGameProfiles.thermalCapMhz.takeIf { it > 0 } ?: "", game).joinToString(",") + "\n")
                if (skin != null) {
                    if (!alerted && skin >= SKIN_ALERT_C) {
                        alerted = true
                        notifyHot(context, skin, watt, game)
                    } else if (alerted && skin <= SKIN_REARM_C) alerted = false
                }
            }.onFailure { Log.w(TAG, "sample failed", it) }
            try { Thread.sleep(INTERVAL_MS) } catch (_: InterruptedException) { break }
        }
    }

    private fun notifyHot(context: Context, skin: Double, watt: Double?, game: String) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.thermal_channel),
                NotificationManager.IMPORTANCE_DEFAULT))
            val name = game.takeIf { it.isNotEmpty() }?.let { scope ->
                runCatching {
                    com.droiddeck.launcher.frontend.LibraryCache.snapshot(context).first.firstOrNull { it.profileKey == scope }?.name
                }.getOrNull()
            }
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

    private fun batteryTemp(context: Context): Double? = runCatching {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.div(10.0)
    }.getOrNull()

    private fun degrees(raw: String): Double? = raw.trim().toDoubleOrNull()?.let { if (kotlin.math.abs(it) > 1000) it / 1000 else it }

    private fun f(v: Double?, digits: Int = 1): String = v?.let { "%.${digits}f".format(java.util.Locale.ROOT, it) } ?: ""
}

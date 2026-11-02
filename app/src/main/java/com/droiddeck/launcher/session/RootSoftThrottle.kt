package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.core.RootShell

/**
 * Soft throttle: lowers the GPU limit one step at a time as the phone warms, before Android's thermal
 * engine drops the clock all at once, and gives it back once things cool down.
 *
 * Measured on the RedMagic 11 Pro: Android caps the GPU (kgsl thermal_pwrlevel 7 = 578 MHz) once the skin
 * sensor reaches about 43-44 °C and releases it around 41 °C, while the GPU itself is only at ~60 °C and the
 * battery at ~37 °C. So the skin sensor leads; GPU and battery are only fallbacks.
 */
object RootSoftThrottle {
    private const val TAG = "RootSoftThrottle"
    private val STEPS = listOf(0, 1000, 900, 800, 700, 650)
    private const val SKIN_HOT_C = 41.5
    private const val SKIN_COOL_C = 39.5
    private const val GPU_HOT_C = 80.0
    private const val GPU_COOL_C = 70.0
    private const val BATTERY_HOT_C = 39.0
    private const val BATTERY_COOL_C = 37.5
    private const val POLL_MS = 4000L
    private const val DOWN_EVERY_MS = 20_000L
    private const val UP_EVERY_MS = 45_000L
    private val SKIN_TYPES = listOf("skin-msm-therm", "skin-therm", "xo-therm", "quiet-therm")
    private const val KGSL = "/sys/class/kgsl/kgsl-3d0"

    @Volatile private var thread: Thread? = null

    @Synchronized
    fun start(context: Context, report: (String) -> Unit) {
        stop()
        if (!SessionPrefs.rootSoftThrottle(context)) return
        val app = context.applicationContext
        thread = Thread({ loop(app, report) }, "root-soft-throttle").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        thread?.interrupt()
        thread = null
    }

    private data class Reading(val skin: Double?, val gpu: Double?, val androidLevel: Int?)

    /** Finds the skin sensor's thermal zone once (sysfs thermal is root-only under SELinux). */
    private fun findSkinZone(): String? {
        val out = RootShell.run(listOf("for z in /sys/class/thermal/thermal_zone*; do echo \"\$z \$(cat \$z/type)\"; done"), 10).output
        val zones = out.lines().mapNotNull { l -> l.trim().split(' ').takeIf { it.size == 2 }?.let { it[1] to it[0] } }.toMap()
        return SKIN_TYPES.firstNotNullOfOrNull { zones[it] }
    }

    private fun read(skinZone: String?): Reading {
        val cmd = "echo \$(cat ${skinZone ?: "/nonexistent"}/temp 2>/dev/null || echo x) \$(cat $KGSL/temp 2>/dev/null || echo x) " +
            "\$(cat $KGSL/thermal_pwrlevel 2>/dev/null || echo x)"
        val parts = RootShell.run(listOf(cmd), 5).output.trim().lines().lastOrNull().orEmpty().split(' ')
        return Reading(parts.getOrNull(0)?.let(::degrees), parts.getOrNull(1)?.let(::degrees), parts.getOrNull(2)?.toIntOrNull())
    }

    private fun loop(context: Context, report: (String) -> Unit) {
        val skinZone = runCatching { findSkinZone() }.getOrNull()
        report("soft throttle: on (skin ${skinZone?.substringAfterLast('/') ?: "not found, battery"} " +
            "${SKIN_HOT_C} °C, GPU ${GPU_HOT_C.toInt()} °C, battery ${BATTERY_HOT_C} °C)")
        var step = 0
        var lastChange = 0L
        var lastAndroid: Int? = null
        while (!Thread.currentThread().isInterrupted) {
            val r = runCatching { read(skinZone) }.getOrDefault(Reading(null, null, null))
            val battery = batteryTemp(context)
            val now = System.currentTimeMillis()
            val hot = (r.skin != null && r.skin >= SKIN_HOT_C) || (r.gpu != null && r.gpu >= GPU_HOT_C) ||
                (r.skin == null && battery != null && battery >= BATTERY_HOT_C)
            val cool = (r.skin == null || r.skin <= SKIN_COOL_C) && (r.gpu == null || r.gpu <= GPU_COOL_C) &&
                (r.skin != null || battery == null || battery <= BATTERY_COOL_C)
            val next = when {
                hot && step < STEPS.lastIndex && now - lastChange >= DOWN_EVERY_MS -> step + 1
                cool && step > 0 && now - lastChange >= UP_EVERY_MS -> step - 1
                else -> step
            }
            val temps = "skin ${r.skin?.let { "%.1f".format(it) } ?: "?"} °C, gpu ${r.gpu?.toInt() ?: "?"} °C, battery ${battery ?: "?"} °C"
            if (next != step) {
                step = next
                lastChange = now
                val result = RootGameProfiles.setThermalCap(context, STEPS[step])
                report("soft throttle: $temps -> " + (if (STEPS[step] == 0) "no extra limit" else "gpu <= ${STEPS[step]} MHz") +
                    (result?.let { " ($it)" } ?: ""))
            }
            if (r.androidLevel != null && r.androidLevel != lastAndroid) {
                // Android's own limit moving shows whether the soft steps came early enough.
                if (lastAndroid != null) report("soft throttle: android thermal level $lastAndroid -> ${r.androidLevel} ($temps)")
                lastAndroid = r.androidLevel
            }
            try { Thread.sleep(POLL_MS) } catch (_: InterruptedException) { break }
        }
        runCatching { RootGameProfiles.setThermalCap(context, 0) }
        Log.i(TAG, "stopped")
    }

    private fun batteryTemp(context: Context): Double? = runCatching {
        val intent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        intent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.div(10.0)
    }.getOrNull()

    /** Millidegrees or degrees - both come back as degrees. */
    private fun degrees(raw: String): Double? = raw.trim().toDoubleOrNull()?.let { if (kotlin.math.abs(it) > 1000) it / 1000 else it }
}

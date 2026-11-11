package com.droiddeck.launcher.session

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.droiddeck.launcher.core.RootShell

/**
 * One reading of the values the root features watch (skin, GPU, power draw), taken through the long-lived root
 * shell and shared for a moment, so the governor, the thermal log and the agent don't each read on their own.
 */
object RootSensors {
    private const val KGSL = "/sys/class/kgsl/kgsl-3d0"
    private const val BAT = "/sys/class/power_supply/battery"
    private const val FAN_RPM = "/sys/kernel/fan/fan_speed_count"
    private const val FRESH_MS = 1500L
    private val SKIN_TYPES = listOf("skin-msm-therm", "skin-therm", "xo-therm", "quiet-therm")

    data class Sample(
        val atMs: Long, val skin: Double?, val gpuC: Double?, val gpuMhz: Int?, val gpuMaxMhz: Int?, val gpuBusy: Int?,
        val watt: Double?, val fanRpm: Int?, val batteryC: Double?, val charging: Boolean,
    )

    @Volatile private var skinZone: String? = null
    @Volatile private var skinSearched = false
    @Volatile private var last: Sample? = null

    /** The skin sensor's thermal zone, or null when the device names none of the known ones. */
    fun skinZone(): String? {
        if (skinSearched) return skinZone
        synchronized(this) {
            if (skinSearched) return skinZone
            val out = RootShell.query("for z in /sys/class/thermal/thermal_zone*; do echo \"\$z \$(cat \$z/type)\"; done", 8000)
                ?: return null
            val zones = out.lines().mapNotNull { l -> l.trim().split(' ').takeIf { it.size == 2 }?.let { it[1] to it[0] } }.toMap()
            skinZone = SKIN_TYPES.firstNotNullOfOrNull { zones[it] }
            skinSearched = true
            return skinZone
        }
    }

    @Synchronized
    fun read(context: Context): Sample? {
        val now = System.currentTimeMillis()
        last?.takeIf { now - it.atMs < FRESH_MS }?.let { return it }
        val zone = skinZone()
        fun cat(path: String) = "\$(cat $path 2>/dev/null || echo x)"
        val out = RootShell.query(
            "echo ${cat((zone ?: "/nonexistent") + "/temp")} ${cat("$KGSL/temp")} ${cat("$KGSL/clock_mhz")} ${cat("$KGSL/max_clock_mhz")} " +
                "${cat("$KGSL/gpu_busy_percentage")} ${cat("$BAT/current_now")} ${cat("$BAT/voltage_now")} ${cat(FAN_RPM)}",
        ) ?: return null
        // gpu_busy_percentage prints "44 %", so the fields are found by position after dropping the percent sign.
        val f = out.trim().lines().lastOrNull().orEmpty().replace("%", "").split(Regex("\\s+"))
        if (f.size < 8) return null
        val ua = f[5].toDoubleOrNull()
        val uv = f[6].toDoubleOrNull()
        val battery = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val sample = Sample(
            now, degrees(f[0]), degrees(f[1]), f[2].toIntOrNull(), f[3].toIntOrNull(), f[4].toIntOrNull(),
            if (ua != null && uv != null) kotlin.math.abs(ua * uv) / 1e12 else null, f[7].toIntOrNull(),
            battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.div(10.0),
            plugged,
        )
        last = sample
        return sample
    }

    /** Millidegrees or degrees - both come back as degrees. */
    private fun degrees(raw: String): Double? = raw.trim().toDoubleOrNull()?.let { if (kotlin.math.abs(it) > 1000) it / 1000 else it }
}

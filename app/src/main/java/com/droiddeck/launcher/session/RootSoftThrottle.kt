package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Soft throttle: lowers the GPU limit one step at a time as the GPU or battery warms, before Android's
 * thermal engine drops the clock all at once, and gives it back once things cool down.
 */
object RootSoftThrottle {
    private const val TAG = "RootSoftThrottle"
    private val STEPS = listOf(0, 1000, 900, 800, 700)
    private const val GPU_HOT_C = 70.0
    private const val GPU_COOL_C = 62.0
    private const val BATTERY_HOT_C = 39.0
    private const val BATTERY_COOL_C = 37.5
    private const val POLL_MS = 3000L
    private const val DOWN_EVERY_MS = 20_000L
    private const val UP_EVERY_MS = 45_000L

    @Volatile private var thread: Thread? = null

    @Synchronized
    fun start(context: Context, report: (String) -> Unit) {
        stop()
        if (!SessionPrefs.rootSoftThrottle(context)) return
        val app = context.applicationContext
        thread = Thread({ loop(app, report) }, "root-soft-throttle").apply { isDaemon = true; start() }
        report("soft throttle: on (GPU ${GPU_HOT_C.toInt()} °C / battery ${BATTERY_HOT_C} °C)")
    }

    @Synchronized
    fun stop() {
        thread?.interrupt()
        thread = null
    }

    private fun loop(context: Context, report: (String) -> Unit) {
        var step = 0
        var lastChange = 0L
        while (!Thread.currentThread().isInterrupted) {
            val gpu = readTemp("/sys/class/kgsl/kgsl-3d0/temp")
            val battery = batteryTemp(context)
            val now = System.currentTimeMillis()
            val hot = (gpu != null && gpu >= GPU_HOT_C) || (battery != null && battery >= BATTERY_HOT_C)
            val cool = (gpu == null || gpu <= GPU_COOL_C) && (battery == null || battery <= BATTERY_COOL_C)
            val next = when {
                hot && step < STEPS.lastIndex && now - lastChange >= DOWN_EVERY_MS -> step + 1
                cool && step > 0 && now - lastChange >= UP_EVERY_MS -> step - 1
                else -> step
            }
            if (next != step) {
                step = next
                lastChange = now
                val result = RootGameProfiles.setThermalCap(context, STEPS[step])
                report("soft throttle: gpu ${gpu?.toInt() ?: "?"} °C, battery ${battery ?: "?"} °C -> " +
                    (if (STEPS[step] == 0) "no extra limit" else "gpu <= ${STEPS[step]} MHz") + (result?.let { " ($it)" } ?: ""))
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
    private fun readTemp(path: String): Double? = runCatching {
        val raw = File(path).readText().trim().toDouble()
        when {
            kotlin.math.abs(raw) > 1000 -> raw / 1000
            else -> raw
        }
    }.getOrNull()
}

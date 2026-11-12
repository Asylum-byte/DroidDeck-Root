package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log

/**
 * Governor for long play without a cooler. It steps the game down a little when the phone gets warm or draws
 * more than the chosen power target, and gives the steps back when there is room again.
 *
 * It lowers the frame rate first, with gamescope's live limiter: on this hardware the power goes with the
 * frame rate - CPU and translation work - while the GPU is often half idle, so a GPU clock limit saves
 * little. The limiter only holds rates that divide a refresh rate of the panel, so the steps are those
 * rates (60, 48, 45, 40, 36, 30 on a 144 Hz panel), not a fixed 5 FPS. Where no live limit exists (sessions
 * without gamescope, or a limiter that shows no effect) it falls back to lowering the GPU limit as before.
 *
 * Measured on the RedMagic 11 Pro: Android cuts the GPU once the skin sensor reaches about 43-44 °C and
 * releases it around 41 °C, while the GPU itself is only at ~60 °C and the battery at ~37 °C. So the skin
 * sensor leads; GPU and battery are only fallbacks.
 */
object RootSoftThrottle {
    private const val TAG = "RootSoftThrottle"
    private val GPU_STEPS = listOf(0, 1000, 900, 800, 700, 650)
    private const val FPS_FLOOR = 30
    private const val FPS_DEFAULT_BASE = 60
    private const val SKIN_HOT_C = 41.5
    private const val SKIN_COOL_C = 39.5
    private const val GPU_HOT_C = 80.0
    private const val GPU_COOL_C = 70.0
    private const val BATTERY_HOT_C = 39.0
    private const val BATTERY_COOL_C = 37.5
    private const val POLL_MS = 4000L
    private const val DOWN_EVERY_MS = 20_000L
    private const val UP_EVERY_MS = 45_000L
    /** Power is averaged over this many readings (about 20 s) so one loading screen doesn't move a step. */
    private const val WATT_WINDOW = 5
    private const val WATT_OVER = 0.3
    /** GPU steps only: how far under the target the draw has to be before a step is given back. */
    private const val WATT_UNDER = 0.6
    /** Roughly what the phone draws with the game standing still; only the rest goes up with the frame rate. */
    private const val IDLE_WATT = 2.0

    @Volatile private var thread: Thread? = null

    /** Set while the tuner tries settings itself, so the two don't pull against each other. */
    @Volatile var paused: Boolean = false

    /** Frame limit the governor currently holds (0 = none). */
    @Volatile var fpsLimit: Int = 0
        private set

    fun wanted(context: Context): Boolean = SessionPrefs.rootSoftThrottle(context) || SessionPrefs.rootWattTargetTenths(context) > 0

    @Synchronized
    fun start(context: Context, report: (String) -> Unit) {
        stop()
        if (!wanted(context)) return
        val app = context.applicationContext
        thread = Thread({ loop(app, report) }, "root-soft-throttle").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        thread?.interrupt()
        thread = null
        fpsLimit = 0
        paused = false
    }

    /**
     * Where the steps count down from: the game's own limit, else what it reaches right now - a game that
     * only makes 45 FPS gains nothing from a limit of 48.
     */
    private fun baseFps(): Int {
        RootGameProfiles.applied?.fpsLimit?.takeIf { it > 0 }?.let { return it }
        SessionState.gameFpsLimit.takeIf { it > 0 }?.let { return it }
        val stats = com.droiddeck.launcher.hud.LiveFrames.stats
        val fresh = (System.nanoTime() - com.droiddeck.launcher.hud.LiveFrames.updatedNs) < 3_000_000_000L
        val now = if (stats != null && fresh) stats.fps else return FPS_DEFAULT_BASE
        return kotlin.math.ceil(now - 0.5).toInt().coerceIn(FPS_FLOOR + 1, 144)
    }

    private fun loop(context: Context, report: (String) -> Unit) {
        val thermal = SessionPrefs.rootSoftThrottle(context)
        val target = SessionPrefs.rootWattTargetTenths(context) / 10.0
        report("governor: on (" + listOfNotNull(
            "skin ${SKIN_HOT_C} °C".takeIf { thermal }, "power target ${"%.1f".format(target)} W".takeIf { target > 0 },
        ).joinToString(", ") + ")")
        var step = 0
        var lastChange = 0L
        var useFps = true
        var base = FPS_DEFAULT_BASE
        // The limits below the base the limiter can hold, highest first; step n is ladder[n - 1].
        var ladder = emptyList<Int>()
        val watts = ArrayDeque<Double>()
        while (!Thread.currentThread().isInterrupted) {
            val r = runCatching { RootSensors.read(context) }.getOrNull()
            val now = System.currentTimeMillis()
            if (r != null && !paused) {
                r.watt?.let { watts.addLast(it); while (watts.size > WATT_WINDOW) watts.removeFirst() }
                val watt = watts.takeIf { it.size >= 3 }?.average()
                // While charging the battery current is the charge current, not what the game draws.
                val budget = target > 0 && !r.charging && watt != null
                val hot = thermal && ((r.skin != null && r.skin >= SKIN_HOT_C) || (r.gpuC != null && r.gpuC >= GPU_HOT_C) ||
                    (r.skin == null && r.batteryC != null && r.batteryC >= BATTERY_HOT_C))
                val cool = !thermal || ((r.skin == null || r.skin <= SKIN_COOL_C) && (r.gpuC == null || r.gpuC <= GPU_COOL_C) &&
                    (r.skin != null || r.batteryC == null || r.batteryC <= BATTERY_COOL_C))
                val over = budget && watt!! > target + WATT_OVER
                if (useFps && LiveFpsLimit.works == false) {
                    useFps = false
                    step = 0
                    fpsLimit = 0
                    report("governor: the live frame limit shows no effect here, using the GPU limit instead")
                }
                if (step == 0) {
                    base = baseFps()
                    ladder = if (useFps) LiveFpsLimit.achievable(context).filter { it in FPS_FLOOR until base } else emptyList()
                }
                val byFps = useFps && ladder.isNotEmpty()
                val maxStep = if (byFps) ladder.size else GPU_STEPS.lastIndex
                // A frame step is large (60 -> 48), so giving one back is judged by what the draw would be
                // at the higher rate, not by a fixed margin: otherwise the steps flip back and forth.
                val under = when {
                    !budget -> true
                    byFps && step in 1..ladder.size -> {
                        val here = ladder[step - 1]
                        val above = if (step == 1) base else ladder[step - 2]
                        watt!! + (watt - IDLE_WATT).coerceAtLeast(0.0) * (above.toDouble() / here - 1) <= target
                    }
                    else -> watt!! < target - WATT_UNDER
                }
                val next = when {
                    (hot || over) && step < maxStep && now - lastChange >= DOWN_EVERY_MS -> step + 1
                    cool && under && step > 0 && now - lastChange >= UP_EVERY_MS -> step - 1
                    else -> step.coerceAtMost(maxStep)
                }
                if (next != step) {
                    val why = "skin ${r.skin?.let { "%.1f".format(it) } ?: "?"} °C, ${watt?.let { "%.1f".format(it) } ?: "?"} W"
                    val applied = if (byFps) {
                        // Step 0 hands the game back its own limit (or none), not the governor's base.
                        val own = RootGameProfiles.applied?.fpsLimit ?: 0
                        val limit = if (next == 0) own else ladder[(next - 1).coerceAtMost(ladder.lastIndex)]
                        if (LiveFpsLimit.set(context, limit)) {
                            fpsLimit = if (next == 0) 0 else limit
                            if (next == 0) "frame limit released" else "frames <= $limit"
                        } else {
                            useFps = false
                            null
                        }
                    } else null
                    val text = applied ?: run {
                        val gpuStep = next.coerceAtMost(GPU_STEPS.lastIndex)
                        val result = RootGameProfiles.setThermalCap(context, GPU_STEPS[gpuStep])
                        (if (GPU_STEPS[gpuStep] == 0) "no extra GPU limit" else "gpu <= ${GPU_STEPS[gpuStep]} MHz") + (result?.let { " ($it)" } ?: "")
                    }
                    step = next
                    lastChange = now
                    report("governor: $why -> $text")
                }
            }
            try { Thread.sleep(POLL_MS) } catch (_: InterruptedException) { break }
        }
        // No reset here: on session end it raced the restore and re-applied the profile (fan included);
        // whoever stops the governor mid-session clears its limits itself.
        Log.i(TAG, "stopped")
    }
}

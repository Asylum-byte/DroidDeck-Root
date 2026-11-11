package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.core.RootShell
import java.io.File

object RootPerfProfile {
    private const val TAG = "RootPerfProfile"
    const val OFF = "off"
    const val EFFICIENT = "efficient"
    const val PERFORMANCE = "performance"

    private const val SNAPSHOT = "root-perf-snapshot"
    private const val CPUFREQ = "/sys/devices/system/cpu/cpufreq"
    private const val KGSL = "/sys/class/kgsl/kgsl-3d0"
    private const val EFFICIENT_SHARE = 0.75
    val GPU_CAPS = listOf(0, 1000, 900, 800, 700)
    val CPU_CAPS = listOf(0, 90, 80, 70)
    /** Extra limit for the fastest cluster only (the two prime cores), which burns the most per MHz. */
    val PRIME_CAPS = listOf(0, 80, 70, 60, 50)
    /** Power targets for the governor in tenths of a watt (0 = none). */
    val WATT_TARGETS = listOf(0, 45, 50, 55, 60, 70, 80)

    // Nubia/RedMagic kernels expose the built-in fan here; other devices simply lack the nodes.
    private const val FAN_ENABLE = "/sys/kernel/fan/fan_enable"
    private const val FAN_LEVEL = "/sys/kernel/fan/fan_speed_level"
    private const val FAN_TOP_LEVEL = "5"

    private val NODE = Regex("^(/sys|/dev/cpuset)/[A-Za-z0-9_./-]+$")
    /** Android's groups for apps that are not on screen; narrowing them keeps their work off the game's cores. */
    private val BACKGROUND_SETS = listOf("/dev/cpuset/background/cpus", "/dev/cpuset/system-background/cpus")

    data class Settings(val profile: String, val fan: Boolean, val gpuCapMhz: Int, val cpuCapPercent: Int, val primeCapPercent: Int = 0,
                        val quietBackground: Boolean = false) {
        val off: Boolean get() = profile == OFF && !fan && gpuCapMhz <= 0 && cpuCapPercent <= 0 && primeCapPercent <= 0 && !quietBackground
    }

    fun globalSettings(context: Context) = Settings(
        SessionPrefs.rootPerfProfile(context), SessionPrefs.rootFanMax(context),
        SessionPrefs.rootGpuCapMhz(context), SessionPrefs.rootCpuCapPercent(context),
        SessionPrefs.rootPrimeCapPercent(context), SessionPrefs.rootQuietBackground(context),
    )

    /** Swaps the running session over to other settings, undoing the previous ones first. */
    @Synchronized
    fun switchTo(context: Context, settings: Settings): String = start(context, settings)

    @Synchronized
    fun start(context: Context, settings: Settings = globalSettings(context)): String {
        val profile = settings.profile
        val fan = settings.fan
        val gpuCap = settings.gpuCapMhz
        val cpuCap = settings.cpuCapPercent
        stopGuard()
        if (File(context.filesDir, SNAPSHOT).exists() && RootShell.known()) restore(context)
        if (settings.off) return "root perf: off"
        if (!RootShell.available()) return "root perf: no root, left alone"
        restore(context)
        val probe = listOf("$KGSL/min_pwrlevel", "$KGSL/max_pwrlevel", "$KGSL/num_pwrlevels", "$KGSL/max_clock_mhz", FAN_ENABLE, FAN_LEVEL) +
            BACKGROUND_SETS
        val present = RootShell.run(listOf("for p in ${probe.joinToString(" ")}; do [ -e \$p ] && echo \$p; done"))
            .output.lines().map { it.trim() }.toSet()
        val gpuLevels = RootShell.run(listOf("cat $KGSL/num_pwrlevels 2>/dev/null")).lastLine.toLongOrNull()
        val gpuFreqs = RootShell.run(listOf("cat $KGSL/gpu_available_frequencies 2>/dev/null")).lastLine
            .split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
        val capLevel = if (gpuCap > 0) gpuCapLevel(gpuFreqs, gpuCap) else null
        if (gpuCap > 0 && capLevel == null) Log.w(TAG, "gpu cap $gpuCap MHz: no frequency table (${gpuFreqs.size} entries)")
        val writes = plan(profile, fan, present, gpuLevels, capLevel, cpuCap, settings.primeCapPercent).toMutableList()
        val capMhz = capLevel?.let { (gpuFreqs[it] / 1_000_000).toInt() } ?: gpuCap.takeIf { it > 0 }
        if (capMhz != null && "$KGSL/max_clock_mhz" in present) writes += "$KGSL/max_clock_mhz" to capMhz.toString()
        val quietCores = if (settings.quietBackground) backgroundCores() else null
        if (quietCores != null) BACKGROUND_SETS.filter { it in present }.forEach { writes += it to quietCores }
        if (writes.isEmpty()) return "root perf: nothing to change on this device"
        val snapshot = RootShell.run(writes.map { (path, _) -> "echo \"$path=$(cat $path 2>/dev/null)\"" })
        val saved = snapshot.output.lines().filter { line ->
            val path = line.substringBefore('=', "")
            NODE.matches(path) && line.substringAfter('=').isNotBlank()
        }.map { line ->
            // The fan always ends up off: "on" before the session is usually a leftover (a killed session,
            // the vendor game mode), and restoring it kept the fan running after DroidDeck was closed.
            if (line.substringBefore('=') == FAN_ENABLE) "$FAN_ENABLE=0" else line
        }
        File(context.filesDir, SNAPSHOT).writeText(saved.joinToString("\n"))
        ensureWatchdog(context)
        val applied = RootShell.run(writes.map { (path, value) -> "echo $value > $path" })
        if (capMhz != null) guard(context, capLevel?.takeIf { "$KGSL/max_pwrlevel" in present },
            capMhz.takeIf { "$KGSL/max_clock_mhz" in present })
        val caps = listOfNotNull(
            capMhz?.let { "gpu <= $it MHz" + if (capLevel == null) " (no level table, clock node only)" else "" },
            cpuCap.takeIf { it > 0 }?.let { "cpu <= $it %" },
            settings.primeCapPercent.takeIf { it > 0 }?.let { "prime <= $it %" },
            quietCores?.let { "background apps on cpu $it" },
        ).joinToString(", ")
        val summary = "root perf: $profile${if (fan) " + fan" else ""}${if (caps.isNotEmpty()) ", $caps" else ""}, ${writes.size} nodes"
        return if (applied.ok) summary else "$summary (some writes refused: ${applied.output.trim().take(200)})"
    }

    @Synchronized
    fun stop(context: Context) {
        stopGuard()
        restore(context)
    }

    @Volatile private var guardProcess: Process? = null
    @Volatile private var watchdogPid: Int = 0

    /**
     * Swiping the app away or killing it skips the normal restore, which left the fan at full speed and the
     * clock limits in place. A root shell outside the app's cgroup (so Android's kill doesn't take it along)
     * waits for the app process to die and then writes the snapshot back, just like stop() would.
     */
    private fun ensureWatchdog(context: Context) {
        val app = android.os.Process.myPid()
        if (watchdogPid == app) return
        val snap = File(context.filesDir, SNAPSHOT).absolutePath
        val inner = "echo \$\$ > /sys/fs/cgroup/cgroup.procs 2>/dev/null; " +
            "while [ -d /proc/$app ]; do sleep 3; done; " +
            "[ -f $snap ] && { tac $snap | while IFS== read -r p v; do case \"\$p\" in /sys/*|/dev/cpuset/*) echo \"\$v\" > \"\$p\";; esac; done; rm -f $snap; }"
        val result = RootShell.run(listOf("setsid sh -c '$inner' </dev/null >/dev/null 2>&1 &"))
        if (result.ok) watchdogPid = app
        Log.i(TAG, "watchdog: restore on app exit ${if (result.ok) "armed" else "failed (${result.output.trim().take(120)})"}")
    }

    /**
     * Vendor game modes rewrite the GPU limits while a game runs, so a root shell re-applies the cap
     * every couple of seconds. It only ever lowers the limit: a stricter thermal limit is left alone.
     */
    private fun guard(context: Context, level: Int?, mhz: Int?) {
        stopGuard()
        if (level == null && mhz == null) return
        val app = android.os.Process.myPid()
        val checks = buildList {
            if (level != null) add("v=$(cat $KGSL/max_pwrlevel); [ \"\$v\" -lt $level ] && echo $level > $KGSL/max_pwrlevel && echo \"guard: max_pwrlevel \$v -> $level\"")
            if (mhz != null) add("v=$(cat $KGSL/max_clock_mhz); [ \"\$v\" -gt $mhz ] && echo $mhz > $KGSL/max_clock_mhz && echo \"guard: max_clock_mhz \$v -> $mhz\"")
        }
        val script = "while [ -d /proc/$app ]; do ${checks.joinToString("; ")}; sleep 2; done\n"
        val process = try { ProcessBuilder("su").redirectErrorStream(true).start() } catch (e: Exception) {
            Log.w(TAG, "guard: no su", e); return
        }
        guardProcess = process
        runCatching { process.outputStream.write(script.toByteArray()); process.outputStream.flush() }
        Thread({
            var fixes = 0
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    fixes++
                    if (fixes <= 5 || fixes % 50 == 0) Log.i(TAG, "$line (#$fixes)")
                }
            }
        }, "root-gpu-guard").apply { isDaemon = true; start() }
        Log.i(TAG, "guard: holding gpu at level ${level ?: "-"} / ${mhz ?: "-"} MHz")
    }

    private fun stopGuard() {
        guardProcess?.let { p -> runCatching { p.outputStream.close() }; p.destroy() }
        guardProcess = null
    }

    /** The built-in fan right now: true/false, or null when the device has no fan node or no root. */
    fun fanOn(): Boolean? {
        if (!RootShell.known()) return null
        return when (RootShell.query("cat $FAN_ENABLE 2>/dev/null")?.trim()) {
            "1" -> true
            "0" -> false
            else -> null
        }
    }

    /** Switches the built-in fan by hand (full speed when on); returns the state read back. */
    @Synchronized
    fun setFan(on: Boolean): Boolean? {
        if (!RootShell.available()) return null
        val commands = if (on) listOf("echo 1 > $FAN_ENABLE", "[ -e $FAN_LEVEL ] && echo $FAN_TOP_LEVEL > $FAN_LEVEL")
        else listOf("echo 0 > $FAN_ENABLE")
        RootShell.run(commands, 5)
        return fanOn()
    }

    @Synchronized
    fun clearLeftover(context: Context) {
        if (File(context.filesDir, SNAPSHOT).exists() && RootShell.available()) restore(context)
    }

    private fun restore(context: Context) {
        val file = File(context.filesDir, SNAPSHOT)
        if (!file.exists()) return
        val lines = file.readLines().filter { it.isNotBlank() }
        val commands = lines.reversed().mapNotNull { line ->
            val path = line.substringBefore('=')
            val value = line.substringAfter('=').trim()
            if (!NODE.matches(path) || value.isEmpty() || value.any { it == '\'' || it == '\n' }) null
            else "echo '$value' > $path"
        }
        if (commands.isNotEmpty()) {
            val result = RootShell.run(commands)
            Log.i(TAG, "restored ${commands.size} nodes (exit ${result.exit})")
        }
        file.delete()
    }

    /** kgsl lists frequencies from the fastest down; the list index is the power level. */
    internal fun gpuCapLevel(freqsHz: List<Long>, capMhz: Int): Int? {
        if (freqsHz.size < 2) return null
        val limit = capMhz * 1_000_000L
        val level = freqsHz.indexOfFirst { it <= limit }
        return when {
            level < 0 -> freqsHz.lastIndex
            level == 0 -> null
            else -> level
        }
    }

    private fun plan(profile: String, fan: Boolean, present: Set<String>, gpuLevels: Long?,
                     gpuCapLevel: Int?, cpuCapPercent: Int, primeCapPercent: Int = 0): List<Pair<String, String>> {
        val writes = ArrayList<Pair<String, String>>()
        val policies = File(CPUFREQ).listFiles { f -> f.name.startsWith("policy") }?.sortedBy { it.name }.orEmpty()
        val cpuShare = when {
            cpuCapPercent > 0 -> cpuCapPercent / 100.0
            profile == EFFICIENT -> EFFICIENT_SHARE
            else -> null
        }
        val tops = policies.associateWith { readLong(File(it, "cpuinfo_max_freq")) }
        val primeTop = tops.values.filterNotNull().maxOrNull()
        // Only a real big/prime split counts: with one cluster the prime limit would just be a second CPU limit.
        val primePolicy = policies.takeIf { primeCapPercent > 0 && tops.values.filterNotNull().distinct().size > 1 }
            ?.firstOrNull { tops[it] == primeTop }
        val cpuCaps = policies.associateWith { p ->
            val top = tops[p]
            val share = listOfNotNull(cpuShare, (primeCapPercent / 100.0).takeIf { p == primePolicy }).minOrNull()
            if (top == null || share == null) null else nearestBelow(p, (top * share).toLong())
        }
        policies.forEach { p ->
            val top = readLong(File(p, "cpuinfo_max_freq")) ?: return@forEach
            val cap = cpuCaps[p]
            if (cap != null) writes += "${p.path}/scaling_max_freq" to cap.toString()
            if (profile == PERFORMANCE) writes += "${p.path}/scaling_min_freq" to (cap ?: top).toString()
        }
        val gpuMax = gpuCapLevel ?: if (profile == EFFICIENT && gpuLevels != null && gpuLevels > 2) 1 else null
        if (gpuMax != null && "$KGSL/max_pwrlevel" in present) writes += "$KGSL/max_pwrlevel" to gpuMax.toString()
        if (profile == PERFORMANCE && "$KGSL/min_pwrlevel" in present) writes += "$KGSL/min_pwrlevel" to (gpuMax ?: 0).toString()
        if (fan && FAN_ENABLE in present) {
            writes += FAN_ENABLE to "1"
            if (FAN_LEVEL in present) writes += FAN_LEVEL to FAN_TOP_LEVEL
        }
        return writes
    }

    /**
     * Two cores of the slower cluster for background apps, e.g. "0-1"; null when the device has a single
     * cluster or too few cores to spare.
     */
    private fun backgroundCores(): String? {
        val policies = File(CPUFREQ).listFiles { f -> f.name.startsWith("policy") }.orEmpty()
        if (policies.size < 2) return null
        val slow = policies.minByOrNull { readLong(File(it, "cpuinfo_max_freq")) ?: Long.MAX_VALUE } ?: return null
        val cpus = runCatching { File(slow, "related_cpus").readText().trim().split(Regex("\\s+")).mapNotNull { it.toIntOrNull() } }
            .getOrNull().orEmpty().sorted()
        if (cpus.size < 4) return null
        return "${cpus[0]}-${cpus[1]}".takeIf { cpus[1] == cpus[0] + 1 } ?: "${cpus[0]},${cpus[1]}"
    }

    private fun nearestBelow(policy: File, target: Long): Long? {
        val available = File(policy, "scaling_available_frequencies").takeIf { it.canRead() }?.let { f ->
            runCatching { f.readText().trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() } }.getOrNull()
        }.orEmpty()
        return available.filter { it <= target }.maxOrNull() ?: target.takeIf { available.isEmpty() }
    }

    private fun readLong(file: File): Long? = runCatching { file.readText().trim().toLong() }.getOrNull()
}

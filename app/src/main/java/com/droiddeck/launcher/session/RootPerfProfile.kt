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

    // Nubia/RedMagic kernels expose the built-in fan here; other devices simply lack the nodes.
    private const val FAN_ENABLE = "/sys/kernel/fan/fan_enable"
    private const val FAN_LEVEL = "/sys/kernel/fan/fan_speed_level"
    private const val FAN_TOP_LEVEL = "5"

    private val NODE = Regex("^/sys/[A-Za-z0-9_./-]+$")

    @Synchronized
    fun start(context: Context): String {
        val profile = SessionPrefs.rootPerfProfile(context)
        val fan = SessionPrefs.rootFanMax(context)
        val gpuCap = SessionPrefs.rootGpuCapMhz(context)
        val cpuCap = SessionPrefs.rootCpuCapPercent(context)
        if (profile == OFF && !fan && gpuCap <= 0 && cpuCap <= 0) return "root perf: off"
        if (!RootShell.available()) return "root perf: no root, left alone"
        restore(context)
        val probe = listOf("$KGSL/min_pwrlevel", "$KGSL/max_pwrlevel", "$KGSL/num_pwrlevels", FAN_ENABLE, FAN_LEVEL)
        val present = RootShell.run(listOf("for p in ${probe.joinToString(" ")}; do [ -e \$p ] && echo \$p; done"))
            .output.lines().map { it.trim() }.toSet()
        val gpuLevels = RootShell.run(listOf("cat $KGSL/num_pwrlevels 2>/dev/null")).lastLine.toLongOrNull()
        val gpuFreqs = RootShell.run(listOf("cat $KGSL/gpu_available_frequencies 2>/dev/null")).lastLine
            .split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
        val capLevel = if (gpuCap > 0) gpuCapLevel(gpuFreqs, gpuCap) else null
        if (gpuCap > 0 && capLevel == null) Log.w(TAG, "gpu cap $gpuCap MHz: no frequency table (${gpuFreqs.size} entries)")
        val writes = plan(profile, fan, present, gpuLevels, capLevel, cpuCap)
        if (writes.isEmpty()) return "root perf: nothing to change on this device"
        val snapshot = RootShell.run(writes.map { (path, _) -> "echo \"$path=$(cat $path 2>/dev/null)\"" })
        val saved = snapshot.output.lines().filter { line ->
            val path = line.substringBefore('=', "")
            NODE.matches(path) && line.substringAfter('=').isNotBlank()
        }
        File(context.filesDir, SNAPSHOT).writeText(saved.joinToString("\n"))
        val applied = RootShell.run(writes.map { (path, value) -> "echo $value > $path" })
        val caps = listOfNotNull(
            capLevel?.let { "gpu <= ${gpuFreqs[it] / 1_000_000} MHz" },
            cpuCap.takeIf { it > 0 }?.let { "cpu <= $it %" },
        ).joinToString(", ")
        val summary = "root perf: $profile${if (fan) " + fan" else ""}${if (caps.isNotEmpty()) ", $caps" else ""}, ${writes.size} nodes"
        return if (applied.ok) summary else "$summary (some writes refused: ${applied.output.trim().take(200)})"
    }

    @Synchronized
    fun stop(context: Context) {
        restore(context)
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
                     gpuCapLevel: Int?, cpuCapPercent: Int): List<Pair<String, String>> {
        val writes = ArrayList<Pair<String, String>>()
        val policies = File(CPUFREQ).listFiles { f -> f.name.startsWith("policy") }?.sortedBy { it.name }.orEmpty()
        val cpuShare = when {
            cpuCapPercent > 0 -> cpuCapPercent / 100.0
            profile == EFFICIENT -> EFFICIENT_SHARE
            else -> null
        }
        val cpuCaps = policies.associateWith { p ->
            val top = readLong(File(p, "cpuinfo_max_freq"))
            if (top == null || cpuShare == null) null else nearestBelow(p, (top * cpuShare).toLong())
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

    private fun nearestBelow(policy: File, target: Long): Long? {
        val available = File(policy, "scaling_available_frequencies").takeIf { it.canRead() }?.let { f ->
            runCatching { f.readText().trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() } }.getOrNull()
        }.orEmpty()
        return available.filter { it <= target }.maxOrNull() ?: target.takeIf { available.isEmpty() }
    }

    private fun readLong(file: File): Long? = runCatching { file.readText().trim().toLong() }.getOrNull()
}

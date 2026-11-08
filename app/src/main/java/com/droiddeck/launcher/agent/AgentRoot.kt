package com.droiddeck.launcher.agent

import android.content.Context
import com.droiddeck.launcher.session.RootGameBoost
import com.droiddeck.launcher.session.RootGameProfiles
import com.droiddeck.launcher.session.RootPerfProfile
import com.droiddeck.launcher.session.SessionPrefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Root performance settings for agents: the global values, the per-game profiles and the game list,
 * so a local assistant running as root can read and tune them. Changes apply from the next game start.
 */
internal object AgentRoot {
    private const val INHERIT = RootGameProfiles.INHERIT

    fun handle(context: Context, request: JSONObject): JSONObject = when (val op = request.optString("op", "status")) {
        "status" -> status(context)
        "games" -> games(context)
        "get" -> profile(context, scope(request))
        "set" -> {
            val scope = scope(request)
            val old = RootGameProfiles.get(context, scope)
            val next = RootGameProfiles.Profile(
                pick(request, "gpuCapMhz", RootPerfProfile.GPU_CAPS, old.gpuCapMhz),
                pick(request, "cpuCapPercent", RootPerfProfile.CPU_CAPS, old.cpuCapPercent),
                pick(request, "boost", RootGameBoost.LEVELS, old.boost),
                pick(request, "primeCapPercent", RootPerfProfile.PRIME_CAPS, old.primeCapPercent),
            )
            RootGameProfiles.set(context, scope, next)
            profile(context, scope)
        }
        "global" -> {
            request.optJSONObject("values")?.let { v ->
                if (v.has("gpuCapMhz")) SessionPrefs.setRootGpuCapMhz(context, checked(v.getInt("gpuCapMhz"), RootPerfProfile.GPU_CAPS))
                if (v.has("cpuCapPercent")) SessionPrefs.setRootCpuCapPercent(context, checked(v.getInt("cpuCapPercent"), RootPerfProfile.CPU_CAPS))
                if (v.has("primeCapPercent")) SessionPrefs.setRootPrimeCapPercent(context, checked(v.getInt("primeCapPercent"), RootPerfProfile.PRIME_CAPS))
                if (v.has("matchRefresh")) SessionPrefs.setMatchRefreshToGame(context, v.getBoolean("matchRefresh"))
                if (v.has("boost")) SessionPrefs.setRootGameBoost(context, checked(v.getInt("boost"), RootGameBoost.LEVELS))
                if (v.has("fan")) SessionPrefs.setRootFanMax(context, v.getBoolean("fan"))
                if (v.has("profile")) SessionPrefs.setRootPerfProfile(context,
                    v.getString("profile").takeIf { it in setOf(RootPerfProfile.OFF, RootPerfProfile.EFFICIENT, RootPerfProfile.PERFORMANCE) }
                        ?: throw AgentException("INVALID_REQUEST", "profile must be off, efficient or performance"))
                if (v.has("chroot")) SessionPrefs.setRootChroot(context, v.getBoolean("chroot"))
                if (v.has("softThrottle")) {
                    SessionPrefs.setRootSoftThrottle(context, v.getBoolean("softThrottle"))
                    if (com.droiddeck.launcher.session.SessionState.running) {
                        if (v.getBoolean("softThrottle")) com.droiddeck.launcher.session.RootSoftThrottle.start(context) {}
                        else { com.droiddeck.launcher.session.RootSoftThrottle.stop(); RootGameProfiles.setThermalCap(context, 0) }
                    }
                }
            }
            status(context)
        }
        "frames" -> frames()
        "dx" -> directX(context, request)
        "fan" -> {
            val on = if (request.has("on")) RootPerfProfile.setFan(request.getBoolean("on")) else RootPerfProfile.fanOn()
            JSONObject().put("fanOn", on ?: JSONObject.NULL)
        }
        "thermal" -> thermal(context, request)
        "env" -> env(context, request)
        "trial" -> {
            val scope = RootGameProfiles.runningScope(context) ?: throw AgentException("NO_GAME", "No game is running")
            val base = RootGameProfiles.effective(context, scope)
            val next = RootGameProfiles.Effective(
                base.perf.copy(
                    gpuCapMhz = if (request.has("gpuCapMhz")) checked(request.getInt("gpuCapMhz"), RootPerfProfile.GPU_CAPS) else base.perf.gpuCapMhz,
                    cpuCapPercent = if (request.has("cpuCapPercent")) checked(request.getInt("cpuCapPercent"), RootPerfProfile.CPU_CAPS) else base.perf.cpuCapPercent,
                    primeCapPercent = if (request.has("primeCapPercent")) checked(request.getInt("primeCapPercent"), RootPerfProfile.PRIME_CAPS) else base.perf.primeCapPercent,
                ),
                if (request.has("boost")) checked(request.getInt("boost"), RootGameBoost.LEVELS) else base.boost,
            )
            JSONObject().put("scope", scope).put("applied", RootGameProfiles.applyNow(context, next))
        }
        "trialEnd" -> {
            val scope = RootGameProfiles.runningScope(context)
            JSONObject().put("scope", scope ?: JSONObject.NULL)
                .put("applied", RootGameProfiles.applyNow(context, RootGameProfiles.effective(context, scope)))
        }
        else -> throw AgentException("INVALID_REQUEST", "root op '$op' is not status, games, get, set, global, frames, thermal, env, trial or trialEnd")
    }.put("ok", true).put("command", "root")

    private fun frames(): JSONObject {
        val stats = com.droiddeck.launcher.hud.LiveFrames.stats ?: return JSONObject().put("available", false)
        val now = System.nanoTime()
        val graph = FloatArray(com.droiddeck.launcher.hud.FrameStats.GRAPH_FRAMES).also { stats.graph(it) }
        val lows = stats.lows(now)
        return JSONObject()
            .put("available", true)
            .put("ageMs", (now - com.droiddeck.launcher.hud.LiveFrames.updatedNs) / 1_000_000)
            .put("engine", com.droiddeck.launcher.hud.LiveFrames.engine)
            .put("fps", stats.fps.toDouble())
            .put("frametimeMs", stats.frametimeMs().toDouble())
            .put("averageFps", stats.averageFps().toDouble())
            .put("low1Fps", lows.low1Fps.toDouble())
            .put("low01Fps", lows.low01Fps.toDouble())
            .put("frames", stats.count)
            .put("graphMs", JSONArray().apply { graph.forEach { put(it.toDouble()) } })
    }

    /** Session thermal logs: the list, and the CSV of one (latest by default). */
    private fun thermal(context: Context, request: JSONObject): JSONObject {
        val dir = com.droiddeck.launcher.session.RootThermalLog.dir(context)
        val files = dir.listFiles { f -> f.name.matches(Regex("[0-9]{10,16}\\.csv")) }?.sortedByDescending { it.name }.orEmpty()
        val wanted = request.optString("session").takeIf { it.matches(Regex("[0-9]{10,16}")) }
        val file = wanted?.let { w -> files.firstOrNull { it.name == "$w.csv" } } ?: files.firstOrNull()
        val current = com.droiddeck.launcher.session.RootThermalLog.currentFile
        val sessions = JSONArray()
        files.take(30).forEach { f ->
            sessions.put(JSONObject().put("id", f.nameWithoutExtension).put("bytes", f.length())
                .put("running", current?.name == f.name))
        }
        return JSONObject().put("sessions", sessions)
            .put("session", file?.nameWithoutExtension ?: JSONObject.NULL)
            .put("csv", file?.let { f -> f.readText().takeLast(400_000) } ?: "")
    }

    /** DirectX choice of a Steam game: read, or set with {"api": "auto"|"dx11"|"dx12"}. */
    private fun directX(context: Context, request: JSONObject): JSONObject {
        val scope = request.optString("scope")
        val appId = scope.toIntOrNull()?.takeIf { it > 0 && scope.matches(Regex("[1-9][0-9]{0,9}")) }
            ?: throw AgentException("INVALID_REQUEST", "scope must be a Steam app id")
        val dx = com.droiddeck.launcher.frontend.DirectXChoice
        val result = JSONObject()
        if (request.has("api")) {
            val api = when (request.getString("api").lowercase()) {
                "auto" -> com.droiddeck.launcher.frontend.DirectXChoice.Api.AUTO
                "dx11", "11" -> com.droiddeck.launcher.frontend.DirectXChoice.Api.DX11
                "dx12", "12" -> com.droiddeck.launcher.frontend.DirectXChoice.Api.DX12
                else -> throw AgentException("INVALID_REQUEST", "api must be auto, dx11 or dx12")
            }
            result.put("saved", dx.set(context, appId, api).name.lowercase())
        }
        val options = com.droiddeck.launcher.frontend.SteamLaunchOptions.current(context, appId)
        return result.put("api", options?.let { dx.of(it).name.lowercase() } ?: JSONObject.NULL)
            .put("launchOptions", options ?: JSONObject.NULL)
    }

    private const val BACKUPS = 15

    /** FEX preset, sync backend and variables of one game; every change keeps a copy of the file first. */
    private fun env(context: Context, request: JSONObject): JSONObject {
        val scope = scope(request)
        val store = com.droiddeck.launcher.session.GameEnvironmentStore
        val ge = com.droiddeck.launcher.core.GameEnvironment
        val changes = request.has("fexPreset") || request.has("sync") || request.has("vars")
        if (changes) {
            backup(context)
            var config = store.read(context)
            if (request.has("fexPreset")) {
                val id = if (request.isNull("fexPreset")) null else request.getString("fexPreset").uppercase()
                if (id != null && com.droiddeck.launcher.core.FexPreset.all.none { it.id == id })
                    throw AgentException("INVALID_REQUEST", "unknown FEX preset $id")
                config = ge.withGameFexPreset(config, scope, id?.ifEmpty { null })
            }
            if (request.has("sync")) {
                val backend = if (request.isNull("sync")) null else request.getString("sync")
                if (backend != null && backend !in ge.syncBackends) throw AgentException("INVALID_REQUEST", "sync must be one of ${ge.syncBackends}")
                config = ge.withGameSync(config, scope, backend)
            }
            request.optJSONObject("vars")?.let { vars ->
                vars.keys().forEach { name ->
                    if (!ge.validName(name) || !ge.supported(name)) throw AgentException("INVALID_REQUEST", "variable $name is not allowed")
                    val value = if (vars.isNull(name)) null else vars.getString(name)
                    if (value != null && !ge.validValue(value)) throw AgentException("INVALID_REQUEST", "value of $name is not allowed")
                    config = ge.withScopeValue(config, scope, name, value)
                }
            }
            store.save(context, config)
        }
        val config = store.read(context)
        val presetKeys = com.droiddeck.launcher.core.FexPreset.environmentKeys
        val vars = JSONObject()
        config.games[scope].orEmpty().filterKeys { it !in presetKeys }.forEach { (k, v) -> vars.put(k, v ?: JSONObject.NULL) }
        val shared = JSONObject()
        (ge.defaults(com.droiddeck.launcher.session.SessionPrefs.fexPreset(context),
            com.droiddeck.launcher.session.SessionPrefs.forceSsbs(context)) + config.shared)
            .forEach { (k, v) -> shared.put(k, v ?: JSONObject.NULL) }
        return JSONObject().put("scope", scope)
            .put("shared", shared)
            .put("globalFexPreset", com.droiddeck.launcher.session.SessionPrefs.fexPreset(context))
            .put("globalSync", com.droiddeck.launcher.session.SessionPrefs.syncBackend(context))
            .put("fexPreset", runCatching { ge.gameFexPreset(config, scope) }.getOrNull() ?: JSONObject.NULL)
            .put("sync", runCatching { ge.gameSync(config, scope) }.getOrNull() ?: JSONObject.NULL)
            .put("vars", vars)
            .put("fexPresets", JSONArray(com.droiddeck.launcher.core.FexPreset.all.map { it.id }.filter { it.isNotEmpty() }))
            .put("syncBackends", JSONArray(ge.syncBackends))
    }

    private fun backup(context: Context) {
        val source = File(context.filesDir, "game-environment.json").takeIf { it.isFile } ?: return
        val dir = File(context.filesDir, "agent-backups").apply { mkdirs() }
        source.copyTo(File(dir, "game-environment-${System.currentTimeMillis()}.json"), overwrite = true)
        dir.listFiles()?.sortedByDescending { it.name }?.drop(BACKUPS)?.forEach { it.delete() }
    }

    private fun status(context: Context): JSONObject {
        val g = RootPerfProfile.globalSettings(context)
        val running = RootGameProfiles.runningScope(context)
        return JSONObject()
            .put("global", JSONObject()
                .put("profile", g.profile).put("fan", g.fan).put("gpuCapMhz", g.gpuCapMhz)
                .put("cpuCapPercent", g.cpuCapPercent).put("primeCapPercent", g.primeCapPercent)
                .put("boost", SessionPrefs.rootGameBoost(context))
                .put("matchRefresh", SessionPrefs.matchRefreshToGame(context))
                .put("chroot", SessionPrefs.rootChroot(context))
                .put("softThrottle", SessionPrefs.rootSoftThrottle(context)))
            .put("thermalCapMhz", RootGameProfiles.thermalCapMhz)
            .put("fanOn", RootPerfProfile.fanOn() ?: JSONObject.NULL)
            .put("gameFpsLimit", com.droiddeck.launcher.session.SessionState.gameFpsLimit)
            .put("refreshHz", com.droiddeck.launcher.session.SessionState.refreshHz.toDouble())
            .put("runningGame", running ?: JSONObject.NULL)
            .put("options", JSONObject()
                .put("gpuCapMhz", JSONArray(RootPerfProfile.GPU_CAPS))
                .put("cpuCapPercent", JSONArray(RootPerfProfile.CPU_CAPS))
                .put("primeCapPercent", JSONArray(RootPerfProfile.PRIME_CAPS))
                .put("boost", JSONArray(RootGameBoost.LEVELS)))
    }

    private fun games(context: Context): JSONObject {
        val cached = com.droiddeck.launcher.frontend.LibraryCache.snapshot(context).first
        val list = JSONArray()
        val env = runCatching { com.droiddeck.launcher.session.GameEnvironmentStore.read(context) }.getOrNull()
        cached.distinctBy { it.profileKey }.forEach { game ->
            val p = RootGameProfiles.get(context, game.profileKey)
            // gameId is what the frontend launches (64-bit for shortcuts); noSteam games run without the Steam client.
            list.put(JSONObject().put("scope", game.profileKey).put("appId", game.appId).put("name", game.name)
                .put("gameId", game.gameIdString)
                .put("noSteam", env != null && com.droiddeck.launcher.session.NoSteamLaunch.enabled(env, game))
                .put("profile", encode(p)))
        }
        return JSONObject().put("games", list)
    }

    private fun profile(context: Context, scope: String): JSONObject {
        val effective = RootGameProfiles.effective(context, scope)
        return JSONObject().put("scope", scope).put("profile", encode(RootGameProfiles.get(context, scope)))
            .put("effective", JSONObject().put("gpuCapMhz", effective.perf.gpuCapMhz)
                .put("cpuCapPercent", effective.perf.cpuCapPercent).put("primeCapPercent", effective.perf.primeCapPercent)
                .put("boost", effective.boost))
    }

    private fun encode(p: RootGameProfiles.Profile) = JSONObject()
        .put("gpuCapMhz", p.gpuCapMhz.takeIf { it != INHERIT } ?: JSONObject.NULL)
        .put("cpuCapPercent", p.cpuCapPercent.takeIf { it != INHERIT } ?: JSONObject.NULL)
        .put("boost", p.boost.takeIf { it != INHERIT } ?: JSONObject.NULL)
        .put("primeCapPercent", p.primeCapPercent.takeIf { it != INHERIT } ?: JSONObject.NULL)

    private fun scope(request: JSONObject): String = request.optString("scope").takeIf { it.matches(Regex("[1-9][0-9]{0,9}")) }
        ?: throw AgentException("INVALID_REQUEST", "scope must be the game's profile key (decimal)")

    /** Absent keeps the old value, null or "global" falls back to the global setting. */
    private fun pick(request: JSONObject, key: String, allowed: List<Int>, old: Int): Int = when {
        !request.has(key) -> old
        request.isNull(key) || request.optString(key) == "global" -> INHERIT
        else -> checked(request.getInt(key), allowed)
    }

    private fun checked(value: Int, allowed: List<Int>): Int =
        value.takeIf { it in allowed } ?: throw AgentException("INVALID_REQUEST", "$value is not one of $allowed")
}

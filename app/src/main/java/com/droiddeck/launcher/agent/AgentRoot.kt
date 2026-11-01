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
            )
            RootGameProfiles.set(context, scope, next)
            profile(context, scope)
        }
        "global" -> {
            request.optJSONObject("values")?.let { v ->
                if (v.has("gpuCapMhz")) SessionPrefs.setRootGpuCapMhz(context, checked(v.getInt("gpuCapMhz"), RootPerfProfile.GPU_CAPS))
                if (v.has("cpuCapPercent")) SessionPrefs.setRootCpuCapPercent(context, checked(v.getInt("cpuCapPercent"), RootPerfProfile.CPU_CAPS))
                if (v.has("boost")) SessionPrefs.setRootGameBoost(context, checked(v.getInt("boost"), RootGameBoost.LEVELS))
                if (v.has("fan")) SessionPrefs.setRootFanMax(context, v.getBoolean("fan"))
                if (v.has("profile")) SessionPrefs.setRootPerfProfile(context,
                    v.getString("profile").takeIf { it in setOf(RootPerfProfile.OFF, RootPerfProfile.EFFICIENT, RootPerfProfile.PERFORMANCE) }
                        ?: throw AgentException("INVALID_REQUEST", "profile must be off, efficient or performance"))
                if (v.has("chroot")) SessionPrefs.setRootChroot(context, v.getBoolean("chroot"))
            }
            status(context)
        }
        else -> throw AgentException("INVALID_REQUEST", "root op '$op' is not status, games, get, set or global")
    }.put("ok", true).put("command", "root")

    private fun status(context: Context): JSONObject {
        val g = RootPerfProfile.globalSettings(context)
        val running = runCatching { File(context.filesDir, ".wayland-rt/droiddeck-game").readText().trim() }.getOrNull()
        return JSONObject()
            .put("global", JSONObject()
                .put("profile", g.profile).put("fan", g.fan).put("gpuCapMhz", g.gpuCapMhz)
                .put("cpuCapPercent", g.cpuCapPercent).put("boost", SessionPrefs.rootGameBoost(context))
                .put("chroot", SessionPrefs.rootChroot(context)))
            .put("runningGame", running ?: JSONObject.NULL)
            .put("options", JSONObject()
                .put("gpuCapMhz", JSONArray(RootPerfProfile.GPU_CAPS))
                .put("cpuCapPercent", JSONArray(RootPerfProfile.CPU_CAPS))
                .put("boost", JSONArray(RootGameBoost.LEVELS)))
    }

    private fun games(context: Context): JSONObject {
        val cached = com.droiddeck.launcher.frontend.LibraryCache.snapshot(context).first
        val list = JSONArray()
        cached.distinctBy { it.profileKey }.forEach { game ->
            val p = RootGameProfiles.get(context, game.profileKey)
            list.put(JSONObject().put("scope", game.profileKey).put("appId", game.appId).put("name", game.name)
                .put("profile", encode(p)))
        }
        return JSONObject().put("games", list)
    }

    private fun profile(context: Context, scope: String): JSONObject {
        val effective = RootGameProfiles.effective(context, scope)
        return JSONObject().put("scope", scope).put("profile", encode(RootGameProfiles.get(context, scope)))
            .put("effective", JSONObject().put("gpuCapMhz", effective.perf.gpuCapMhz)
                .put("cpuCapPercent", effective.perf.cpuCapPercent).put("boost", effective.boost))
    }

    private fun encode(p: RootGameProfiles.Profile) = JSONObject()
        .put("gpuCapMhz", p.gpuCapMhz.takeIf { it != INHERIT } ?: JSONObject.NULL)
        .put("cpuCapPercent", p.cpuCapPercent.takeIf { it != INHERIT } ?: JSONObject.NULL)
        .put("boost", p.boost.takeIf { it != INHERIT } ?: JSONObject.NULL)

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

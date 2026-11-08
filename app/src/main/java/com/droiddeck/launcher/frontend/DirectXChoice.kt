package com.droiddeck.launcher.frontend

import android.content.Context
import com.droiddeck.launcher.core.GameEnvironment
import com.droiddeck.launcher.session.GameEnvironmentStore

/**
 * Per-game DirectX choice for games that ship both renderers. Games pick their API from a launch argument,
 * and the spelling depends on the engine (Unreal: -dx11/-dx12, Unity: -force-d3d11/-force-d3d12), so both
 * spellings go into the Steam launch options; games ignore arguments they don't know. For DX11, Proton also
 * leaves out vkd3d (PROTON_NO_D3D12), which makes games that only probe for DX12 fall back to DX11.
 * Games that keep the renderer in their own settings file (RE Engine, for example) still need the in-game
 * option.
 */
object DirectXChoice {
    enum class Api { AUTO, DX11, DX12 }

    private val DX11 = listOf("-dx11", "-force-d3d11")
    private val DX12 = listOf("-dx12", "-force-d3d12")
    private val KNOWN = setOf("-dx11", "-dx12", "-d3d11", "-d3d12", "-force-d3d11", "-force-d3d12", "-force-d3d11-no-singlethreaded")
    private const val NO_D3D12 = "PROTON_NO_D3D12"

    fun of(options: String): Api {
        val tokens = options.split(Regex("\\s+")).map { it.lowercase() }
        return when {
            tokens.any { it == "-dx12" || it == "-d3d12" || it == "-force-d3d12" } -> Api.DX12
            tokens.any { it == "-dx11" || it == "-d3d11" || it.startsWith("-force-d3d11") } -> Api.DX11
            else -> Api.AUTO
        }
    }

    /** The launch options with the DirectX arguments replaced; everything else stays as written. */
    fun withApi(options: String, api: Api): String {
        val kept = options.trim().split(Regex("\\s+")).filter { it.isNotEmpty() && it.lowercase() !in KNOWN }
        val added = when (api) { Api.AUTO -> emptyList(); Api.DX11 -> DX11; Api.DX12 -> DX12 }
        // Arguments before %command% would be run as the command; with it they go after, as game arguments.
        return (kept + added).joinToString(" ")
    }

    /** Saves the choice into Steam's launch options and the game's environment. */
    fun set(context: Context, appId: Int, api: Api): SteamLaunchOptions.Saved {
        val before = SteamLaunchOptions.current(context, appId) ?: return SteamLaunchOptions.Saved.NO
        val after = withApi(before, api)
        val saved = if (after.trim() == before.trim()) SteamLaunchOptions.Saved.YES
        else SteamLaunchOptions.set(context, appId, after, before)
        if (saved == SteamLaunchOptions.Saved.YES) setNoD3d12(context, appId.toString(), api == Api.DX11)
        return saved
    }

    /** An added game's launch options live in DroidDeck's own settings, keyed by its folder. */
    fun ofAdded(context: Context, folder: String): Api =
        of(com.droiddeck.launcher.session.SessionPrefs.addedGameLaunch(context, folder))

    fun setAdded(context: Context, folder: String, scope: String, api: Api): Boolean {
        val before = com.droiddeck.launcher.session.SessionPrefs.addedGameLaunch(context, folder)
        val after = withApi(before, api)
        if (after.trim() != before.trim()) AddedGameEdits.setLaunchOptions(context, folder, after) ?: return false
        setNoD3d12(context, scope, api == Api.DX11)
        return true
    }

    /** Agent entry: a Steam game by app id, or an added game by its profile key. Null when unknown. */
    fun forScope(context: Context, scope: String): Pair<Api, String>? {
        added(context, scope)?.let { folder ->
            val options = com.droiddeck.launcher.session.SessionPrefs.addedGameLaunch(context, folder)
            return of(options) to options
        }
        val appId = scope.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val options = SteamLaunchOptions.current(context, appId) ?: return null
        return of(options) to options
    }

    fun setScope(context: Context, scope: String, api: Api): SteamLaunchOptions.Saved {
        added(context, scope)?.let { folder ->
            return if (setAdded(context, folder, scope, api)) SteamLaunchOptions.Saved.YES else SteamLaunchOptions.Saved.NO
        }
        val appId = scope.toIntOrNull()?.takeIf { it > 0 } ?: return SteamLaunchOptions.Saved.NO
        return set(context, appId, api)
    }

    private fun added(context: Context, scope: String): String? = runCatching {
        LibraryCache.snapshot(context).first.firstOrNull { it.library == Library.ADDED && it.profileKey == scope }?.gameFiles?.path
    }.getOrNull()

    @Synchronized
    private fun setNoD3d12(context: Context, scope: String, on: Boolean) {
        val config = GameEnvironmentStore.read(context)
        val current = config.games[scope]?.get(NO_D3D12)
        if ((current == "1") == on) return
        GameEnvironmentStore.save(context, GameEnvironment.withScopeValue(config, scope, NO_D3D12, if (on) "1" else null))
    }
}

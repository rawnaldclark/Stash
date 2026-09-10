package com.stash.core.data.discord

import android.util.Log
import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthService
import com.stash.core.auth.model.AuthState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DiscordRpc"

/**
 * Owns the single [DiscordRpcClient] for the connected account. Mirrors
 * [com.stash.core.data.lastfm.LastFmScrobbler]'s shape: a singleton that
 * observes session state and exposes one call for playback to invoke,
 * no-op when Discord isn't connected. Must be called once from
 * Application.onCreate, same as LastFmScrobbler.start().
 */
@Singleton
class DiscordRpcCoordinator @Inject constructor(
    private val tokenManager: TokenManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var client: DiscordRpcClient? = null

    private val _needsPresenceConsent = MutableStateFlow(false)
    val needsPresenceConsent: StateFlow<Boolean> = _needsPresenceConsent.asStateFlow()

    fun consentHandled() {
        _needsPresenceConsent.value = false
    }

    fun start() {
        scope.launch {
            tokenManager.discordAuthState.collect { state ->
                Log.d(TAG, "discordAuthState -> ${state::class.simpleName}")
                when (state) {
                    is AuthState.Connected -> {
                        if (client == null) {
                            val token = tokenManager.getDiscordUserToken()
                            if (token == null) {
                                Log.w(TAG, "discordAuthState is Connected but getDiscordUserToken() returned null — token store/authState mismatch")
                                return@collect
                            }
                            Log.i(TAG, "Building DiscordRpcClient (token length=${token.length})")
                            client = DiscordRpcClient(
                                userToken = token,
                                onUnauthorized = {
                                    Log.w(TAG, "Discord token rejected as unauthorized — clearing auth")
                                    scope.launch { tokenManager.clearAuth(AuthService.DISCORD) }
                                },
                                onNeedsConsent = {
                                    Log.w(TAG, "Presence needs one-time browser consent")
                                    _needsPresenceConsent.value = true
                                },
                            )
                        }
                    }
                    else -> {
                        if (client != null) Log.i(TAG, "Discord disconnected — clearing presence")
                        client?.clearNow()
                        client = null
                    }
                }
            }
        }
    }

    /**
    * Called on every track transition / play-pause / seek. No-op if not
    * connected. [isPlaying] false clears presence rather than showing a stale
    * "Listening to X" while paused.
    *
    * [album] may be blank (not every source populates it) — falls back to
    * omitting the third line rather than showing an empty one. [durationMs]
    * may be 0/unset right at a track transition before the player has fully
    * prepared the item — timestamps (and therefore the seekbar) are skipped
    * entirely in that case rather than showing a bogus/zero-length bar; the
    * very next update once duration resolves will add them.
    */
    fun updateNowPlaying(
        title: String,
        artist: String,
        album: String,
        albumArtUrl: String?,
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean,
    ) {
        val active = client
        if (active == null) {
            Log.d(TAG, "updateNowPlaying('$title') ignored — no active Discord client (not connected)")
            return
        }
        if (title.isBlank() || !isPlaying) {
            Log.d(TAG, "Clearing presence (title blank or paused)")
            active.requestActivity(null)
            return
        }
        Log.d(TAG, "Requesting activity: '$title' by '$artist'")
        active.requestActivity(
            DiscordActivity(
                applicationId = APPLICATION_ID,
                name = "Stash",
                platform = "android",
                type = DiscordActivityType.Listening.value,
                details = title,
                state = artist,
                assets = DiscordActivity.Assets(
                    largeImage = albumArtUrl ?: FALLBACK_ICON_URL,
                    largeText = album.takeIf { it.isNotBlank() },
                ),
                timestamps = if (durationMs > 0) {
                    val now = System.currentTimeMillis()
                    DiscordActivity.Timestamps(
                        start = now - positionMs,
                        end = now + (durationMs - positionMs),
                    )
                } else {
                    null
                },
            ),
        )
    }

    companion object {
        private const val APPLICATION_ID = "934292861724270632"

        private const val FALLBACK_ICON_URL =
            "https://raw.githubusercontent.com/rawnaldclark/Stash/refs/heads/master/app/src/main/res/drawable/ic_launcher_foreground.png"
    }
}
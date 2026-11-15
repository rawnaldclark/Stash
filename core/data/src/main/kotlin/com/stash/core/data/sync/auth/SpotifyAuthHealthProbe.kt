package com.stash.core.data.sync.auth

import android.util.Log
import com.stash.core.auth.spotify.SpotifyAccounts
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real HTTP-based Spotify auth probe (issue #117).
 *
 * `SpotifyAccounts.getSpotifyAccessToken()` is the right signal: it returns the
 * cached access token if still fresh (no network), otherwise refreshes via the
 * stored sp_dc cookie. Refresh fails — and the call returns `null` — when the
 * sp_dc is invalid or expired (Spotify returns an anonymous token, which
 * [com.stash.core.auth.spotify.SpotifyAuthManager.getAccessToken] maps to
 * `null`). A non-null result means auth is healthy.
 *
 * **Contract assumption:** the caller (`PlaylistFetchWorker.runAuthProbes`)
 * only invokes this probe when `SpotifyAccounts.spotifyAuthState is Connected`,
 * so `null` here cannot mean "not connected" — it can only mean
 * "expired / invalid sp_dc". The connection-state guard lives at the worker.
 *
 * Side benefit: when the cached token is stale, the probe refreshes it and
 * persists the new value via SpotifyAccounts. The about-to-run sync gets a
 * pre-warmed token for free.
 *
 * Conservative on exceptions: a network failure (DNS, timeout, etc.) returns
 * `false` so a flaky Wi-Fi connection doesn't false-positive the banner.
 * Real auth failures still get caught mid-sync by `DownloadFailureClassifier`
 * (Task 7) when individual track downloads return 401/403.
 *
 * Second side job: a working session is when Stash can learn whose account
 * it is, if that was never recorded ([com.stash.core.auth.spotify.SpotifyAccounts.rememberSpotifyAccount]).
 * The account-swap guard needs it later, when this session may be dead and a
 * "Reconnect" could be signed in as another account. Best-effort: it never
 * changes the verdict.
 */
@Singleton
class SpotifyAuthHealthProbe @Inject constructor(
    private val spotifyAccounts: SpotifyAccounts,
) : AuthHealthProbe {
    override val source: AuthSource = AuthSource.SPOTIFY

    override suspend fun isExpired(): Boolean {
        val expired = try {
            spotifyAccounts.getSpotifyAccessToken() == null
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: Throwable) {
            Log.w(TAG, "probe failed conservatively", e)
            return false
        }
        if (!expired) rememberAccount()
        return expired
    }

    private suspend fun rememberAccount() {
        try {
            spotifyAccounts.rememberSpotifyAccount()
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: Throwable) {
            Log.w(TAG, "couldn't record the Spotify account", e)
        }
    }

    private companion object {
        const val TAG = "SpotifyAuthProbe"
    }
}

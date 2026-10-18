package com.stash.app.ambient

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.stash.core.media.PlayerRepository
import com.stash.core.ui.ambient.AlbumColors
import com.stash.core.ui.ambient.AmbientPalette
import com.stash.core.ui.ambient.PaletteCache
import com.stash.core.ui.ambient.retryingLoad
import com.stash.core.ui.ambient.tintedWhilePlaying
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The album colour for the whole app, computed at the player level: the current song's cover read
 * into an [AmbientPalette] (the web's palette.ts maths), plus whether a song is playing. It does not
 * depend on Now Playing being open, so the mini player, the wordmark and the tab bar wear the same
 * colour from any screen. The scaffold publishes it through `ProvideAlbumChrome`.
 *
 * While a new cover is being read the previous palette stays (no flash of the default); a song with
 * no cover gets the Stash-purple default, a black-and-white one a neutral grey. Covers are read
 * through the shared [PaletteCache], so a cover heard before costs nothing. A cover that fails to load (offline,
 * a tunnel at the song change) shows the default and is read again a few times, each only after
 * the network comes back or a play/pause, so the chrome catches up without polling. Work runs only
 * while the UI collects [colors] (5 s grace across a configuration change); the last value is kept.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class AlbumColorSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playerRepository: PlayerRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The cover's palette, re-read only when the artwork itself changes (not on every player tick). */
    private val palette: Flow<AmbientPalette> = playerRepository.playerState
        .map { state -> state.currentTrack?.let { it.albumArtPath ?: it.albumArtUrl } }
        .distinctUntilChanged()
        .flatMapLatest { model ->
            if (model == null) {
                flowOf(AmbientPalette.Default)
            } else {
                retryingLoad(
                    load = { PaletteCache.load(context, model) },
                    fallback = AmbientPalette.Default,
                    signals = retrySignals(),
                    backoffMs = RETRY_BACKOFF_MS,
                )
            }
        }
        .distinctUntilChanged()

    /** The app's album colours; [AlbumColors.Idle] until the first cover is read. */
    val colors: StateFlow<AlbumColors> = combine(
        palette,
        playerRepository.playerState.tintedWhilePlaying(),
    ) { p, tinted -> AlbumColors(palette = p, tinted = tinted) }
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AlbumColors.Idle)

    /**
     * When a failed cover read is worth another go: the default network becoming available (it
     * also fires at once when one already is; the retry backoff spaces those out) or the user
     * pressing play/pause. Cold: each wait registers afresh and unregisters when done.
     */
    private fun retrySignals(): Flow<Unit> = merge(
        networkAvailable(),
        playerRepository.playerState.map { it.isPlaying }.distinctUntilChanged().drop(1).map { },
    )

    private fun networkAvailable(): Flow<Unit> = callbackFlow {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm == null) {
            awaitClose()
            return@callbackFlow
        }
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(Unit)
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Can't watch the network (e.g. the per-app callback limit): play/pause still retries.
            Log.w(TAG, "album colour: no network callback", e)
        }
        awaitClose { runCatching { cm.unregisterNetworkCallback(cb) } }
    }

    private companion object {
        const val TAG = "AlbumColorSource"
        const val STOP_TIMEOUT_MS = 5_000L

        /** Up to three more reads of a failed cover, each after a signal and this long. */
        val RETRY_BACKOFF_MS = listOf(3_000L, 10_000L, 30_000L)
    }
}

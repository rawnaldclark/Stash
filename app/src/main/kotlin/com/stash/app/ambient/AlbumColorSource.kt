package com.stash.app.ambient

import android.content.Context
import android.util.Log
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import com.stash.core.media.PlayerRepository
import com.stash.core.ui.ambient.AlbumColors
import com.stash.core.ui.ambient.AmbientPalette
import com.stash.core.ui.ambient.ambientPaletteFromBitmap
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
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The album colour for the whole app, computed at the player level: the current song's cover read
 * into an [AmbientPalette] (the web's palette.ts maths), plus whether a song is playing. It does not
 * depend on Now Playing being open, so the mini player, the wordmark and the tab bar wear the same
 * colour from any screen. The scaffold publishes it through `ProvideAlbumChrome`.
 *
 * While a new cover is being read the previous palette stays (no flash of the default); a song with
 * no cover, or a greyscale one, gets the Stash-purple default. Work runs only while the UI collects
 * [colors] (5 s grace across a configuration change); the last value is kept in between.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class AlbumColorSource @Inject constructor(
    @ApplicationContext private val context: Context,
    playerRepository: PlayerRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The cover's palette, re-read only when the artwork itself changes (not on every player tick). */
    private val palette: Flow<AmbientPalette> = playerRepository.playerState
        .map { state -> state.currentTrack?.let { it.albumArtPath ?: it.albumArtUrl } }
        .distinctUntilChanged()
        .mapLatest { model -> if (model == null) AmbientPalette.Default else paletteOf(model) }
        .distinctUntilChanged()

    /** The app's album colours; [AlbumColors.Idle] until the first cover is read. */
    val colors: StateFlow<AlbumColors> = combine(
        palette,
        playerRepository.playerState.tintedWhilePlaying(),
    ) { p, tinted -> AlbumColors(palette = p, tinted = tinted) }
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AlbumColors.Idle)

    /**
     * Decodes [model] small and in software (pixels must be readable) through the app's Coil
     * loader, so a cover already on disk or in memory costs no network, then derives the palette.
     */
    private suspend fun paletteOf(model: String): AmbientPalette {
        val request = ImageRequest.Builder(context)
            .data(model)
            .size(Size(DECODE_PX, DECODE_PX))
            .allowHardware(false)
            .build()
        return try {
            val result = SingletonImageLoader.get(context).execute(request)
            if (result !is SuccessResult) return AmbientPalette.Default
            val bitmap = result.image.toBitmap()
            withContext(Dispatchers.Default) { ambientPaletteFromBitmap(bitmap, key = "art:$model") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "album colour: couldn't read the cover", e)
            AmbientPalette.Default
        }
    }

    private companion object {
        const val TAG = "AlbumColorSource"

        /**
         * Decode size before the filtered scale to the web's 32x32: big enough that the scale
         * averages neighbouring pixels (as the browser's canvas draw does), small enough to be cheap.
         */
        const val DECODE_PX = 128
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

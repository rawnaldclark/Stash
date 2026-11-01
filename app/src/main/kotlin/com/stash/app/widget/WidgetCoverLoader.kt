package com.stash.app.widget

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.graphics.toArgb
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.size.Scale
import coil3.toBitmap
import com.stash.core.ui.ambient.AccentRoles
import com.stash.core.ui.ambient.Ground
import com.stash.core.ui.ambient.ambientPaletteFromBitmap
import com.stash.core.ui.ambient.brandRoles
import com.stash.core.ui.ambient.roles
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * The widget's colours for one song, as ARGB ints: the album accent (the play button) and the ink
 * on it, worked out for the light and the dark theme with the app's own contrast rules (the same
 * roles as LocalAlbumChrome, so the widget's play button matches the mini player's).
 */
@Serializable
data class WidgetAccents(
    val accentLight: Int,
    val onAccentLight: Int,
    val accentDark: Int,
    val onAccentDark: Int,
) {
    companion object {
        /** Stash purple: no song, no readable cover. */
        val Brand: WidgetAccents = of(light = brandRoles(Ground.LIGHT), dark = brandRoles(Ground.DARK))

        fun of(light: AccentRoles, dark: AccentRoles) = WidgetAccents(
            accentLight = light.accent.toArgb(),
            onAccentLight = light.onAccent.toArgb(),
            accentDark = dark.accent.toArgb(),
            onAccentDark = dark.onAccent.toArgb(),
        )
    }
}

/** A cover ready for the widget: a square software bitmap no bigger than [WidgetCoverLoader.COVER_PX]. */
class WidgetCover(val bitmap: Bitmap, val accents: WidgetAccents)

/**
 * Loads a song's cover for the widget through the app's Coil pipeline (its memory and disk cache,
 * the shared OkHttp client, the art-size interceptor), so a cover the app already showed costs no
 * network.
 *
 * The bitmap is capped at [COVER_PX] square and kept in software memory: it crosses Binder inside
 * the widget's RemoteViews (once per widget size), and a big or hardware bitmap there fails with
 * TransactionTooLargeException or not at all.
 */
@Singleton
class WidgetCoverLoader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** The cover at [url], or null when it can't be loaded within [TIMEOUT_MS]. Never throws but for cancellation. */
    suspend fun load(url: String): WidgetCover? = try {
        withTimeoutOrNull(TIMEOUT_MS) {
            val request = ImageRequest.Builder(context)
                .data(url)
                .size(COVER_PX)
                .scale(Scale.FILL)
                .precision(Precision.INEXACT)
                .allowHardware(false)
                .build()
            val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult
                ?: return@withTimeoutOrNull null
            val square = squareCrop(result.image.toBitmap(), COVER_PX)
            val palette = ambientPaletteFromBitmap(square, key = url)
            WidgetCover(square, WidgetAccents.of(light = palette.roles(Ground.LIGHT), dark = palette.roles(Ground.DARK)))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "cover load failed: ${e.javaClass.simpleName}")
        null
    }

    companion object {
        private const val TAG = "StashWidget"

        /**
         * The widget cover's edge. A 2×2 cover is about 150 dp; 256 px keeps it sharp enough on the
         * small 4×1 cover and under 256 KB per copy in the RemoteViews.
         */
        const val COVER_PX = 256

        private const val TIMEOUT_MS = 8_000L

        /** The centre square of [source], scaled down to at most [maxPx]; [source] itself when it already fits. */
        internal fun squareCrop(source: Bitmap, maxPx: Int): Bitmap {
            val side = min(source.width, source.height)
            val cropped = if (source.width == source.height) {
                source
            } else {
                Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
            }
            return if (side <= maxPx) cropped else Bitmap.createScaledBitmap(cropped, maxPx, maxPx, true)
        }
    }
}

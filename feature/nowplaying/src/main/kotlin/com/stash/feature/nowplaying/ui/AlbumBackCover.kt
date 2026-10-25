package com.stash.feature.nowplaying.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Precision

/** The archive's thumbnail is 1200px: no decode above it. */
private const val BACK_COVER_MAX_DECODE_PX = 1200

/**
 * The back cover's image request, one for the ViewModel's prefetch and the flip's draw, so the
 * draw finds the prefetched bitmap in memory. Decoded at the screen's short side, as the front is
 * ([rememberNpArtRequest]), read from the display so the ViewModel builds the same request.
 * Discogs' images are kept in memory only: its terms allow no copy older than six hours, and the
 * disk cache would keep one for as long as its headers say.
 */
internal fun backCoverRequest(context: Context, url: String): ImageRequest {
    val metrics = context.resources.displayMetrics
    val px = minOf(metrics.widthPixels, metrics.heightPixels).coerceIn(1, BACK_COVER_MAX_DECODE_PX)
    return ImageRequest.Builder(context)
        .data(url)
        .size(px)
        .precision(Precision.INEXACT)
        .apply { if (isDiscogsImage(url)) diskCachePolicy(CachePolicy.DISABLED) }
        .build()
}

private fun isDiscogsImage(url: String): Boolean {
    val host = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()
    return host == "discogs.com" || host.endsWith(".discogs.com")
}

/** A back cover in hand: its [request] (now cached) and its shape, width over height. */
internal class LoadedBackCover(val request: ImageRequest, val aspectRatio: Float)

/**
 * [url]'s back cover once its image has loaded, null until then and when it can't load. The art
 * flips only to a back that is ready: a tap never turns the cover over onto a blank.
 */
@Composable
internal fun rememberLoadedBackCover(url: String?): LoadedBackCover? {
    val context = LocalContext.current
    var loaded by remember(url) { mutableStateOf<LoadedBackCover?>(null) }
    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        val request = backCoverRequest(context, url)
        val image = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image
            ?: return@LaunchedEffect
        if (image.width > 0 && image.height > 0) {
            loaded = LoadedBackCover(request, image.width.toFloat() / image.height)
        }
    }
    return loaded
}

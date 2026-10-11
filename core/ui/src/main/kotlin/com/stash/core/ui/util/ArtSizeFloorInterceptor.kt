package com.stash.core.ui.util

import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import com.stash.core.common.ArtUrlSizer

/**
 * Makes sure a cover [ArtSizeMapper] sized up never draws FEWER pixels than the URL it replaced.
 *
 * Only Last.fm can do that: the stored `770x0` is the CDN's own 770px rendering, while the
 * original (`ar0`) the mapper asks for on a big surface can be as small as 350px. Measured on a
 * Pixel 5 Now Playing cover (948px), the CDN's 350 -> 770 resample then the phone's 770 -> 948
 * stretch looks cleaner than the phone stretching 350 -> 948 in one go. So when the sized-up
 * result comes back under the stored URL's size, this draws the stored URL instead (one extra
 * fetch of a small original, and the stored URL is usually on disk already from the rows).
 */
class ArtSizeFloorInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val result = chain.proceed()
        if (result !is SuccessResult) return result
        val data = chain.request.data as? String ?: return result
        if (chain.request.extras[ArtSizeMapper.SkipSizing] == true) return result
        val px = ArtSizeMapper.targetPx(chain.size) ?: return result
        val image = result.image
        if (!shouldDrawStored(data, px, maxOf(image.width, image.height))) return result
        val stored = chain.request.newBuilder()
            .apply { extras[ArtSizeMapper.SkipSizing] = true }
            .build()
        return chain.withRequest(stored).proceed()
    }

    companion object {
        /** Decoders can round a sampled size down by a pixel or two. */
        private const val ROUNDING_SLACK_PX = 2

        /**
         * True when the request for [data] at [targetPx] was sized up by [ArtSizeMapper] and the
         * result's long side, [resultPx], is smaller than what [data] itself delivers.
         */
        fun shouldDrawStored(data: String, targetPx: Int, resultPx: Int): Boolean {
            val floor = ArtUrlSizer.storedPx(data) ?: return false
            if (ArtUrlSizer.forSize(data, targetPx) == data) return false // not sized up
            return resultPx < floor - ROUNDING_SLACK_PX
        }
    }
}

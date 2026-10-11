package com.stash.data.download.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Shrinks a downloaded cover to at most [AlbumArtCache.EMBED_PX] on its long side before it is
 * embedded, re-encoded as a JPEG.
 *
 * The bigger variants the embed asks for are unbounded originals for some hosts: Qobuz `_max`
 * measured up to 4000px / 2.2 MB, Last.fm `ar0` up to 3000px, Spotify's original 2000px. A file's
 * cover needs none of that. A cover already within the limit is passed through byte for byte, and
 * so are bytes the device can't decode (embedded as fetched, as before this existed).
 *
 * Memory: the decode samples by a power of two first (a 4000px cover decodes at 2000px, a 16 MB
 * bitmap), then scales to the limit. Downloads run in parallel, so one [Mutex] keeps those
 * transient bitmaps from stacking up (the low-RAM Samsungs of #238/#239). If the device runs out
 * of memory anyway, [fit] says so (null) and the caller embeds the smaller stored cover instead.
 */
@Singleton
open class EmbedArtScaler @Inject constructor() {

    /** [bytes] fitted within the limit, and its long side in pixels (0: unknown, not decodable). */
    class Fitted(val bytes: ByteArray, val longSidePx: Int)

    private val decodeLock = Mutex()

    /**
     * [bytes] fitted within [maxPx] on the long side; [bytes] as they are when within the limit
     * or not decodable here; or null when they couldn't be fitted for lack of memory.
     */
    open suspend fun fit(bytes: ByteArray, maxPx: Int): Fitted? {
        val (w, h) = readBounds(bytes)
        if (w <= 0 || h <= 0) return Fitted(bytes, longSidePx = 0)
        val longSide = maxOf(w, h)
        if (longSide <= maxPx) return Fitted(bytes, longSide)

        return decodeLock.withLock {
            var decoded: Bitmap? = null
            var scaled: Bitmap? = null
            try {
                decoded = decodeSampled(bytes, sampleSizeFor(longSide, maxPx))
                    ?: return@withLock Fitted(bytes, longSidePx = 0)
                val scale = maxPx.toDouble() / maxOf(decoded.width, decoded.height)
                scaled = if (scale < 1.0) {
                    Bitmap.createScaledBitmap(
                        decoded,
                        (decoded.width * scale).roundToInt().coerceAtLeast(1),
                        (decoded.height * scale).roundToInt().coerceAtLeast(1),
                        true,
                    )
                } else {
                    decoded
                }
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                Fitted(out.toByteArray(), maxOf(scaled.width, scaled.height))
            } catch (oom: OutOfMemoryError) {
                null
            } finally {
                if (scaled != null && scaled !== decoded) scaled.recycle()
                decoded?.recycle()
            }
        }
    }

    /** The encoded image's width and height, without decoding its pixels; zeros if unreadable. */
    protected open fun readBounds(bytes: ByteArray): Pair<Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bounds.outWidth to bounds.outHeight
    }

    /** The image decoded at 1/[sampleSize] of its size, or null if it can't be decoded. */
    protected open fun decodeSampled(bytes: ByteArray, sampleSize: Int): Bitmap? =
        BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        )

    companion object {
        /** Google serves its covers at `-l90`; matching it keeps a 1200px cover near 0.5 MB. */
        private const val JPEG_QUALITY = 90

        /**
         * The largest power-of-two sample that still leaves at least [maxPx] on the long side, so
         * the final scale is always a downscale: 4000 -> 2, 2400 -> 2, 2000 -> 1.
         */
        internal fun sampleSizeFor(longSide: Int, maxPx: Int): Int {
            var sample = 1
            while (longSide / (sample * 2) >= maxPx) sample *= 2
            return sample
        }
    }
}

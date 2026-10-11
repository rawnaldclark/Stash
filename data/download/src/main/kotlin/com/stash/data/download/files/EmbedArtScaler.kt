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
 * cover needs none of that. A cover already within the limit is passed through byte for byte.
 *
 * Memory: the decode samples by a power of two first (a 4000px cover decodes at 2000px, a 16 MB
 * bitmap), then scales to the limit. Downloads run in parallel, so one [Mutex] keeps those
 * transient bitmaps from stacking up (the low-RAM Samsungs of #238/#239).
 */
@Singleton
open class EmbedArtScaler @Inject constructor() {

    /** [bytes] fitted within the limit, and its long side in pixels. */
    class Fitted(val bytes: ByteArray, val longSidePx: Int)

    private val decodeLock = Mutex()

    /**
     * [bytes] fitted within [maxPx] on the long side, or null when they are not a decodable image.
     */
    open suspend fun fit(bytes: ByteArray, maxPx: Int): Fitted? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        val longSide = maxOf(w, h)
        if (longSide <= maxPx) return Fitted(bytes, longSide)

        return decodeLock.withLock {
            var sample = 1
            while (longSide / (sample * 2) >= maxPx) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: return@withLock null
            val scale = maxPx.toDouble() / maxOf(decoded.width, decoded.height)
            val scaled = if (scale < 1.0) {
                Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * scale).roundToInt().coerceAtLeast(1),
                    (decoded.height * scale).roundToInt().coerceAtLeast(1),
                    true,
                )
            } else {
                decoded
            }
            try {
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                Fitted(out.toByteArray(), maxOf(scaled.width, scaled.height))
            } finally {
                if (scaled !== decoded) scaled.recycle()
                decoded.recycle()
            }
        }
    }

    private companion object {
        /** Google serves its covers at `-l90`; matching it keeps a 1200px cover near 0.5 MB. */
        const val JPEG_QUALITY = 90
    }
}

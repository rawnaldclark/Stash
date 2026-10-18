package com.stash.core.ui.ambient

import android.content.Context
import android.util.Log
import androidx.collection.LruCache
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext

/**
 * The one place a cover becomes an [AmbientPalette]: the playing song's colour (AlbumColorSource)
 * and, later, a page's own colour read here, so the same cover always gives the same palette and is
 * only read once.
 *
 * A cover is decoded small and in software (pixels must be readable) through the app's Coil loader,
 * so a cover already on disk or in memory costs no network; the palette is then computed on one
 * background thread at a time (a burst of covers queues instead of fanning out over every core) and
 * kept in a small in-memory LRU keyed by the cover's model string. Only successful reads are kept:
 * a failed one (offline, a 404) is read again next time.
 */
object PaletteCache {
    private const val TAG = "PaletteCache"

    /** Palettes kept: the playing song, its neighbours and a few pages, at a few hundred bytes each. */
    private const val MAX_ENTRIES = 64

    /**
     * Decode size before the filtered scale to the web's 32x32: big enough that the scale averages
     * neighbouring pixels (as the browser's canvas draw does), small enough to be cheap.
     */
    private const val DECODE_PX = 128

    private val cache = LruCache<String, AmbientPalette>(MAX_ENTRIES)

    /** Extraction runs one at a time, off the main thread. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val extraction: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    /** The palette's cache key for a cover model (its identity for crossfades, too). */
    fun keyFor(model: String): String = "art:$model"

    /** The cached palette for [model], or null if it hasn't been read yet. Never blocks. */
    fun peek(model: String): AmbientPalette? = cache[model]

    /**
     * The palette of the cover at [model] (a file path or a URL): from the cache when it has been
     * read before, else decoded and read now. Null when the cover couldn't be loaded (worth a retry
     * later); a readable but colourless cover is the neutral palette, an image with nothing readable
     * in it the brand default, neither a failure.
     */
    suspend fun load(context: Context, model: String): AmbientPalette? {
        cache[model]?.let { return it }
        val request = ImageRequest.Builder(context)
            .data(model)
            .size(Size(DECODE_PX, DECODE_PX))
            .allowHardware(false)
            .build()
        return try {
            val result = SingletonImageLoader.get(context).execute(request)
            if (result !is SuccessResult) return null
            val bitmap = result.image.toBitmap()
            // Null (pixels unreadable) is a failure: not kept, read again next time.
            val palette = withContext(extraction) { readBitmapPalette(bitmap, key = keyFor(model)) }
                ?: return null
            cache.put(model, palette)
            palette
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "couldn't read the cover's colour", e)
            null
        }
    }
}

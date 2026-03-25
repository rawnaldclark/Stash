package com.stash.core.media.palette

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the three extracted palette colors for an album artwork image.
 *
 * All three properties fall back to [DefaultColors] constants when the palette
 * algorithm cannot produce a suitable swatch (e.g. a monochrome image).
 *
 * @property dominant  The primary color; sourced from the dark-muted swatch so it
 *                     works as a legible background on dark-themed screens.
 * @property vibrant   An eye-catching accent; prefers the pure vibrant swatch,
 *                     falling back to light-vibrant.
 * @property muted     A de-saturated complement; prefers the muted swatch, falling
 *                     back to dark-vibrant.
 */
data class AlbumColors(
    val dominant: Color = DefaultColors.DOMINANT,
    val vibrant: Color = DefaultColors.VIBRANT,
    val muted: Color = DefaultColors.MUTED,
) {
    /**
     * Brand-aligned fallback palette used when the Palette algorithm cannot find
     * a suitable swatch.  These match the purple/cyan accent system used elsewhere
     * in Stash.
     */
    object DefaultColors {
        /** Deep purple — used as a dark background accent. */
        val DOMINANT = Color(0xFF8B5CF6)

        /** Bright cyan — used as a highlight / primary action color. */
        val VIBRANT = Color(0xFF06B6D4)

        /** Dark navy — used as a subtle secondary background tone. */
        val MUTED = Color(0xFF1A1A2E)
    }
}

/**
 * Extracts dominant, vibrant, and muted colors from an album-art [Bitmap] using
 * the AndroidX Palette library.
 *
 * Results are cached in a 50-entry LRU map keyed on the caller-supplied
 * [cacheKey] (typically the album-art URL or local file path).  Cache operations
 * are protected by an intrinsic lock on the map instance so this class is safe to
 * call from multiple coroutines concurrently.
 *
 * All heavy work runs on [Dispatchers.Default]; the caller never needs to
 * switch dispatchers before calling [extractColors].
 */
@Singleton
class ColorExtractor @Inject constructor() {

    /**
     * LRU map capped at [MAX_CACHE_SIZE] entries.
     *
     * [LinkedHashMap] with `accessOrder = true` maintains insertion order sorted
     * by most-recent access.  [removeEldestEntry] evicts the least-recently-used
     * entry whenever the map exceeds the cap.
     */
    private val cache = object : LinkedHashMap<String, AlbumColors>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AlbumColors>?): Boolean =
            size > MAX_CACHE_SIZE
    }

    /**
     * Derives [AlbumColors] from the provided [bitmap].
     *
     * If [cacheKey] is non-null and a cached result exists for that key, the cached
     * value is returned immediately without re-running the Palette analysis.
     *
     * The bitmap is internally down-scaled to a 128×128 pixel area before palette
     * generation, which keeps the analysis fast even on high-resolution artwork.
     *
     * @param bitmap   The album-art image to analyse. Must not be recycled.
     * @param cacheKey An optional stable identifier for this image (e.g. URL or
     *                 local file path). Pass `null` to skip caching.
     * @return Extracted [AlbumColors], or [AlbumColors.DefaultColors] fallbacks if
     *         the palette algorithm produces no usable swatches.
     */
    suspend fun extractColors(bitmap: Bitmap, cacheKey: String? = null): AlbumColors {
        // Fast path: return cached result if available.
        if (cacheKey != null) {
            synchronized(cache) { cache[cacheKey] }?.let { return it }
        }

        return withContext(Dispatchers.Default) {
            // Resize to a small area before palette analysis to minimise CPU cost.
            val palette = Palette.from(bitmap)
                .resizeBitmapArea(PALETTE_RESIZE_AREA)
                .generate()

            val colors = AlbumColors(
                dominant = palette.darkMutedSwatch?.rgb
                    ?.let { Color(it) }
                    ?: AlbumColors.DefaultColors.DOMINANT,

                vibrant = (palette.vibrantSwatch?.rgb ?: palette.lightVibrantSwatch?.rgb)
                    ?.let { Color(it) }
                    ?: AlbumColors.DefaultColors.VIBRANT,

                muted = (palette.mutedSwatch?.rgb ?: palette.darkVibrantSwatch?.rgb)
                    ?.let { Color(it) }
                    ?: AlbumColors.DefaultColors.MUTED,
            )

            // Cache the result before returning.
            if (cacheKey != null) {
                synchronized(cache) { cache[cacheKey] = colors }
            }

            colors
        }
    }

    /**
     * Removes all entries from the color cache.
     *
     * Call this when memory pressure is detected (e.g. from
     * `Activity.onTrimMemory`) or when the user clears their library.
     */
    fun clearCache() {
        synchronized(cache) { cache.clear() }
    }

    companion object {
        /** Maximum number of palette results to keep in memory. */
        private const val MAX_CACHE_SIZE = 50

        /**
         * Target pixel area passed to [Palette.Builder.resizeBitmapArea].
         * 128×128 = 16 384 pixels — enough detail for accurate color extraction
         * without analysing full-resolution artwork.
         */
        private const val PALETTE_RESIZE_AREA = 128 * 128
    }
}

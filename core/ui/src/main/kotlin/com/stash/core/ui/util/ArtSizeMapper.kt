package com.stash.core.ui.util

import coil3.map.Mapper
import coil3.request.Options
import coil3.size.Dimension
import coil3.size.Size
import com.stash.core.common.ArtUrlSizer

/**
 * Asks each cover's host for a variant at least as big as the surface it is drawn on.
 *
 * Coil runs mappers after it has resolved the request's target size, and before it derives the
 * memory- and disk-cache keys, so [options] carries the real pixel size of THIS surface: about
 * 130px for a list row, the whole cover for Now Playing. [ArtUrlSizer.forSize] only ever rewrites
 * a URL that is smaller than that, so:
 *
 * - a row keeps exactly the URL it has today (and its cache entries);
 * - Now Playing asks Qobuz for `_max` instead of `_600`, YouTube Music for `=w1024` instead of
 *   `=w60`, and so on;
 * - the big variant has its own cache key, so a small bitmap decoded for a row can never stand in
 *   for the big cover, and the big bitmap is only decoded where it is drawn big.
 *
 * Registered once, in the app's ImageLoader, so every `AsyncImage` gets it with no call-site
 * change. Coil chains mappers, so the String this returns still goes through Coil's own
 * String -> Uri mapping.
 */
class ArtSizeMapper : Mapper<String, String> {
    override fun map(data: String, options: Options): String? {
        val px = targetPx(options.size) ?: return null
        val sized = ArtUrlSizer.forSize(data, px)
        // Null tells Coil "not mine": the data goes on unchanged.
        return sized?.takeIf { it != data }
    }

    companion object {
        /**
         * The long side of [size] in pixels, or null when the request has no real target: an
         * original-size request (nothing to size to), or one with neither side known.
         */
        fun targetPx(size: Size): Int? {
            val w = (size.width as? Dimension.Pixels)?.px
            val h = (size.height as? Dimension.Pixels)?.px
            return listOfNotNull(w, h).maxOrNull()?.takeIf { it > 0 }
        }
    }
}

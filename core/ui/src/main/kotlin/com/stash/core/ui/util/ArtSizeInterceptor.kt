package com.stash.core.ui.util

import coil3.Extras
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.size.Dimension
import coil3.size.Size
import com.stash.core.common.ArtUrlSizer
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Asks each cover's host for a variant at least as big as the request, and falls back to the
 * stored URL whenever that variant can't do better.
 *
 * Coil resolves a request's target size before its interceptors run, so [Interceptor.Chain.size]
 * is this request's size in pixels: about 130px for a list row, the cover's size (or the explicit
 * size it asked for) on Now Playing. [ArtUrlSizer.forSize] only rewrites a URL that is clearly
 * smaller than that, so:
 *
 * - a row keeps exactly the URL it has today, and its cache entries;
 * - Now Playing asks Qobuz for `_max` instead of `_600`, YouTube Music for `=w1024` instead of
 *   `=w60`, and so on;
 * - the big variant gets its own memory and disk-cache key (Coil keys on the request's data), so
 *   a small bitmap decoded for a row can never stand in for the big cover.
 *
 * **The stored URL is the floor.** The sized-up request is tried first; the original request,
 * with the stored URL, is drawn instead when:
 *
 * - the sized-up request FAILED, for any reason: offline (the stored URL is usually in the disk
 *   cache, from the mini player and the rows), a 5xx or a 403. Without this, Now Playing showed
 *   an empty tile where it used to show the cached cover;
 * - it took longer than [sizedAttemptBudgetMs]: a stalled or captive connection would otherwise
 *   hold it for the shared client's 30s timeouts (per read, so longer on a trickle) before the
 *   cached cover showed. A cancelled fetch writes nothing to disk, so on a link too slow for the
 *   big variant every view draws the stored URL, which is what it always drew before;
 * - it came back with FEWER pixels than the stored URL delivers ([ArtUrlSizer.storedPx]): a
 *   Last.fm original under 770px, which the CDN's own 770 rendering resamples better than a phone
 *   stretch does (measured on a Pixel 5).
 *
 * A cancellation of the whole load (scrolled away, track skipped) is not a failure: it
 * propagates, and nothing falls back.
 *
 * Registered once, in the app's ImageLoader, so every `AsyncImage` gets it with no call-site
 * change. A request can opt out with [SkipSizing]; one that sets its own memory or disk cache key
 * is left alone too, since both attempts would then share that one key.
 *
 * @param maxVariantPx the largest variant to pick, read per request: [ArtUrlSizer.DATA_SAVER_MAX_PX]
 *   while Android's Data Saver restricts a metered network (see [com.stash.core.common.DataSaverArtCap]).
 * @param sizedAttemptBudgetMs how long the sized-up attempt may take before the stored URL is
 *   drawn instead.
 */
class ArtSizeInterceptor(
    private val maxVariantPx: () -> Int = { ArtUrlSizer.NO_CAP },
    private val sizedAttemptBudgetMs: Long = SIZED_ATTEMPT_BUDGET_MS,
) : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val data = request.data as? String ?: return chain.proceed()
        if (request.extras[SkipSizing] == true) return chain.proceed()
        // An explicit cache key would be shared by both attempts: the stored fallback could then
        // be cached as (or served for) the big variant. Nobody sets one today; stay out if so.
        if (request.memoryCacheKey != null || request.diskCacheKey != null) return chain.proceed()
        val px = targetPx(chain.size) ?: return chain.proceed()
        val sized = ArtUrlSizer.forSize(data, px, maxVariantPx())
        if (sized == null || sized == data) return chain.proceed()

        // withTimeoutOrNull returns null only for its own timeout; a cancellation of this load
        // throws straight through it, so a skipped track never falls back.
        val result = withTimeoutOrNull(sizedAttemptBudgetMs) {
            chain.withRequest(request.newBuilder().data(sized).build()).proceed()
        }
        if (result != null && !shouldDrawStored(data, result)) return result
        // `chain` still carries the original request: the stored URL, keyed as the rows key it.
        return chain.proceed()
    }

    companion object {
        /**
         * Set on a request to draw its URL exactly as given, e.g. a blurred backdrop or a 32x32
         * palette sample that gains nothing from a bigger cover.
         */
        val SkipSizing = Extras.Key(default = false)

        /**
         * How long the bigger variant may take. ~6s is a 2 MB Qobuz `_max` at under 3 Mbit/s,
         * yet short enough that a stalled link falls back to the cached cover quickly.
         */
        const val SIZED_ATTEMPT_BUDGET_MS = 6_000L

        /** Decoders can round a sampled size down by a pixel or two. */
        private const val ROUNDING_SLACK_PX = 2

        /**
         * The long side of [size] in pixels, or null when the request has no real target: an
         * original-size request (nothing to size to), or one with neither side known.
         */
        fun targetPx(size: Size): Int? {
            val w = (size.width as? Dimension.Pixels)?.px
            val h = (size.height as? Dimension.Pixels)?.px
            return listOfNotNull(w, h).maxOrNull()?.takeIf { it > 0 }
        }

        /**
         * True when the sized-up [result] for stored URL [storedUrl] should be dropped for the
         * stored URL: it failed, or it delivered fewer pixels than the stored URL does.
         */
        fun shouldDrawStored(storedUrl: String, result: ImageResult): Boolean = when (result) {
            is ErrorResult -> true
            is SuccessResult -> {
                val floor = ArtUrlSizer.storedPx(storedUrl)
                floor != null && maxOf(result.image.width, result.image.height) < floor - ROUNDING_SLACK_PX
            }
        }
    }
}

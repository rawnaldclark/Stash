package com.stash.core.media.service

import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import com.stash.core.common.ArtUrlSizer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Loads the media session's artwork (the notification, the lock screen and Android Auto's
 * now-playing screen) at a sharp size, with the stored cover as the fallback.
 *
 * Stash only ever sets `artworkUri`, and that is the URL the library stored, which for many
 * sources is small: YouTube Music `=w60`/`=w544`, Qobuz `_600`, Last.fm `770x0`, YouTube
 * `hqdefault?sqp=` (400x225). The in-app covers already ask for a bigger variant per request
 * (`ArtSizeInterceptor`, Coil); this does the same for media3, which loads artwork on its own.
 * The URI in the metadata is left as stored: share links, the web handoff and the cache key all
 * keep seeing the URL they always did.
 *
 * For an http(s) artwork URI, [ArtUrlSizer.forSize] at [targetPx] is tried first and, as in the
 * app, the stored URL is loaded instead when that bigger variant:
 * - fails for any reason (offline, a 5xx, a 404 the OkHttp walk couldn't save), or
 * - takes longer than [budgetMs] (a stalled or captive link would otherwise leave the
 *   notification without art for the client's 30s timeouts), or
 * - comes back with fewer pixels than the stored URL ([ArtUrlSizer.storedPx]: a Last.fm original
 *   under its CDN's 770 rendering).
 *
 * Without the fallback, a missing big cover would leave the notification with no art at all.
 * Cancelling the returned future (media3 does on a track change) cancels the attempt and never
 * starts the fallback.
 *
 * Size: the platform downsamples artwork for the notification and lock screen, and the session
 * metadata has to cross Binder, so [targetPx] is 1024 and the delegate must cap its decode
 * (`DataSourceBitmapLoader.Builder.setMaximumOutputDimension`): a Qobuz `_max` is up to 4000px.
 *
 * Composition: inside the cache, so the cached bitmap (keyed by the stored URI) is the final one:
 * `SafeBitmapLoader(CacheBitmapLoader(SizedArtBitmapLoader(DataSourceBitmapLoader)))`.
 */
@UnstableApi
internal class SizedArtBitmapLoader(
    private val delegate: BitmapLoader,
    private val maxVariantPx: () -> Int = { ArtUrlSizer.NO_CAP },
    private val targetPx: Int = TARGET_PX,
    private val budgetMs: Long = BUDGET_MS,
    private val scheduler: ScheduledExecutorService = timeoutScheduler,
) : BitmapLoader by delegate {

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        val stored = uri.toString()
        val sized = ArtUrlSizer.forSize(stored, targetPx, maxVariantPx())
        if (sized == null || sized == stored) return delegate.loadBitmap(uri)

        val attempt = Futures.withTimeout(
            delegate.loadBitmap(Uri.parse(sized)), budgetMs, TimeUnit.MILLISECONDS, scheduler,
        )
        val floorPx = ArtUrlSizer.storedPx(stored)
        val checked = Futures.transformAsync(
            attempt,
            { bitmap ->
                if (floorPx != null && isSmallerThanStored(maxOf(bitmap.width, bitmap.height), floorPx)) {
                    delegate.loadBitmap(uri)
                } else {
                    Futures.immediateFuture(bitmap)
                }
            },
            MoreExecutors.directExecutor(),
        )
        return Futures.catchingAsync(
            checked,
            Throwable::class.java,
            { delegate.loadBitmap(uri) },
            MoreExecutors.directExecutor(),
        )
    }

    /**
     * True when a decoded bitmap of [decodedPx] proves the source was smaller than the stored
     * URL's [floorPx]. The delegate caps its decode at [targetPx] by power-of-two sampling only,
     * so a result above half the cap is ambiguous: 600px may be a 600px original or a 1200px one
     * sampled by 2. Only a result at or under half the cap is certainly the original's own size;
     * anything above it is kept rather than paying a refetch for every mid-size original.
     */
    private fun isSmallerThanStored(decodedPx: Int, floorPx: Int): Boolean =
        decodedPx < floorPx - ROUNDING_SLACK_PX && decodedPx <= targetPx / 2

    /**
     * Routed through [loadBitmap] (and [decodeBitmap] for embedded bytes), as BitmapLoader's
     * default does; delegating it would skip the sizing for the URI path, Stash's only one.
     */
    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? {
        metadata.artworkData?.let { return decodeBitmap(it) }
        return metadata.artworkUri?.let { loadBitmap(it) }
    }

    companion object {
        /** Long side of the session artwork: plenty for a notification and a lock screen. */
        const val TARGET_PX = 1024

        /** How long the bigger variant may take before the stored cover is loaded instead. */
        const val BUDGET_MS = 6_000L

        /** Decoders can round a sampled size down by a pixel or two. */
        private const val ROUNDING_SLACK_PX = 2

        /**
         * The threads the delegate DataSourceBitmapLoader fetches and decodes on. Two, not media3's
         * shared single-thread default: when the sized-up attempt stalls on a dead link, the
         * stored-URL fallback (and the next track's art) must not queue behind it until the
         * socket times out. The timeout cancels the stalled future, but a blocking read only
         * notices at its next timeout, so the second thread is what lets the fallback run now.
         */
        val loadExecutor: ListeningExecutorService by lazy {
            MoreExecutors.listeningDecorator(
                Executors.newFixedThreadPool(LOAD_THREADS) { r ->
                    Thread(r, "stash-art-load").apply { isDaemon = true }
                },
            )
        }

        private const val LOAD_THREADS = 2

        /** One daemon thread that only fires the [BUDGET_MS] timeouts. */
        private val timeoutScheduler: ScheduledExecutorService by lazy {
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "stash-art-timeout").apply { isDaemon = true }
            }
        }
    }
}

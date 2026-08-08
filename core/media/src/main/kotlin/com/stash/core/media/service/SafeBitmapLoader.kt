package com.stash.core.media.service

import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

/**
 * Wraps a [BitmapLoader] so every artwork bitmap handed to the platform
 * MediaSession is a fresh copy that nothing else holds a reference to.
 *
 * Fixes #387: on Android-15 AOSP-derived ROMs (LineageOS, Project Infinity X,
 * …) the app crashes with `IllegalArgumentException: cannot use a recycled
 * source in createBitmap` inside `MediaMetadata.Builder.scaleBitmap` during
 * `MediaSession.setMetadata`. The same stack was reported against four
 * unrelated media3 apps (ViTune, PipePipe, Stremio, Stash) and appeared on a
 * ROM upgrade with no app change, so the recycle is ROM-side lifecycle
 * enforcement on media3's shared cached artwork bitmap, not Stash code (Stash
 * only ever sets `artworkUri`, never a bitmap). A defensive copy is what
 * fixed it for the ViTune reporter.
 *
 * Composition matters — this must be the OUTERMOST loader, wrapping the cache:
 * `SafeBitmapLoader(CacheBitmapLoader(DataSourceBitmapLoader(ctx)))`. The
 * cache keeps the decoded original pristine (it's never handed to the
 * platform, so nothing recycles it) while every hand-out is a throwaway copy.
 * If the source ever does come back recycled, [harden] fails the future and
 * media3 publishes metadata without artwork — a degraded notification, never
 * a crash.
 *
 * `by delegate` forwards everything not overridden (e.g. supportsMimeType).
 * [loadBitmapFromMetadata] MUST be overridden explicitly: the default
 * implementation would route through the delegate's own decode/load and skip
 * the copy — and the URI path (Stash's only artwork path) goes through it.
 */
@UnstableApi
internal class SafeBitmapLoader(
    private val delegate: BitmapLoader,
) : BitmapLoader by delegate {

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        harden(delegate.decodeBitmap(data))

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
        harden(delegate.loadBitmap(uri))

    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? =
        delegate.loadBitmapFromMetadata(metadata)?.let(::harden)

    private fun harden(future: ListenableFuture<Bitmap>): ListenableFuture<Bitmap> =
        Futures.transform(
            future,
            { bitmap ->
                require(!bitmap.isRecycled) { "artwork bitmap already recycled" }
                bitmap.copy(Bitmap.Config.ARGB_8888, /* isMutable = */ false)
            },
            MoreExecutors.directExecutor(),
        )
}

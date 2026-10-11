package com.stash.core.media.service

import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.BitmapLoader
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.stash.core.common.ArtUrlSizer
import java.io.IOException
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [SizedArtBitmapLoader]: the session artwork (notification, lock screen, Android Auto's now
 * playing) asks for a bigger cover variant, and falls back to the stored URL when that fails,
 * stalls, or turns out smaller. Which URLs are loaded, in what order, and which bitmap wins.
 */
@RunWith(RobolectricTestRunner::class)
class SizedArtBitmapLoaderTest {

    private val qobuz600 = "https://static.qobuz.com/images/covers/25/40/0060254794025_600.jpg"
    private val qobuzMax = "https://static.qobuz.com/images/covers/25/40/0060254794025_max.jpg"
    private val lastFm770 = "https://lastfm-img.freetls.fastly.net/i/u/770x0/c9ebed7e766b45dabedaa8ee759b5181.jpg"
    private val lastFmOrig = "https://lastfm-img.freetls.fastly.net/i/u/ar0/c9ebed7e766b45dabedaa8ee759b5181.jpg"
    private val yt60 = "https://yt3.googleusercontent.com/abc=w60-h60-l90-rj"
    private val yt1024 = "https://yt3.googleusercontent.com/abc=w1024-h1024-l90-rj"

    private fun bitmap(px: Int) = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)

    /** Answers each URI with what [respond] gives, recording every URI loaded, in order. */
    private class FakeLoader(
        private val respond: (String) -> ListenableFuture<Bitmap>,
    ) : BitmapLoader {
        val loaded = mutableListOf<String>()
        val futures = mutableListOf<ListenableFuture<Bitmap>>()
        override fun supportsMimeType(mimeType: String) = true
        override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
            Futures.immediateFailedFuture(UnsupportedOperationException())
        override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
            loaded += uri.toString()
            return respond(uri.toString()).also { futures += it }
        }
    }

    private fun loader(
        fake: FakeLoader,
        cap: Int = ArtUrlSizer.NO_CAP,
        budgetMs: Long = SizedArtBitmapLoader.BUDGET_MS,
    ) = SizedArtBitmapLoader(fake, maxVariantPx = { cap }, budgetMs = budgetMs)

    private fun get(future: ListenableFuture<Bitmap>): Bitmap = future.get(5, TimeUnit.SECONDS)

    @Test
    fun `a small stored cover is loaded at the bigger variant`() {
        val big = bitmap(1024)
        val fake = FakeLoader { Futures.immediateFuture(if (it == yt1024) big else bitmap(60)) }
        val result = get(loader(fake).loadBitmap(Uri.parse(yt60)))
        assertThat(result).isSameInstanceAs(big)
        assertThat(fake.loaded).containsExactly(yt1024)
    }

    @Test
    fun `a stored cover big enough is loaded as-is, once`() {
        val fake = FakeLoader { Futures.immediateFuture(bitmap(1024)) }
        get(loader(fake).loadBitmap(Uri.parse(yt1024)))
        assertThat(fake.loaded).containsExactly(yt1024)
    }

    @Test
    fun `a failing bigger variant falls back to the stored url`() {
        val stored = bitmap(600)
        val fake = FakeLoader {
            if (it == qobuzMax) Futures.immediateFailedFuture(IOException("503")) else Futures.immediateFuture(stored)
        }
        val result = get(loader(fake).loadBitmap(Uri.parse(qobuz600)))
        assertThat(result).isSameInstanceAs(stored)
        assertThat(fake.loaded).containsExactly(qobuzMax, qobuz600).inOrder()
    }

    @Test
    fun `a stalled bigger variant falls back after the budget, and is cancelled`() {
        val stored = bitmap(600)
        val fake = FakeLoader { if (it == qobuzMax) SettableFuture.create() else Futures.immediateFuture(stored) }
        val result = get(loader(fake, budgetMs = 100).loadBitmap(Uri.parse(qobuz600)))
        assertThat(result).isSameInstanceAs(stored)
        assertThat(fake.loaded).containsExactly(qobuzMax, qobuz600).inOrder()
        // Guava's withTimeout fails the timer first (the fallback runs at once), then cancels the
        // stalled attempt on its scheduler thread: give that a moment.
        val deadline = System.currentTimeMillis() + 2_000
        while (!fake.futures.first().isCancelled && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertThat(fake.futures.first().isCancelled).isTrue()
    }

    @Test
    fun `a bigger variant smaller than the stored cover falls back to the stored url`() {
        // Sinnerman: Last.fm's original is 350px; its stored 770x0 is the CDN's cleaner upscale.
        val stored = bitmap(770)
        val fake = FakeLoader { Futures.immediateFuture(if (it == lastFmOrig) bitmap(350) else stored) }
        val result = get(loader(fake).loadBitmap(Uri.parse(lastFm770)))
        assertThat(result).isSameInstanceAs(stored)
        assertThat(fake.loaded).containsExactly(lastFmOrig, lastFm770).inOrder()
    }

    @Test
    fun `when both fail the error is reported, so the notification just has no art as before`() {
        val fake = FakeLoader { Futures.immediateFailedFuture(IOException("offline")) }
        val future = loader(fake).loadBitmap(Uri.parse(qobuz600))
        val error = runCatching { get(future) }.exceptionOrNull()
        assertThat(error).isNotNull()
        assertThat(fake.loaded).containsExactly(qobuzMax, qobuz600).inOrder()
    }

    @Test
    fun `cancelling the load (a track change) cancels the attempt and never loads the stored url`() {
        val fake = FakeLoader { SettableFuture.create() }
        val future = loader(fake).loadBitmap(Uri.parse(qobuz600))
        future.cancel(true)
        Thread.sleep(50)
        assertThat(fake.loaded).containsExactly(qobuzMax)
        assertThat(fake.futures.first().isCancelled).isTrue()
    }

    @Test
    fun `data saver keeps qobuz on the stored 600`() {
        val fake = FakeLoader { Futures.immediateFuture(bitmap(600)) }
        get(loader(fake, cap = ArtUrlSizer.DATA_SAVER_MAX_PX).loadBitmap(Uri.parse(qobuz600)))
        assertThat(fake.loaded).containsExactly(qobuz600)
    }

    @Test
    fun `metadata artwork uris go through the sizing too`() {
        val fake = FakeLoader { Futures.immediateFuture(bitmap(1024)) }
        val metadata = MediaMetadata.Builder().setArtworkUri(Uri.parse(yt60)).build()
        get(loader(fake).loadBitmapFromMetadata(metadata)!!)
        assertThat(fake.loaded).containsExactly(yt1024)
    }

    @Test
    fun `local artwork is never sized`() {
        val fake = FakeLoader { Futures.immediateFuture(bitmap(300)) }
        get(loader(fake).loadBitmap(Uri.parse("file:///data/user/0/com.stash.app/files/art/a.jpg")))
        assertThat(fake.loaded).containsExactly("file:///data/user/0/com.stash.app/files/art/a.jpg")
    }

    // ── Android Auto browse tiles: sized only to variants that always exist ──────────────────

    @Test
    fun `auto tiles size a tiny cover up, keep risky hosts and mosaics as stored`() {
        assertThat(autoTileArtUri(yt60).toString()).isEqualTo("https://yt3.googleusercontent.com/abc=w512-h512-l90-rj")
        val sqp = "https://i.ytimg.com/vi/abc/hqdefault.jpg?sqp=x"
        assertThat(autoTileArtUri(sqp).toString()).isEqualTo(sqp)
        val mosaic = "$yt60|$qobuz600"
        assertThat(autoTileArtUri(mosaic).toString()).isEqualTo(mosaic)
        assertThat(autoTileArtUri(null)).isNull()
        assertThat(autoTileArtUri(qobuz600).toString()).isEqualTo(qobuz600)
    }
}

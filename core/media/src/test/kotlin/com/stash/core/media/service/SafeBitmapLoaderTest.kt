package com.stash.core.media.service

import android.graphics.Bitmap
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.BitmapLoader
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ExecutionException
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins [SafeBitmapLoader] — the fix for the Android-15-AOSP artwork crash
 * (#387): `IllegalArgumentException: cannot use a recycled source in
 * createBitmap` inside `MediaMetadata.Builder.scaleBitmap` when media3 hands
 * the platform session a shared artwork bitmap that has since been recycled.
 *
 * The loader's contract: hand the platform a COPY nothing else references,
 * and if the source is already recycled, fail the future (media3 then
 * publishes metadata without artwork — a degraded notification instead of a
 * crash).
 */
@RunWith(RobolectricTestRunner::class)
class SafeBitmapLoaderTest {

    private fun bitmap() = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)

    /** Minimal delegate returning whatever bitmap it's handed. */
    private class FakeLoader(private val supply: () -> Bitmap) : BitmapLoader {
        override fun supportsMimeType(mimeType: String) = true
        override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
            Futures.immediateFuture(supply())
        override fun loadBitmap(uri: android.net.Uri): ListenableFuture<Bitmap> =
            Futures.immediateFuture(supply())
        override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap> =
            Futures.immediateFuture(supply())
    }

    @Test
    fun `hands out a copy, not the delegate's instance`() {
        val original = bitmap()
        val loader = SafeBitmapLoader(FakeLoader { original })

        val handed = loader.loadBitmap(android.net.Uri.EMPTY).get()

        assertThat(handed).isNotSameInstanceAs(original)
        assertThat(handed.isRecycled).isFalse()
    }

    @Test
    fun `loadBitmapFromMetadata is hardened, not routed to the delegate raw`() {
        val original = bitmap()
        val loader = SafeBitmapLoader(FakeLoader { original })

        val handed = loader.loadBitmapFromMetadata(MediaMetadata.EMPTY)!!.get()

        assertThat(handed).isNotSameInstanceAs(original)
    }

    @Test
    fun `decodeBitmap is hardened`() {
        val original = bitmap()
        val loader = SafeBitmapLoader(FakeLoader { original })

        val handed = loader.decodeBitmap(ByteArray(0)).get()

        assertThat(handed).isNotSameInstanceAs(original)
    }

    @Test
    fun `a recycled source fails the future instead of crashing setMetadata`() {
        val recycled = bitmap().apply { recycle() }
        val loader = SafeBitmapLoader(FakeLoader { recycled })

        // media3 catches this failed future and drops artwork; the platform
        // never receives a recycled bitmap, so scaleBitmap can't crash.
        assertThrows(ExecutionException::class.java) {
            loader.loadBitmap(android.net.Uri.EMPTY).get()
        }
    }
}

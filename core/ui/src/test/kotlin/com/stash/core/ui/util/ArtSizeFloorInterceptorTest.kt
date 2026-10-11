package com.stash.core.ui.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a sized-up cover falls back to the stored URL. Measured 2026-10-10: Last.fm originals
 * (`ar0`) range 350-3000px, and the stored `770x0` is the CDN's upscale of the small ones.
 */
class ArtSizeFloorInterceptorTest {
    private val lastFm770 = "https://lastfm-img.freetls.fastly.net/i/u/770x0/c9ebed7e766b45dabedaa8ee759b5181.jpg"

    @Test
    fun `a lastfm original smaller than the stored 770 draws the stored url instead`() {
        // Sinnerman: ar0 is 350x347.
        assertTrue(ArtSizeFloorInterceptor.shouldDrawStored(lastFm770, targetPx = 1024, resultPx = 350))
    }

    @Test
    fun `a lastfm original at least as big as 770 is kept`() {
        assertFalse(ArtSizeFloorInterceptor.shouldDrawStored(lastFm770, targetPx = 1024, resultPx = 1000))
        assertFalse(ArtSizeFloorInterceptor.shouldDrawStored(lastFm770, targetPx = 1024, resultPx = 769))
    }

    @Test
    fun `a request that was not sized up is never retried`() {
        // A 495px grid tile keeps the stored 770 URL, so there is nothing to fall back from.
        assertFalse(ArtSizeFloorInterceptor.shouldDrawStored(lastFm770, targetPx = 495, resultPx = 495))
    }

    @Test
    fun `other hosts are never retried`() {
        assertFalse(
            ArtSizeFloorInterceptor.shouldDrawStored(
                "https://static.qobuz.com/images/covers/a/b/x_600.jpg", targetPx = 1024, resultPx = 600,
            ),
        )
        assertFalse(
            ArtSizeFloorInterceptor.shouldDrawStored(
                "https://lh3.googleusercontent.com/abc=w60-h60-l90-rj", targetPx = 1024, resultPx = 60,
            ),
        )
    }
}

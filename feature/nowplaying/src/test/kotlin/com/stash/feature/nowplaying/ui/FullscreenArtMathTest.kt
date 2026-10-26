package com.stash.feature.nowplaying.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FullscreenArtMathTest {

    @Test fun `the song is shared evenly between the pages`() {
        // Paranoid Android, 6:23, over OK Computer OKNOTOK's ten pages: 38.3 s each, once round.
        val s = fullscreenSchedule(383_000L, 10)
        assertEquals((0 until 10).toList(), s.pages)
        assertEquals(38_300L, s.holdMs)
        assertEquals(0, s.segmentAt(0L))
        assertEquals(1, s.segmentAt(38_300L))
        assertEquals(9, s.segmentAt(382_999L))
        // The song's last moment, or a position past it, stays on the last page.
        assertEquals(9, s.segmentAt(400_000L))
    }

    @Test fun `a long song goes round the pages again`() {
        // Eight minutes over two pages: no page held past 40 s, so six rounds of twelve stretches.
        val s = fullscreenSchedule(480_000L, 2)
        assertEquals(12, s.pages.size)
        assertEquals(listOf(0, 1, 0, 1), s.pages.take(4))
        assertTrue(s.holdMs <= FULLSCREEN_MAX_HOLD_MS)
    }

    @Test fun `a short song shows the first pages it has time for`() {
        // 45 s over ten pages: four pages of ten seconds or more, the front and the back first.
        val s = fullscreenSchedule(45_000L, 10)
        assertEquals(listOf(0, 1, 2, 3), s.pages)
        assertTrue(s.holdMs >= FULLSCREEN_MIN_HOLD_MS)
        // Shorter than one hold: the front alone, for the whole song.
        assertEquals(listOf(0), fullscreenSchedule(6_000L, 10).pages)
    }

    @Test fun `a one-page album holds its front through the song in stretches`() {
        val s = fullscreenSchedule(200_000L, 1)
        assertTrue(s.pages.all { it == 0 })
        assertTrue(s.holdMs in FULLSCREEN_MIN_HOLD_MS..FULLSCREEN_MAX_HOLD_MS)
    }

    @Test fun `an unknown length goes round the pages for as long as it plays`() {
        val s = fullscreenSchedule(0L, 3)
        assertTrue(s.endless)
        assertEquals(FULLSCREEN_UNKNOWN_HOLD_MS, s.holdMs)
        assertEquals(7, s.segmentAt(7 * FULLSCREEN_UNKNOWN_HOLD_MS))
        assertEquals(1, s.pageAt(7))
    }

    @Test fun `a square page starts whole and ends filling the screen's height`() {
        val start = fullscreenCamera(1200f, 1187f, 412f, 915f, progress = 0f, direction = 1)
        assertEquals(0f, start.left, 0.01f)
        assertEquals(412f, start.width, 0.01f)
        val end = fullscreenCamera(1200f, 1187f, 412f, 915f, progress = 1f, direction = 1)
        assertEquals(915f, end.height, 0.5f)
        assertEquals(0f, end.top, 0.5f)
        // Toward the left: the page's left edge stays on the screen's.
        assertEquals(0f, end.left, 0.01f)
        val right = fullscreenCamera(1200f, 1187f, 412f, 915f, progress = 1f, direction = -1)
        assertEquals(412f, right.right, 0.01f)
    }

    @Test fun `the camera never uncovers the screen's sides`() {
        for (step in 0..20) {
            val p = step / 20f
            for (direction in listOf(1, -1)) {
                val r = fullscreenCamera(1169f, 1200f, 412f, 915f, p, direction)
                assertTrue("left at $p", r.left <= 0.01f)
                assertTrue("right at $p", r.right >= 411.99f)
            }
        }
    }

    @Test fun `a spread pans its whole width`() {
        val start = fullscreenCamera(1200f, 585f, 412f, 915f, progress = 0f, direction = 1)
        val end = fullscreenCamera(1200f, 585f, 412f, 915f, progress = 1f, direction = 1)
        assertEquals(0f, start.left, 0.01f)
        assertEquals(412f, end.right, 0.01f)
        assertEquals(915f * 0.6f, start.height, 0.5f)
    }

    @Test fun `the art square crops the page to fill it`() {
        val r = coverCrop(1200f, 585f, 300f, 300f)
        assertEquals(300f, r.height, 0.01f)
        assertTrue(r.width > 300f)
        assertEquals((300f - r.width) / 2f, r.left, 0.01f)
    }
}

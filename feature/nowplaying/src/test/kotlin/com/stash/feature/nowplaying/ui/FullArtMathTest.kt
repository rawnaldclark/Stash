package com.stash.feature.nowplaying.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FullArtMathTest {

    @Test fun `the song is shared evenly between the pages`() {
        // Paranoid Android, 6:23, over OK Computer OKNOTOK's ten pages: 38.3 s each, once round.
        val s = fullArtSchedule(383_000L, 10)
        assertEquals((0 until 10).toList(), s.pages)
        assertEquals(38_300L, s.holdMs)
        assertEquals(0, s.segmentAt(0L))
        assertEquals(1, s.segmentAt(38_300L))
        assertEquals(9, s.segmentAt(382_999L))
        // A position past the end stays on the last page.
        assertEquals(9, s.segmentAt(400_000L))
    }

    @Test fun `a long song goes round the pages again`() {
        // Eight minutes over two pages: no page held past 40 s, so six rounds of twelve stretches.
        val s = fullArtSchedule(480_000L, 2)
        assertEquals(12, s.pages.size)
        assertEquals(listOf(0, 1, 0, 1), s.pages.take(4))
        assertTrue(s.holdMs <= FULL_ART_MAX_HOLD_MS)
    }

    @Test fun `a short song shows the first pages it has time for`() {
        val s = fullArtSchedule(45_000L, 10)
        assertEquals(listOf(0, 1, 2, 3), s.pages)
        assertTrue(s.holdMs >= FULL_ART_MIN_HOLD_MS)
        assertEquals(listOf(0), fullArtSchedule(6_000L, 10).pages)
    }

    @Test fun `an unknown length goes round the pages for as long as it plays`() {
        val s = fullArtSchedule(0L, 3)
        assertTrue(s.endless)
        assertEquals(FULL_ART_UNKNOWN_HOLD_MS, s.holdMs)
        assertEquals(7, s.segmentAt(7 * FULL_ART_UNKNOWN_HOLD_MS))
        assertEquals(1, s.pageAt(7))
    }

    @Test fun `a square page pans across its crop then rests`() {
        // 390 x 520 art: a square page is 520 wide, 130 hidden; at 16 px/s that's about 8 s.
        val start = panCrop(1200f, 1200f, 390f, 520f, elapsedS = 0f, direction = 1, holdS = 38f, speedPxPerS = 16f)
        assertEquals(0f, start.left, 0.01f)
        assertEquals(520f, start.width, 0.01f)
        val end = panCrop(1200f, 1200f, 390f, 520f, elapsedS = 9f, direction = 1, holdS = 38f, speedPxPerS = 16f)
        assertEquals(390f, end.right, 0.01f)
        val resting = panCrop(1200f, 1200f, 390f, 520f, elapsedS = 30f, direction = 1, holdS = 38f, speedPxPerS = 16f)
        assertEquals(end.left, resting.left, 0.01f)
        // The other way: from the right edge back to the left.
        val back = panCrop(1200f, 1200f, 390f, 520f, elapsedS = 0f, direction = -1, holdS = 38f, speedPxPerS = 16f)
        assertEquals(390f, back.right, 0.01f)
    }

    @Test fun `a spread too wide to cross in its time is crossed through its middle`() {
        // 2:1 at 520 high is 1040 wide: 650 hidden, but 38 s at 16 px/s crosses only 608.
        val start = panCrop(1200f, 600f, 390f, 520f, elapsedS = 0f, direction = 1, holdS = 38f, speedPxPerS = 16f)
        assertEquals(-21f, start.left, 0.01f)
        val end = panCrop(1200f, 600f, 390f, 520f, elapsedS = 38f, direction = 1, holdS = 38f, speedPxPerS = 16f)
        assertEquals(-629f, end.left, 0.01f)
    }

    @Test fun `the pan never uncovers the art's sides`() {
        for (step in 0..40) {
            for (direction in listOf(1, -1)) {
                val r = panCrop(1169f, 1200f, 390f, 520f, step / 2f, direction, holdS = 20f, speedPxPerS = 16f)
                assertTrue(r.left <= 0.01f)
                assertTrue(r.right >= 389.99f)
                assertEquals(0f, r.top, 0.01f)
            }
        }
    }

    @Test fun `zoom 1 fits the whole cover across the screen`() {
        // 412 x 915 screen: the box is a square the screen's width, so a square cover isn't cropped.
        val (box, extra) = fullArtBox(412f, 915f, zoom = 1f)
        assertEquals(412f, box.height, 0.01f)
        assertEquals(1f, extra, 0.0001f)
        val r = panCrop(1200f, 1200f, box.width, box.height, elapsedS = 5f, direction = 1, holdS = 38f, speedPxPerS = 16f, zoom = extra)
        assertEquals(0f, r.left, 0.01f)
        assertEquals(412f, r.width, 0.01f)
    }

    @Test fun `zoom past the art's tallest scales the page up inside it`() {
        // 2.0x on 412 x 915: the box stops at 62% of the height (567.3); the rest scales the page.
        val (box, extra) = fullArtBox(412f, 915f, zoom = 2f)
        assertEquals(915f * FULL_ART_MAX_HEIGHT, box.height, 0.01f)
        assertEquals(824f / (915f * FULL_ART_MAX_HEIGHT), extra, 0.0001f)
        val r = panCrop(1200f, 1200f, box.width, box.height, elapsedS = 0f, direction = 1, holdS = 38f, speedPxPerS = 16f, zoom = extra)
        assertEquals(824f, r.width, 0.5f)
        // Centred on the side it doesn't pan along.
        assertEquals((box.height - r.height) / 2f, r.top, 0.01f)
    }

    @Test fun `with no pan the page sits centred`() {
        val r = panCrop(1200f, 1200f, 390f, 520f, elapsedS = 5f, direction = 1, holdS = 38f, speedPxPerS = 0f)
        assertEquals(-65f, r.left, 0.01f)
    }
}

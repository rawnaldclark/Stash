package com.stash.core.ui.ambient

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parity with stash-web player/src/lib/ui/palette.ts `wordmarkInk` (the expected hexes were produced by the
 * web code itself, scratchpad pal2.mts / pal3.mts), plus the contrast floor it promises.
 */
class WordmarkInkTest {

    private fun solid(r: Int, g: Int, b: Int, n: Int = 400) =
        IntArray(n) { (0xFF shl 24) or (r shl 16) or (g shl 8) or b }

    private fun hex(c: Color): String = "#%02x%02x%02x".format(
        Math.round(c.red * 255), Math.round(c.green * 255), Math.round(c.blue * 255),
    )

    @Test fun `no palette is the plain ink`() {
        assertEquals("#ffffff", hex(wordmarkInk(null, light = false)))
        assertEquals("#1e0742", hex(wordmarkInk(null, light = true)))
    }

    @Test fun `a playing song tints the wordmark as the web does`() {
        val orange = ambientPaletteFromPixels(solid(226, 115, 58))!!
        assertEquals("#f9cdb6", hex(wordmarkInk(orange, light = false)))
        assertEquals("#53292f", hex(wordmarkInk(orange, light = true)))
        val teal = ambientPaletteFromPixels(solid(20, 163, 148))!!
        assertEquals("#b4faf3", hex(wordmarkInk(teal, light = false)))
        assertEquals("#1f4558", hex(wordmarkInk(teal, light = true)))
        val yellow = ambientPaletteFromPixels(solid(240, 220, 40))!!
        assertEquals("#fdf5b2", hex(wordmarkInk(yellow, light = false)))
        assertEquals("#4f412e", hex(wordmarkInk(yellow, light = true)))
        assertEquals("#cebbfb", hex(wordmarkInk(AmbientPalette.Default, light = false)))
        assertEquals("#3b2171", hex(wordmarkInk(AmbientPalette.Default, light = true)))
    }

    @Test fun `a mood too dark for the dark bases steps back toward white`() {
        // 60 % toward navy fails 4.5:1 on the dark bases; 55 % fails too; 50 % holds.
        val navy = AmbientPalette.Default.copy(textDark = Color(0xFF000080))
        assertEquals("#8080c0", hex(wordmarkInk(navy, light = false)))
        // A mood that already holds at 60 % is used as is.
        val slate = AmbientPalette.Default.copy(textDark = Color(0xFF303040))
        assertEquals("#83838c", hex(wordmarkInk(slate, light = false)))
    }

    @Test fun `a mood too pale for the light bases steps back toward the dark ink`() {
        val pale = AmbientPalette.Default.copy(textLight = Color(0xFFF0F0A0))
        assertEquals("#726468", hex(wordmarkInk(pale, light = true)))
        val white = AmbientPalette.Default.copy(textLight = Color.White)
        assertEquals("#786a8e", hex(wordmarkInk(white, light = true)))
    }

    @Test fun `every tint holds 4 point 5 to 1 on every base it sits on`() {
        val covers = listOf(
            intArrayOf(226, 115, 58), intArrayOf(20, 163, 148), intArrayOf(240, 220, 40),
            intArrayOf(150, 120, 230), intArrayOf(40, 60, 200), intArrayOf(250, 250, 120),
        )
        for (c in covers) {
            val p = ambientPaletteFromPixels(solid(c[0], c[1], c[2]))!!
            for (light in listOf(false, true)) {
                val ink = wordmarkInk(p, light)
                for (bg in if (light) WordmarkBasesLight else WordmarkBasesDark) {
                    assertTrue("${c.toList()} light=$light on ${hex(bg)}", contrastRatio(ink, bg) >= 4.5)
                }
            }
        }
    }

    @Test fun `contrast ratio follows WCAG`() {
        assertEquals(21.0, contrastRatio(Color.White, Color.Black), 0.01)
        assertEquals(1.0, contrastRatio(Color(0xFF777777), Color(0xFF777777)), 0.0001)
        // symmetric
        assertEquals(contrastRatio(Color.White, Color(0xFF8B5CF6)), contrastRatio(Color(0xFF8B5CF6), Color.White), 0.0001)
    }
}

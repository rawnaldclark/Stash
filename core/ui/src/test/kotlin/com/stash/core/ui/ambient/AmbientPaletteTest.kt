package com.stash.core.ui.ambient

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parity with stash-web player/src/lib/ui/palette.ts (expected hexes produced by the web code itself). */
class AmbientPaletteTest {

    private fun solid(r: Int, g: Int, b: Int, n: Int = 400) =
        IntArray(n) { (0xFF shl 24) or (r shl 16) or (g shl 8) or b }

    private fun hex(c: Color): String = "#%02x%02x%02x".format(
        Math.round(c.red * 255), Math.round(c.green * 255), Math.round(c.blue * 255),
    )

    @Test fun `orange cover matches the web palette`() {
        val p = ambientPaletteFromPixels(solid(226, 115, 58))!!
        assertEquals("#6b2f10", hex(p.dom))
        assertEquals("#e2733a", hex(p.vib))
        assertEquals("#514138", hex(p.mut))
        val l = p.forScheme(light = true)
        assertEquals("#de6121", hex(l.dom))
        assertEquals("#e2733a", hex(l.vib))
        assertEquals("#bf8669", hex(l.mut))
    }

    @Test fun `teal cover matches the web palette`() {
        val p = ambientPaletteFromPixels(solid(20, 163, 148))!!
        assertEquals("#0d6d63", hex(p.dom))
        assertEquals("#19ccba", hex(p.vib))
        assertEquals("#38514f", hex(p.mut))
        val l = p.forScheme(light = true)
        assertEquals("#1ce3cf", hex(l.dom))
        assertEquals("#69bfb6", hex(l.mut))
    }

    @Test fun `text inks and accent ink match the web palette`() {
        val orange = ambientPaletteFromPixels(solid(226, 115, 58))!!
        assertEquals("#f5ab85", hex(orange.textDark))
        assertEquals("#773f22", hex(orange.textLight))
        assertEquals("#1a0b2e", hex(orange.ink)) // bright accent: near-black ink
        val lilac = ambientPaletteFromPixels(solid(150, 120, 230))!!
        assertEquals("#a588f1", hex(lilac.textDark))
        assertEquals("#392277", hex(lilac.textLight))
        assertEquals("#ffffff", hex(lilac.ink))
    }

    @Test fun `light scheme darkens the text ink until it holds 5 to 1 on the wash`() {
        // palette.ts forScheme: teal's #22776e falls short on its own wash, #206f67 holds.
        val teal = ambientPaletteFromPixels(solid(20, 163, 148))!!
        assertEquals("#22776e", hex(teal.textLight))
        assertEquals("#206f67", hex(teal.forScheme(light = true).textLight))
        // ...and the dark-scheme ink is untouched.
        assertEquals("#82f7eb", hex(teal.forScheme(light = true).textDark))
    }

    @Test fun `the default carries the web's default inks`() {
        assertEquals("#ae8df9", hex(AmbientPalette.Default.textDark))
        assertEquals("#4f3390", hex(AmbientPalette.Default.textLight))
        assertEquals("#ffffff", hex(AmbientPalette.Default.ink))
    }

    @Test fun `greyscale and transparent covers keep the default`() {
        assertNull(ambientPaletteFromPixels(solid(128, 128, 128)))
        assertNull(ambientPaletteFromPixels(IntArray(400)))
    }

    // ---- The app's reading: cover / neutral / nothing (readCoverPalette) ----

    private fun px(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun assertGrey(what: String, c: Color) {
        val s = rgbToHsl(Math.round(c.red * 255), Math.round(c.green * 255), Math.round(c.blue * 255))[1]
        assertTrue("$what ${hex(c)} saturation $s", s <= NEUTRAL_MAX_SATURATION + 0.01)
    }

    @Test fun `a black and white cover reads as neutral grey, never purple`() {
        // A 32x32 checker of black and white, plus a band of mid grey.
        val bw = IntArray(1024) { i -> if (i < 200) px(128, 128, 128) else if ((i / 32 + i) % 2 == 0) px(0, 0, 0) else px(255, 255, 255) }
        val p = readCoverPalette(bw, key = "bw")!!
        assertEquals(PaletteKind.NEUTRAL, p.kind)
        listOf(p.dom, p.vib, p.mut, p.textDark, p.textLight).forEach { assertGrey("bw", it) }
        assertEquals("pure greys lean no way: plain grey", "#3d3d3d", hex(p.dom))
        // Its light lift stays grey too.
        val l = p.forScheme(light = true)
        listOf(l.dom, l.mut, l.textLight).forEach { assertGrey("bw light", it) }
    }

    @Test fun `JPEG-style noise on a black and white cover stays grey`() {
        // A black and white checker with every channel jittered by up to 3 levels, as a JPEG's
        // chroma noise does: each pixel has a random hue (and near-black / near-white pixels a large
        // HSL saturation), but they lean no way together.
        val rnd = java.util.Random(42)
        fun j(v: Int) = (v + rnd.nextInt(7) - 3).coerceIn(0, 255)
        val noisy = IntArray(1024) { i ->
            val v = if ((i / 32 + i) % 2 == 0) 12 else 243
            px(j(v), j(v), j(v))
        }
        val p = readCoverPalette(noisy, key = "noisy")!!
        assertEquals(PaletteKind.NEUTRAL, p.kind)
        for (c in listOf(p.dom, p.vib, p.mut, p.textDark, p.textLight)) {
            val s = rgbToHsl(Math.round(c.red * 255), Math.round(c.green * 255), Math.round(c.blue * 255))[1]
            assertTrue("noise must not tint ${hex(c)} ($s)", s <= 0.01)
        }
    }

    @Test fun `a sepia cover reads as a warm neutral`() {
        // Sepia: colour well below the vote's 0.18 saturation floor, hue about 34 degrees.
        val sepia = IntArray(1024) { i -> if (i % 3 == 0) px(140, 128, 112) else px(90, 80, 68) }
        assertNull("the web's vote finds no colour", ambientPaletteFromPixels(sepia))
        val p = readCoverPalette(sepia)!!
        assertEquals(PaletteKind.NEUTRAL, p.kind)
        val hsl = rgbToHsl(Math.round(p.vib.red * 255), Math.round(p.vib.green * 255), Math.round(p.vib.blue * 255))
        assertTrue("warm hue ${hsl[0]}", hsl[0] in 20.0..50.0)
        assertTrue("a hint of it ${hsl[1]}", hsl[1] > 0.02)
        assertGrey("sepia", p.vib)
        assertTrue("red over blue", p.vib.red > p.vib.blue)
    }

    @Test fun `a small but real colour on a black and white cover still wins the vote`() {
        // 22 red pixels of 1024 (2.1 %): over the vote's 2 % floor, so the cover is red.
        val logo = IntArray(1024) { i -> if (i < 22) px(220, 30, 40) else if (i % 2 == 0) px(0, 0, 0) else px(250, 250, 250) }
        val p = readCoverPalette(logo)!!
        assertEquals(PaletteKind.COVER, p.kind)
        val hue = rgbToHsl(Math.round(p.vib.red * 255), Math.round(p.vib.green * 255), Math.round(p.vib.blue * 255))[0]
        assertTrue("red ($hue)", hue < 15 || hue > 345)
        // 1 % is a speck: neutral.
        val speck = IntArray(1024) { i -> if (i < 10) px(220, 30, 40) else if (i % 2 == 0) px(0, 0, 0) else px(250, 250, 250) }
        assertEquals(PaletteKind.NEUTRAL, readCoverPalette(speck)!!.kind)
    }

    @Test fun `nothing readable gives null, so the brand default stays`() {
        assertNull(readCoverPalette(IntArray(400))) // fully transparent
        assertNull(readCoverPalette(IntArray(0)))
        assertNull(readCoverPalette(IntArray(400) { 0x40FF0000 })) // red, but almost see-through
    }

    @Test fun `colourful covers read exactly as the web's vote reads them`() {
        for (c in listOf(intArrayOf(226, 115, 58), intArrayOf(20, 163, 148), intArrayOf(150, 120, 230), intArrayOf(240, 220, 40))) {
            val pixels = solid(c[0], c[1], c[2])
            val web = ambientPaletteFromPixels(pixels, key = "k")!!
            assertEquals(web, readCoverPalette(pixels, key = "k"))
            assertEquals(PaletteKind.COVER, web.kind)
        }
        assertEquals(PaletteKind.BRAND, AmbientPalette.Default.kind)
    }

    @Test fun `default and dark are left alone by forScheme`() {
        assertSame(AmbientPalette.Default, AmbientPalette.Default.forScheme(light = true))
        val p = ambientPaletteFromPixels(solid(226, 115, 58))!!
        assertSame(p, p.forScheme(light = false))
    }
}

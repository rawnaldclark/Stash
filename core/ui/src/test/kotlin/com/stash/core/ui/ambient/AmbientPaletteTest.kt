package com.stash.core.ui.ambient

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    @Test fun `default and dark are left alone by forScheme`() {
        assertSame(AmbientPalette.Default, AmbientPalette.Default.forScheme(light = true))
        val p = ambientPaletteFromPixels(solid(226, 115, 58))!!
        assertSame(p, p.forScheme(light = false))
    }
}

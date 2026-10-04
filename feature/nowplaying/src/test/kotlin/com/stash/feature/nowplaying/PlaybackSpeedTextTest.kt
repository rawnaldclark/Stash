package com.stash.feature.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackSpeedTextTest {

    @Test fun `speeds read the same in every locale`() {
        assertEquals("1x", formatSpeed(1f))
        assertEquals("2x", formatSpeed(2f))
        assertEquals("1.25x", formatSpeed(1.25f))
        assertEquals("0.5x", formatSpeed(0.5f))
        assertEquals("0.1x", formatSpeed(0.1f))
    }

    @Test fun `normal speed is called Normal`() {
        assertEquals("Normal", speedLabel(1f))
        assertEquals("1.5x", speedLabel(1.5f))
    }

    @Test fun `a decimal keypad's comma types a point`() {
        // Comma-decimal locales (de, fr, pt-BR…) put "," on the decimal keypad; refusing it left
        // whole numbers as the only custom speeds.
        assertEquals("1.5", customSpeedInput("1,5"))
    }

    @Test fun `the custom field takes digits and one point with up to two places`() {
        assertEquals("", customSpeedInput(""))
        assertEquals("1.", customSpeedInput("1."))
        assertEquals("1.25", customSpeedInput("1.25"))
        assertEquals(".5", customSpeedInput(".5"))
        assertNull(customSpeedInput("1.255")) // the picker shows two places, so it takes two
        assertNull(customSpeedInput("1.2.5"))
        assertNull(customSpeedInput("1x"))
        assertNull(customSpeedInput("-1"))
    }

    @Test fun `a custom speed is accepted from 0_1x to 4x`() {
        assertEquals(0.1f, parseCustomSpeed("0.1"))
        assertEquals(4f, parseCustomSpeed("4"))
        assertEquals(0.5f, parseCustomSpeed(".5"))
        assertEquals(1f, parseCustomSpeed("1."))
        assertNull(parseCustomSpeed("0.09"))
        assertNull(parseCustomSpeed("4.01"))
        assertNull(parseCustomSpeed(""))
        assertNull(parseCustomSpeed("."))
    }

    @Test fun `the chips drop to two columns when the widest label won't fit four`() {
        // A 312 px row with 8 px gaps leaves 72 px a chip at four columns.
        assertEquals(4, speedPresetColumns(rowWidthPx = 312, widestChipPx = 72, gapPx = 8))
        assertEquals(2, speedPresetColumns(rowWidthPx = 312, widestChipPx = 73, gapPx = 8))
    }
}

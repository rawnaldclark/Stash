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

    @Test fun `the speed action names a non-standard tuning`() {
        assertEquals("Playback speed", speedActionLabel(1f, 440f))
        assertEquals("Playback speed · A432", speedActionLabel(1f, 432f))
        assertEquals("Playback speed · A431.5", speedActionLabel(1f, 431.5f))
        assertEquals("Playback speed · 1.25x", speedActionLabel(1.25f, 440f))
        assertEquals("Playback speed · 1.25x · A444", speedActionLabel(1.25f, 444f))
    }

    @Test fun `the speed action is on for a speed or a tuning`() {
        assertEquals(false, speedActionActive(1f, 440f))
        assertEquals(true, speedActionActive(1f, 432f))
        assertEquals(true, speedActionActive(0.75f, 440f))
    }

    @Test fun `the sheet's tuning heading`() {
        assertEquals("Tuning · Standard", tuningHeading(440f))
        assertEquals("Tuning · A = 432 Hz", tuningHeading(432f))
        assertEquals("Tuning · A = 431.5 Hz", tuningHeading(431.5f))
    }
}

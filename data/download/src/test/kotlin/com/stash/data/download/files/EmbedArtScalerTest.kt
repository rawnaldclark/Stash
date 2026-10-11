package com.stash.data.download.files

import android.graphics.Bitmap
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [EmbedArtScaler]'s decisions, with the platform decoder stubbed: what passes through untouched,
 * what is decoded at which sample size, and that running out of memory is reported (null) rather
 * than thrown. The pixel work itself (scale + JPEG encode) needs a real device.
 */
class EmbedArtScalerTest {

    /** A scaler whose "image" is [w]x[h] and whose sampled decode does [decode]. */
    private class Stubbed(
        private val w: Int,
        private val h: Int,
        private val decode: (Int) -> Bitmap?,
    ) : EmbedArtScaler() {
        val samples = mutableListOf<Int>()
        override fun readBounds(bytes: ByteArray) = w to h
        override fun decodeSampled(bytes: ByteArray, sampleSize: Int): Bitmap? {
            samples += sampleSize
            return decode(sampleSize)
        }
    }

    private val bytes = ByteArray(16) { it.toByte() }

    @Test
    fun `bytes the device can't read pass through unchanged, size unknown`() = runTest {
        val fitted = Stubbed(0, 0) { error("must not decode") }.fit(bytes, 1200)!!
        assertSame(bytes, fitted.bytes)
        assertEquals(0, fitted.longSidePx)
    }

    @Test
    fun `a cover within the limit passes through byte for byte`() = runTest {
        val fitted = Stubbed(1000, 1000) { error("must not decode") }.fit(bytes, 1200)!!
        assertSame(bytes, fitted.bytes)
        assertEquals(1000, fitted.longSidePx)
    }

    @Test
    fun `a cover whose pixels won't decode passes through unchanged`() = runTest {
        val fitted = Stubbed(4000, 4000) { null }.fit(bytes, 1200)!!
        assertSame(bytes, fitted.bytes)
        assertEquals(0, fitted.longSidePx)
    }

    @Test
    fun `running out of memory is reported as null, after sampling a 4000px cover by 2`() = runTest {
        val scaler = Stubbed(4000, 4000) { throw OutOfMemoryError("4000px") }
        assertNull(scaler.fit(bytes, 1200))
        assertEquals(listOf(2), scaler.samples)
    }

    @Test
    fun `the sample keeps at least the limit on the long side`() {
        assertEquals(2, EmbedArtScaler.sampleSizeFor(4000, 1200))
        assertEquals(2, EmbedArtScaler.sampleSizeFor(2400, 1200))
        assertEquals(1, EmbedArtScaler.sampleSizeFor(2000, 1200))
        assertEquals(1, EmbedArtScaler.sampleSizeFor(1300, 1200))
        assertEquals(4, EmbedArtScaler.sampleSizeFor(4800, 1200))
    }
}

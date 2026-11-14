package com.stash.core.data.audio

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [FlacAudioEnd] on real FLAC bytes: `flac/tidal-preview-shape.flac` is 29.907 s of frames under a
 * STREAMINFO saying 274.773 s, the shape Tidal's 30 s PREVIEW of "Linger" became when it was saved
 * (Pixel 5, 2026-11-13). Its frames (see the fixture's README): 0-127 are 14 bytes from offset 60,
 * 128-319 are 15 bytes, 320 starts at 4732 and the last, 321, at 12994; the file is 21,271 bytes.
 */
class FlacAudioEndTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun fixture(): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/flac/tidal-preview-shape.flac")) { "missing flac/tidal-preview-shape.flac" }
            .use { it.readBytes() }

    private fun file(bytes: ByteArray): File = tmp.newFile().apply { writeBytes(bytes) }

    /** [bytes] with STREAMINFO's total samples set to [total] (36 bits: the low nibble of byte 21, bytes 22-25). */
    private fun withTotalSamples(bytes: ByteArray, total: Long): ByteArray = bytes.copyOf().also {
        it[21] = ((it[21].toInt() and 0xF0) or ((total shr 32).toInt() and 0x0F)).toByte()
        for (i in 0 until 4) it[22 + i] = (total shr (24 - 8 * i)).toByte()
    }

    @Test
    fun `a preview under the whole song's header measures where its frames stop, not what the header says`() {
        val lengths = FlacAudioEnd.read(file(fixture()))!!

        assertThat(lengths.headerMs).isEqualTo(274_773L)
        assertThat(lengths.framesMs).isEqualTo(29_907L)
        assertThat(lengths.frameSamples).isEqualTo(322L * 4096)
    }

    @Test
    fun `an open file, as a storage-access document is read, measures the same as its path`() {
        val path = file(fixture())
        val (lengths, isFlac) = java.io.FileInputStream(path).use { FlacAudioEnd.read(it.channel) to FlacAudioEnd.isFlac(it.channel) }

        assertThat(lengths).isEqualTo(FlacAudioEnd.read(path))
        assertThat(isFlac).isTrue()
        java.io.FileInputStream(file(fixture().copyOf(60))).use { assertThat(FlacAudioEnd.read(it.channel)).isNull() }
    }

    @Test
    fun `an honest file measures the length its header states`() {
        val lengths = FlacAudioEnd.read(file(withTotalSamples(fixture(), 322L * 4096)))!!

        assertThat(lengths.framesMs).isEqualTo(lengths.headerMs)
    }

    @Test
    fun `a file cut short measures where its frames stop`() {
        // Cut just before the last frame: frame 320 is the last whole one.
        assertThat(FlacAudioEnd.read(file(fixture().copyOf(12_994)))!!.frameSamples).isEqualTo(321L * 4096)
        // Cut two bytes into frame 100's data: its header is whole, so its frame counts.
        assertThat(FlacAudioEnd.read(file(fixture().copyOf(1_460 + 8)))!!.frameSamples).isEqualTo(101L * 4096)
    }

    @Test
    fun `bytes that look like a frame header after the last frame are not taken for one`() {
        // A well-formed header with a good CRC-8, numbering itself frame 100: frame 321 is the last one all the same.
        val forged = byteArrayOf(0xFF.toByte(), 0xF8.toByte(), 0xC9.toByte(), 0x18, 100, 0)
        forged[5] = FlacAudioEnd.crc8(forged, 0, 5).toByte()
        val junk = forged + ByteArray(40) { 0x55 }

        assertThat(FlacAudioEnd.read(file(fixture() + junk))!!.framesMs).isEqualTo(29_907L)
    }

    @Test
    fun `an ID3v2 tag in front of the FLAC is stepped over`() {
        // "ID3", version 4.0, no flags, a 20-byte tag (syncsafe), then 20 bytes of it.
        val tag = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0, 0, 0, 0, 20) + ByteArray(20)
        val tagged = file(tag + fixture())

        assertThat(FlacAudioEnd.isFlac(tagged)).isTrue()
        assertThat(FlacAudioEnd.read(tagged)!!.framesMs).isEqualTo(29_907L)
    }

    @Test
    fun `a variable-blocksize stream ends where its last frame's sample number and block size say`() {
        // STREAMINFO: blocks 1152-4608, 44.1 kHz stereo 16-bit, total unknown (0).
        val info = ByteArray(34).also {
            it[0] = 0x04; it[1] = 0x80.toByte() // min block 1152
            it[2] = 0x12; it[3] = 0x00 // max block 4608
            it[10] = 0x0A; it[11] = 0xC4.toByte(); it[12] = 0x42; it[13] = 0xF0.toByte() // 44100 Hz, 2 ch, 16 bit
        }
        val head = "fLaC".toByteArray() + byteArrayOf(0x80.toByte(), 0, 0, 34) + info
        fun frame(sampleNumberUtf8: ByteArray, blockCode: Int): ByteArray {
            val h = byteArrayOf(0xFF.toByte(), 0xF9.toByte(), ((blockCode shl 4) or 9).toByte(), 0x18) + sampleNumberUtf8
            return h + FlacAudioEnd.crc8(h, 0, h.size).toByte() + ByteArray(30) { 0x33 }
        }
        // Sample 0, 1152 samples (code 3); then sample 1152 (UTF-8 0xD2 0x80), 576 samples (code 2).
        val bytes = head + frame(byteArrayOf(0), 3) + frame(byteArrayOf(0xD2.toByte(), 0x80.toByte()), 2)

        val lengths = FlacAudioEnd.read(file(bytes))!!

        assertThat(lengths.frameSamples).isEqualTo(1_152L + 576)
        assertThat(lengths.headerMs).isNull()
    }

    @Test
    fun `a file that is not a FLAC, or has no frames, is not measured`() {
        val mp4 = file(byteArrayOf(0, 0, 0, 0x20) + "ftypM4A ".toByteArray() + ByteArray(64))
        assertThat(FlacAudioEnd.isFlac(mp4)).isFalse()
        assertThat(FlacAudioEnd.read(mp4)).isNull()
        // The metadata alone: fLaC, STREAMINFO and the comment block end at offset 60.
        assertThat(FlacAudioEnd.read(file(fixture().copyOf(60)))).isNull()
        assertThat(FlacAudioEnd.read(File(tmp.root, "missing.flac"))).isNull()
    }
}

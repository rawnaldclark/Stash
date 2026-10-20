package com.stash.data.cloud.tags

/**
 * Duration, bitrate and sample rate of an MP3 from its first audio frame: the Xing/Info or VBRI
 * header when there is one (VBR files), else the constant-bitrate estimate from the file size.
 * Needs only the bytes right after the ID3v2 tag.
 */
internal object MpegAudioParser {

    /** How far past the tag to look for the first frame (junk / padding between tag and audio). */
    private const val SYNC_SEARCH_BYTES = 64 * 1024

    private val BITRATES_V1 = arrayOf(
        intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448), // Layer I
        intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384), // Layer II
        intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320), // Layer III
    )
    private val BITRATES_V2 = arrayOf(
        intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256), // Layer I
        intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160), // Layer II & III
    )
    private val SAMPLE_RATES = intArrayOf(44100, 48000, 32000)

    /** A parsed 4-byte frame header. */
    internal data class FrameHeader(
        val mpeg1: Boolean,
        val layer: Int,
        val bitrateKbps: Int,
        val sampleRate: Int,
        val padding: Int,
        val mono: Boolean,
    ) {
        val samplesPerFrame: Int
            get() = when {
                layer == 1 -> 384
                layer == 2 -> 1152
                mpeg1 -> 1152
                else -> 576
            }

        val frameLength: Int
            get() = when (layer) {
                1 -> (12 * bitrateKbps * 1000 / sampleRate + padding) * 4
                3 -> (if (mpeg1) 144 else 72) * bitrateKbps * 1000 / sampleRate + padding
                else -> 144 * bitrateKbps * 1000 / sampleRate + padding
            }
    }

    internal fun header(b: ByteArray, i: Int): FrameHeader? {
        if (i + 4 > b.size) return null
        if (b.u8(i) != 0xFF || b.u8(i + 1) and 0xE0 != 0xE0) return null
        val versionBits = (b.u8(i + 1) shr 3) and 3 // 0 = 2.5, 1 = reserved, 2 = 2, 3 = 1
        val layerBits = (b.u8(i + 1) shr 1) and 3 // 1 = III, 2 = II, 3 = I
        if (versionBits == 1 || layerBits == 0) return null
        val bitrateIdx = b.u8(i + 2) shr 4
        val rateIdx = (b.u8(i + 2) shr 2) and 3
        if (bitrateIdx == 0 || bitrateIdx == 15 || rateIdx == 3) return null
        val layer = 4 - layerBits
        val mpeg1 = versionBits == 3
        val bitrate = if (mpeg1) BITRATES_V1[layer - 1][bitrateIdx]
        else BITRATES_V2[if (layer == 1) 0 else 1][bitrateIdx]
        val rate = SAMPLE_RATES[rateIdx] / when (versionBits) { 3 -> 1; 2 -> 2; else -> 4 }
        return FrameHeader(
            mpeg1 = mpeg1,
            layer = layer,
            bitrateKbps = bitrate,
            sampleRate = rate,
            padding = (b.u8(i + 2) shr 1) and 1,
            mono = (b.u8(i + 3) shr 6) == 3,
        )
    }

    /** True when [b] at [at] begins with what looks like an MPEG audio frame (and the next one). */
    fun looksLikeMpeg(b: ByteArray, at: Int = 0): Boolean {
        val h = header(b, at) ?: return false
        val next = at + h.frameLength
        return next + 4 > b.size || header(b, next) != null
    }

    /** Reads the audio properties of the MP3 whose audio starts at or after [audioStart]. */
    suspend fun parse(reader: RangeReader, audioStart: Long): AudioTags {
        val buf = reader.read(audioStart, SYNC_SEARCH_BYTES)
        var i = 0
        while (i + 4 <= buf.size) {
            val h = header(buf, i)
            // A false sync inside junk is common; require the following frame to line up too.
            if (h != null && (i + h.frameLength + 4 > buf.size || header(buf, i + h.frameLength) != null)) {
                return fromFrame(buf, i, h, reader.size - (audioStart + i))
            }
            i++
        }
        return AudioTags.EMPTY
    }

    private fun fromFrame(buf: ByteArray, at: Int, h: FrameHeader, audioBytes: Long): AudioTags {
        val sideInfo = if (h.mpeg1) (if (h.mono) 17 else 32) else (if (h.mono) 9 else 17)
        val xingAt = at + 4 + sideInfo
        var frames: Long? = null
        if (buf.startsWithAscii("Xing", xingAt) || buf.startsWithAscii("Info", xingAt)) {
            if (xingAt + 12 <= buf.size && buf.u32be(xingAt + 4) and 1L != 0L) frames = buf.u32be(xingAt + 8)
        } else {
            val vbriAt = at + 4 + 32
            if (buf.startsWithAscii("VBRI", vbriAt) && vbriAt + 18 <= buf.size) frames = buf.u32be(vbriAt + 14)
        }
        val durationMs = if (frames != null && frames > 0) {
            frames * h.samplesPerFrame * 1000L / h.sampleRate
        } else {
            audioBytes * 8L / h.bitrateKbps // bytes*8 / (kbit/s) = ms
        }
        val bitrate = if (frames != null && frames > 0 && durationMs > 0) {
            (audioBytes * 8L / durationMs).toInt()
        } else {
            h.bitrateKbps
        }
        return AudioTags(
            durationMs = durationMs.takeIf { it > 0 },
            sampleRateHz = h.sampleRate,
            bitrateKbps = bitrate.takeIf { it > 0 },
            codec = "mp3",
        )
    }
}

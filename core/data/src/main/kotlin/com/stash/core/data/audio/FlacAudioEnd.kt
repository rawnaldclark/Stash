package com.stash.core.data.audio

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * How long a FLAC file's audio really runs, read from its last frame rather than its header.
 *
 * A FLAC's header (STREAMINFO) states a length, and that is what Android's `MediaMetadataRetriever`
 * reports. It can lie. Tidal answers a seat that may not play a track with a 30 s PREVIEW whose init
 * segment still carries the whole song's STREAMINFO, and remuxing those segments to FLAC keeps it:
 * "Linger" on the Pixel 5 (2026-11-13) said 4:34.77 and stopped at 0:29.91. Every FLAC frame header
 * names where its frame starts (a frame number, or a sample number), so the last frame in the file
 * says where the audio ends, without decoding a sample.
 *
 * Cheap: the metadata blocks and at most [MAX_TAIL_BYTES] of the file's end are read. Pure JVM (no
 * Android), so it is tested on real bytes.
 *
 * **Finding the last frame.** Frames have no length field, so the tail is scanned backwards for the
 * 14-bit frame sync. Audio data can hold those two bytes by chance, so a candidate counts only when
 * its header is well formed, agrees with STREAMINFO (sample rate, channels, sample size, block size),
 * passes its CRC-8, and is confirmed by the frame before it: that frame must end exactly where this one
 * starts. A lone candidate is believed only when it is the first frame after the metadata. Anything
 * this cannot settle (no STREAMINFO, no two frames that chain in the window) is null: unknown, never a
 * reason to refuse a file.
 */
object FlacAudioEnd {

    /** What a FLAC's header says, and where its frames end. */
    data class Lengths(
        /** STREAMINFO's sample rate, in Hz. */
        val sampleRate: Int,
        /** STREAMINFO's total samples; 0 when the header does not say. */
        val headerSamples: Long,
        /** One past the last sample of the last frame in the file. */
        val frameSamples: Long,
    ) {
        /** The length the header states, in ms; null when it states none. */
        val headerMs: Long? get() = if (headerSamples > 0L) headerSamples * 1_000L / sampleRate else null

        /** The length the frames run, in ms. */
        val framesMs: Long get() = frameSamples * 1_000L / sampleRate
    }

    /** The most of a file's end that is read: several of the largest frames any real FLAC has. */
    const val MAX_TAIL_BYTES = 1024 * 1024

    /** At least this much of the end is read, whatever STREAMINFO says the largest frame is. */
    private const val MIN_TAIL_BYTES = 64 * 1024

    /** A file with more metadata blocks than this is not one this reads. */
    private const val MAX_METADATA_BLOCKS = 1024

    /** Whether [file] starts as a FLAC does (`fLaC`, after an ID3v2 tag when it has one). */
    fun isFlac(file: File): Boolean = try {
        RandomAccessFile(file, "r").use { raf -> audioMarkerAt(raf.asBytes()) != null }
    } catch (e: IOException) {
        false
    }

    /** [isFlac] for an open file, such as a storage-access document's (read from its start; the position is not used). */
    fun isFlac(channel: FileChannel): Boolean = try {
        audioMarkerAt(channel.asBytes()) != null
    } catch (e: IOException) {
        false
    }

    /** [file]'s header and frame lengths; null when it is not a FLAC, or its end can't be settled. */
    fun read(file: File): Lengths? = try {
        RandomAccessFile(file, "r").use { read(it.asBytes()) }
    } catch (e: IOException) {
        null
    }

    /** [read] for an open file, such as a storage-access document's. The caller closes [channel]. */
    fun read(channel: FileChannel): Lengths? = try {
        read(channel.asBytes())
    } catch (e: IOException) {
        null
    }

    internal fun read(raf: RandomAccessFile): Lengths? = read(raf.asBytes())

    private fun read(bytes: Bytes): Lengths? {
        val info = streamInfo(bytes) ?: return null
        val length = bytes.length()
        val available = length - info.audioStart
        if (available < MIN_HEADER_BYTES) return null
        // Three of the largest frames, so the last frame and the one before it are both in the window.
        val wanted = if (info.maxFrameBytes > 0) 3L * info.maxFrameBytes + MIN_HEADER_BYTES else MAX_TAIL_BYTES.toLong()
        val window = minOf(available, wanted.coerceIn(MIN_TAIL_BYTES.toLong(), MAX_TAIL_BYTES.toLong())).toInt()
        val windowStart = length - window
        val tail = ByteArray(window)
        bytes.readFully(windowStart, tail)
        val end = lastFrameEnd(tail, windowStart, info) ?: return null
        return Lengths(info.sampleRate, info.totalSamples, end)
    }

    /** What the reader needs of a file: its length, and bytes at a position. */
    private interface Bytes {
        fun length(): Long

        /** Fills [buf] from [pos]; an [EOFException] when the file ends first. */
        fun readFully(pos: Long, buf: ByteArray)
    }

    private fun RandomAccessFile.asBytes(): Bytes = object : Bytes {
        override fun length(): Long = this@asBytes.length()
        override fun readFully(pos: Long, buf: ByteArray) {
            seek(pos)
            this@asBytes.readFully(buf)
        }
    }

    private fun FileChannel.asBytes(): Bytes = object : Bytes {
        override fun length(): Long = size()
        override fun readFully(pos: Long, buf: ByteArray) {
            val into = ByteBuffer.wrap(buf)
            while (into.hasRemaining()) {
                if (read(into, pos + into.position()) < 0) throw EOFException()
            }
        }
    }

    /** What the scan needs from STREAMINFO, and where the first frame starts. */
    internal class StreamInfo(
        val maxBlock: Int,
        val maxFrameBytes: Int,
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val totalSamples: Long,
        val audioStart: Long,
    )

    /** Where `fLaC` is in [bytes] (0, or just after an ID3v2 tag); null when it is not there. */
    private fun audioMarkerAt(bytes: Bytes): Long? {
        val length = bytes.length()
        if (length < 4) return null
        val head = ByteArray(minOf(10L, length).toInt())
        bytes.readFully(0, head)
        var at = 0L
        if (head.size >= 10 && head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) {
            // "ID3", two version bytes, flags, then the tag's length in four 7-bit bytes; a footer adds 10.
            val size = ((head[6].toInt() and 0x7F) shl 21) or ((head[7].toInt() and 0x7F) shl 14) or
                ((head[8].toInt() and 0x7F) shl 7) or (head[9].toInt() and 0x7F)
            at = 10L + size + if ((head[5].toInt() and 0x10) != 0) 10 else 0
        }
        if (at + 4 > length) return null
        val marker = ByteArray(4)
        bytes.readFully(at, marker)
        val isFlac = marker[0] == 'f'.code.toByte() && marker[1] == 'L'.code.toByte() &&
            marker[2] == 'a'.code.toByte() && marker[3] == 'C'.code.toByte()
        return if (isFlac) at else null
    }

    /** STREAMINFO (the first metadata block, as the format requires) and where the audio starts. */
    private fun streamInfo(bytes: Bytes): StreamInfo? {
        val length = bytes.length()
        var pos = (audioMarkerAt(bytes) ?: return null) + 4
        var info: StreamInfo? = null
        val blockHeader = ByteArray(4)
        for (block in 0 until MAX_METADATA_BLOCKS) {
            if (pos + 4 > length) return null
            bytes.readFully(pos, blockHeader)
            val last = (blockHeader[0].toInt() and 0x80) != 0
            val type = blockHeader[0].toInt() and 0x7F
            val size = ((blockHeader[1].toInt() and 0xFF) shl 16) or ((blockHeader[2].toInt() and 0xFF) shl 8) or
                (blockHeader[3].toInt() and 0xFF)
            if (block == 0) {
                if (type != 0 || size < STREAMINFO_BYTES || pos + 4 + STREAMINFO_BYTES > length) return null
                val b = ByteArray(STREAMINFO_BYTES)
                bytes.readFully(pos + 4, b)
                info = parseStreamInfo(b) ?: return null
            }
            pos += 4L + size
            if (pos > length) return null
            if (last) return info?.let { StreamInfo(it.maxBlock, it.maxFrameBytes, it.sampleRate, it.channels, it.bitsPerSample, it.totalSamples, pos) }
        }
        return null
    }

    private fun parseStreamInfo(b: ByteArray): StreamInfo? {
        fun u(i: Int) = b[i].toInt() and 0xFF
        val maxBlock = (u(2) shl 8) or u(3)
        val maxFrame = (u(7) shl 16) or (u(8) shl 8) or u(9)
        val rate = (u(10) shl 12) or (u(11) shl 4) or (u(12) shr 4)
        val channels = ((u(12) shr 1) and 0x07) + 1
        val bits = (((u(12) and 0x01) shl 4) or (u(13) shr 4)) + 1
        val total = ((u(13) and 0x0F).toLong() shl 32) or
            (u(14).toLong() shl 24) or (u(15).toLong() shl 16) or (u(16).toLong() shl 8) or u(17).toLong()
        if (rate <= 0 || maxBlock < MIN_BLOCK) return null
        return StreamInfo(maxBlock, maxFrame, rate, channels, bits, total, audioStart = 0L)
    }

    /** One frame header found in the tail: where it is, and the samples it covers. */
    private class Frame(val at: Long, val startSample: Long, val endSample: Long)

    /**
     * The end, in samples, of the last frame in [tail] (the file's bytes from [tailStart]) that the
     * frame before it confirms (the class doc). Null when no frame in the window is confirmed.
     */
    private fun lastFrameEnd(tail: ByteArray, tailStart: Long, info: StreamInfo): Long? {
        // Candidates later in the file, not yet confirmed: a frame found before them may end where one starts.
        val waiting = ArrayList<Frame>()
        var i = tail.size - 2
        while (i >= 0) {
            if (tail[i] == 0xFF.toByte() && (tail[i + 1].toInt() and 0xFE) == 0xF8) {
                val frame = parseFrameHeader(tail, i, tailStart + i, info)
                if (frame != null) {
                    // This frame confirms whichever waiting one starts where it ends: the latest such is the answer.
                    val confirmed = waiting.filter { it.startSample == frame.endSample }.maxByOrNull { it.endSample }
                    if (confirmed != null) return confirmed.endSample
                    // The first frame after the metadata needs no frame before it. Reached with nothing
                    // confirmed, it is the only frame: every later real one would have been confirmed by
                    // the one before it, so what is still waiting was in its own data.
                    if (frame.at == info.audioStart && frame.startSample == 0L) return frame.endSample
                    waiting += frame
                }
            }
            i--
        }
        return null
    }

    /**
     * The frame header at [p] in [buf] ([at] in the file), or null when the bytes there are not a
     * frame header of this stream. Layout per RFC 9639 §9.1.
     */
    private fun parseFrameHeader(buf: ByteArray, p: Int, at: Long, info: StreamInfo): Frame? {
        if (p + MIN_HEADER_BYTES > buf.size) return null
        fun u(i: Int) = buf[i].toInt() and 0xFF
        val variable = (u(p + 1) and 0x01) == 1
        val blockCode = u(p + 2) shr 4
        val rateCode = u(p + 2) and 0x0F
        val channelCode = u(p + 3) shr 4
        val sizeCode = (u(p + 3) shr 1) and 0x07
        if (u(p + 3) and 0x01 != 0) return null // reserved bit
        if (blockCode == 0 || rateCode == 15 || sizeCode == 3) return null // reserved codes

        // Channels: 0-7 = that many plus one, 8-10 = the stereo decorrelation modes; others reserved.
        val channels = when (channelCode) {
            in 0..7 -> channelCode + 1
            in 8..10 -> 2
            else -> return null
        }
        if (channels != info.channels) return null
        val bits = when (sizeCode) {
            0 -> info.bitsPerSample
            1 -> 8
            2 -> 12
            4 -> 16
            5 -> 20
            6 -> 24
            else -> 32
        }
        if (bits != info.bitsPerSample) return null

        // The coded number: UTF-8-style, 1-7 bytes (7 only for a sample number).
        var q = p + 4
        val first = u(q++)
        val extra = when {
            first and 0x80 == 0 -> 0
            first and 0xE0 == 0xC0 -> 1
            first and 0xF0 == 0xE0 -> 2
            first and 0xF8 == 0xF0 -> 3
            first and 0xFC == 0xF8 -> 4
            first and 0xFE == 0xFC -> 5
            first == 0xFE -> 6
            else -> return null
        }
        if (!variable && extra > 5) return null // a frame number has at most 31 bits
        var number = if (extra == 0) first.toLong() else (first and (0x3F shr extra)).toLong()
        repeat(extra) {
            if (q >= buf.size) return null
            val c = u(q++)
            if (c and 0xC0 != 0x80) return null
            number = (number shl 6) or (c and 0x3F).toLong()
        }

        val blockSize = when (blockCode) {
            1 -> 192
            in 2..5 -> 576 shl (blockCode - 2)
            6 -> if (q < buf.size) u(q++) + 1 else return null
            7 -> if (q + 1 < buf.size) (((u(q) shl 8) or u(q + 1)) + 1).also { q += 2 } else return null
            else -> 256 shl (blockCode - 8)
        }
        if (blockSize > info.maxBlock) return null

        val rate = when (rateCode) {
            0 -> info.sampleRate
            1 -> 88_200
            2 -> 176_400
            3 -> 192_000
            4 -> 8_000
            5 -> 16_000
            6 -> 22_050
            7 -> 24_000
            8 -> 32_000
            9 -> 44_100
            10 -> 48_000
            11 -> 96_000
            12 -> if (q < buf.size) u(q++) * 1_000 else return null
            13 -> if (q + 1 < buf.size) ((u(q) shl 8) or u(q + 1)).also { q += 2 } else return null
            else -> if (q + 1 < buf.size) (((u(q) shl 8) or u(q + 1)) * 10).also { q += 2 } else return null
        }
        if (rate != info.sampleRate) return null

        if (q >= buf.size || crc8(buf, p, q) != u(q)) return null

        // A fixed-blocksize stream numbers its frames; every frame but the last holds the stream's block size.
        val start = if (variable) number else number * info.maxBlock
        return Frame(at, start, start + blockSize)
    }

    /** CRC-8 of [buf] from [from] up to [to]: polynomial x^8 + x^2 + x + 1, initial value 0. */
    internal fun crc8(buf: ByteArray, from: Int, to: Int): Int {
        var crc = 0
        for (i in from until to) {
            crc = crc xor (buf[i].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF }
        }
        return crc
    }

    private const val STREAMINFO_BYTES = 34

    /** The smallest block size the format allows (RFC 9639 §8.2). */
    private const val MIN_BLOCK = 16

    /** Sync, two code bytes, a one-byte number and the CRC-8. */
    private const val MIN_HEADER_BYTES = 6
}

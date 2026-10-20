package com.stash.data.cloud.tags

import java.nio.charset.Charset

/**
 * ID3v2.2 / 2.3 / 2.4 reader for the frames Stash shows: title, artist, album, album artist,
 * track/disc number, year, length and the cover picture.
 *
 * Only ever reads the tag itself (its size is in the 10-byte header), never the audio.
 * Compressed and encrypted frames are skipped; unsynchronisation is undone.
 */
internal object Id3v2Parser {

    const val HEADER_SIZE = 10

    /**
     * The total byte length of the ID3v2 tag starting at [at] in [head] (header + body +
     * footer), or 0 when there is none.
     */
    fun tagLength(head: ByteArray, at: Int = 0): Int {
        if (head.size < at + HEADER_SIZE || !head.startsWithAscii("ID3", at)) return 0
        val major = head.u8(at + 3)
        if (major !in 2..4) return 0
        val body = syncsafe(head, at + 6) ?: return 0
        val footer = if (major == 4 && head.u8(at + 5) and 0x10 != 0) HEADER_SIZE else 0
        return HEADER_SIZE + body + footer
    }

    /** Parses the tag at the start of [reader]; [AudioTags.EMPTY] when there is none. */
    suspend fun parse(reader: RangeReader, at: Long = 0): AudioTags {
        val header = reader.read(at, HEADER_SIZE)
        val total = tagLength(header)
        if (total == 0) return AudioTags.EMPTY
        val tag = reader.read(at, total)
        return parseTag(tag)
    }

    /** Parses a whole tag (header included) already in memory. */
    fun parseTag(tag: ByteArray): AudioTags {
        if (tagLength(tag) == 0) return AudioTags.EMPTY
        val major = tag.u8(3)
        val flags = tag.u8(5)
        val bodyLen = minOf(syncsafe(tag, 6) ?: return AudioTags.EMPTY, tag.size - HEADER_SIZE)
        var body = tag.copyOfRange(HEADER_SIZE, HEADER_SIZE + bodyLen)
        // v2.2/2.3 unsynchronise the whole tag; v2.4 does it per frame.
        if (major < 4 && flags and 0x80 != 0) body = removeUnsync(body)
        var pos = 0
        if (flags and 0x40 != 0 && major >= 3) {
            // Extended header: v2.3 size excludes its own 4 bytes, v2.4 is syncsafe and includes them.
            pos = if (major == 3) (body.u32be(0).toInt() + 4) else (syncsafe(body, 0) ?: return AudioTags.EMPTY)
        }

        val frames = Frames()
        val idLen = if (major == 2) 3 else 4
        val headerLen = if (major == 2) 6 else 10
        while (pos + headerLen <= body.size) {
            if (body.u8(pos) == 0) break // padding
            val id = body.ascii(pos, idLen)
            if (!id.all { it.isLetterOrDigit() }) break
            val size = when (major) {
                2 -> body.u24be(pos + 3)
                3 -> body.u32be(pos + 4).toInt()
                else -> frameSize24(body, pos + 4)
            }
            if (size <= 0 || pos + headerLen + size > body.size) break
            val frameFlags = if (major == 2) 0 else body.u16be(pos + 8)
            var data = body.copyOfRange(pos + headerLen, pos + headerLen + size)
            pos += headerLen + size
            data = unwrapFrame(major, frameFlags, data) ?: continue
            frames.accept(id, data)
        }
        return frames.toTags()
    }

    /**
     * v2.4 frame sizes are syncsafe, but some writers (old iTunes) wrote plain 32-bit sizes.
     * A byte with its top bit set cannot be syncsafe, so it must be plain.
     */
    private fun frameSize24(b: ByteArray, i: Int): Int =
        syncsafe(b, i) ?: b.u32be(i).toInt()

    /** Applies per-frame flags; null when the frame can't be read (compressed / encrypted). */
    private fun unwrapFrame(major: Int, flags: Int, data: ByteArray): ByteArray? {
        var d = data
        when (major) {
            3 -> {
                if (flags and 0x0080 != 0 || flags and 0x0040 != 0) return null // compression / encryption
                if (flags and 0x0020 != 0) d = d.copyOfRange(minOf(1, d.size), d.size) // grouping id
            }
            4 -> {
                if (flags and 0x0008 != 0 || flags and 0x0004 != 0) return null
                if (flags and 0x0040 != 0) d = d.copyOfRange(minOf(1, d.size), d.size) // grouping id
                if (flags and 0x0001 != 0) d = d.copyOfRange(minOf(4, d.size), d.size) // data length indicator
                if (flags and 0x0002 != 0) d = removeUnsync(d)
            }
        }
        return d
    }

    private class Frames {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var albumArtist: String? = null
        var track: Int? = null
        var disc: Int? = null
        var year: Int? = null
        var lengthMs: Long? = null
        var picture: EmbeddedPicture? = null
        var pictureIsFront = false

        fun accept(id: String, data: ByteArray) {
            when (id) {
                "TIT2", "TT2" -> title = title ?: text(data)
                "TPE1", "TP1" -> artist = artist ?: text(data)
                "TALB", "TAL" -> album = album ?: text(data)
                "TPE2", "TP2" -> albumArtist = albumArtist ?: text(data)
                "TRCK", "TRK" -> track = track ?: parseLeadingInt(text(data))
                "TPOS", "TPA" -> disc = disc ?: parseLeadingInt(text(data))
                "TDRC", "TYER", "TYE", "TDOR", "TORY" -> year = year ?: parseYear(text(data))
                "TLEN", "TLE" -> lengthMs = lengthMs ?: text(data)?.toLongOrNull()?.takeIf { it > 0 }
                "APIC" -> picture(data, v22 = false)
                "PIC" -> picture(data, v22 = true)
            }
        }

        private fun picture(data: ByteArray, v22: Boolean) {
            if (pictureIsFront || data.size < 4) return
            val enc = data.u8(0)
            var p = 1
            val mime: String
            if (v22) {
                val fmt = data.ascii(1, 3).uppercase()
                mime = when (fmt) { "PNG" -> "image/png"; "JPG" -> "image/jpeg"; else -> "" }
                p = 4
            } else {
                val end = indexOfTerminator(data, p, 0)
                mime = String(data, p, end - p, Charsets.ISO_8859_1).lowercase().let {
                    // Some writers put just "jpg" / "png".
                    if ('/' in it || it.isEmpty()) it else "image/$it"
                }
                p = end + 1
            }
            if (p >= data.size) return
            val type = data.u8(p)
            p++
            p = indexOfTerminator(data, p, enc) + terminatorWidth(enc)
            if (p >= data.size) return
            val bytes = data.copyOfRange(p, data.size)
            if (bytes.isEmpty() || bytes.size > MAX_PICTURE_BYTES) return
            val isFront = type == PICTURE_TYPE_FRONT_COVER
            if (picture == null || isFront) {
                picture = EmbeddedPicture(mime, bytes)
                pictureIsFront = isFront
            }
        }

        fun toTags() = AudioTags(
            title = title, artist = artist, album = album, albumArtist = albumArtist,
            trackNumber = track, discNumber = disc, year = year, durationMs = lengthMs, picture = picture,
        )
    }

    /** Decodes a text frame: encoding byte, then one or more NUL-separated values (joined). */
    internal fun text(data: ByteArray): String? {
        if (data.isEmpty()) return null
        val enc = data.u8(0)
        val raw = decode(data, 1, data.size, enc)
        return raw.split('\u0000').mapNotNull { it.cleanTag() }.distinct().joinToString(", ").cleanTag()
    }

    private fun charsetOf(enc: Int): Charset = when (enc) {
        1 -> Charsets.UTF_16 // BOM-led
        2 -> Charsets.UTF_16BE
        3 -> Charsets.UTF_8
        else -> Charsets.ISO_8859_1
    }

    private fun decode(data: ByteArray, from: Int, to: Int, enc: Int): String {
        if (to <= from) return ""
        if (enc == 1) {
            // UTF-16 with a BOM per value; decode each NUL-NUL separated value on its own so a
            // second value's BOM doesn't come out as U+FEFF.
            val parts = mutableListOf<String>()
            var s = from
            var i = from
            while (i + 1 < to) {
                if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                    parts += String(data, s, i - s, Charsets.UTF_16)
                    s = i + 2
                }
                i += 2
            }
            if (s < to) parts += String(data, s, to - s, Charsets.UTF_16)
            return parts.joinToString("\u0000")
        }
        return String(data, from, to - from, charsetOf(enc))
    }

    private fun terminatorWidth(enc: Int) = if (enc == 1 || enc == 2) 2 else 1

    /** Index of the string terminator at or after [from] for [enc], or data.size when missing. */
    private fun indexOfTerminator(data: ByteArray, from: Int, enc: Int): Int {
        if (terminatorWidth(enc) == 1) {
            for (i in from until data.size) if (data[i] == 0.toByte()) return i
            return data.size
        }
        var i = from
        while (i + 1 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) return i
            i += 2
        }
        return data.size
    }

    /** A 4-byte syncsafe integer, or null when a byte has its top bit set (not syncsafe). */
    private fun syncsafe(b: ByteArray, i: Int): Int? {
        if (i + 4 > b.size) return null
        if ((b.u8(i) or b.u8(i + 1) or b.u8(i + 2) or b.u8(i + 3)) and 0x80 != 0) return null
        return (b.u8(i) shl 21) or (b.u8(i + 1) shl 14) or (b.u8(i + 2) shl 7) or b.u8(i + 3)
    }

    /** Undoes ID3 unsynchronisation: every `FF 00` becomes `FF`. */
    private fun removeUnsync(b: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            out.write(b[i].toInt())
            if (b.u8(i) == 0xFF && i + 1 < b.size && b[i + 1] == 0.toByte()) i++
            i++
        }
        return out.toByteArray()
    }
}

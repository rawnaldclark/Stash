package com.stash.data.cloud.tags

import java.util.Base64

/**
 * Vorbis comments (FLAC's VORBIS_COMMENT block, Ogg Vorbis and Opus comment headers):
 * little-endian vendor string, then `KEY=value` pairs. Keys are case-insensitive.
 */
internal object VorbisComments {

    /** Parses a comment list starting at [from] in [b] (right after any codec-specific magic). */
    fun parse(b: ByteArray, from: Int): AudioTags {
        val values = linkedMapOf<String, MutableList<String>>()
        var p = from
        if (p + 4 > b.size) return AudioTags.EMPTY
        val vendorLen = b.u32le(p)
        p += 4
        if (vendorLen < 0 || p + vendorLen > b.size) return AudioTags.EMPTY
        p += vendorLen.toInt()
        if (p + 4 > b.size) return AudioTags.EMPTY
        val count = b.u32le(p)
        p += 4
        var n = 0L
        while (n < count && p + 4 <= b.size) {
            val len = b.u32le(p)
            p += 4
            if (len < 0 || p + len > b.size) break
            val entry = String(b, p, len.toInt(), Charsets.UTF_8)
            p += len.toInt()
            n++
            val eq = entry.indexOf('=')
            if (eq <= 0) continue
            values.getOrPut(entry.substring(0, eq).uppercase()) { mutableListOf() } += entry.substring(eq + 1)
        }
        fun first(vararg keys: String) = keys.firstNotNullOfOrNull { k -> values[k]?.firstNotNullOfOrNull { it.cleanTag() } }
        fun joined(key: String) = values[key]?.mapNotNull { it.cleanTag() }?.distinct()?.joinToString(", ")?.cleanTag()

        val picture = values["METADATA_BLOCK_PICTURE"]?.firstNotNullOfOrNull { encoded ->
            runCatching { Base64.getDecoder().decode(encoded.trim()) }.getOrNull()?.let { FlacParser.pictureFromBlock(it) }
        }
        return AudioTags(
            title = first("TITLE"),
            artist = joined("ARTIST"),
            album = first("ALBUM"),
            albumArtist = first("ALBUMARTIST", "ALBUM ARTIST", "ALBUM_ARTIST"),
            trackNumber = parseLeadingInt(first("TRACKNUMBER")),
            discNumber = parseLeadingInt(first("DISCNUMBER")),
            year = parseYear(first("DATE", "YEAR", "ORIGINALDATE")),
            picture = picture,
        )
    }
}

/**
 * FLAC metadata blocks: STREAMINFO (duration, sample rate, bit depth), VORBIS_COMMENT and
 * PICTURE. Walks block headers through the [RangeReader], so skipping a large SEEKTABLE or
 * PADDING block costs nothing, and only the chosen picture's bytes are read.
 */
internal object FlacParser {

    private const val TYPE_STREAMINFO = 0
    private const val TYPE_VORBIS_COMMENT = 4
    private const val TYPE_PICTURE = 6

    /** Largest VORBIS_COMMENT block read (an embedded base64 picture can make them big). */
    private const val MAX_COMMENT_BYTES = 8 * 1024 * 1024

    /** Most blocks walked before giving up on a corrupt stream. */
    private const val MAX_BLOCKS = 256

    private class PictureRef(val offset: Long, val length: Int, val type: Int)

    /** Parses the FLAC stream whose `fLaC` marker is at [at]. */
    suspend fun parse(reader: RangeReader, at: Long): AudioTags {
        if (!reader.read(at, 4).startsWithAscii("fLaC")) return AudioTags.EMPTY
        var pos = at + 4
        var info = AudioTags(codec = "flac")
        var comments = AudioTags.EMPTY
        val pictures = mutableListOf<PictureRef>()
        for (i in 0 until MAX_BLOCKS) {
            val h = reader.read(pos, 4)
            if (h.size < 4) break
            val last = h.u8(0) and 0x80 != 0
            val type = h.u8(0) and 0x7F
            val len = h.u24be(1)
            val body = pos + 4
            when (type) {
                TYPE_STREAMINFO -> info = streamInfo(reader.read(body, minOf(len, 34)))
                TYPE_VORBIS_COMMENT -> if (len <= MAX_COMMENT_BYTES) {
                    comments = VorbisComments.parse(reader.read(body, len), 0)
                }
                TYPE_PICTURE -> {
                    // Picture type is the block's first 4 bytes; the data comes later.
                    val pType = reader.read(body, 4).takeIf { it.size == 4 }?.u32be(0)?.toInt() ?: -1
                    pictures += PictureRef(body, len, pType)
                }
            }
            if (last || type == 127) break
            pos = body + len
        }
        val chosen = pictures.firstOrNull { it.type == PICTURE_TYPE_FRONT_COVER } ?: pictures.firstOrNull()
        val picture = chosen?.takeIf { it.length <= MAX_PICTURE_BYTES + 4096 }?.let {
            pictureFromBlock(reader.read(it.offset, it.length))
        }
        return comments.copy(picture = comments.picture ?: picture).orElse(info)
    }

    /** STREAMINFO: 20-bit sample rate, 5-bit bits-per-sample - 1, 36-bit total samples. */
    internal fun streamInfo(b: ByteArray): AudioTags {
        if (b.size < 18) return AudioTags(codec = "flac")
        val rate = (b.u8(10) shl 12) or (b.u8(11) shl 4) or (b.u8(12) shr 4)
        val bps = (((b.u8(12) and 0x01) shl 4) or (b.u8(13) shr 4)) + 1
        val total = ((b.u8(13) and 0x0F).toLong() shl 32) or b.u32be(14)
        return AudioTags(
            durationMs = if (rate > 0 && total > 0) total * 1000L / rate else null,
            sampleRateHz = rate.takeIf { it > 0 },
            bitsPerSample = bps,
            codec = "flac",
        )
    }

    /** A FLAC PICTURE block body (also what Ogg's METADATA_BLOCK_PICTURE base64-encodes). */
    internal fun pictureFromBlock(b: ByteArray): EmbeddedPicture? {
        var p = 4 // picture type
        if (p + 4 > b.size) return null
        val mimeLen = b.u32be(p).toInt(); p += 4
        if (mimeLen < 0 || p + mimeLen > b.size) return null
        val mime = String(b, p, mimeLen, Charsets.US_ASCII); p += mimeLen
        if (p + 4 > b.size) return null
        val descLen = b.u32be(p).toInt(); p += 4
        if (descLen < 0 || p + descLen > b.size) return null
        p += descLen + 16 // description, width, height, depth, colours
        if (p + 4 > b.size) return null
        val dataLen = b.u32be(p).toInt(); p += 4
        if (dataLen <= 0 || dataLen > MAX_PICTURE_BYTES || p + dataLen > b.size) return null
        return EmbeddedPicture(mime, b.copyOfRange(p, p + dataLen))
    }
}

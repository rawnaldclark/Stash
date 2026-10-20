package com.stash.data.cloud.tags

/**
 * What a cloud file's own bytes say about it. Every field is optional: a file may carry no tags
 * at all, and the scanner fills the gaps from the folder path ([CloudMetadataFallbacks]).
 */
data class AudioTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val durationMs: Long? = null,
    val sampleRateHz: Int? = null,
    val bitsPerSample: Int? = null,
    val bitrateKbps: Int? = null,
    /** The audio codec as the container declares it: flac, mp3, aac, alac, opus, vorbis, pcm. */
    val codec: String? = null,
    val picture: EmbeddedPicture? = null,
) {
    /** This, with every null field taken from [other]. */
    fun orElse(other: AudioTags): AudioTags = AudioTags(
        title = title ?: other.title,
        artist = artist ?: other.artist,
        album = album ?: other.album,
        albumArtist = albumArtist ?: other.albumArtist,
        trackNumber = trackNumber ?: other.trackNumber,
        discNumber = discNumber ?: other.discNumber,
        year = year ?: other.year,
        durationMs = durationMs ?: other.durationMs,
        sampleRateHz = sampleRateHz ?: other.sampleRateHz,
        bitsPerSample = bitsPerSample ?: other.bitsPerSample,
        bitrateKbps = bitrateKbps ?: other.bitrateKbps,
        codec = codec ?: other.codec,
        picture = picture ?: other.picture,
    )

    companion object {
        val EMPTY = AudioTags()
    }
}

/** Cover art found inside the file. [mimeType] may be blank when the tag didn't say. */
class EmbeddedPicture(val mimeType: String, val bytes: ByteArray) {
    override fun toString(): String = "EmbeddedPicture($mimeType, ${bytes.size} bytes)"
}

/** Cover-art type codes shared by ID3v2 APIC and FLAC PICTURE. */
internal const val PICTURE_TYPE_FRONT_COVER = 3

/** Largest embedded picture the scanner downloads; bigger art is skipped, not truncated. */
internal const val MAX_PICTURE_BYTES = 5 * 1024 * 1024

/** Trims, drops blanks and NULs. */
internal fun String?.cleanTag(): String? =
    this?.replace("\u0000", "")?.trim()?.takeIf { it.isNotEmpty() }

/** "3", "03", "3/12" → 3. Null for anything without a leading positive number. */
internal fun parseLeadingInt(raw: String?): Int? =
    raw?.trim()?.takeWhile { it.isDigit() }?.toIntOrNull()?.takeIf { it > 0 }

/** "2001", "2001-05-01", "2001-05-01T00:00" → 2001. */
internal fun parseYear(raw: String?): Int? =
    raw?.trim()?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }?.toInt()

// ── Byte helpers ─────────────────────────────────────────────────────────

internal fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF

internal fun ByteArray.u16be(i: Int): Int = (u8(i) shl 8) or u8(i + 1)

internal fun ByteArray.u24be(i: Int): Int = (u8(i) shl 16) or (u8(i + 1) shl 8) or u8(i + 2)

internal fun ByteArray.u32be(i: Int): Long =
    (u8(i).toLong() shl 24) or (u8(i + 1).toLong() shl 16) or (u8(i + 2).toLong() shl 8) or u8(i + 3).toLong()

internal fun ByteArray.u64be(i: Int): Long = (u32be(i) shl 32) or u32be(i + 4)

internal fun ByteArray.u16le(i: Int): Int = u8(i) or (u8(i + 1) shl 8)

internal fun ByteArray.u32le(i: Int): Long =
    u8(i).toLong() or (u8(i + 1).toLong() shl 8) or (u8(i + 2).toLong() shl 16) or (u8(i + 3).toLong() shl 24)

internal fun ByteArray.u64le(i: Int): Long = u32le(i) or (u32le(i + 4) shl 32)

internal fun ByteArray.ascii(i: Int, n: Int): String =
    if (i + n > size) "" else String(this, i, n, Charsets.ISO_8859_1)

/** True when [prefix] (ASCII) is at [at]. */
internal fun ByteArray.startsWithAscii(prefix: String, at: Int = 0): Boolean =
    at + prefix.length <= size && prefix.indices.all { this[at + it] == prefix[it].code.toByte() }

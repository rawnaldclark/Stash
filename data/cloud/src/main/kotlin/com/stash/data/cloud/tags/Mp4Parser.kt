package com.stash.data.cloud.tags

/**
 * MP4 / M4A (AAC and ALAC): iTunes-style tags from `moov/udta/meta/ilst`, the duration from
 * `mvhd`/`mdhd`, sample rate and bit depth from the first `stsd` entry.
 *
 * Walks box headers through the [RangeReader] instead of loading `moov` whole, so the big
 * sample tables (`stsz`, `stco`) are never fetched. `moov` may come after `mdat` (the audio):
 * the walk then jumps over the audio and reads from near the end of the file.
 */
internal object Mp4Parser {

    /** Most boxes visited at one level before giving up on a corrupt file. */
    private const val MAX_BOXES_PER_LEVEL = 512

    /** Containers the walk descends into. */
    private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "udta", "ilst")

    private class Box(val type: String, val start: Long, val headerSize: Int, val size: Long) {
        val bodyStart: Long get() = start + headerSize
        val end: Long get() = start + size
    }

    private class State {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var albumArtist: String? = null
        var track: Int? = null
        var disc: Int? = null
        var year: Int? = null
        var mvhdDurationMs: Long? = null
        var mdhdDurationMs: Long? = null
        var sampleRate: Int? = null
        var bits: Int? = null
        var codec: String? = null
        var picture: EmbeddedPicture? = null
        var sawMoov = false
    }

    /** True when [head] starts like an ISO-BMFF file (`ftyp` first). */
    fun looksLikeMp4(head: ByteArray): Boolean = head.startsWithAscii("ftyp", 4)

    suspend fun parse(reader: RangeReader): AudioTags {
        val s = State()
        walk(reader, 0, reader.size, s, depth = 0)
        if (!s.sawMoov) return AudioTags.EMPTY
        return AudioTags(
            title = s.title, artist = s.artist, album = s.album, albumArtist = s.albumArtist,
            trackNumber = s.track, discNumber = s.disc, year = s.year,
            durationMs = s.mdhdDurationMs ?: s.mvhdDurationMs,
            sampleRateHz = s.sampleRate, bitsPerSample = s.bits, codec = s.codec, picture = s.picture,
        )
    }

    private suspend fun readBox(reader: RangeReader, at: Long, limit: Long): Box? {
        if (at + 8 > limit) return null
        val h = reader.read(at, 16)
        if (h.size < 8) return null
        var size = h.u32be(0)
        val type = h.ascii(4, 4)
        var headerSize = 8
        when (size) {
            1L -> {
                if (h.size < 16) return null
                size = h.u64be(8); headerSize = 16
            }
            0L -> size = limit - at // to end of enclosing box / file
        }
        if (size < headerSize || at + size > limit) return null
        return Box(type, at, headerSize, size)
    }

    private suspend fun walk(reader: RangeReader, from: Long, to: Long, s: State, depth: Int) {
        var pos = from
        var n = 0
        while (pos < to && n++ < MAX_BOXES_PER_LEVEL) {
            val box = readBox(reader, pos, to) ?: return
            when (box.type) {
                "moov" -> { s.sawMoov = true; walk(reader, box.bodyStart, box.end, s, depth + 1) }
                "mvhd" -> s.mvhdDurationMs = timedDuration(reader.read(box.bodyStart, 32))
                "mdhd" -> if (s.mdhdDurationMs == null) s.mdhdDurationMs = timedDuration(reader.read(box.bodyStart, 32))
                "stsd" -> if (s.codec == null) sampleEntry(reader, box, s)
                "meta" -> meta(reader, box, s)
                in CONTAINERS -> walk(reader, box.bodyStart, box.end, s, depth + 1)
            }
            // Once moov is read there is nothing left to learn at the top level.
            if (depth == 0 && box.type == "moov") return
            pos = box.end
        }
    }

    /**
     * `meta` is a full box (4 bytes version/flags) in iTunes files but a plain box in some
     * QuickTime writers: tell them apart by whether a box header follows directly.
     */
    private suspend fun meta(reader: RangeReader, box: Box, s: State) {
        val peek = reader.read(box.bodyStart, 12)
        val plain = peek.size >= 8 && peek.ascii(4, 4).let { it == "hdlr" || it == "ilst" }
        val start = if (plain) box.bodyStart else box.bodyStart + 4
        var pos = start
        var n = 0
        while (pos < box.end && n++ < MAX_BOXES_PER_LEVEL) {
            val child = readBox(reader, pos, box.end) ?: return
            if (child.type == "ilst") ilst(reader, child, s)
            pos = child.end
        }
    }

    private suspend fun ilst(reader: RangeReader, box: Box, s: State) {
        var pos = box.bodyStart
        var n = 0
        while (pos < box.end && n++ < MAX_BOXES_PER_LEVEL) {
            val item = readBox(reader, pos, box.end) ?: return
            pos = item.end
            val key = item.type
            if (key == "covr" && item.size > MAX_PICTURE_BYTES + 64) continue
            val data = firstData(reader, item) ?: continue
            val (typeCode, payload) = data
            fun text() = String(payload, Charsets.UTF_8).cleanTag()
            when (key) {
                "©nam" -> s.title = s.title ?: text()
                "©ART" -> s.artist = s.artist ?: text()
                "©alb" -> s.album = s.album ?: text()
                "aART" -> s.albumArtist = s.albumArtist ?: text()
                "©day" -> s.year = s.year ?: parseYear(text())
                "trkn" -> if (payload.size >= 4) s.track = s.track ?: payload.u16be(2).takeIf { it > 0 }
                "disk" -> if (payload.size >= 4) s.disc = s.disc ?: payload.u16be(2).takeIf { it > 0 }
                "covr" -> if (s.picture == null && payload.isNotEmpty()) {
                    val mime = when (typeCode) { 14 -> "image/png"; 13 -> "image/jpeg"; else -> "" }
                    s.picture = EmbeddedPicture(mime, payload)
                }
            }
        }
    }

    /** The first `data` child of an ilst item: (type code, payload after type + locale). */
    private suspend fun firstData(reader: RangeReader, item: Box): Pair<Int, ByteArray>? {
        var pos = item.bodyStart
        var n = 0
        while (pos < item.end && n++ < 8) {
            val child = readBox(reader, pos, item.end) ?: return null
            if (child.type == "data" && child.size >= child.headerSize + 8) {
                val len = (child.size - child.headerSize).toInt()
                val body = reader.read(child.bodyStart, len)
                if (body.size < 8) return null
                return (body.u32be(0) and 0xFFFFFF).toInt() to body.copyOfRange(8, body.size)
            }
            pos = child.end
        }
        return null
    }

    /** `mvhd` / `mdhd` body: version 0 = 32-bit times, version 1 = 64-bit. */
    private fun timedDuration(b: ByteArray): Long? {
        if (b.isEmpty()) return null
        return if (b.u8(0) == 1) {
            if (b.size < 32) return null
            val scale = b.u32be(20); val dur = b.u64be(24)
            if (scale > 0 && dur > 0) dur * 1000L / scale else null
        } else {
            if (b.size < 20) return null
            val scale = b.u32be(12); val dur = b.u32be(16)
            if (scale > 0 && dur > 0 && dur != 0xFFFFFFFFL) dur * 1000L / scale else null
        }
    }

    /** First `stsd` entry: an AudioSampleEntry (`mp4a`, `alac`, …). */
    private suspend fun sampleEntry(reader: RangeReader, stsd: Box, s: State) {
        val entryAt = stsd.bodyStart + 8 // version/flags + entry count
        val entry = readBox(reader, entryAt, stsd.end) ?: return
        val b = reader.read(entry.bodyStart, 28)
        if (b.size < 28) return
        // 6 reserved + 2 data ref + 8 version/revision/vendor, then channels, sample size, …, rate (16.16).
        val sampleSize = b.u16be(18)
        val rate = (b.u32be(24) shr 16).toInt()
        s.codec = when (entry.type) {
            "mp4a" -> "aac"
            "alac" -> "alac"
            "fLaC" -> "flac"
            "Opus" -> "opus"
            else -> entry.type.trim().lowercase()
        }
        if (rate > 0) s.sampleRate = rate
        // Bit depth only means something for lossless codecs.
        if (s.codec == "alac" || s.codec == "flac") s.bits = sampleSize.takeIf { it > 0 }
        if (s.codec == "alac") alacCookie(reader, entry, s)
    }

    /**
     * The `alac` magic cookie inside an ALAC sample entry holds the true bit depth and sample
     * rate: the entry's 16.16 rate field can't hold 88.2/96/192 kHz.
     */
    private suspend fun alacCookie(reader: RangeReader, entry: Box, s: State) {
        val cookie = readBox(reader, entry.bodyStart + 28, entry.end) ?: return
        if (cookie.type != "alac") return
        val b = reader.read(cookie.bodyStart, 28)
        if (b.size < 28) return
        b.u8(9).takeIf { it > 0 }?.let { s.bits = it }
        b.u32be(24).toInt().takeIf { it > 0 }?.let { s.sampleRate = it }
    }
}

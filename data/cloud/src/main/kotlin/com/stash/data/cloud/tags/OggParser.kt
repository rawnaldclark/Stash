package com.stash.data.cloud.tags

/**
 * Ogg Vorbis and Ogg Opus: the identification header (sample rate, Opus pre-skip), the comment
 * header (tags, cover via METADATA_BLOCK_PICTURE) and the duration from the last page's
 * granule position, which needs one read of the file's tail.
 */
internal object OggParser {

    private const val PAGE_HEADER = 27

    /** Largest comment packet assembled (a base64 cover can make it big). */
    private const val MAX_PACKET_BYTES = 8 * 1024 * 1024

    /** How much of the tail to read looking for the last page. */
    private const val TAIL_BYTES = 64 * 1024

    private const val OPUS_RATE = 48_000

    suspend fun parse(reader: RangeReader): AudioTags {
        val packets = firstPackets(reader, count = 2) ?: return AudioTags.EMPTY
        val id = packets.getOrNull(0) ?: return AudioTags.EMPTY
        val comment = packets.getOrNull(1)
        return when {
            id.startsWithAscii("OpusHead") -> {
                val preSkip = if (id.size >= 12) id.u16le(10) else 0
                val tags = comment?.takeIf { it.startsWithAscii("OpusTags") }?.let { VorbisComments.parse(it, 8) }
                    ?: AudioTags.EMPTY
                val granule = lastGranule(reader)
                tags.copy(
                    durationMs = granule?.let { ((it - preSkip).coerceAtLeast(0)) * 1000L / OPUS_RATE },
                    sampleRateHz = OPUS_RATE,
                    codec = "opus",
                )
            }
            id.size >= 16 && id.u8(0) == 1 && id.startsWithAscii("vorbis", 1) -> {
                val rate = id.u32le(12).toInt()
                val bitrateNominal = if (id.size >= 24) id.u32le(20).toInt() else 0
                val tags = comment?.takeIf { it.size > 7 && it.u8(0) == 3 && it.startsWithAscii("vorbis", 1) }
                    ?.let { VorbisComments.parse(it, 7) } ?: AudioTags.EMPTY
                val granule = lastGranule(reader)
                tags.copy(
                    durationMs = if (rate > 0) granule?.let { it * 1000L / rate } else null,
                    sampleRateHz = rate.takeIf { it > 0 },
                    bitrateKbps = (bitrateNominal / 1000).takeIf { it > 0 },
                    codec = "vorbis",
                )
            }
            else -> AudioTags.EMPTY
        }
    }

    /** The first [count] packets of the first logical stream, assembled across pages. */
    private suspend fun firstPackets(reader: RangeReader, count: Int): List<ByteArray>? {
        val packets = mutableListOf<ByteArray>()
        var current = java.io.ByteArrayOutputStream()
        var pos = 0L
        var serial: Long? = null
        while (packets.size < count) {
            val h = reader.read(pos, PAGE_HEADER)
            if (h.size < PAGE_HEADER || !h.startsWithAscii("OggS")) return if (packets.isEmpty()) null else packets
            val pageSerial = h.u32le(14)
            val nSegs = h.u8(26)
            val table = reader.read(pos + PAGE_HEADER, nSegs)
            if (table.size < nSegs) return null
            var dataPos = pos + PAGE_HEADER + nSegs
            val bodyLen = (0 until nSegs).sumOf { table.u8(it) }
            if (serial == null) serial = pageSerial
            if (pageSerial == serial) {
                val body = reader.read(dataPos, bodyLen)
                var off = 0
                for (s in 0 until nSegs) {
                    val segLen = table.u8(s)
                    current.write(body, off, minOf(segLen, body.size - off))
                    off += segLen
                    if (current.size() > MAX_PACKET_BYTES) return packets.ifEmpty { null }
                    if (segLen < 255) {
                        packets += current.toByteArray()
                        current = java.io.ByteArrayOutputStream()
                        if (packets.size == count) break
                    }
                }
            }
            dataPos += bodyLen
            pos = dataPos
        }
        return packets
    }

    /** The granule position of the last page in the file's tail (total samples), if found. */
    private suspend fun lastGranule(reader: RangeReader): Long? {
        val start = maxOf(0L, reader.size - TAIL_BYTES)
        val tail = reader.read(start, (reader.size - start).toInt())
        var i = tail.size - PAGE_HEADER
        while (i >= 0) {
            if (tail.startsWithAscii("OggS", i)) {
                val g = tail.u64le(i + 6)
                if (g > 0) return g
            }
            i--
        }
        return null
    }
}

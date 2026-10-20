package com.stash.data.cloud.tags

/**
 * WAV (RIFF/WAVE): `fmt ` (rate, bit depth), `data` (length → duration), `LIST/INFO` tags and an
 * `id3 ` chunk when a tagger added one.
 */
internal object WavParser {

    private const val MAX_CHUNKS = 64
    private const val MAX_INFO_BYTES = 256 * 1024

    suspend fun parse(reader: RangeReader): AudioTags {
        val riff = reader.read(0, 12)
        if (!riff.startsWithAscii("RIFF") || !riff.startsWithAscii("WAVE", 8)) return AudioTags.EMPTY
        var pos = 12L
        var rate = 0
        var bits = 0
        var byteRate = 0L
        var dataLen = 0L
        var info = AudioTags.EMPTY
        var id3 = AudioTags.EMPTY
        var n = 0
        while (pos + 8 <= reader.size && n++ < MAX_CHUNKS) {
            val h = reader.read(pos, 8)
            if (h.size < 8) break
            val id = h.ascii(0, 4)
            val len = h.u32le(4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    val f = reader.read(body, 16)
                    if (f.size >= 16) {
                        rate = f.u32le(4).toInt(); byteRate = f.u32le(8); bits = f.u16le(14)
                    }
                }
                "data" -> dataLen = minOf(len, reader.size - body)
                "LIST" -> if (len in 4..MAX_INFO_BYTES) info = listInfo(reader.read(body, len.toInt()))
                "id3 ", "ID3 " -> if (len <= CachingRangeReader.DEFAULT_MAX_TOTAL_BYTES) {
                    id3 = Id3v2Parser.parseTag(reader.read(body, len.toInt()))
                }
            }
            pos = body + len + (len and 1L) // chunks are word-aligned
        }
        val format = AudioTags(
            durationMs = if (byteRate > 0 && dataLen > 0) dataLen * 1000L / byteRate else null,
            sampleRateHz = rate.takeIf { it > 0 },
            bitsPerSample = bits.takeIf { it > 0 },
            codec = "pcm",
        )
        return id3.orElse(info).orElse(format)
    }

    private fun listInfo(b: ByteArray): AudioTags {
        if (!b.startsWithAscii("INFO")) return AudioTags.EMPTY
        val values = mutableMapOf<String, String>()
        var p = 4
        while (p + 8 <= b.size) {
            val id = b.ascii(p, 4)
            val len = b.u32le(p + 4).toInt()
            if (len < 0 || p + 8 + len > b.size) break
            String(b, p + 8, len, Charsets.UTF_8).cleanTag()?.let { values[id] = it }
            p += 8 + len + (len and 1)
        }
        return AudioTags(
            title = values["INAM"], artist = values["IART"], album = values["IPRD"],
            trackNumber = parseLeadingInt(values["ITRK"] ?: values["IPRT"]), year = parseYear(values["ICRD"]),
        )
    }
}

/**
 * Reads a cloud file's tags from as few bytes as possible.
 *
 * The first read is [CachingRangeReader.HEAD_BYTES] (256 KB) from the start; everything else is
 * read on demand by the format's parser: the rest of a large ID3v2 tag, a FLAC PICTURE block,
 * an MP4 `moov` at the end of the file, an Ogg tail for the duration. The container is chosen
 * by its magic bytes, not the extension (a `.m4a` that is really an MP3 still reads).
 *
 * Never throws for malformed data: an unreadable file gives [AudioTags.EMPTY], and the scanner
 * falls back to folder/filename metadata. I/O errors (and [TagReadBudgetExceeded]) do propagate,
 * so the caller can tell "no tags" from "couldn't fetch".
 */
object TagReader {

    suspend fun read(reader: RangeReader): AudioTags {
        val head = reader.read(0, CachingRangeReader.HEAD_BYTES)
        if (head.size < 4) return AudioTags.EMPTY
        return try {
            when {
                head.startsWithAscii("ID3") -> afterId3(reader, head)
                head.startsWithAscii("fLaC") -> FlacParser.parse(reader, 0)
                head.startsWithAscii("OggS") -> OggParser.parse(reader)
                head.startsWithAscii("RIFF") -> WavParser.parse(reader)
                Mp4Parser.looksLikeMp4(head) -> Mp4Parser.parse(reader)
                MpegAudioParser.looksLikeMpeg(head) -> MpegAudioParser.parse(reader, 0)
                else -> AudioTags.EMPTY
            }
        } catch (e: TagReadBudgetExceeded) {
            throw e
        } catch (e: java.io.IOException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            // Index/argument errors from a truncated or corrupt structure: treat as untagged.
            AudioTags.EMPTY
        }
    }

    /** An ID3v2 tag can lead an MP3 or (rarely) a FLAC: parse the tag, then what follows it. */
    private suspend fun afterId3(reader: RangeReader, head: ByteArray): AudioTags {
        val tagLen = Id3v2Parser.tagLength(head).toLong()
        val id3 = Id3v2Parser.parse(reader, 0)
        val after = reader.read(tagLen, 4)
        val audio = if (after.startsWithAscii("fLaC")) FlacParser.parse(reader, tagLen)
        else MpegAudioParser.parse(reader, tagLen)
        // FLAC's own Vorbis comments win over a stray ID3 tag; for MP3 the tag is all there is.
        return if (audio.codec == "flac") audio.orElse(id3) else id3.orElse(audio).let {
            // The frame-derived duration beats TLEN, which taggers often get wrong.
            it.copy(durationMs = audio.durationMs ?: it.durationMs)
        }
    }
}

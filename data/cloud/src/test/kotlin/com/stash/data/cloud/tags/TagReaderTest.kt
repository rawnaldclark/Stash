package com.stash.data.cloud.tags

import com.google.common.truth.Truth.assertThat
import com.stash.data.cloud.tags.Fixtures.apic
import com.stash.data.cloud.tags.Fixtures.ascii
import com.stash.data.cloud.tags.Fixtures.box
import com.stash.data.cloud.tags.Fixtures.bytes
import com.stash.data.cloud.tags.Fixtures.flacBlock
import com.stash.data.cloud.tags.Fixtures.fullBox
import com.stash.data.cloud.tags.Fixtures.id3
import com.stash.data.cloud.tags.Fixtures.ilstCover
import com.stash.data.cloud.tags.Fixtures.ilstPair
import com.stash.data.cloud.tags.Fixtures.ilstText
import com.stash.data.cloud.tags.Fixtures.mp3Frames
import com.stash.data.cloud.tags.Fixtures.mvhd
import com.stash.data.cloud.tags.Fixtures.oggPage
import com.stash.data.cloud.tags.Fixtures.pictureBlock
import com.stash.data.cloud.tags.Fixtures.stsd
import com.stash.data.cloud.tags.Fixtures.streamInfo
import com.stash.data.cloud.tags.Fixtures.textFrame23
import com.stash.data.cloud.tags.Fixtures.textFrame24
import com.stash.data.cloud.tags.Fixtures.u16be
import com.stash.data.cloud.tags.Fixtures.u32be
import com.stash.data.cloud.tags.Fixtures.u32le
import com.stash.data.cloud.tags.Fixtures.u8
import com.stash.data.cloud.tags.Fixtures.vorbisComments
import com.stash.data.cloud.tags.Fixtures.zeros
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.Base64

class TagReaderTest {

    private suspend fun read(bytes: ByteArray) = TagReader.read(ByteArrayRangeReader(bytes))

    // ── ID3v2 + MP3 ──────────────────────────────────────────────────────

    @Test
    fun `id3v23 text frames, front cover preferred, CBR duration`() = runTest {
        val tag = id3(
            3,
            textFrame23("TIT2", "Idioteque"),
            textFrame23("TPE1", "Radiohead"),
            textFrame23("TALB", "Kid A", utf16 = true),
            textFrame23("TPE2", "Radiohead"),
            textFrame23("TRCK", "8/10"),
            textFrame23("TPOS", "1/1"),
            textFrame23("TYER", "2000"),
            apic(3, type = 0, image = Fixtures.PNG, mime = "image/png"),
            apic(3, type = 3, image = Fixtures.JPEG),
        )
        val audio = mp3Frames(100)
        val tags = read(tag + audio)

        assertThat(tags.title).isEqualTo("Idioteque")
        assertThat(tags.artist).isEqualTo("Radiohead")
        assertThat(tags.album).isEqualTo("Kid A")
        assertThat(tags.albumArtist).isEqualTo("Radiohead")
        assertThat(tags.trackNumber).isEqualTo(8)
        assertThat(tags.discNumber).isEqualTo(1)
        assertThat(tags.year).isEqualTo(2000)
        assertThat(tags.codec).isEqualTo("mp3")
        assertThat(tags.sampleRateHz).isEqualTo(44100)
        assertThat(tags.bitrateKbps).isEqualTo(128)
        // 100 frames * 417 bytes * 8 / 128 kbps
        assertThat(tags.durationMs).isEqualTo(100L * 417 * 8 / 128)
        assertThat(tags.picture!!.mimeType).isEqualTo("image/jpeg")
        assertThat(tags.picture!!.bytes).isEqualTo(Fixtures.JPEG)
    }

    @Test
    fun `id3v24 utf8 frames and multiple artists joined`() = runTest {
        val tag = id3(4, textFrame24("TIT2", "Café"), textFrame24("TPE1", "A\u0000B"), textFrame24("TDRC", "2019-04-01"))
        val tags = read(tag + mp3Frames(4))
        assertThat(tags.title).isEqualTo("Café")
        assertThat(tags.artist).isEqualTo("A, B")
        assertThat(tags.year).isEqualTo(2019)
    }

    @Test
    fun `xing header gives the VBR duration`() = runTest {
        val tags = read(id3(3, textFrame23("TIT2", "x")) + mp3Frames(5, xingFrames = 1000))
        // 1000 frames * 1152 samples / 44100 Hz
        assertThat(tags.durationMs).isEqualTo(1000L * 1152 * 1000 / 44100)
    }

    @Test
    fun `bare mp3 without a tag still reads its format`() = runTest {
        val tags = read(mp3Frames(10))
        assertThat(tags.codec).isEqualTo("mp3")
        assertThat(tags.title).isNull()
    }

    @Test
    fun `an id3 tag bigger than the head is read with a second exact fetch`() = runTest {
        val bigCover = ByteArray(400 * 1024) { (it % 251).toByte() }
        val bytes = id3(3, textFrame23("TIT2", "Big"), apic(3, 3, bigCover)) + mp3Frames(3)
        val reader = CachingRangeReader(bytes.size.toLong()) { off, len -> bytes.copyOfRange(off.toInt(), (off + len).toInt()) }
        val tags = TagReader.read(reader)
        assertThat(tags.title).isEqualTo("Big")
        assertThat(tags.picture!!.bytes.size).isEqualTo(bigCover.size)
        assertThat(reader.fetchCount).isAtMost(3)
    }

    // ── FLAC ─────────────────────────────────────────────────────────────

    @Test
    fun `flac streaminfo, vorbis comments and the front cover picture`() = runTest {
        val bytes = bytes {
            ascii("fLaC")
            write(flacBlock(0, streamInfo(rate = 96000, bits = 24, channels = 2, totalSamples = 96000L * 200), last = false))
            write(flacBlock(1, ByteArray(1000), last = false)) // padding
            write(flacBlock(6, pictureBlock(4, "image/png", Fixtures.PNG), last = false))
            write(flacBlock(4, vorbisComments("TITLE=Song", "ARTIST=One", "ARTIST=Two", "ALBUM=Record", "ALBUMARTIST=Band", "TRACKNUMBER=03", "DISCNUMBER=2/2", "DATE=1999-01-01"), last = false))
            write(flacBlock(6, pictureBlock(3, "image/jpeg", Fixtures.JPEG), last = true))
            zeros(5000)
        }
        val tags = read(bytes)
        assertThat(tags.codec).isEqualTo("flac")
        assertThat(tags.sampleRateHz).isEqualTo(96000)
        assertThat(tags.bitsPerSample).isEqualTo(24)
        assertThat(tags.durationMs).isEqualTo(200_000L)
        assertThat(tags.title).isEqualTo("Song")
        assertThat(tags.artist).isEqualTo("One, Two")
        assertThat(tags.album).isEqualTo("Record")
        assertThat(tags.albumArtist).isEqualTo("Band")
        assertThat(tags.trackNumber).isEqualTo(3)
        assertThat(tags.discNumber).isEqualTo(2)
        assertThat(tags.year).isEqualTo(1999)
        assertThat(tags.picture!!.bytes).isEqualTo(Fixtures.JPEG)
    }

    @Test
    fun `flac picture past the head is fetched exactly, the audio never`() = runTest {
        val cover = ByteArray(1_000_000) { 7 }
        val bytes = bytes {
            ascii("fLaC")
            write(flacBlock(0, streamInfo(44100, 16, 2, 44100L * 60), last = false))
            write(flacBlock(4, vorbisComments("TITLE=T"), last = false))
            write(flacBlock(1, ByteArray(300 * 1024), last = false)) // padding pushes the picture past 256 KB
            write(flacBlock(6, pictureBlock(3, "image/jpeg", cover), last = true))
            zeros(3_000_000) // "audio"
        }
        val reader = CachingRangeReader(bytes.size.toLong()) { off, len -> bytes.copyOfRange(off.toInt(), (off + len).toInt()) }
        val tags = TagReader.read(reader)
        assertThat(tags.title).isEqualTo("T")
        assertThat(tags.picture!!.bytes.size).isEqualTo(cover.size)
        assertThat(reader.fetchedBytes).isLessThan(2_000_000L)
    }

    // ── MP4 ──────────────────────────────────────────────────────────────

    private fun m4a(moovAtEnd: Boolean, codec: String = "mp4a", rate: Int = 44100, sampleSize: Int = 16, extra: ByteArray = ByteArray(0)): ByteArray {
        val ilst = box(
            "ilst",
            ilstText("©nam", "Title M"),
            ilstText("©ART", "Artist M"),
            ilstText("©alb", "Album M"),
            ilstText("aART", "AlbumArtist M"),
            ilstText("©day", "2012"),
            ilstPair("trkn", 5, 12),
            ilstPair("disk", 1, 2),
            ilstCover(Fixtures.PNG, typeCode = 14),
        )
        val meta = fullBox("meta", bytes { write(box("hdlr", ByteArray(25))); write(ilst) })
        val trak = box("trak", box("mdia", box("minf", box("stbl", stsd(codec, rate, sampleSize, extra), box("stsz", ByteArray(4000))))))
        val moov = box("moov", mvhd(1000, 215_000), trak, box("udta", meta))
        val ftyp = box("ftyp", bytes { ascii("M4A "); u32be(0) })
        val mdat = box("mdat", ByteArray(600 * 1024))
        return if (moovAtEnd) ftyp + mdat + moov else ftyp + moov + mdat
    }

    @Test
    fun `m4a with moov first`() = runTest {
        val tags = read(m4a(moovAtEnd = false))
        assertThat(tags.title).isEqualTo("Title M")
        assertThat(tags.artist).isEqualTo("Artist M")
        assertThat(tags.album).isEqualTo("Album M")
        assertThat(tags.albumArtist).isEqualTo("AlbumArtist M")
        assertThat(tags.year).isEqualTo(2012)
        assertThat(tags.trackNumber).isEqualTo(5)
        assertThat(tags.discNumber).isEqualTo(1)
        assertThat(tags.durationMs).isEqualTo(215_000L)
        assertThat(tags.codec).isEqualTo("aac")
        assertThat(tags.sampleRateHz).isEqualTo(44100)
        assertThat(tags.bitsPerSample).isNull()
        assertThat(tags.picture!!.mimeType).isEqualTo("image/png")
    }

    @Test
    fun `m4a with moov after the audio reads the tail, not the audio`() = runTest {
        val bytes = m4a(moovAtEnd = true)
        val reader = CachingRangeReader(bytes.size.toLong()) { off, len -> bytes.copyOfRange(off.toInt(), (off + len).toInt()) }
        val tags = TagReader.read(reader)
        assertThat(tags.title).isEqualTo("Title M")
        assertThat(tags.durationMs).isEqualTo(215_000L)
        // Head (256 KB) + one tail window; the 600 KB mdat in between is skipped.
        assertThat(reader.fetchedBytes).isLessThan(400L * 1024)
    }

    @Test
    fun `alac takes rate and depth from its cookie`() = runTest {
        val cookie = fullBox("alac", bytes { u32be(4096); u8(0); u8(24); u8(40); u8(10); u8(14); u8(2); u16be(255); u32be(0); u32be(0); u32be(96000) })
        val tags = read(m4a(moovAtEnd = false, codec = "alac", rate = 0, sampleSize = 24, extra = cookie))
        assertThat(tags.codec).isEqualTo("alac")
        assertThat(tags.bitsPerSample).isEqualTo(24)
        assertThat(tags.sampleRateHz).isEqualTo(96000)
    }

    // ── Ogg ──────────────────────────────────────────────────────────────

    @Test
    fun `opus head, tags with a base64 cover, duration from the last granule`() = runTest {
        val head = bytes { ascii("OpusHead"); u8(1); u8(2); u8(0x38); u8(0x01); u32le(44100); u16be(0); u8(0) } // pre-skip 312 (LE 0x0138)
        val pic = Base64.getEncoder().encodeToString(pictureBlock(3, "image/jpeg", Fixtures.JPEG))
        val tagsPacket = bytes { ascii("OpusTags"); write(vorbisComments("TITLE=Opus Song", "ARTIST=Opus Artist", "METADATA_BLOCK_PICTURE=$pic")) }
        val bytes = oggPage(7, 0, 0, head, headerType = 2) + oggPage(7, 1, 0, tagsPacket) +
            oggPage(7, 2, 48_000L * 10, ByteArray(300)) + oggPage(7, 3, 48_000L * 30 + 312, ByteArray(300), headerType = 4)
        val tags = read(bytes)
        assertThat(tags.codec).isEqualTo("opus")
        assertThat(tags.title).isEqualTo("Opus Song")
        assertThat(tags.artist).isEqualTo("Opus Artist")
        assertThat(tags.durationMs).isEqualTo(30_000L)
        assertThat(tags.picture!!.bytes).isEqualTo(Fixtures.JPEG)
    }

    @Test
    fun `vorbis comment packet spanning pages is assembled`() = runTest {
        val id = bytes { u8(1); ascii("vorbis"); u32le(0); u8(2); u32le(44100); u32le(0); u32le(160000); u32le(0); u8(0); u8(1) }
        val longValue = "x".repeat(70_000)
        val comment = bytes { u8(3); ascii("vorbis"); write(vorbisComments("TITLE=Vorb", "COMMENT=$longValue")); u8(1) }
        // Split the comment packet: first page carries 255*255 bytes (all 255 lacing, packet continues).
        val first = comment.copyOfRange(0, 255 * 255)
        val rest = comment.copyOfRange(255 * 255, comment.size)
        val page1 = bytes {
            ascii("OggS"); u8(0); u8(0); repeat(8) { u8(0) }; u32le(9); u32le(1); u32le(0)
            u8(255); repeat(255) { u8(255) }; write(first)
        }
        val bytes = oggPage(9, 0, 0, id, headerType = 2) + page1 + oggPage(9, 2, 0, rest, headerType = 1) +
            oggPage(9, 3, 44100L * 42, ByteArray(10), headerType = 4)
        val tags = read(bytes)
        assertThat(tags.codec).isEqualTo("vorbis")
        assertThat(tags.title).isEqualTo("Vorb")
        assertThat(tags.sampleRateHz).isEqualTo(44100)
        assertThat(tags.bitrateKbps).isEqualTo(160)
        assertThat(tags.durationMs).isEqualTo(42_000L)
    }

    // ── WAV ──────────────────────────────────────────────────────────────

    @Test
    fun `wav fmt, data length and LIST INFO`() = runTest {
        val info = bytes {
            ascii("INFO")
            ascii("INAM"); u32le(5); ascii("Wavy"); u8(0); u8(0) // odd length → pad byte
            ascii("IART"); u32le(4); ascii("Pcm!")
        }
        val bytes = bytes {
            ascii("RIFF"); u32le(0); ascii("WAVE")
            ascii("fmt "); u32le(16); write(byteArrayOf(1, 0, 2, 0)); u32le(48000); u32le(48000L * 2 * 3); write(byteArrayOf(6, 0, 24, 0))
            ascii("LIST"); u32le(info.size.toLong()); write(info)
            ascii("data"); u32le(48000L * 6 * 2); zeros(48000 * 6 * 2)
        }
        val tags = read(bytes)
        assertThat(tags.codec).isEqualTo("pcm")
        assertThat(tags.sampleRateHz).isEqualTo(48000)
        assertThat(tags.bitsPerSample).isEqualTo(24)
        assertThat(tags.durationMs).isEqualTo(2000L)
        assertThat(tags.title).isEqualTo("Wavy")
        assertThat(tags.artist).isEqualTo("Pcm!")
    }

    // ── Robustness ───────────────────────────────────────────────────────

    @Test
    fun `garbage and truncated files give empty tags, not a crash`() = runTest {
        assertThat(read(ByteArray(10) { 0x41 })).isEqualTo(AudioTags.EMPTY)
        val truncatedFlac = bytes { ascii("fLaC"); u8(0x04); write(byteArrayOf(0x7F, 0x00, 0x00)); ascii("short") }
        assertThat(read(truncatedFlac).title).isNull()
        val truncatedM4a = box("ftyp", bytes { ascii("M4A ") }) + bytes { u32be(100_000); ascii("moov") }
        assertThat(read(truncatedM4a)).isEqualTo(AudioTags.EMPTY)
    }

    @Test(expected = TagReadBudgetExceeded::class)
    fun `a read past the budget fails instead of downloading the file`() = runTest {
        val bytes = ByteArray(2_000_000)
        val reader = CachingRangeReader(bytes.size.toLong(), maxTotalBytes = 300_000) { off, len -> bytes.copyOfRange(off.toInt(), (off + len).toInt()) }
        reader.read(0, 256 * 1024)
        reader.read(1_000_000, 100_000)
    }
}

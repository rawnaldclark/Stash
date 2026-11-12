package com.stash.core.media.streaming

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.Track
import org.junit.Test

/**
 * review-882 M9: for a stream an add-on answered, Now Playing's quality tags are what the player's
 * extractor found, not what the add-on said ([PlayedFormat]). The arguments are the fields of
 * media3's `Format` for the selected audio track; -1 is its "not known".
 *
 * Not proven here: that `PlayerRepositoryImpl.updateState` reads those fields from the controller
 * and that the controller carries them across the session. That needs the phone.
 */
class PlayedFormatTest {

    private fun playing(origin: String?, format: String = "flac", bits: Int? = 16, rate: Int? = 44_100, kbps: Int = 0) = Track(
        id = 1, title = "T", artist = "A", fileFormat = format, bitsPerSample = bits, sampleRateHz = rate, qualityKbps = kbps, streamOrigin = origin,
    )

    private val pcm16 = 2
    private val pcm24 = 21
    private val unknown = -1

    @Test fun `an add-on that called an AAC file lossless is shown as AAC, with no bit depth`() {
        val shown = PlayedFormat.applyTo(playing("addon:stranger", bits = 24, rate = 96_000), "audio/mp4a-latm", 44_100, unknown, 256_000)
        assertThat(shown.fileFormat).isEqualTo("aac")
        assertThat(shown.bitsPerSample).isNull()
        assertThat(shown.sampleRateHz).isEqualTo(44_100)
        assertThat(shown.qualityKbps).isEqualTo(256)
    }

    @Test fun `an MP3 the add-on called FLAC loses the stated bit rate too when the extractor has none`() {
        val shown = PlayedFormat.applyTo(playing("addon:stranger", kbps = 1411), "audio/mpeg", 44_100, unknown, unknown)
        assertThat(shown.fileFormat).isEqualTo("mp3")
        assertThat(shown.bitsPerSample).isNull()
        assertThat(shown.qualityKbps).isEqualTo(0)
    }

    @Test fun `a FLAC that is one is shown with the depth and rate the extractor read`() {
        // The add-on said CD; the file is 24-bit / 96 kHz.
        val shown = PlayedFormat.applyTo(playing("addon:stranger"), "audio/flac", 96_000, pcm24, unknown)
        assertThat(shown.fileFormat).isEqualTo("flac")
        assertThat(shown.bitsPerSample).isEqualTo(24)
        assertThat(shown.sampleRateHz).isEqualTo(96_000)
    }

    @Test fun `a lossless stream keeps the stated depth and rate the extractor did not report`() {
        // A FLAC in fragmented MP4 (DASH) does not always carry its depth in the track format.
        val stated = playing("addon:stranger", bits = 24, rate = 48_000)
        val shown = PlayedFormat.applyTo(stated, "audio/flac", unknown, unknown, unknown)
        assertThat(shown).isEqualTo(stated)
        assertThat(PlayedFormat.applyTo(stated, "audio/flac", 48_000, pcm16, unknown).bitsPerSample).isEqualTo(16)
    }

    @Test fun `a lossy stream the add-on labelled honestly keeps its stated bit rate`() {
        val stated = playing("addon:stranger", format = "mp3", bits = null, kbps = 320)
        assertThat(PlayedFormat.applyTo(stated, "audio/mpeg", 44_100, unknown, unknown)).isEqualTo(stated)
    }

    @Test fun `only an add-on's stream is relabelled - Stash's own sources keep their catalog's word`() {
        for (origin in listOf("qbdlx", "tidal", "jiosaavn", "youtube", "cloud", null)) {
            val stated = playing(origin, bits = 24, rate = 96_000)
            assertThat(PlayedFormat.applyTo(stated, "audio/mp4a-latm", 44_100, unknown, 128_000)).isEqualTo(stated)
        }
    }

    @Test fun `until the extractor names a codec this app knows, the add-on's label stands`() {
        val stated = playing("addon:stranger")
        assertThat(PlayedFormat.applyTo(stated, null, unknown, unknown, unknown)).isEqualTo(stated)
        assertThat(PlayedFormat.applyTo(stated, "audio/x-something-new", 44_100, pcm16, unknown)).isEqualTo(stated)
    }

    @Test fun `the codec tags are the ones Now Playing shows`() {
        assertThat(PlayedFormat.codecTag("audio/flac")).isEqualTo("flac")
        assertThat(PlayedFormat.codecTag("audio/alac")).isEqualTo("alac")
        assertThat(PlayedFormat.codecTag("audio/raw")).isEqualTo("wav")
        assertThat(PlayedFormat.codecTag("audio/mp4a-latm")).isEqualTo("aac")
        assertThat(PlayedFormat.codecTag("AUDIO/MPEG")).isEqualTo("mp3")
        assertThat(PlayedFormat.codecTag("audio/opus")).isEqualTo("opus")
        assertThat(PlayedFormat.codecTag("audio/vorbis")).isEqualTo("vorbis")
        assertThat(PlayedFormat.codecTag("audio/eac3-joc")).isEqualTo("eac3")
        assertThat(PlayedFormat.codecTag("video/avc")).isNull()
    }
}

package com.stash.core.data.audio

import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowMediaExtractor
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.util.DataSource

/**
 * Pins where [AudioDurationExtractor.extract] reads the codec from: the audio
 * track's MIME, not the retriever's MIMETYPE, which is the container. On a
 * Pixel 5 (2026-09-28) JioSaavn's AAC-LC 320 file (an MP4) read as "mp4", so
 * the `format == "aac"` check threw away every JioSaavn download.
 */
@RunWith(RobolectricTestRunner::class)
class AudioDurationExtractorTest {

    private val extractor = AudioDurationExtractor(ApplicationProvider.getApplicationContext())

    private fun retrieverReports(path: String, mime: String) {
        ShadowMediaMetadataRetriever.addMetadata(path, MediaMetadataRetriever.METADATA_KEY_MIMETYPE, mime)
        ShadowMediaMetadataRetriever.addMetadata(path, MediaMetadataRetriever.METADATA_KEY_BITRATE, "317571")
        ShadowMediaMetadataRetriever.addMetadata(path, MediaMetadataRetriever.METADATA_KEY_DURATION, "293500")
    }

    @Test
    fun `AAC in an MP4 container reads as aac, not the container's mp4`() {
        val path = "/music/jiosaavn_pehla_nasha.m4a"
        retrieverReports(path, "audio/mp4")
        ShadowMediaExtractor.addTrack(
            DataSource.toDataSource(path),
            MediaFormat.createAudioFormat("audio/mp4a-latm", 44_100, 2),
            ByteArray(0),
        )

        val meta = extractor.extract(path)!!

        assertThat(meta.format).isEqualTo("aac")
        assertThat(meta.bitrateKbps).isEqualTo(317)
        assertThat(meta.sampleRateHz).isEqualTo(44_100)
    }

    @Test
    fun `a video track ahead of the audio doesn't hide the audio codec`() {
        val path = "/music/search_fallback.webm"
        retrieverReports(path, "video/webm")
        ShadowMediaExtractor.addTrack(
            DataSource.toDataSource(path),
            MediaFormat.createVideoFormat("video/x-vnd.on2.vp9", 1920, 1080),
            ByteArray(0),
        )
        ShadowMediaExtractor.addTrack(
            DataSource.toDataSource(path),
            MediaFormat.createAudioFormat("audio/opus", 48_000, 2),
            ByteArray(0),
        )

        assertThat(extractor.extract(path)!!.format).isEqualTo("opus")
    }

    @Test
    fun `a FLAC whose track reads as decoded PCM stays flac`() {
        // Android's FLAC extractor decodes to PCM itself, so its track MIME is "audio/raw"
        // (Pixel 5, 2026-09-28): taking it stored every new FLAC as "raw".
        val path = "/music/radiohead_nude.flac"
        retrieverReports(path, "audio/flac")
        ShadowMediaExtractor.addTrack(
            DataSource.toDataSource(path),
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_RAW, 44_100, 2),
            ByteArray(0),
        )

        assertThat(extractor.extract(path)!!.format).isEqualTo("flac")
    }

    @Test
    fun `falls back to the container MIME when the extractor finds no audio track`() {
        val path = "/music/unreadable_by_extractor.flac"
        retrieverReports(path, "audio/flac")

        assertThat(extractor.extract(path)!!.format).isEqualTo("flac")
    }
}

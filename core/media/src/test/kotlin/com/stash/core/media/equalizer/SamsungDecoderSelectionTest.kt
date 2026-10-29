package com.stash.core.media.equalizer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** #705: Samsung's audio decoders are passed over, whatever the case of their names, while another can play the format. */
class SamsungDecoderSelectionTest {
    @Test fun `the Note 9's upper-case OMX_SEC decoder is a Samsung one, as are c2_sec ones`() {
        assertThat(isSamsungAudioDecoder("OMX.SEC.flac.dec")).isTrue()
        assertThat(isSamsungAudioDecoder("OMX.sec.mp3.dec")).isTrue()
        assertThat(isSamsungAudioDecoder("c2.sec.flac.decoder")).isTrue()
        assertThat(isSamsungAudioDecoder("C2.SEC.aac.decoder")).isTrue()
        assertThat(isSamsungAudioDecoder("c2.android.flac.decoder")).isFalse()
        assertThat(isSamsungAudioDecoder("OMX.google.flac.decoder")).isFalse()
        assertThat(isSamsungAudioDecoder("c2.qti.flac.decoder")).isFalse()
    }

    @Test fun `every audio format passes Samsung's decoders over when another is there`() {
        val flac = listOf("OMX.SEC.flac.dec", "c2.android.flac.decoder")
        assertThat(withoutSamsungAudio("audio/flac", flac) { it }).containsExactly("c2.android.flac.decoder")
        val mp3 = listOf("OMX.SEC.mp3.dec", "c2.android.mp3.decoder")
        assertThat(withoutSamsungAudio("audio/mpeg", mp3) { it }).containsExactly("c2.android.mp3.decoder")
    }

    @Test fun `a format only Samsung can decode keeps its decoder, and video is left alone`() {
        val only = listOf("c2.sec.ac4.decoder")
        assertThat(withoutSamsungAudio("audio/ac4", only) { it }).isEqualTo(only)
        val video = listOf("c2.sec.avc.decoder", "c2.android.avc.decoder")
        assertThat(withoutSamsungAudio("video/avc", video) { it }).isEqualTo(video)
    }
}

package com.stash.core.media.service

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The MIME hint a cast receiver gets for a queued song (cast spec 2026-10-06 §4). */
@RunWith(RobolectricTestRunner::class)
class CastContentTypeTest {

    @Test fun `the stream's codec wins over the URL`() {
        assertThat(castContentType(Uri.parse("https://cdn.example/x?sig=1"), "FLAC")).isEqualTo("audio/flac")
        assertThat(castContentType(Uri.parse("https://cdn.example/a.mp3"), "opus")).isEqualTo("audio/ogg")
    }

    @Test fun `downloaded files go by extension`() {
        assertThat(castContentType(Uri.parse("file:///music/a.m4a"), null)).isEqualTo("audio/mp4")
        assertThat(castContentType(Uri.parse("file:///music/a.webm"), null)).isEqualTo("audio/webm")
        assertThat(castContentType(Uri.parse("file:///music/a.FLAC"), null)).isEqualTo("audio/flac")
    }

    @Test fun `unknown falls back to MPEG audio`() {
        assertThat(castContentType(Uri.parse("stash-resolve://track/42"), null)).isEqualTo("audio/mpeg")
        assertThat(castContentType(Uri.parse("file:///music/noext"), null)).isEqualTo("audio/mpeg")
    }
}

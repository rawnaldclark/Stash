package com.stash.core.media.streaming

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [ResolveFirstMediaSource] takes a metadata-only change in place (a cover learnt, the quality stamped): the same placeholder
 * URI, or the URI its child plays. Another URI is another song to play, which ExoPlayer still replaces.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ResolveFirstMediaSourceTest {
    private val placeholder = "stash-resolve://track/42"

    private fun item(uri: String, title: String) = MediaItem.Builder().setMediaId("42").setUri(uri)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build()).build()

    private fun source(childFor: (MediaItem, StreamUrl?) -> MediaSource) = ResolveFirstMediaSource(
        mediaItem = item(placeholder, "Before"),
        scope = CoroutineScope(Dispatchers.Unconfined),
        resolve = { null },
        childFor = childFor,
    )

    /** A child that plays [uri], taking any update for the same URI. */
    private fun child(uri: String): MediaSource {
        var current = item(uri, "Before")
        return mockk(relaxed = true) {
            every { mediaItem } answers { current }
            every { canUpdateMediaItem(any()) } answers { firstArg<MediaItem>().localConfiguration?.uri.toString() == uri }
            every { updateMediaItem(any()) } answers { current = firstArg() }
        }
    }

    private fun MediaSource.prepare() {
        prepareSource({ _, _ -> }, null, PlayerId.UNSET)
        shadowOf(Looper.getMainLooper()).idle() // the resolve's answer, posted back to this thread
    }

    @Test fun `the same song with new metadata is updated in place, another URI is not`() {
        val s = source { _, _ -> child(placeholder) }
        assertThat(s.canUpdateMediaItem(item(placeholder, "With a cover"))).isTrue()
        assertThat(s.canUpdateMediaItem(item("stash-resolve://track/43", "Another song"))).isFalse()
        assertThat(s.canUpdateMediaItem(MediaItem.Builder().setMediaId("42").build())).isFalse()
    }

    @Test fun `the update keeps the new metadata and hands it to the child, each keeping its own URI`() {
        val mpd = "https://relay.example/dash/td/42.mpd"
        val kid = child(mpd) // Tidal's DASH child: the player shows its item, with the MPD's URI
        val s = source { _, _ -> kid }
        s.prepare()
        // a replacement built from what the player shows (the child's URI) is the same song too
        assertThat(s.canUpdateMediaItem(item(mpd, "Stamped"))).isTrue()
        s.updateMediaItem(item(mpd, "Stamped"))
        assertThat(s.mediaItem.mediaMetadata.title.toString()).isEqualTo("Stamped")
        assertThat(s.mediaItem.localConfiguration!!.uri.toString()).isEqualTo(placeholder)
        val handed = slot<MediaItem>()
        verify { kid.updateMediaItem(capture(handed)) }
        assertThat(handed.captured.mediaMetadata.title.toString()).isEqualTo("Stamped")
        assertThat(handed.captured.localConfiguration!!.uri.toString()).isEqualTo(mpd)
    }

    @Test fun `a change before the resolve lands builds the child from the newest item`() {
        var builtFrom: MediaItem? = null
        val s = source { item, _ -> builtFrom = item; child(placeholder) }
        s.updateMediaItem(item(placeholder, "With a cover"))
        s.prepare()
        assertThat(builtFrom!!.mediaMetadata.title.toString()).isEqualTo("With a cover")
    }
}

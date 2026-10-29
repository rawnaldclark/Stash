package com.stash.core.media.streaming

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.test.utils.ExoPlayerTestRunner
import androidx.media3.test.utils.FakeMediaSource
import androidx.media3.test.utils.FakeTimeline
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A real ExoPlayer playing `stash-resolve://` placeholders through [ResolveFirstMediaSource] (as every streamed song does
 * while a Tidal server is configured), and a song's item replaced mid-song with new metadata, as the quality stamp and a learnt
 * cover do. In place, the song plays on: same source, same position, the new metadata shown, the shuffle order untouched. A
 * replacement with another URI is another song: ExoPlayer removes the source and adds a new one, as before.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ResolveFirstMediaSourcePlayerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** The sources the player asked for, by URI: one per song unless a replace made a new one. */
    private val built = mutableListOf<String>()

    private val factory = object : MediaSource.Factory {
        override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider) = this
        override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy) = this
        override fun getSupportedTypes(): IntArray = intArrayOf()
        override fun createMediaSource(mediaItem: MediaItem): MediaSource {
            built += mediaItem.localConfiguration!!.uri.toString()
            return ResolveFirstMediaSource(
                mediaItem = mediaItem,
                scope = CoroutineScope(Dispatchers.Unconfined),
                resolve = { null },
                // The lazy child: a minute of audio playing the placeholder's own item
                childFor = { item, _ ->
                    FakeMediaSource(
                        FakeTimeline(FakeTimeline.TimelineWindowDefinition.Builder().setDurationUs(60_000_000).setMediaItem(item).build()),
                        ExoPlayerTestRunner.AUDIO_FORMAT,
                    ).apply { setCanUpdateMediaItems(true) }
                },
            )
        }
    }

    private val player: ExoPlayer = TestExoPlayerBuilder(context).setMediaSourceFactory(factory).build()

    @After fun release() = player.release()

    private fun song(id: Int, title: String = "Song $id", uri: String = "stash-resolve://track/$id") = MediaItem.Builder()
        .setMediaId("$id").setUri(uri).setMediaMetadata(MediaMetadata.Builder().setTitle(title).build()).build()

    private fun playTenSecondsOf(items: List<MediaItem>) {
        player.setMediaItems(items)
        player.prepare()
        player.play()
        TestPlayerRunHelper.playUntilPosition(player, 0, 10_000)
    }

    @Test fun `new metadata for the playing song is taken in place - it plays on`() {
        playTenSecondsOf(listOf(song(1), song(2), song(3)))
        built.clear()

        player.replaceMediaItem(0, song(1, title = "Song 1, with its cover"))
        TestPlayerRunHelper.run(player).untilPendingCommandsAreFullyHandled()

        assertThat(built).isEmpty() // no new source: nothing prepared again
        assertThat(player.currentMediaItemIndex).isEqualTo(0)
        assertThat(player.currentPosition).isAtLeast(10_000)
        assertThat(player.currentMediaItem!!.mediaMetadata.title.toString()).isEqualTo("Song 1, with its cover")
    }

    @Test fun `several neighbours updated in one call, the shuffle order as it was`() {
        player.shuffleModeEnabled = true
        playTenSecondsOf((1..6).map { song(it) })
        fun order(): List<Int> {
            val t = player.currentTimeline
            val out = mutableListOf<Int>()
            var i = t.getFirstWindowIndex(true)
            while (i != androidx.media3.common.C.INDEX_UNSET) {
                out += i
                i = t.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true)
            }
            return out
        }
        val before = order()
        val playing = player.currentMediaItemIndex
        val position = player.currentPosition
        built.clear()

        player.replaceMediaItems(0, 6, (1..6).map { song(it, title = "Song $it, with its cover") })
        TestPlayerRunHelper.run(player).untilPendingCommandsAreFullyHandled()

        assertThat(built).isEmpty()
        assertThat(order()).isEqualTo(before)
        assertThat(player.currentMediaItemIndex).isEqualTo(playing)
        assertThat(player.currentPosition).isAtLeast(position)
        assertThat((0 until 6).map { player.getMediaItemAt(it).mediaMetadata.title.toString() })
            .isEqualTo((1..6).map { "Song $it, with its cover" })
    }

    @Test fun `another URI is another song - replaced, and it starts from the top`() {
        playTenSecondsOf(listOf(song(1), song(2)))
        built.clear()

        player.replaceMediaItem(0, song(1, uri = "stash-resolve://track/99"))
        TestPlayerRunHelper.run(player).untilPendingCommandsAreFullyHandled()

        assertThat(built).containsExactly("stash-resolve://track/99")
        assertThat(player.currentPosition).isLessThan(10_000)
    }
}

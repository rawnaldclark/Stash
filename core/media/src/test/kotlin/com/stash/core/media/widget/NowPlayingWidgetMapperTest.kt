package com.stash.core.media.widget

import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Playback state → widget state ([NowPlayingWidgetMapper]). */
class NowPlayingWidgetMapperTest {

    private fun snapshot(
        hasCurrentItem: Boolean = true,
        title: CharSequence? = "Starting Over",
        artist: CharSequence? = "LSD and the Search for God",
        artworkUri: String? = "https://i.scdn.co/image/abc",
        playWhenReady: Boolean = true,
        playbackState: Int = Player.STATE_READY,
    ) = WidgetPlayerSnapshot(hasCurrentItem, title, artist, artworkUri, playWhenReady, playbackState)

    @Test
    fun `a playing song maps to its title, artist, cover and playing`() {
        assertThat(NowPlayingWidgetMapper.map(snapshot())).isEqualTo(
            NowPlayingWidgetState(
                title = "Starting Over",
                artist = "LSD and the Search for God",
                artUrl = "https://i.scdn.co/image/abc",
                isPlaying = true,
            ),
        )
    }

    @Test
    fun `buffering while meant to play still shows playing (Pause stays tappable through a stall)`() {
        assertThat(NowPlayingWidgetMapper.map(snapshot(playbackState = Player.STATE_BUFFERING))!!.isPlaying).isTrue()
    }

    @Test
    fun `paused is not playing`() {
        assertThat(NowPlayingWidgetMapper.map(snapshot(playWhenReady = false))!!.isPlaying).isFalse()
    }

    @Test
    fun `idle or ended is not playing even with playWhenReady still set`() {
        assertThat(NowPlayingWidgetMapper.map(snapshot(playbackState = Player.STATE_IDLE))!!.isPlaying).isFalse()
        assertThat(NowPlayingWidgetMapper.map(snapshot(playbackState = Player.STATE_ENDED))!!.isPlaying).isFalse()
    }

    @Test
    fun `an empty player maps to null, which leaves the widget's last song alone`() {
        assertThat(NowPlayingWidgetMapper.map(snapshot(hasCurrentItem = false))).isNull()
    }

    @Test
    fun `an item without a title maps to null`() {
        assertThat(NowPlayingWidgetMapper.map(snapshot(title = null))).isNull()
        assertThat(NowPlayingWidgetMapper.map(snapshot(title = "   "))).isNull()
    }

    @Test
    fun `text is trimmed, a missing artist is empty and a blank cover is none`() {
        val state = NowPlayingWidgetMapper.map(snapshot(title = "  Song \n", artist = null, artworkUri = "  "))!!
        assertThat(state.title).isEqualTo("Song")
        assertThat(state.artist).isEmpty()
        assertThat(state.artUrl).isNull()
    }

    @Test
    fun `a local cover path is kept as it is`() {
        val state = NowPlayingWidgetMapper.map(snapshot(artworkUri = "/storage/emulated/0/Music/Stash/art/1.jpg"))!!
        assertThat(state.artUrl).isEqualTo("/storage/emulated/0/Music/Stash/art/1.jpg")
    }
}

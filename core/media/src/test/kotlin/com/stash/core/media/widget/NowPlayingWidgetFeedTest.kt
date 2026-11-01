package com.stash.core.media.widget

import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The service-side wiring ([NowPlayingWidgetFeed]): a real Media3 player's events reach the sink,
 * debounced, from whichever player the session holds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NowPlayingWidgetFeedTest {

    /** A player with one current song whose fields the test changes. */
    private class SongPlayer(var title: String, var playing: Boolean = true) : SimpleBasePlayer(Looper.getMainLooper()) {
        var empty = false

        fun change(block: SongPlayer.() -> Unit) {
            block()
            invalidateState()
        }

        override fun getState(): State {
            val item = MediaItem.Builder()
                .setMediaId(title)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist("Artist")
                        .setArtworkUri(Uri.parse("https://covers/$title"))
                        .build(),
                )
                .build()
            return State.Builder()
                .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
                .setPlaylist(if (empty) emptyList() else listOf(MediaItemData.Builder(title).setMediaItem(item).build()))
                .setCurrentMediaItemIndex(if (empty) C.INDEX_UNSET else 0)
                .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setPlaybackState(if (empty) Player.STATE_IDLE else Player.STATE_READY)
                .build()
        }
    }

    private class RecordingSink : NowPlayingWidgetSink {
        val published = mutableListOf<NowPlayingWidgetState>()
        var stops = 0
        override fun publish(state: NowPlayingWidgetState) { published += state }
        override fun serviceStopped() { stops++ }
    }

    private val scope = TestScope(StandardTestDispatcher())
    private val sink = RecordingSink()
    private val feed = NowPlayingWidgetFeed(scope, sink, clock = { scope.testScheduler.currentTime })

    /** Delivers the player's onEvents (a looper message) and lets the debounce settle. */
    private fun settle() {
        shadowOf(Looper.getMainLooper()).idle()
        scope.advanceTimeBy(WidgetUpdateDebouncer.MAX_WAIT_MS + 1)
        scope.runCurrent()
    }

    private fun state(title: String, playing: Boolean) =
        NowPlayingWidgetState(title, "Artist", "https://covers/$title", playing)

    @Test
    fun `following a player publishes what it shows`() {
        feed.follow(SongPlayer("One"))
        settle()
        assertThat(sink.published).containsExactly(state("One", playing = true))
    }

    @Test
    fun `a song change and a pause reach the sink`() {
        val player = SongPlayer("One")
        feed.follow(player)
        settle()

        player.change { title = "Two" }
        settle()
        player.change { playing = false }
        settle()

        assertThat(sink.published)
            .containsExactly(state("One", true), state("Two", true), state("Two", false))
            .inOrder()
    }

    @Test
    fun `an emptied player leaves the widget's song alone`() {
        val player = SongPlayer("One")
        feed.follow(player)
        settle()

        player.change { empty = true }
        settle()

        assertThat(sink.published).containsExactly(state("One", true))
    }

    @Test
    fun `after the session moves to another player the old one is ignored`() {
        val master = SongPlayer("Local")
        val cast = SongPlayer("On the speaker")
        feed.follow(master)
        settle()
        feed.follow(cast)
        settle()

        master.change { title = "Ghost" }
        settle()

        assertThat(sink.published)
            .containsExactly(state("Local", true), state("On the speaker", true))
            .inOrder()
    }

    @Test
    fun `stopping tells the sink at once and drops a pending update`() {
        val player = SongPlayer("One")
        feed.follow(player)
        feed.stopped() // before the first state settled
        settle()

        assertThat(sink.stops).isEqualTo(1)
        assertThat(sink.published).isEmpty()
    }

    @Test
    fun `release detaches from the player`() {
        val player = SongPlayer("One")
        feed.follow(player)
        settle()
        feed.release()

        player.change { title = "Two" }
        settle()

        assertThat(sink.published).containsExactly(state("One", true))
    }
}

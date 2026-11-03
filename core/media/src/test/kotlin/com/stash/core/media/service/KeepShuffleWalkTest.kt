package com.stash.core.media.service

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [keepShuffleWalkInTimelineOrder] on a real ExoPlayer: a big queue's window is already in play
 * order, so under shuffle the player must walk its timeline as it is, after Media3 shuffles it
 * and after an insert, which Media3 puts at a random place in its walk.
 */
@RunWith(RobolectricTestRunner::class)
class KeepShuffleWalkTest {

    private lateinit var player: ExoPlayer

    @Before
    fun setUp() {
        player = ExoPlayer.Builder(ApplicationProvider.getApplicationContext()).build()
    }

    @After
    fun tearDown() {
        player.release()
    }

    private fun items(range: IntRange) = range.map { MediaItem.Builder().setMediaId("$it").setUri("file:///s/$it.opus").build() }

    /** The ids in the order the player would play them under shuffle. */
    private fun walk(): List<String> {
        val timeline = player.currentTimeline
        val out = mutableListOf<String>()
        var i = timeline.getFirstWindowIndex(true)
        while (i != androidx.media3.common.C.INDEX_UNSET) {
            out += player.getMediaItemAt(i).mediaId
            i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true)
        }
        return out
    }

    @Test
    fun `under shuffle the player walks the timeline as it is, after an insert too`() {
        player.setMediaItems(items(0..59))
        player.shuffleModeEnabled = true
        assertThat(walk()).isNotEqualTo((0..59).map(Int::toString)) // Media3's own random walk

        assertThat(keepShuffleWalkInTimelineOrder(player)).isTrue()
        assertThat(walk()).isEqualTo((0..59).map(Int::toString))
        assertThat(keepShuffleWalkInTimelineOrder(player)).isFalse() // already in order: nothing to set

        // A window slide adds the next songs: Media3 inserts them at random places in its walk.
        player.addMediaItems(items(60..119))
        assertThat(walk()).isNotEqualTo((0..119).map(Int::toString))
        keepShuffleWalkInTimelineOrder(player)
        assertThat(walk()).isEqualTo((0..119).map(Int::toString))
    }

    @Test
    fun `with shuffle off it leaves the player alone`() {
        player.setMediaItems(items(0..9))
        assertThat(keepShuffleWalkInTimelineOrder(player)).isFalse()
    }
}

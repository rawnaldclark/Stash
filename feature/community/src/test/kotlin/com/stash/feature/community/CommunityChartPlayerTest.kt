package com.stash.feature.community

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.community.CommunityRepository
import com.stash.core.data.community.CommunityResult
import com.stash.core.media.PlayerRepository
import com.stash.core.model.PlayerState
import com.stash.core.model.Track
import com.stash.core.model.community.CommunityPost
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommunityChartPlayerTest {
    private val repo = mockk<CommunityRepository>(relaxed = true)
    private val tracks = mockk<CommunityTracks>()
    private val player = mockk<PlayerRepository>(relaxed = true)
    private val tracker = mockk<CommunityListenTracker>(relaxed = true)
    private val chart = CommunityChartPlayer(repo, tracks, player, tracker)

    private fun post(i: Int) = CommunityPost(
        id = "P$i", kind = "playlist", title = "Post $i", name = "Maya", count = 3, createdAt = 1, up = 0, down = 0,
    )
    // Built once per post: Track stamps dateAdded with the clock, so a rebuilt list wouldn't be equal.
    private val songLists = mutableMapOf<Int, List<Track>>()
    private fun songs(i: Int) = songLists.getOrPut(i) { listOf(Track(id = i * 10L, title = "S$i", artist = "A")) }

    private fun everyPostOpens(posts: List<CommunityPost>) {
        for ((i, p) in posts.withIndex()) {
            coEvery { repo.open(p.id) } returns CommunityResult.Ok(p)
            coEvery { tracks.of(p, CommunityChartPlayer.SONGS_PER_POST) } returns songs(i)
        }
    }

    @Test fun `opens only the top ten posts, plays the first and adds the rest behind it`() = runTest {
        val posts = (0 until 15).map(::post)
        everyPostOpens(posts)
        var startedCalls = 0

        val started = chart.play(posts, shuffle = false) { startedCalls++ }

        assertThat(started).isTrue()
        assertThat(startedCalls).isEqualTo(1)
        coVerify(exactly = 10) { repo.open(any()) }
        coVerify(exactly = 0) { repo.open("P10") }
        coVerify(exactly = 1) { player.setQueue(songs(0), 0, any()) }
        coVerify(exactly = 9) { player.addToQueue(any<List<Track>>()) }
    }

    @Test fun `a post that won't open or has no songs is skipped`() = runTest {
        val posts = (0 until 3).map(::post)
        everyPostOpens(posts)
        coEvery { repo.open("P0") } returns CommunityResult.Failed("offline")
        coEvery { tracks.of(posts[1], any()) } returns emptyList()

        val started = chart.play(posts, shuffle = false) {}

        assertThat(started).isTrue()
        coVerify(exactly = 1) { player.setQueue(songs(2), 0, any()) }
        coVerify(exactly = 0) { player.addToQueue(any<List<Track>>()) }
    }

    @Test fun `nothing playable says so and leaves the queue alone`() = runTest {
        val posts = (0 until 3).map(::post)
        coEvery { repo.open(any()) } returns CommunityResult.Failed("offline")

        val started = chart.play(posts, shuffle = true) {}

        assertThat(started).isFalse()
        coVerify(exactly = 0) { player.setQueue(any(), any(), any()) }
    }

    @Test fun `Play all counts toward each post's listen - 30 s of one post's songs marks that post`() = runTest {
        val state = MutableStateFlow(PlayerState())
        val heard = mutableListOf<String>()
        val tracker = CommunityListenTracker(state, onHeard = { heard += it }, scope = backgroundScope, nowMs = { testScheduler.currentTime })
        val posts = (1..3).map(::post)
        everyPostOpens(posts)
        val started = CommunityChartPlayer(repo, tracks, player, tracker).play(posts, shuffle = false) {}
        assertThat(started).isTrue()
        coVerify { player.setQueue(songs(0), 0, CommunityListenTracker.CHART_SOURCE) }
        // The player now plays the chart: the first post's song, for 30 s.
        state.value = PlayerState(currentTrack = songs(0).single(), isPlaying = true, source = CommunityListenTracker.CHART_SOURCE)
        runCurrent()
        advanceTimeBy(29_999); runCurrent()
        assertThat(heard).isEmpty()
        advanceTimeBy(1); runCurrent()
        assertThat(heard).containsExactly("P1")
        // On to the second post's song.
        state.value = state.value.copy(currentTrack = songs(1).single()); runCurrent()
        advanceTimeBy(30_000); runCurrent()
        assertThat(heard).containsExactly("P1", "P2").inOrder()
    }

    @Test fun `the chart is begun before its queue is set, and your own post or one heard isn't counted`() = runTest {
        val posts = listOf(post(1).copy(mine = true), post(2).copy(heard = true), post(3))
        everyPostOpens(posts)

        chart.play(posts, shuffle = false) {}

        coVerifyOrder {
            tracker.beginChart()
            player.setQueue(songs(0), 0, CommunityListenTracker.CHART_SOURCE)
            tracker.chartPost("P3", listOf(songs(2).single().id))
            player.addToQueue(songs(2))
        }
        verify(exactly = 0) { tracker.chartPost("P1", any()) }
        verify(exactly = 0) { tracker.chartPost("P2", any()) }
    }
}

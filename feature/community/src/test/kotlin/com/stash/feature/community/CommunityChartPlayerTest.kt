package com.stash.feature.community

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.community.CommunityRepository
import com.stash.core.data.community.CommunityResult
import com.stash.core.media.PlayerRepository
import com.stash.core.model.Track
import com.stash.core.model.community.CommunityPost
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CommunityChartPlayerTest {
    private val repo = mockk<CommunityRepository>(relaxed = true)
    private val tracks = mockk<CommunityTracks>()
    private val player = mockk<PlayerRepository>(relaxed = true)
    private val chart = CommunityChartPlayer(repo, tracks, player)

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
}

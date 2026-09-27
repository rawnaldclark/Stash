package com.stash.feature.community

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.community.CommunityRepository
import com.stash.core.data.community.CommunityResult
import com.stash.core.data.community.VoteCounts
import com.stash.core.data.community.communityMessage
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.share.SharedMixDocument
import com.stash.core.data.share.SharedMixRepository
import com.stash.core.data.social.LikeCoordinator
import com.stash.core.media.PlayerRepository
import com.stash.core.model.Track
import com.stash.core.model.community.CommunityPost
import com.stash.core.model.share.SharedTrack
import com.stash.feature.community.CommunityPostViewModel.UiState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommunityPostViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<CommunityRepository>(relaxed = true)
    private val sharedMix = mockk<SharedMixRepository>(relaxed = true)
    private val music = mockk<MusicRepository>(relaxed = true)
    private val player = mockk<PlayerRepository>(relaxed = true)
    private val likes = mockk<LikeCoordinator>(relaxed = true)

    private val tracks = listOf(SharedTrack("T1", "A", durationMs = 3_600_000), SharedTrack("T2", "B", durationMs = 300_000))
    private fun playlistPost(kind: String = "playlist") = CommunityPost(
        id = "AAAAAAAA", kind = kind, title = "sad boy hours", name = "Maya", count = 2,
        covers = listOf("https://i.scdn.co/image/a"), createdAt = 1, up = 3, down = 0, tracks = tracks,
    )
    private val songPost = CommunityPost(
        id = "AAAAAAAA", kind = "song", title = "garden", name = "Sam", count = 1, artist = "Death Plus",
        createdAt = 1, up = 1, down = 0, track = SharedTrack("garden", "Death Plus", youtubeId = "9Vz"),
    )

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        // Not in this phone's library unless a test says so.
        every { music.observeTrackByYoutubeId(any()) } returns flowOf(null)
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm() = CommunityPostViewModel(SavedStateHandle(mapOf("postId" to "AAAAAAAA")), repo, sharedMix, music, player, likes)

    @Test fun `the post screen reads each kind`() {
        assertThat(playlistPost().headline()).isEqualTo("Playlist · 2 songs · 1 h 5 min")
        assertThat(playlistPost("mix").headline()).isEqualTo("Mix · 2 songs · 1 h 5 min")
        assertThat(songPost.headline()).isEqualTo("Death Plus")
    }

    @Test fun `whole hours drop the minutes, and an unknown length is left out`() {
        fun lasting(vararg ms: Long?) = playlistPost().copy(tracks = ms.map { SharedTrack("T", "A", durationMs = it) })
        assertThat(lasting(3_600_000).headline()).isEqualTo("Playlist · 2 songs · 1 h")
        assertThat(lasting(300_000).headline()).isEqualTo("Playlist · 2 songs · 5 min")
        assertThat(lasting(null, null).headline()).isEqualTo("Playlist · 2 songs")
    }

    @Test fun `a gone post says so, and a failed load can be retried`() = runTest(dispatcher) {
        coEvery { repo.open("AAAAAAAA") } returnsMany listOf(
            CommunityResult.Rejected("gone"), CommunityResult.Failed("offline"), CommunityResult.Ok(songPost),
        )
        val vm = vm(); advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(UiState.Gone)
        vm.load(); advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(UiState.Failed("Couldn't reach Community. Try again."))
        vm.load(); advanceUntilIdle()
        assertThat((vm.state.value as UiState.Loaded).post).isEqualTo(songPost)
    }

    @Test fun `a rate-limited open says to slow down`() = runTest(dispatcher) {
        coEvery { repo.open(any()) } returns CommunityResult.Rejected("rate_limited")
        val vm = vm(); advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(UiState.Failed("Slow down a moment."))
    }

    @Test fun `Play queues the frozen songs, and Save a copy saves the post as posted`() = runTest(dispatcher) {
        coEvery { repo.open(any()) } returns CommunityResult.Ok(playlistPost())
        val queued = listOf(Track(id = 1, title = "T1", artist = "A"), Track(id = 2, title = "T2", artist = "B"))
        coEvery { sharedMix.tracksFor(any()) } returns queued
        coEvery { sharedMix.saveCopy(any()) } returns 7L
        val vm = vm(); advanceUntilIdle()
        vm.play(); advanceUntilIdle()
        coVerify { player.setQueue(queued, 0, any()) }
        var saved: Long? = null
        vm.saveCopy { saved = it }; advanceUntilIdle()
        assertThat(saved).isEqualTo(7L)
        coVerify {
            sharedMix.saveCopy(SharedMixDocument(name = "sad boy hours", sharedBy = "Maya", covers = listOf("https://i.scdn.co/image/a"), tracks = tracks))
        }
    }

    @Test fun `a failed action says why and frees the buttons`() = runTest(dispatcher) {
        coEvery { repo.open(any()) } returns CommunityResult.Ok(playlistPost())
        coEvery { sharedMix.tracksFor(any()) } throws IllegalStateException("database closed")
        val vm = vm(); advanceUntilIdle()
        vm.play(); advanceUntilIdle()
        val s = vm.state.value as UiState.Loaded
        assertThat(s.message).isEqualTo("Couldn't play this. Try again.")
        assertThat(s.busy).isFalse()
    }

    @Test fun `a double tap on Save a copy makes one copy`() = runTest(dispatcher) {
        coEvery { repo.open(any()) } returns CommunityResult.Ok(playlistPost())
        coEvery { sharedMix.saveCopy(any()) } returns 7L
        val vm = vm(); advanceUntilIdle()
        val saved = mutableListOf<Long>()
        vm.saveCopy { saved += it }
        vm.saveCopy { saved += it }
        advanceUntilIdle()
        coVerify(exactly = 1) { sharedMix.saveCopy(any()) }
        assertThat(saved).containsExactly(7L)
    }

    @Test fun `Like saves the song and likes it`() = runTest(dispatcher) {
        coEvery { repo.open(any()) } returns CommunityResult.Ok(songPost)
        coEvery { music.ensureTrackPersisted(any()) } returns 5L
        val vm = vm(); advanceUntilIdle()
        vm.like(); advanceUntilIdle()
        coVerify { likes.setLiked(5L, true) }
        assertThat((vm.state.value as UiState.Loaded).liked).isTrue()
    }

    @Test fun `Play on a song post plays its library row and shows whether it's liked`() = runTest(dispatcher) {
        coEvery { repo.open(any()) } returns CommunityResult.Ok(songPost)
        coEvery { music.ensureTrackPersisted(any()) } returns 5L
        val row = Track(id = 5, title = "garden", artist = "Death Plus", stashLikedAt = 1L)
        every { music.observeTrackById(5L) } returns flowOf(row)
        val vm = vm(); advanceUntilIdle()
        assertThat((vm.state.value as UiState.Loaded).liked).isFalse()
        vm.play(); advanceUntilIdle()
        coVerify { player.setQueue(listOf(row), 0, any()) }
        assertThat((vm.state.value as UiState.Loaded).liked).isTrue()
    }

    @Test fun `a song you already like opens as liked`() = runTest(dispatcher) {
        coEvery { repo.open(any()) } returns CommunityResult.Ok(songPost)
        every { music.observeTrackByYoutubeId("9Vz") } returns flowOf(Track(id = 5, title = "garden", artist = "Death Plus", stashLikedAt = 1L))
        val vm = vm(); advanceUntilIdle()
        assertThat((vm.state.value as UiState.Loaded).liked).isTrue()
        // Only a lookup: opening a post never adds the song to the library.
        coVerify(exactly = 0) { music.ensureTrackPersisted(any()) }
    }

    @Test fun `a vote shows at once and flips back when refused`() = runTest(dispatcher) {
        coEvery { repo.open(any()) } returns CommunityResult.Ok(songPost)
        coEvery { repo.vote("AAAAAAAA", 1) } returns CommunityResult.Rejected("gone")
        val vm = vm(); advanceUntilIdle()
        vm.vote(1)
        assertThat((vm.state.value as UiState.Loaded).post.up).isEqualTo(2)
        advanceUntilIdle()
        val s = vm.state.value as UiState.Loaded
        assertThat(s.post).isEqualTo(songPost)
        assertThat(s.message).isEqualTo("This post is no longer available.")
    }

    @Test fun `a tap while a vote is out shows at once and is sent after it`() = runTest(dispatcher) {
        coEvery { repo.open(any()) } returns CommunityResult.Ok(songPost)
        coEvery { repo.vote("AAAAAAAA", 1) } coAnswers { delay(1_000); CommunityResult.Ok(VoteCounts(up = 2, down = 0, myVote = 1)) }
        coEvery { repo.vote("AAAAAAAA", -1) } returns CommunityResult.Ok(VoteCounts(up = 1, down = 5, myVote = -1))
        val vm = vm(); advanceUntilIdle()
        vm.vote(1); runCurrent()
        vm.vote(-1)
        assertThat((vm.state.value as UiState.Loaded).post).isEqualTo(songPost.copy(down = 1, myVote = -1))
        advanceUntilIdle()
        coVerifyOrder {
            repo.vote("AAAAAAAA", 1)
            repo.vote("AAAAAAAA", -1)
        }
        assertThat((vm.state.value as UiState.Loaded).post).isEqualTo(songPost.copy(down = 5, myVote = -1))
    }

    @Test fun `your own post can't be voted on`() = runTest(dispatcher) {
        val mine = songPost.copy(mine = true)
        coEvery { repo.open(any()) } returns CommunityResult.Ok(mine)
        val vm = vm(); advanceUntilIdle()
        vm.vote(1); advanceUntilIdle()
        assertThat((vm.state.value as UiState.Loaded).post).isEqualTo(mine)
        coVerify(exactly = 0) { repo.vote(any(), any()) }
    }

    @Test fun `taking your post down leaves the screen`() = runTest(dispatcher) {
        coEvery { repo.open(any()) } returns CommunityResult.Ok(songPost.copy(mine = true))
        coEvery { repo.takeDown("AAAAAAAA") } returns CommunityResult.Ok(Unit)
        val vm = vm(); advanceUntilIdle()
        var left = false
        vm.takeDown { left = true }; advanceUntilIdle()
        assertThat(left).isTrue()
    }

    @Test fun `a refused take-down stays and says why`() = runTest(dispatcher) {
        coEvery { repo.open(any()) } returns CommunityResult.Ok(songPost.copy(mine = true))
        val refused = CommunityResult.Failed("offline")
        coEvery { repo.takeDown("AAAAAAAA") } returns refused
        val vm = vm(); advanceUntilIdle()
        var left = false
        vm.takeDown { left = true }; advanceUntilIdle()
        assertThat(left).isFalse()
        assertThat((vm.state.value as UiState.Loaded).message).isEqualTo(communityMessage(refused))
    }
}

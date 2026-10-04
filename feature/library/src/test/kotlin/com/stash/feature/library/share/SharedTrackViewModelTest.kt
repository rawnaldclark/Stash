package com.stash.feature.library.share

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.share.ShareApiClient
import com.stash.core.data.share.ShareResult
import com.stash.core.data.social.LikeCoordinator
import com.stash.core.media.PlayerRepository
import com.stash.core.model.Track
import com.stash.core.model.share.ShareLinks
import com.stash.core.model.share.SharedTrack
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SharedTrackViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val music = mockk<MusicRepository>(relaxed = true)
    private val player = mockk<PlayerRepository>(relaxed = true)
    private val likes = mockk<LikeCoordinator>(relaxed = true)
    private val api = mockk<ShareApiClient>()
    private val song = SharedTrack("Teardrop", "Massive Attack", "Mezzanine", artUrl = "https://i.scdn.co/image/a")
    private val link = ShareLinks.trackUrl(SharedTrack("Teardrop", "Massive Attack"))
    /** What MainActivity hands the route for `https://stashfm.app/t/Ab3xY9qk`. */
    private val shortLink = ShareLinks.trackShortUrl("Ab3xY9qk")

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }
    private fun vm(route: String? = link) =
        SharedTrackViewModel(SavedStateHandle(mapOf("link" to route)), music, player, likes, api)

    @Test fun `a long link shows its song at once, with no network call`() = runTest(dispatcher) {
        val vm = vm(); advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(SharedTrackUiState.Loaded(SharedTrack("Teardrop", "Massive Attack")))
        coVerify(exactly = 0) { api.getTrack(any()) }
    }

    @Test fun `a short link loads, then shows the song`() = runTest(dispatcher) {
        coEvery { api.getTrack("Ab3xY9qk") } returns ShareResult.Ok(song)
        val vm = vm(shortLink)
        assertThat(vm.state.value).isEqualTo(SharedTrackUiState.Loading)
        advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(SharedTrackUiState.Loaded(song))
    }

    @Test fun `a short link the server doesn't know says so after one more look, with no retry button`() = runTest(dispatcher) {
        coEvery { api.getTrack(any()) } returns ShareResult.NotFound
        val vm = vm(shortLink); advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(SharedTrackUiState.Error("This song link doesn't exist.", retryable = false))
        coVerify(exactly = 2) { api.getTrack("Ab3xY9qk") }
    }

    @Test fun `a link made a moment ago in another region loads on the second look, 2 s later`() = runTest(dispatcher) {
        // KV can take up to a minute to show a new link everywhere, so the first read may 404.
        coEvery { api.getTrack(any()) } returnsMany listOf(ShareResult.NotFound, ShareResult.Ok(song))
        val vm = vm(shortLink)
        runCurrent()
        coVerify(exactly = 1) { api.getTrack(any()) }
        advanceTimeBy(1_999); runCurrent()
        assertThat(vm.state.value).isEqualTo(SharedTrackUiState.Loading)
        coVerify(exactly = 1) { api.getTrack(any()) }
        advanceTimeBy(1); runCurrent()
        assertThat(vm.state.value).isEqualTo(SharedTrackUiState.Loaded(song))
        coVerify(exactly = 2) { api.getTrack(any()) }
    }

    @Test fun `offline offers a retry that loads the song`() = runTest(dispatcher) {
        coEvery { api.getTrack(any()) } returns ShareResult.Failed("Unable to resolve host")
        val vm = vm(shortLink); advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(
            SharedTrackUiState.Error("Couldn't load this song. Check your connection and try again.", retryable = true),
        )
        coEvery { api.getTrack(any()) } returns ShareResult.Ok(song)
        vm.load()
        assertThat(vm.state.value).isEqualTo(SharedTrackUiState.Loading)
        advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(SharedTrackUiState.Loaded(song))
        coVerify(exactly = 2) { api.getTrack("Ab3xY9qk") }
    }

    @Test fun `a crash while loading is a retry, not a crash`() = runTest(dispatcher) {
        coEvery { api.getTrack(any()) } throws IllegalStateException("boom")
        val vm = vm(shortLink); advanceUntilIdle()
        assertThat((vm.state.value as SharedTrackUiState.Error).retryable).isTrue()
    }

    @Test fun `a route that isn't a song link shows the expired message`() = runTest(dispatcher) {
        val vm = vm(route = null); advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(SharedTrackUiState.Error("This link has expired. Open it again.", retryable = false))
        coVerify(exactly = 0) { api.getTrack(any()) }
    }

    @Test fun `play shows an existing like and a double tap runs once`() = runTest(dispatcher) {
        val saved = mockk<Track> { every { stashLikedAt } returns 1L }
        coEvery { music.ensureTrackPersisted(any()) } returns 7L
        every { music.observeTrackById(7L) } returns flowOf(saved)
        val vm = vm()
        vm.play(); vm.play(); advanceUntilIdle()
        coVerify(exactly = 1) { music.ensureTrackPersisted(any()) }
        coVerify(exactly = 1) { player.setQueue(listOf(saved), 0, any()) }
        assertThat(vm.liked.value).isTrue()
    }

    @Test fun `a short link's song plays with its cover once loaded, and not before`() = runTest(dispatcher) {
        coEvery { api.getTrack(any()) } returns ShareResult.Ok(song)
        val persisted = slot<Track>()
        coEvery { music.ensureTrackPersisted(capture(persisted)) } returns 7L
        every { music.observeTrackById(7L) } returns flowOf(mockk<Track>(relaxed = true))
        val vm = vm(shortLink)
        vm.play(); advanceUntilIdle() // still loading: nothing to play yet
        coVerify(exactly = 0) { music.ensureTrackPersisted(any()) }
        vm.play(); advanceUntilIdle()
        assertThat(persisted.captured.title).isEqualTo("Teardrop")
        assertThat(persisted.captured.albumArtUrl).isEqualTo("https://i.scdn.co/image/a")
    }
}

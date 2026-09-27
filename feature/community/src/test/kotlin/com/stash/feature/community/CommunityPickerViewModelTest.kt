package com.stash.feature.community

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.community.CommunityRepository
import com.stash.core.data.repository.MusicRepository
import com.stash.core.model.MusicSource
import com.stash.core.model.Playlist
import com.stash.core.model.PlaylistType
import com.stash.core.model.Track
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommunityPickerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val music = mockk<MusicRepository>(relaxed = true)
    private val repo = mockk<CommunityRepository>(relaxed = true)

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun playlist(id: Long, name: String, type: PlaylistType, count: Int) =
        Playlist(id = id, name = name, source = MusicSource.BOTH, type = type, trackCount = count)

    @Test fun `the picker lists your playlists and mixes, then your recent songs`() = runTest(dispatcher) {
        every { music.getAllPlaylists() } returns flowOf(listOf(
            playlist(1, "sad boy hours", PlaylistType.CUSTOM, 42),
            playlist(2, "Downloads", PlaylistType.DOWNLOADS_MIX, 10),
            playlist(3, "Empty", PlaylistType.CUSTOM, 0),
            playlist(4, "Daily Discovery", PlaylistType.STASH_MIX, 34),
            playlist(5, "Liked Songs", PlaylistType.LIKED_SONGS, 812),
            playlist(6, "Stash Liked", PlaylistType.STASH_LIKED, 57),
            playlist(7, "Workout", PlaylistType.CUSTOM, 18),
        ))
        coEvery { repo.recentSongs() } returns listOf(Track(id = 9, title = "garden", artist = "Death Plus"))
        val vm = CommunityPickerViewModel(music, repo)
        vm.load(); advanceUntilIdle()
        assertThat(vm.state.value.playlists.map { it.name }).containsExactly("Daily Discovery", "sad boy hours", "Workout").inOrder()
        assertThat(vm.state.value.songs.map { it.title }).containsExactly("garden")
        assertThat(vm.state.value.loading).isFalse()
    }

    @Test fun `a failed reload keeps the lists already on screen`() = runTest(dispatcher) {
        every { music.getAllPlaylists() } returns flowOf(listOf(playlist(1, "sad boy hours", PlaylistType.CUSTOM, 42)))
        val vm = CommunityPickerViewModel(music, repo)
        vm.load(); advanceUntilIdle()
        every { music.getAllPlaylists() } throws IllegalStateException("database closed")
        vm.load()
        assertThat(vm.state.value.playlists.map { it.name }).containsExactly("sad boy hours")
        advanceUntilIdle()
        assertThat(vm.state.value.playlists.map { it.name }).containsExactly("sad boy hours")
        assertThat(vm.state.value.loading).isFalse()
    }
}

package com.stash.feature.listening.recap

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.ArtistImageDao
import com.stash.core.data.genre.ArtistGenreIndex
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaybackSource
import com.stash.core.model.Playlist
import com.stash.core.model.Track
import com.stash.feature.listening.FakeHistory
import com.stash.feature.listening.FakeHistory.Companion.TODAY
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Test

/** The Recap ViewModel: the story it builds, and the RecapAudio calls it makes (begin / play / pause / end). */
@OptIn(ExperimentalCoroutinesApi::class)
class RecapViewModelTest {

    /** Records every call, in order. */
    private class FakeAudio : RecapStoryAudio {
        val calls = mutableListOf<String>()
        override fun begin() { calls += "begin" }
        override fun play(track: Track) { calls += "play ${track.id}" }
        override fun pause() { calls += "pause" }
        override fun resume() { calls += "resume" }
        override fun end(restoreMain: Boolean) { calls += "end $restoreMain" }
        override suspend fun endAndWait(restoreMain: Boolean) { calls += "endAndWait $restoreMain" }
    }

    private val audio = FakeAudio()
    private val player = mockk<PlayerRepository>(relaxed = true)
    private val music = mockk<MusicRepository>(relaxed = true)
    private val connectivity = mockk<ConnectivityMonitor>().also { every { it.isConnected() } returns true }
    private val artistImages = mockk<ArtistImageDao>().also { every { it.observeAll() } returns flowOf(emptyList()) }

    @After fun tearDown() = Dispatchers.resetMain()

    private val octoberStart = 20_727L // 2026-10-01

    /** An October with [listens] listens over 8 songs (song 1 the most played), from Oct 1. */
    private fun october(listens: Int = 60) = FakeHistory().apply {
        var n = 0
        // Some history before October, so "new to you" means something.
        add(track(99, artist = "Old"), octoberStart - 20, 600)
        outer@ for (day in 0 until 18) {
            for (song in 1..8) {
                val times = (9 - song).coerceAtLeast(1)
                repeat(if (day % 2 == 0) times / 2 + 1 else 1) {
                    if (n >= listens) break@outer
                    add(track(song.toLong(), artist = "Artist ${(song + 1) / 2}"), octoberStart + day, 600 + n)
                    n++
                }
            }
        }
    }

    private fun TestScope.viewModel(
        fake: FakeHistory,
        args: Map<String, Any?> = mapOf("kind" to "month", "year" to 2026, "month" to 10),
        muted: Boolean = false,
    ): RecapViewModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        return RecapViewModel(
            SavedStateHandle(args), fake.repository(), artistImages, flowOf(ArtistGenreIndex.EMPTY), audio,
            MutableStateFlow(muted), {}, player, music, connectivity, dispatcher,
        )
    }

    private fun RecapViewModel.ready() = state.value as RecapUiState.Ready

    @Test fun `a month with enough listens becomes a story that ends on the summary`() = runTest {
        val vm = viewModel(october())
        advanceUntilIdle()
        val s = vm.ready()
        assertThat(s.title).isEqualTo("Your October so far")
        assertThat(s.longName).isEqualTo("October 2026")
        assertThat(s.cards.first()).isInstanceOf(RecapCard.Intro::class.java)
        assertThat(s.cards.last()).isInstanceOf(RecapCard.Summary::class.java)
        assertThat(s.cues).hasSize(s.cards.size)
        assertThat(s.topSongs.first().track.id).isEqualTo(1L)
        assertThat((s.cards.first() as RecapCard.Intro).plays).isEqualTo(60)
        val no1 = s.cards.filterIsInstance<RecapCard.TopSong>().single()
        assertThat(no1.firstListen?.localDay).isEqualTo(octoberStart)
    }

    @Test fun `under twenty listens is empty and never touches the music`() = runTest {
        val vm = viewModel(october(listens = 19))
        advanceUntilIdle()
        assertThat(vm.state.value).isInstanceOf(RecapUiState.Empty::class.java)
        vm.startAudio()
        vm.closeAudio()
        assertThat(audio.calls).isEmpty()
    }

    @Test fun `begin once, each card's song, silence on the quiz, end on close and dispose only once`() = runTest {
        val vm = viewModel(october())
        advanceUntilIdle()
        val s = vm.ready()
        vm.startAudio()
        vm.startAudio()
        vm.onCard(0)
        assertThat(audio.calls.count { it == "begin" }).isEqualTo(1)
        val firstSong = (s.cues[0] as SongCue.Play).song.track.id
        assertThat(audio.calls).contains("play $firstSong")

        val quiz = s.cards.indexOfFirst { it is RecapCard.Quiz }
        assertThat(quiz).isAtLeast(0)
        audio.calls.clear()
        vm.onCard(quiz)
        assertThat(audio.calls).containsExactly("pause")
        audio.calls.clear()
        vm.onCard(quiz + 1) // the #1 reveal
        assertThat(audio.calls).containsExactly("play 1", "resume").inOrder()

        vm.closeAudio()
        vm.closeAudio() // onDispose after the close button
        assertThat(audio.calls.count { it.startsWith("end") }).isEqualTo(1)
        assertThat(audio.calls.last()).isEqualTo("end true")
    }

    @Test fun `hold pauses and letting go resumes, but not on the silent quiz`() = runTest {
        val vm = viewModel(october())
        advanceUntilIdle()
        vm.startAudio()
        vm.onCard(0)
        audio.calls.clear()
        vm.setHeld(true)
        assertThat(audio.calls.last()).isEqualTo("pause")
        vm.setHeld(false)
        assertThat(audio.calls.last()).isEqualTo("resume")

        val quiz = vm.ready().cards.indexOfFirst { it is RecapCard.Quiz }
        vm.onCard(quiz)
        audio.calls.clear()
        vm.setHeld(true)
        vm.setHeld(false)
        assertThat(audio.calls).doesNotContain("resume")
    }

    @Test fun `muted never begins, unmuting begins, muting again ends with the music back`() = runTest {
        val vm = viewModel(october(), muted = true)
        advanceUntilIdle()
        vm.startAudio()
        vm.onCard(0)
        assertThat(audio.calls).isEmpty()
        vm.toggleMute()
        assertThat(audio.calls.first()).isEqualTo("begin")
        vm.toggleMute()
        assertThat(audio.calls.last()).isEqualTo("end true")
    }

    @Test fun `Play your top songs ends the story's music without restoring, then plays the top songs`() = runTest {
        val vm = viewModel(october())
        advanceUntilIdle()
        vm.startAudio()
        vm.playTopSongs()
        advanceUntilIdle()
        assertThat(audio.calls).contains("endAndWait false")
        coVerify { player.setQueue(match { it.first().id == 1L }, 0, PlaybackSource.Listening("Your October so far")) }
        vm.closeAudio()
        assertThat(audio.calls.count { it.startsWith("end") }).isEqualTo(1)
    }

    @Test fun `Save as playlist names it after the period, once`() = runTest {
        every { music.getUserCreatedPlaylists() } returns flowOf(emptyList())
        coEvery { music.createPlaylistWithTracks(any(), any()) } returns 7L
        val vm = viewModel(october())
        advanceUntilIdle()
        vm.saveAsPlaylist()
        vm.saveAsPlaylist()
        advanceUntilIdle()
        coVerify(exactly = 1) { music.createPlaylistWithTracks("Your October 2026", match { it.first() == 1L }) }
        assertThat(vm.saved.value).isTrue()
    }

    @Test fun `a rotation (screen recreated, no close) keeps one story playing`() = runTest {
        val vm = viewModel(october())
        advanceUntilIdle()
        vm.startAudio()
        vm.onCard(0)
        // The recreated screen starts again; it never called closeAudio() (a config change isn't a close).
        vm.startAudio()
        vm.onCard(0)
        assertThat(audio.calls.count { it == "begin" }).isEqualTo(1)
        assertThat(audio.calls.none { it.startsWith("end") }).isTrue()
    }

    @Test fun `a double tap on Play your top songs sets the queue once`() = runTest {
        val vm = viewModel(october())
        advanceUntilIdle()
        vm.startAudio()
        vm.playTopSongs()
        vm.playTopSongs()
        advanceUntilIdle()
        coVerify(exactly = 1) { player.setQueue(any(), any(), any()) }
        assertThat(audio.calls.count { it.startsWith("endAndWait") }).isEqualTo(1)
    }

    @Test fun `saving a recap that already has its playlist adds to it instead of a second one`() = runTest {
        val existing = Playlist(id = 9, name = "Your October 2026", source = MusicSource.BOTH)
        every { music.getUserCreatedPlaylists() } returns flowOf(listOf(existing))
        every { music.getTracksByPlaylist(9) } returns flowOf(listOf(Track(id = 1, title = "Song 1", artist = "Artist 1")))
        val vm = viewModel(october())
        advanceUntilIdle()
        vm.saveAsPlaylist()
        advanceUntilIdle()
        coVerify(exactly = 0) { music.createPlaylistWithTracks(any(), any()) }
        coVerify(exactly = 1) { music.addTracksToPlaylist(match { 1L !in it && it.isNotEmpty() }, 9) }
    }

    @Test fun `the quiz keeps the first answer`() = runTest {
        val vm = viewModel(october())
        advanceUntilIdle()
        vm.answerQuiz(2)
        vm.answerQuiz(1)
        assertThat(vm.quizAnswer.value).isEqualTo(2)
    }

    @Test fun `a route without a period is empty`() = runTest {
        val vm = viewModel(october(), args = mapOf("kind" to "month", "year" to 2026, "month" to 0))
        advanceUntilIdle()
        assertThat(vm.state.value).isInstanceOf(RecapUiState.Empty::class.java)
        assertThat(TODAY).isEqualTo(20_744L)
    }
}

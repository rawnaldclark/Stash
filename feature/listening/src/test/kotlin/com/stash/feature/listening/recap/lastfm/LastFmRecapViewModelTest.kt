package com.stash.feature.listening.recap.lastfm

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.ArtistImageDao
import com.stash.core.data.genre.ArtistGenreIndex
import com.stash.core.data.lastfm.recap.LastFmRecapException
import com.stash.core.data.lastfm.recap.LastFmRecapUser
import com.stash.core.data.lastfm.recap.LastFmSpanTotal
import com.stash.core.data.lastfm.recap.LastFmRecapSpan
import com.stash.core.data.listening.ListeningHistoryRepository
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.model.Track
import com.stash.feature.listening.recap.LastFmRecapVoice
import com.stash.feature.listening.recap.RecapCard
import com.stash.feature.listening.recap.RecapPeriod
import com.stash.feature.listening.recap.RecapSource
import com.stash.feature.listening.recap.RecapStoryAudio
import com.stash.feature.listening.recap.RecapUiState
import com.stash.feature.listening.recap.RecapViewModel
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Test
import java.time.YearMonth

/**
 * The Recap story with route source "lastfm": labelled Last.fm, built from the Last.fm source, and
 * separate from Stash's own listening — Stash's history is never read beyond today's date, and
 * the end actions (which would touch the library or the queue) do nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LastFmRecapViewModelTest {

    private class FakeAudio : RecapStoryAudio {
        val calls = mutableListOf<String>()
        override fun begin() { calls += "begin" }
        override fun play(track: Track) { calls += "play ${track.id}" }
        override fun pause() { calls += "pause" }
        override fun resume() { calls += "resume" }
        override fun end(restoreMain: Boolean) { calls += "end $restoreMain" }
        override suspend fun endAndWait(restoreMain: Boolean) { calls += "endAndWait $restoreMain" }
    }

    private class FakeSource(var result: LastFmRecapLoad) : LastFmRecapSource {
        var loads = 0
        override suspend fun load(period: RecapPeriod, today: Long, onProgress: (LastFmRecapProgress) -> Unit): LastFmRecapLoad {
            loads++
            onProgress(LastFmRecapProgress.Reading(200, 939))
            return result
        }
    }

    private val audio = FakeAudio()
    private val player = mockk<PlayerRepository>(relaxed = true)
    private val music = mockk<MusicRepository>(relaxed = true)
    private val connectivity = mockk<ConnectivityMonitor>(relaxed = true)
    private val artistImages = mockk<ArtistImageDao>()

    /** STRICT: only today() answers; any read or write of Stash's history would throw. */
    private val history = mockk<ListeningHistoryRepository>().also { every { it.today() } returns LastFmFixtures.today }

    private val ready = LastFmRecapLoad.Ready(LastFmRecapMapper.build(LastFmFixtures.input()))

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(source: LastFmRecapSource, routeSource: String? = "lastfm"): RecapViewModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val args = buildMap<String, Any?> {
            put("kind", "month"); put("year", 2026); put("month", 9)
            if (routeSource != null) put("source", routeSource)
        }
        return RecapViewModel(
            SavedStateHandle(args), history, artistImages, flowOf(ArtistGenreIndex.EMPTY), audio,
            MutableStateFlow(false), {}, player, music, connectivity, dispatcher, source,
        )
    }

    @Test fun `a Last_fm recap is labelled Last_fm everywhere`() = runTest {
        val vm = viewModel(FakeSource(ready))
        advanceUntilIdle()
        val s = vm.state.value as RecapUiState.Ready
        assertThat(s.voice.source).isEqualTo(RecapSource.LASTFM)
        assertThat(s.title).isEqualTo("Your September on Last.fm")
        assertThat(s.footer).isEqualTo("Stash · Last.fm · September 2026")
        assertThat(s.voice.introLead).isEqualTo("You scrobbled")
        assertThat(s.voice.credit).isEqualTo("Scrobbles from Last.fm")
        assertThat(s.voice.endActions).isFalse()
        assertThat((s.cards.last() as RecapCard.Summary).title).isEqualTo("Your September on Last.fm")
    }

    @Test fun `Stash's own history is never read for it`() = runTest {
        val vm = viewModel(FakeSource(ready))
        advanceUntilIdle()
        assertThat(vm.state.value).isInstanceOf(RecapUiState.Ready::class.java)
        verify { history.today() }
        confirmVerified(history)
    }

    @Test fun `the end actions do nothing - no queue, no playlist, no library rows`() = runTest {
        val vm = viewModel(FakeSource(ready))
        advanceUntilIdle()
        vm.playTopSongs()
        vm.saveAsPlaylist()
        advanceUntilIdle()
        confirmVerified(player, music)
        assertThat(vm.saved.value).isFalse()
    }

    @Test fun `story music plays the recap's songs on the story player, library copy or not`() = runTest {
        val vm = viewModel(FakeSource(ready))
        advanceUntilIdle()
        vm.startAudio()
        vm.onCard(0)
        val s = vm.state.value as RecapUiState.Ready
        // Every card's song is one of the recap's own rows (its library copy, or a negative id).
        val ids = s.topSongs.map { it.track.id }.toSet()
        val played = audio.calls.filter { it.startsWith("play ") }.map { it.removePrefix("play ").toLong() }
        assertThat(audio.calls.first()).isEqualTo("begin")
        assertThat(played).isNotEmpty()
        assertThat(ids).containsAtLeastElementsIn(played)
        vm.closeAudio()
        assertThat(audio.calls.last()).isEqualTo("end true")
    }

    @Test fun `while loading, the line says how far it has got`() = runTest {
        val source = FakeSource(ready)
        val vm = viewModel(source)
        val seen = ArrayList<RecapUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { seen += it } }
        advanceUntilIdle()
        val lines = seen.filterIsInstance<RecapUiState.Fetching>().map { it.line }
        assertThat(lines).contains("Reading your scrobbles… 200 of 939")
    }

    @Test fun `not connected, private, offline - calm messages, retry only where it can help`() = runTest {
        val source = FakeSource(LastFmRecapLoad.NotConnected)
        val vm = viewModel(source)
        advanceUntilIdle()
        val notConnected = vm.state.value as RecapUiState.Empty
        assertThat(notConnected.message).isEqualTo(LastFmRecapVoice.NOT_CONNECTED)
        assertThat(notConnected.openSettings).isTrue()

        source.result = LastFmRecapLoad.Failed(LastFmRecapException.Reason.PRIVATE)
        val private = viewModel(source)
        advanceUntilIdle()
        val p = private.state.value as RecapUiState.Empty
        assertThat(p.message).contains("private")
        assertThat(p.canRetry).isFalse()

        source.result = LastFmRecapLoad.Failed(LastFmRecapException.Reason.OFFLINE)
        val offline = viewModel(source)
        advanceUntilIdle()
        assertThat((offline.state.value as RecapUiState.Empty).canRetry).isTrue()
        val before = source.loads
        source.result = ready
        offline.retry()
        advanceUntilIdle()
        assertThat(source.loads).isEqualTo(before + 1)
        assertThat(offline.state.value).isInstanceOf(RecapUiState.Ready::class.java)
    }

    @Test fun `without the source argument it is Stash's own recap - the Last_fm source is never asked`() = runTest {
        val source = FakeSource(ready)
        // Stash's own recap reads the (strict) history: it fails into Empty here, which is fine —
        // the point is the Last.fm source isn't consulted.
        viewModel(source, routeSource = null)
        advanceUntilIdle()
        assertThat(source.loads).isEqualTo(0)
    }

    @Test fun `Past recaps - only spans with 20 scrobbles, months then years`() {
        val offered = LastFmPastRecapsViewModel.offered(
            listOf(
                LastFmSpanTotal(LastFmRecapSpan.Month(YearMonth.of(2026, 10)), 939),
                LastFmSpanTotal(LastFmRecapSpan.Month(YearMonth.of(2026, 9)), 743),
                LastFmSpanTotal(LastFmRecapSpan.Month(YearMonth.of(2026, 8)), 19),
                LastFmSpanTotal(LastFmRecapSpan.Year(2026), 1719),
            ),
        )
        assertThat(offered.map { it.period }).containsExactly(
            RecapPeriod.MonthOf(2026, 10), RecapPeriod.MonthOf(2026, 9), RecapPeriod.YearOf(2026),
        ).inOrder()
        assertThat(offered.map { it.plays }).containsExactly(939, 743, 1719).inOrder()
    }

    @Test fun `Past recaps - not connected or unreadable, no row`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val none = LastFmPastRecapsViewModel(flowOf(null), { _, _ -> error("never") }, { LastFmFixtures.zone })
        val failing = LastFmPastRecapsViewModel(
            flowOf(LastFmRecapUser("hidden", null)),
            { _, _ -> throw LastFmRecapException(LastFmRecapException.Reason.PRIVATE) },
            { LastFmFixtures.zone },
        )
        val ok = LastFmPastRecapsViewModel(
            flowOf(LastFmRecapUser("rawnclark", "sk")),
            { u, _ ->
                assertThat(u.sessionKey).isEqualTo("sk")
                listOf(LastFmSpanTotal(LastFmRecapSpan.Month(YearMonth.of(2026, 9)), 743))
            },
            { LastFmFixtures.zone },
        )
        listOf(none, failing, ok).forEach { it.recaps.launchIn(backgroundScope) }
        advanceUntilIdle()
        assertThat(none.recaps.value).isEmpty()
        assertThat(failing.recaps.value).isEmpty()
        assertThat(ok.recaps.value.single().period).isEqualTo(RecapPeriod.MonthOf(2026, 9))
    }
}

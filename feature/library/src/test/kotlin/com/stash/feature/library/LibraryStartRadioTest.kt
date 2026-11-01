package com.stash.feature.library

import androidx.lifecycle.SavedStateHandle
import com.stash.core.data.db.dao.ArtistImageDao
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.media.RadioStarter
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.model.PlayerState
import com.stash.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/**
 * Start radio in the Library: a song's menu starts a song radio and Library's artist page starts the
 * artist's radio, both through [RadioStarter]; a station that doesn't start says why.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryStartRadioTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    /** Records what was asked; answers [answer] (null = started). */
    private class FakeStarter(var answer: String? = null) : RadioStarter {
        val songs = mutableListOf<Track>()
        val artists = mutableListOf<String>()
        override suspend fun fromSong(track: Track): String? { songs += track; return answer }
        override suspend fun fromArtist(artistName: String): String? { artists += artistName; return answer }
    }

    private fun track(id: Long) = Track(id = id, title = "Song $id", artist = "Slowdive")

    private fun buildVm(starter: RadioStarter?) = ArtistDetailViewModel(
        savedStateHandle = SavedStateHandle(mapOf("artistName" to "Slowdive")),
        musicRepository = mock<MusicRepository> {
            on { getTracksByArtist(any()) } doReturn flowOf(emptyList())
            on { getUserCreatedPlaylists() } doReturn flowOf(emptyList())
        },
        playerRepository = mock<PlayerRepository> { on { playerState } doReturn MutableStateFlow(PlayerState()) },
        artistImageDao = mock<ArtistImageDao> { on { observeByName(any()) } doReturn flowOf(null) },
        connectivityMonitor = mock<ConnectivityMonitor> { on { isConnected() } doReturn true },
        radioStarter = starter,
    )

    @Test
    fun `the artist page's radio starts this artist's station`() = runTest {
        val starter = FakeStarter()
        val vm = buildVm(starter)

        vm.startArtistRadio()
        runCurrent()

        assertEquals(listOf("Slowdive"), starter.artists)
        assertEquals(emptyList<Track>(), starter.songs)
    }

    @Test
    fun `a song's Start radio starts a radio from that song`() = runTest {
        val starter = FakeStarter()
        val vm = buildVm(starter)
        // One instance: Track.dateAdded defaults to the clock, so two track(3L)s can differ.
        val song = track(3L)

        vm.startRadio(song)
        runCurrent()

        assertEquals(listOf(song), starter.songs)
    }

    @Test
    fun `a station that doesn't start says why`() = runTest {
        val vm = buildVm(FakeStarter(answer = "Couldn't find similar songs for this."))
        val message = async { vm.userMessages.first() }
        runCurrent()

        vm.startRadio(track(1L))
        runCurrent()

        assertEquals("Couldn't find similar songs for this.", message.await())
    }
}

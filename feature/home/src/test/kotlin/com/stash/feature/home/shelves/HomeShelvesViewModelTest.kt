package com.stash.feature.home.shelves

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.cache.AlbumCache
import com.stash.core.data.home.BecauseRow
import com.stash.core.data.home.BecauseSeed
import com.stash.core.data.home.BecauseYouLikeRepository
import com.stash.core.data.home.HomeShownSongs
import com.stash.core.data.home.NewFromArtistsRepository
import com.stash.core.data.prefs.DEFAULT_HOME_SECTIONS
import com.stash.core.data.prefs.HomeSection
import com.stash.core.data.prefs.HomeSectionsPreference
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.media.PlayerRepository
import com.stash.core.media.actions.TrackActionsDelegate
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaybackSource
import com.stash.core.model.Track
import com.stash.core.model.TrackItem
import com.stash.data.ytmusic.model.AlbumDetail
import com.stash.data.ytmusic.model.AlbumSummary
import com.stash.data.ytmusic.model.TrackSummary
import com.stash.feature.home.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import java.io.IOException

/**
 * [HomeShelvesViewModel]'s wiring: a shelf loads only while it's on Home (Streaming on, its section shown), with no
 * network it shows what's saved without trying, and it stops loading when it's hidden or Offline mode turns on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeShelvesViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val releases = listOf(AlbumSummary("MPREb_1", "Bring Your Love", "Sabrina Carpenter", null, "2026"))
    private val saved = listOf(AlbumSummary("MPREb_0", "Manchild", "Sabrina Carpenter", null, "2025"))

    private val online = MutableStateFlow(true)
    private val visible = MutableStateFlow(DEFAULT_HOME_SECTIONS)
    private val newFromArtists = mock<NewFromArtistsRepository> {
        onBlocking { cached() } doReturn saved
        onBlocking { shelf() } doReturn releases
    }
    private fun song(n: Int) = TrackSummary("v$n", "Song $n", "Artist $n", "Album $n", 200.0, "https://art/$n", musicVideoType = "ATV")
    private val row = BecauseRow(BecauseSeed("seed", "Africa", "Toto", null), (1..6).map(::song))
    private val because = mock<BecauseYouLikeRepository> {
        onBlocking { cached() } doReturn emptyList()
        onBlocking { shelf() } doReturn listOf(row)
    }
    private val shown = HomeShownSongs()
    private val player = mock<PlayerRepository>()
    private val streaming = mock<StreamingPreference> { on { enabled } doReturn online }
    private val sections = mock<HomeSectionsPreference> { on { visibleSections } doReturn visible }
    private val connectivity = mock<ConnectivityMonitor> { on { isConnected() } doReturn true }
    private val delegate = mock<TrackActionsDelegate> {
        on { userMessages } doReturn MutableSharedFlow()
        on { userPlaylists } doReturn flowOf(emptyList())
        on { inListenTogether } doReturn false
    }
    private val albumCache = mock<AlbumCache>()

    private fun viewModel() =
        HomeShelvesViewModel(newFromArtists, because, shown, player, streaming, sections, connectivity, delegate, albumCache)

    @Test
    fun `shown and online, the row is built and shown`() = runTest(dispatcher) {
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        assertThat(vm.state.value.newFromArtists).isEqualTo(releases)
        verifyBlocking(newFromArtists, times(1)) { shelf() }
    }

    @Test
    fun `a hidden section costs no request, and turning it back on fills it`() = runTest(dispatcher) {
        visible.value = DEFAULT_HOME_SECTIONS - HomeSection.NEW_FROM_ARTISTS
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        vm.onResume()
        runCurrent()
        verifyBlocking(newFromArtists, never()) { shelf() }
        verifyBlocking(newFromArtists, never()) { cached() }
        assertThat(vm.state.value.newFromArtists).isEmpty()
        // Turned back on in Settings > Home layout
        visible.value = DEFAULT_HOME_SECTIONS
        runCurrent()
        verifyBlocking(newFromArtists, times(1)) { shelf() }
        assertThat(vm.state.value.newFromArtists).isEqualTo(releases)
    }

    @Test
    fun `offline mode shows nothing and builds nothing, and switching it off fills the row`() = runTest(dispatcher) {
        online.value = false
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        vm.onResume()
        runCurrent()
        assertThat(vm.state.value).isEqualTo(HomeShelvesState())
        verifyBlocking(newFromArtists, never()) { shelf() }
        online.value = true
        runCurrent()
        verifyBlocking(newFromArtists, times(1)) { shelf() }
        assertThat(vm.state.value.newFromArtists).isEqualTo(releases)
    }

    @Test
    fun `a build under way stops when offline mode turns on`() = runTest(dispatcher) {
        val never = CompletableDeferred<List<AlbumSummary>>()
        var cancelled = false
        whenever(newFromArtists.shelf()).doSuspendableAnswer {
            try {
                never.await()
            } finally {
                cancelled = true
            }
        }
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        assertThat(cancelled).isFalse()
        online.value = false
        runCurrent()
        assertThat(cancelled).isTrue()
        assertThat(vm.state.value.newFromArtists).isEmpty()
    }

    @Test
    fun `each shelf follows its own section - because you like hidden costs nothing while your artists' row loads`() = runTest(dispatcher) {
        visible.value = DEFAULT_HOME_SECTIONS - HomeSection.BECAUSE_YOU_LIKE
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        assertThat(vm.state.value.newFromArtists).isEqualTo(releases)
        assertThat(vm.state.value.becauseYouLike).isEmpty()
        verifyBlocking(because, never()) { shelf() }
        visible.value = DEFAULT_HOME_SECTIONS
        runCurrent()
        assertThat(vm.state.value.becauseYouLike).containsExactly(row)
        // Only the shelf that was turned on loads again
        verifyBlocking(newFromArtists, times(1)) { shelf() }
    }

    @Test
    fun `with no network the saved row shows and no build is tried`() = runTest(dispatcher) {
        whenever(connectivity.isConnected()).doReturn(false)
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        vm.onResume()
        runCurrent()
        assertThat(vm.state.value.newFromArtists).isEqualTo(saved)
        verifyBlocking(newFromArtists, never()) { shelf() }
        // Back on a network: the next return to Home builds
        whenever(connectivity.isConnected()).doReturn(true)
        vm.onResume()
        runCurrent()
        assertThat(vm.state.value.newFromArtists).isEqualTo(releases)
    }

    // ── Because you like ───────────────────────────────────────────────────

    @Test
    fun `because you like shows its rows, less what the record's mixes on Home hold`() = runTest(dispatcher) {
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        assertThat(vm.state.value.becauseYouLike).containsExactly(row)
        shown.publish(HomeShownSongs.RECORD_MIXES, listOf("Song 1" to "Artist 1"))
        runCurrent()
        assertThat(vm.state.value.becauseYouLike.single().songs).isEqualTo(row.songs.drop(1))
        // Three more taken: the row is under four cards and goes
        shown.publish(HomeShownSongs.RECORD_MIXES, (1..3).map { "Song $it" to "Artist $it" })
        runCurrent()
        assertThat(vm.state.value.becauseYouLike).isEmpty()
    }

    @Test
    fun `because you like hidden costs no request`() = runTest(dispatcher) {
        visible.value = DEFAULT_HOME_SECTIONS - HomeSection.BECAUSE_YOU_LIKE
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        vm.onResume()
        runCurrent()
        verifyBlocking(because, never()) { shelf() }
        assertThat(vm.state.value.becauseYouLike).isEmpty()
    }

    @Test
    fun `a card plays its row from that song, streamed, named for the seed`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.playRow(row, 2, "Because you like Africa")
        runCurrent()
        val queue = argumentCaptor<List<Track>>()
        verifyBlocking(player) { setQueue(queue.capture(), eq(2), eq(PlaybackSource.Listening("Because you like Africa"))) }
        assertThat(queue.firstValue.map { it.youtubeId }).isEqualTo(row.songs.map { it.videoId })
        assertThat(queue.firstValue.all { it.source == MusicSource.YOUTUBE && it.isStreamable }).isTrue()
        assertThat(queue.firstValue[2].durationMs).isEqualTo(200_000L)
    }

    // ── A Because you like song's sheet ─────────────────────────────────────

    /** Song 1 as the delegate takes it: every field the card carries, its album and video type included. */
    private val song1Item = TrackItem(
        videoId = "v1", title = "Song 1", artist = "Artist 1", durationSeconds = 200.0, thumbnailUrl = "https://art/1",
        album = "Album 1", musicVideoType = "ATV",
    )

    @Test
    fun `the song sheet binds the delegate once, and its rows go through it with the card's song`() = runTest(dispatcher) {
        val vm = viewModel()
        verify(delegate, times(1)).bindToScope(any())
        vm.playNext(song(1))
        vm.addToQueue(song(1))
        vm.startRadio(song(1))
        vm.download(song(1))
        vm.saveToPlaylist(song(1), 7L)
        vm.createPlaylistAndAdd(song(1), "Road trip")
        verify(delegate).playNext(song1Item)
        verify(delegate).addToQueue(song1Item)
        verify(delegate).startRadio(song1Item)
        verify(delegate).downloadTrack(song1Item)
        verify(delegate).addToPlaylist(song1Item, 7L)
        verify(delegate).createPlaylistAndAdd(song1Item, "Road trip")
    }

    @Test
    fun `the sheet shows the card's song as the row plays it`() {
        val track = song(2).toStreamTrack()
        assertThat(track.youtubeId).isEqualTo("v2")
        assertThat(track.title).isEqualTo("Song 2")
        assertThat(track.artist).isEqualTo("Artist 2")
        assertThat(track.albumArtUrl).isEqualTo("https://art/2")
        assertThat(track.isDownloaded).isFalse() // so the sheet offers Download
    }

    // ── A New from your artists album's sheet ───────────────────────────────

    private val album = AlbumSummary("MPREb_1", "Bring Your Love", "Sabrina Carpenter", "https://cover", "2026")
    private val detail = AlbumDetail(
        id = "MPREb_1",
        title = "Bring Your Love",
        artist = "Sabrina Carpenter",
        artistId = "UCsabrina",
        thumbnailUrl = "https://cover",
        year = "2026",
        tracks = listOf(
            TrackSummary("a1", "Intro", "", null, 60.0, null),
            TrackSummary("a2", "Bring Your Love", "Sabrina Carpenter", null, 180.0, "https://art/a2"),
            TrackSummary("a3", "Outro", "Sabrina Carpenter", null, 90.0, null),
        ),
        moreByArtist = emptyList(),
    )
    private val albumOrder = listOf("a1", "a2", "a3")

    /** The album sheet's toasts, collected from before the action. */
    private fun kotlinx.coroutines.test.TestScope.albumToasts(vm: HomeShelvesViewModel): List<Int> {
        val toasts = mutableListOf<Int>()
        backgroundScope.launch { vm.albumMessages.toList(toasts) }
        runCurrent()
        return toasts
    }

    @Test
    fun `Play plays the album in its order, streamed, named for the album`() = runTest(dispatcher) {
        whenever(albumCache.get(eq("MPREb_1"), any())).doReturn(detail)
        val vm = viewModel()
        vm.playAlbum(album)
        runCurrent()
        val queue = argumentCaptor<List<Track>>()
        verifyBlocking(player) { setQueue(queue.capture(), eq(0), eq(PlaybackSource.Album("Bring Your Love", "Sabrina Carpenter"))) }
        assertThat(queue.firstValue.map { it.youtubeId }).isEqualTo(albumOrder)
        assertThat(queue.firstValue.all { it.source == MusicSource.YOUTUBE && it.isStreamable && it.album == "Bring Your Love" }).isTrue()
        // A row with no artist or cover takes the album's
        assertThat(queue.firstValue[0].artist).isEqualTo("Sabrina Carpenter")
        assertThat(queue.firstValue[0].albumArtUrl).isEqualTo("https://cover")
        assertThat(queue.firstValue[1].albumArtUrl).isEqualTo("https://art/a2")
    }

    @Test
    fun `Shuffle plays every song of the album, named for the album`() = runTest(dispatcher) {
        whenever(albumCache.get(eq("MPREb_1"), any())).doReturn(detail)
        val vm = viewModel()
        vm.playAlbum(album, shuffle = true)
        runCurrent()
        val queue = argumentCaptor<List<Track>>()
        verifyBlocking(player) { setQueue(queue.capture(), eq(0), eq(PlaybackSource.Album("Bring Your Love", "Sabrina Carpenter"))) }
        assertThat(queue.firstValue.map { it.youtubeId }).containsExactlyElementsIn(albumOrder)
    }

    @Test
    fun `Play next puts each song after the current one in album order, and says so`() = runTest(dispatcher) {
        whenever(albumCache.get(eq("MPREb_1"), any())).doReturn(detail)
        whenever(player.addNext(any())).doReturn(true)
        val vm = viewModel()
        val toasts = albumToasts(vm)
        vm.playAlbumNext(album)
        runCurrent()
        val added = argumentCaptor<Track>()
        verifyBlocking(player, times(3)) { addNext(added.capture()) }
        assertThat(added.allValues.map { it.youtubeId }).isEqualTo(albumOrder)
        assertThat(toasts).containsExactly(R.string.home_album_playing_next)
    }

    @Test
    fun `Add to queue adds the album in one go, and says so`() = runTest(dispatcher) {
        whenever(albumCache.get(eq("MPREb_1"), any())).doReturn(detail)
        whenever(player.addToQueue(any<List<Track>>())).doReturn(true)
        val vm = viewModel()
        val toasts = albumToasts(vm)
        vm.addAlbumToQueue(album)
        runCurrent()
        val queued = argumentCaptor<List<Track>>()
        verifyBlocking(player, times(1)) { addToQueue(queued.capture()) }
        assertThat(queued.firstValue.map { it.youtubeId }).isEqualTo(albumOrder)
        verifyBlocking(player, never()) { addToQueue(any<Track>()) }
        assertThat(toasts).containsExactly(R.string.home_album_added_to_queue)
    }

    @Test
    fun `an add the player refuses says it couldn't, and in Listen Together the session confirms a done one`() = runTest(dispatcher) {
        whenever(albumCache.get(eq("MPREb_1"), any())).doReturn(detail)
        whenever(player.addToQueue(any<List<Track>>())).doReturn(false)
        val vm = viewModel()
        val toasts = albumToasts(vm)
        vm.addAlbumToQueue(album)
        runCurrent()
        assertThat(toasts).containsExactly(R.string.home_album_add_failed)
        whenever(player.addToQueue(any<List<Track>>())).doReturn(true)
        whenever(delegate.inListenTogether).doReturn(true)
        vm.addAlbumToQueue(album)
        runCurrent()
        assertThat(toasts).containsExactly(R.string.home_album_add_failed)
    }

    @Test
    fun `an album that doesn't load leaves the queue alone and says so once`() = runTest(dispatcher) {
        whenever(albumCache.get(eq("MPREb_1"), any())).doSuspendableAnswer { throw IOException("offline") }
        val vm = viewModel()
        val toasts = albumToasts(vm)
        vm.playAlbum(album)
        runCurrent()
        verifyBlocking(player, never()) { setQueue(any(), any(), any()) }
        assertThat(toasts).containsExactly(R.string.home_album_load_failed)
    }

    @Test
    fun `Go to artist opens the album's artist, or says the album names none`() = runTest(dispatcher) {
        whenever(albumCache.get(eq("MPREb_1"), any())).doReturn(detail)
        val vm = viewModel()
        val toasts = albumToasts(vm)
        var opened: Pair<String, String>? = null
        vm.openArtist(album) { id, name -> opened = id to name }
        runCurrent()
        assertThat(opened).isEqualTo("UCsabrina" to "Sabrina Carpenter")
        assertThat(toasts).isEmpty()
        whenever(albumCache.get(eq("MPREb_1"), any())).doReturn(detail.copy(artistId = null))
        opened = null
        vm.openArtist(album) { id, name -> opened = id to name }
        runCurrent()
        assertThat(opened).isNull()
        assertThat(toasts).containsExactly(R.string.home_album_no_artist)
    }
}

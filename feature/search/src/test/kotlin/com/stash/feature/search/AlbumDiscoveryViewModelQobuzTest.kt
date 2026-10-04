package com.stash.feature.search

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.stash.core.data.cache.AlbumCache
import com.stash.core.data.discography.QobuzAlbumUnavailableException
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.media.actions.TrackActionsDelegate
import com.stash.core.media.preview.PreviewState
import com.stash.core.model.MusicSource
import com.stash.core.model.Playlist
import com.stash.core.model.Track
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.AlbumDetail
import com.stash.data.ytmusic.model.AlbumSearch
import com.stash.data.ytmusic.model.AlbumSource
import com.stash.data.ytmusic.model.AlbumSummary
import com.stash.data.ytmusic.model.TrackSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException

/**
 * Covers the Qobuz branch of [AlbumDiscoveryViewModel] (Task 12): source
 * routing to the cache, persist-for-resume queue building, guarding the
 * videoId-keyed side-effects, and the YT regression.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlbumDiscoveryViewModelQobuzTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun tear() { Dispatchers.resetMain() }

    private fun qobuzTrack(title: String) = TrackSummary(
        videoId = "", title = title, artist = "MBV", album = "Loveless",
        durationSeconds = 200.0, thumbnailUrl = null,
    )

    private fun qobuzDetail() = AlbumDetail(
        id = "123", title = "Loveless", artist = "MBV", artistId = null,
        thumbnailUrl = null, year = "1991",
        tracks = listOf(qobuzTrack("Only Shallow"), qobuzTrack("Loomer")),
        moreByArtist = emptyList(),
    )

    private fun ytDetail() = AlbumDetail(
        id = "MPRE1", title = "YT Album", artist = "A", artistId = null,
        thumbnailUrl = null, year = "2005",
        tracks = listOf(
            TrackSummary("vid1", "T1", "A", "YT Album", 180.0, null),
        ),
        moreByArtist = emptyList(),
    )

    private fun vm(
        source: AlbumSource,
        cache: AlbumCache,
        prefetcher: PreviewPrefetcher = mock(),
        player: PlayerRepository = mock(),
        musicRepo: MusicRepository = mock(),
        delegate: TrackActionsDelegate = stubDelegate(),
        yt: YTMusicApiClient = mock(),
        title: String = "T",
        artist: String = "A",
        year: String? = null,
        qobuzTrackCount: Int? = null,
    ) = AlbumDiscoveryViewModel(
        savedStateHandle = SavedStateHandle(
            mapOf(
                "browseId" to (if (source == AlbumSource.QOBUZ) "123" else "MPRE1"),
                "title" to title,
                "artist" to artist,
                "thumbnailUrl" to null,
                "year" to year,
                "source" to source,
                "qobuzTrackCount" to qobuzTrackCount,
            ),
        ),
        albumCache = cache,
        ytMusicApiClient = yt,
        prefetcher = prefetcher,
        playerRepository = player,
        musicRepository = musicRepo,
        streamingPreference = mock(),
        delegate = delegate,
        losslessPrefetcher = mock(),
    )

    private fun stubDelegate(
        userPlaylists: List<Playlist> = emptyList(),
    ): TrackActionsDelegate = mock {
        on { previewState } doReturn
            MutableStateFlow(PreviewState.Idle as PreviewState).asStateFlow()
        on { userMessages } doReturn MutableSharedFlow<String>().asSharedFlow()
        on { downloadingIds } doReturn MutableStateFlow(emptySet<String>()).asStateFlow()
        on { downloadedIds } doReturn MutableStateFlow(emptySet<String>()).asStateFlow()
        on { previewLoadingId } doReturn MutableStateFlow<String?>(null).asStateFlow()
        on { this.userPlaylists } doReturn MutableStateFlow(userPlaylists)
    }

    @Test fun `qobuz album passes its source to AlbumCache`() = runTest {
        val cache = mock<AlbumCache>()
        whenever(cache.get(eq("123"), eq(AlbumSource.QOBUZ))).thenReturn(qobuzDetail())
        vm(AlbumSource.QOBUZ, cache); advanceUntilIdle()
        verify(cache).get(eq("123"), eq(AlbumSource.QOBUZ))
    }

    @Test fun `qobuz play persists tracks and queues real ids with null youtubeId`() = runTest {
        val cache = mock<AlbumCache>()
        whenever(cache.get(any(), any())).thenReturn(qobuzDetail())
        val musicRepo = mock<MusicRepository>()
        whenever(musicRepo.ensureTrackPersisted(any())).thenReturn(1001L, 1002L)
        val player = mock<PlayerRepository>()
        val vm = vm(AlbumSource.QOBUZ, cache, musicRepo = musicRepo, player = player)
        advanceUntilIdle()

        vm.playAlbum(0); advanceUntilIdle()

        val cap = argumentCaptor<List<Track>>()
        verify(player).setQueue(cap.capture(), eq(0), any())
        assertEquals(listOf(1001L, 1002L), cap.firstValue.map { it.id })
        assertTrue(cap.firstValue.all { it.youtubeId == null })
    }

    @Test fun `qobuz PLAYLIST play persists tracks with distinct real ids (regression)`() = runTest {
        // Regression: a QOBUZ_PLAYLIST source used to fall through to the YouTube
        // path, giving every track id = "".hashCode() = 0 and youtubeId = "" —
        // so the queue collapsed to one row and playback refused to advance past
        // the first track. It must take the native path (persist → distinct ids).
        val cache = mock<AlbumCache>()
        whenever(cache.get(any(), any())).thenReturn(qobuzDetail())
        val musicRepo = mock<MusicRepository>()
        whenever(musicRepo.ensureTrackPersisted(any())).thenReturn(2001L, 2002L)
        val player = mock<PlayerRepository>()
        val vm = vm(AlbumSource.QOBUZ_PLAYLIST, cache, musicRepo = musicRepo, player = player)
        advanceUntilIdle()

        vm.playAlbum(0); advanceUntilIdle()

        val cap = argumentCaptor<List<Track>>()
        verify(player).setQueue(cap.capture(), eq(0), any())
        assertEquals(listOf(2001L, 2002L), cap.firstValue.map { it.id })
        assertTrue(cap.firstValue.all { it.youtubeId == null })
    }

    @Test fun `qobuz album does NOT run videoId-keyed side effects`() = runTest {
        val cache = mock<AlbumCache>()
        whenever(cache.get(any(), any())).thenReturn(qobuzDetail())
        val prefetcher = mock<PreviewPrefetcher>()
        val musicRepo = mock<MusicRepository>()
        val delegate = stubDelegate()
        vm(AlbumSource.QOBUZ, cache, prefetcher = prefetcher, musicRepo = musicRepo, delegate = delegate)
        advanceUntilIdle()

        verify(prefetcher, never()).prefetch(any())
        verify(delegate, never()).refreshDownloadedIds(any())
        verify(musicRepo, never()).backfillAlbumForTracks(any(), any(), any())
    }

    @Test fun `youtube album still runs prefetch (regression)`() = runTest {
        val cache = mock<AlbumCache>()
        whenever(cache.get(any(), any())).thenReturn(ytDetail())
        val prefetcher = mock<PreviewPrefetcher>()
        vm(AlbumSource.YOUTUBE, cache, prefetcher = prefetcher); advanceUntilIdle()
        verify(prefetcher).prefetch(any())
    }

    // ── #481: an album Qobuz doesn't sell in the user's country ─────────────
    //
    // Qobuz picks its store from the caller's IP. Where it doesn't sell, Home still
    // lists its new releases, but album/get 404s for every one of them. Playback
    // never needed Qobuz, only the track list did, so the screen opens the same
    // album from YouTube Music, and says so plainly when there's no confident match.
    // AlbumRegionFallbackWiringTest runs the same flow through the real cache, Qobuz
    // fetcher and YouTube client.

    /** The YouTube Music copy of [qobuzDetail]'s "Loveless". */
    private fun ytCopy(
        id: String = "MPREb_yt",
        title: String = "Loveless",
        artist: String = "MBV",
        year: String? = "1991",
        releaseType: String? = "Album",
    ) = AlbumSummary(
        id = id, title = title, artist = artist, thumbnailUrl = "yt-art", year = year, releaseType = releaseType,
    )

    private fun ytLoveless() = AlbumDetail(
        id = "MPREb_yt", title = "Loveless", artist = "MBV", artistId = "UCmbv",
        thumbnailUrl = "yt-art", year = "1991",
        tracks = listOf(
            TrackSummary("yv1", "Only Shallow", "MBV", "Loveless", 257.0, null),
            TrackSummary("yv2", "Loomer", "MBV", "Loveless", 158.0, null),
        ),
        moreByArtist = emptyList(),
    )

    /** Qobuz has no album 123 for this caller; the YouTube copy loads. */
    private suspend fun notSoldHereCache(): AlbumCache = mock<AlbumCache>().also {
        whenever(it.get(eq("123"), eq(AlbumSource.QOBUZ)))
            .doSuspendableAnswer { throw QobuzAlbumUnavailableException("123") }
        whenever(it.get(eq("MPREb_yt"), eq(AlbumSource.YOUTUBE))).thenReturn(ytLoveless())
    }

    /** A YouTube client whose album search answers with [candidates], YouTube's best guess first. */
    private suspend fun ytAnswering(vararg candidates: AlbumSummary): YTMusicApiClient = mock<YTMusicApiClient>().also {
        whenever(it.searchAlbums(any(), any()))
            .thenReturn(AlbumSearch.Answered(topAlbum = null, shelf = candidates.toList()))
    }

    private val notAvailable =
        AlbumDiscoveryStatus.Error("This album isn't available in your country.", canRetry = false)
    private val couldNotLoad =
        AlbumDiscoveryStatus.Error("Check your connection and try again.", canRetry = true)

    @Test fun `an album Qobuz doesn't sell here opens its YouTube Music copy`() = runTest {
        val yt = ytAnswering(ytCopy())
        val prefetcher = mock<PreviewPrefetcher>()
        val vm = vm(AlbumSource.QOBUZ, notSoldHereCache(), prefetcher = prefetcher, yt = yt, title = "Loveless", artist = "MBV")
        val statuses = mutableListOf<AlbumDiscoveryStatus>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.uiState.collect { statuses += it.status }
        }
        advanceUntilIdle()

        verify(yt).searchAlbums("Loveless", "MBV")
        val state = vm.uiState.value
        assertEquals(AlbumDiscoveryStatus.Fresh, state.status)
        assertEquals(listOf("yv1", "yv2"), state.tracks.map { it.videoId })
        // The 404 never reaches the screen: Loading straight to Fresh, no error flash.
        assertEquals(listOf(AlbumDiscoveryStatus.Loading, AlbumDiscoveryStatus.Fresh), statuses.distinct())
        // Everything keyed on the album's catalog follows the switch to YouTube.
        assertTrue(vm.downloadSupported)
        assertFalse(vm.isNativeAlbum)
        verify(prefetcher).prefetch(eq(listOf("yv1", "yv2")))
    }

    @Test fun `the YouTube copy plays as YouTube tracks`() = runTest {
        val player = mock<PlayerRepository>()
        val musicRepo = mock<MusicRepository>()
        val vm = vm(
            AlbumSource.QOBUZ, notSoldHereCache(), player = player, musicRepo = musicRepo,
            yt = ytAnswering(ytCopy()), title = "Loveless", artist = "MBV",
        )
        advanceUntilIdle()

        vm.playAlbum(0); advanceUntilIdle()

        val cap = argumentCaptor<List<Track>>()
        verify(player).setQueue(cap.capture(), eq(0), any())
        assertEquals(listOf("yv1", "yv2"), cap.firstValue.map { it.youtubeId })
        // Not the Qobuz path, which persists rows to give videoId-less tracks real ids.
        verify(musicRepo, never()).ensureTrackPersisted(any())
    }

    @Test fun `saving the YouTube copy files it under the YouTube album`() = runTest {
        val musicRepo = mock<MusicRepository>()
        whenever(musicRepo.ensureTrackPersisted(any())).thenReturn(11L, 12L)
        whenever(musicRepo.ensureCustomPlaylist(any(), any(), anyOrNull())).thenReturn(77L)
        val vm = vm(
            AlbumSource.QOBUZ, notSoldHereCache(), musicRepo = musicRepo,
            yt = ytAnswering(ytCopy()), title = "Loveless", artist = "MBV",
        )
        advanceUntilIdle()

        vm.saveAlbum(); advanceUntilIdle()

        verify(musicRepo).ensureCustomPlaylist(eq("Loveless"), eq("album:youtube:MPREb_yt"), anyOrNull())
        verify(musicRepo).addTracksToPlaylist(listOf(11L, 12L), 77L)
    }

    @Test fun `a YouTube copy already in the library shows as saved after the switch`() = runTest {
        val delegate = stubDelegate(
            userPlaylists = listOf(
                Playlist(id = 77L, name = "Loveless", source = MusicSource.BOTH, sourceId = "album:youtube:MPREb_yt"),
            ),
        )
        val vm = vm(
            AlbumSource.QOBUZ, notSoldHereCache(), delegate = delegate,
            yt = ytAnswering(ytCopy()), title = "Loveless", artist = "MBV",
        )
        advanceUntilIdle()

        assertTrue(vm.isSaved.value)
    }

    /** Saved from Qobuz earlier (another country, a VPN): still "Saved", and Save doesn't file it twice. */
    @Test fun `an album saved from Qobuz still shows as saved after the switch, and isn't saved twice`() = runTest {
        val musicRepo = mock<MusicRepository>()
        val delegate = stubDelegate(
            userPlaylists = listOf(
                Playlist(id = 77L, name = "Loveless", source = MusicSource.BOTH, sourceId = "album:qobuz:123"),
            ),
        )
        val vm = vm(
            AlbumSource.QOBUZ, notSoldHereCache(), musicRepo = musicRepo, delegate = delegate,
            yt = ytAnswering(ytCopy()), title = "Loveless", artist = "MBV",
        )
        advanceUntilIdle()

        // The screen did switch: the YouTube copy is what's showing.
        assertEquals(listOf("yv1", "yv2"), vm.uiState.value.tracks.map { it.videoId })
        assertTrue(vm.isSaved.value)
        vm.userMessages.test {
            vm.saveAlbum()
            advanceUntilIdle()
            assertEquals("Already in your library", awaitItem())
        }
        verify(musicRepo, never()).ensureCustomPlaylist(any(), any(), anyOrNull())
    }

    @Test fun `with no YouTube copy it says the album isn't available here, without Retry`() = runTest {
        val vm = vm(AlbumSource.QOBUZ, notSoldHereCache(), yt = ytAnswering(), title = "Loveless", artist = "MBV")

        vm.userMessages.test {
            advanceUntilIdle()
            expectNoEvents() // no "tap Retry" snackbar for a failure Retry can't fix
        }
        assertEquals(notAvailable, vm.uiState.value.status)
    }

    /** "Didn't answer" is not "not available": a lookup that dropped can be retried. */
    @Test fun `when YouTube doesn't answer the search it offers Retry, and Retry asks again`() = runTest {
        val yt = mock<YTMusicApiClient>()
        whenever(yt.searchAlbums(any(), any())).thenReturn(
            AlbumSearch.Failed,
            AlbumSearch.Answered(topAlbum = ytCopy(), shelf = emptyList()),
        )
        val vm = vm(AlbumSource.QOBUZ, notSoldHereCache(), yt = yt, title = "Loveless", artist = "MBV")

        vm.userMessages.test {
            advanceUntilIdle()
            assertEquals("Couldn't load album — tap Retry.", awaitItem())
        }
        assertEquals(couldNotLoad, vm.uiState.value.status)

        vm.retry(); advanceUntilIdle()

        assertEquals(AlbumDiscoveryStatus.Fresh, vm.uiState.value.status)
        verify(yt, times(2)).searchAlbums("Loveless", "MBV")
    }

    /**
     * The search lists the artist's other albums, singles, live albums and albums whose
     * artist merely contains the name, so only a strict match is taken: opening the
     * wrong album is worse than saying this one isn't available.
     */
    @Test fun `a YouTube album that isn't this one is not taken`() = runTest {
        val nearMisses = listOf(
            ytCopy(id = "MPREb_other_album", title = "Isn't Anything"), // same artist, another album
            ytCopy(id = "MPREb_tribute", artist = "MBV Tribute Band"), // same title, another artist
            ytCopy(id = "MPREb_live", title = "Loveless (Live)"), // another recording
            ytCopy(id = "MPREb_single", releaseType = "Single"), // the title track's single
            ytCopy(id = "MPREb_2021", year = "2021"), // same name, another year
        )
        for (nearMiss in nearMisses) {
            val cache = notSoldHereCache()
            val yt = ytAnswering(nearMiss)
            val vm = vm(
                AlbumSource.QOBUZ, cache, yt = yt,
                title = "Loveless", artist = "MBV", year = "1991",
            )
            advanceUntilIdle()

            verify(yt).searchAlbums("Loveless", "MBV") // it was offered, and turned down
            assertEquals("for $nearMiss", notAvailable, vm.uiState.value.status)
            verify(cache, never()).get(eq(nearMiss.id), any())
        }
    }

    @Test fun `it takes the first candidate that is this album, not the first by the artist`() = runTest {
        val cache = notSoldHereCache()
        val yt = ytAnswering(ytCopy(id = "MPREb_other_album", title = "Isn't Anything"), ytCopy())
        val vm = vm(AlbumSource.QOBUZ, cache, yt = yt, title = "Loveless", artist = "MBV")
        advanceUntilIdle()

        assertEquals(AlbumDiscoveryStatus.Fresh, vm.uiState.value.status)
        verify(cache).get(eq("MPREb_yt"), eq(AlbumSource.YOUTUBE))
        verify(cache, never()).get(eq("MPREb_other_album"), any())
    }

    // ── A Qobuz single (Qobuz sends no release type: 1 to 3 tracks is a single) ──

    @Test fun `a Qobuz single opens YouTube's single`() = runTest {
        val cache = notSoldHereCache()
        whenever(cache.get(eq("MPREb_single"), eq(AlbumSource.YOUTUBE))).thenReturn(ytLoveless())
        val yt = ytAnswering(ytCopy(id = "MPREb_single", releaseType = "Single"))
        val vm = vm(AlbumSource.QOBUZ, cache, yt = yt, title = "Loveless", artist = "MBV", qobuzTrackCount = 1)
        advanceUntilIdle()

        assertEquals(AlbumDiscoveryStatus.Fresh, vm.uiState.value.status)
        verify(cache).get(eq("MPREb_single"), eq(AlbumSource.YOUTUBE))
    }

    @Test fun `a Qobuz album with only a same-titled YouTube single is still not available`() = runTest {
        val yt = ytAnswering(ytCopy(id = "MPREb_single", releaseType = "Single"))
        val vm = vm(AlbumSource.QOBUZ, notSoldHereCache(), yt = yt, title = "Loveless", artist = "MBV", qobuzTrackCount = 11)
        advanceUntilIdle()

        assertEquals(notAvailable, vm.uiState.value.status)
    }

    /** Unknown length: the title track's single is the likelier match, so nothing changes. */
    @Test fun `without a track count a YouTube single is still not taken`() = runTest {
        val yt = ytAnswering(ytCopy(id = "MPREb_single", releaseType = "Single"))
        val vm = vm(AlbumSource.QOBUZ, notSoldHereCache(), yt = yt, title = "Loveless", artist = "MBV", qobuzTrackCount = null)
        advanceUntilIdle()

        assertEquals(notAvailable, vm.uiState.value.status)
    }

    /** YouTube lists the album the single comes from first; the single itself is the copy. */
    @Test fun `a Qobuz single takes YouTube's single over the album it comes from`() = runTest {
        val cache = notSoldHereCache()
        whenever(cache.get(eq("MPREb_single"), eq(AlbumSource.YOUTUBE))).thenReturn(ytLoveless())
        val yt = ytAnswering(
            ytCopy(id = "MPREb_album", releaseType = "Album"),
            ytCopy(id = "MPREb_single", releaseType = "Single"),
        )
        val vm = vm(AlbumSource.QOBUZ, cache, yt = yt, title = "Loveless", artist = "MBV", qobuzTrackCount = 2)
        advanceUntilIdle()

        assertEquals(AlbumDiscoveryStatus.Fresh, vm.uiState.value.status)
        verify(cache).get(eq("MPREb_single"), eq(AlbumSource.YOUTUBE))
        verify(cache, never()).get(eq("MPREb_album"), any())
    }

    /** A single's song is on its album too, so with no single on YouTube the album still opens (as before). */
    @Test fun `a Qobuz single opens the album it is on when YouTube has no single`() = runTest {
        val cache = notSoldHereCache()
        val vm = vm(
            AlbumSource.QOBUZ, cache, yt = ytAnswering(ytCopy(releaseType = "Album")),
            title = "Loveless", artist = "MBV", qobuzTrackCount = 1,
        )
        advanceUntilIdle()

        assertEquals(AlbumDiscoveryStatus.Fresh, vm.uiState.value.status)
        verify(cache).get(eq("MPREb_yt"), eq(AlbumSource.YOUTUBE))
    }

    /** Only "not sold here" looks elsewhere; a dropped connection or a Qobuz hiccup can be retried. */
    @Test fun `a network or server failure still offers Retry, in plain words`() = runTest {
        val failures = listOf(
            IOException("Unable to resolve host \"www.qobuz.com\""),
            RuntimeException("""{"status":"error","code":500,"message":"Internal error"}"""),
        )
        for (failure in failures) {
            val cache = mock<AlbumCache>()
            whenever(cache.get(eq("123"), eq(AlbumSource.QOBUZ))).doSuspendableAnswer { throw failure }
            val yt = mock<YTMusicApiClient>()
            val vm = vm(AlbumSource.QOBUZ, cache, yt = yt, title = "Loveless", artist = "MBV")

            vm.userMessages.test {
                advanceUntilIdle()
                assertEquals("Couldn't load album — tap Retry.", awaitItem())
            }
            assertEquals("for $failure", couldNotLoad, vm.uiState.value.status)
            verify(yt, never()).searchAlbums(any(), any())
        }
    }

    /**
     * Once switched, the screen IS the YouTube album: Retry reloads that, not the doomed
     * Qobuz call. The first load fails the way AlbumCache reports a YouTube page that
     * didn't come back (an IOException).
     */
    @Test fun `Retry after the switch reloads the YouTube copy without asking Qobuz again`() = runTest {
        val cache = mock<AlbumCache>()
        whenever(cache.get(eq("123"), eq(AlbumSource.QOBUZ)))
            .doSuspendableAnswer { throw QobuzAlbumUnavailableException("123") }
        var ytLoads = 0
        whenever(cache.get(eq("MPREb_yt"), eq(AlbumSource.YOUTUBE))).doSuspendableAnswer {
            if (ytLoads++ == 0) throw IOException("YouTube Music didn't return album MPREb_yt") else ytLoveless()
        }
        val yt = ytAnswering(ytCopy())
        val vm = vm(AlbumSource.QOBUZ, cache, yt = yt, title = "Loveless", artist = "MBV")
        advanceUntilIdle()
        assertEquals(couldNotLoad, vm.uiState.value.status)

        vm.retry(); advanceUntilIdle()

        assertEquals(AlbumDiscoveryStatus.Fresh, vm.uiState.value.status)
        verify(cache, times(1)).get(eq("123"), eq(AlbumSource.QOBUZ))
        verify(yt, times(1)).searchAlbums(any(), any())
    }

    // ── isSameAlbum: what counts as "the same album" ────────────────────────

    @Test fun `the match ignores case, punctuation, accents and curly quotes`() {
        val apostrophe = AlbumSummary("MPREb_y", "Don't Stop", "JAY-Z", null, null)
        assertTrue(isSameAlbum(apostrophe, title = "Dont Stop", artist = "Jay Z"))
        // Qobuz and YouTube don't agree on apostrophes or accents.
        val curly = AlbumSummary("MPREb_q", "Don\u2019t Stop", "Sade", null, null)
        assertTrue(isSameAlbum(curly, title = "Don't Stop", artist = "Sade"))
        val accent = AlbumSummary("MPREb_z", "Frequency Of Love", "Victoria Monét", null, null)
        assertTrue(isSameAlbum(accent, title = "Frequency of Love", artist = "Victoria Monet"))
    }

    @Test fun `an edition or a featured credit is still the same album`() {
        val same = listOf(
            "Rubber Soul (Super Deluxe)" to "Rubber Soul",
            "Rubber Soul (Remastered 2009)" to "Rubber Soul",
            "Taking The Long Way (20th Anniversary Edition)" to "Taking The Long Way",
            "Lithic [Deluxe Edition]" to "Lithic",
            "My Aim Is True - 49th Anniversary Edition (2026 Remaster)" to "My Aim Is True - 49th Anniversary Edition",
            "Utility Modern (feat. Bill Frisell, Rashaan Carter & Marcus Gilmore)" to "Utility Modern",
            "Cancel Me (I'm Tired)" to "Cancel Me (I'm Tired)",
        )
        for ((youTube, qobuz) in same) {
            assertTrue("'$youTube' should be '$qobuz'", isSameAlbum(ytCopy(title = youTube, year = null), title = qobuz, artist = "MBV"))
        }
    }

    /** These change what's on the record, so they are other albums ("X (Live)" never opens studio "X"). */
    @Test fun `a live, remix, instrumental, acoustic, sped-up or demo release is a different album`() {
        val different = listOf(
            "Loveless (Live)", "Loveless (Remixes)", "Loveless (Instrumental)", "Loveless (Instrumentals)",
            "Loveless (Acoustic)", "Loveless (Sped Up)", "Loveless (Demos)", "Loveless - Live at the Barbican",
            "Loveless (Vol. 2)",
        )
        for (youTube in different) {
            assertFalse("'$youTube' is not 'Loveless'", isSameAlbum(ytCopy(title = youTube), title = "Loveless", artist = "MBV"))
        }
        // ...and the other way round: a live Qobuz album doesn't open the studio one.
        assertFalse(isSameAlbum(ytCopy(title = "Loveless"), title = "Loveless (Live)", artist = "MBV"))
    }

    /** A featured credit is set aside up to the next bracket or " - ", never what follows it. */
    @Test fun `a featured credit is set aside, but not what follows it`() {
        assertTrue(isSameAlbum(ytCopy(title = "Loveless feat. Kevin Shields"), title = "Loveless", artist = "MBV"))
        val live = listOf(
            "Loveless (feat. Kevin Shields) [Live]", "Loveless feat. Kevin Shields (Live)", "Loveless feat. Kevin Shields - Live",
        )
        for (youTube in live) {
            assertFalse("'$youTube' is not 'Loveless'", isSameAlbum(ytCopy(title = youTube), title = "Loveless", artist = "MBV"))
        }
    }

    /** A subtitle in brackets is part of the title: "Cancel Me" isn't "Cancel Me (I'm Tired)". */
    @Test fun `a subtitle in brackets is part of the title`() {
        assertFalse(isSameAlbum(ytCopy(title = "Cancel Me"), title = "Cancel Me (I'm Tired)", artist = "MBV"))
        assertFalse(isSameAlbum(ytCopy(title = "Cancel Me (I'm Tired)"), title = "Cancel Me", artist = "MBV"))
    }

    @Test fun `years more than one apart are different albums`() {
        assertTrue(isSameAlbum(ytCopy(year = "1991"), title = "Loveless", artist = "MBV", year = "1991"))
        assertTrue(isSameAlbum(ytCopy(year = "1992"), title = "Loveless", artist = "MBV", year = "1991"))
        assertTrue(isSameAlbum(ytCopy(year = "1991"), title = "Loveless", artist = "MBV", year = "1991-11-04"))
        assertFalse(isSameAlbum(ytCopy(year = "1993"), title = "Loveless", artist = "MBV", year = "1991"))
        // Unknown on either side: nothing to compare.
        assertTrue(isSameAlbum(ytCopy(year = null), title = "Loveless", artist = "MBV", year = "1991"))
        assertTrue(isSameAlbum(ytCopy(year = "2021"), title = "Loveless", artist = "MBV", year = null))
    }

    /**
     * The two catalogs date a remaster differently: YouTube's "Abbey Road (Remastered 2009)"
     * says 1969 where Qobuz says 2009, and Qobuz dates "Rumours (2004 Remaster)" 1977 where
     * YouTube says 2004. Once an edition was set aside on either side the years aren't
     * compared; a plain same-title album still has to agree.
     */
    @Test fun `an edition the two catalogs date differently is still the same album`() {
        val abbeyRoad = ytCopy(title = "Abbey Road (Remastered 2009)", year = "1969")
        assertTrue(isSameAlbum(abbeyRoad, title = "Abbey Road (Remastered 2009)", artist = "MBV", year = "2009-09-09"))
        assertTrue(isSameAlbum(ytCopy(title = "Rumours", year = "2004"), title = "Rumours (2004 Remaster)", artist = "MBV", year = "1977"))
        assertTrue(isSameAlbum(ytCopy(title = "Rubber Soul (Super Deluxe)", year = "2026"), title = "Rubber Soul", artist = "MBV", year = "1965"))
        // A featured credit isn't an edition: the years still count.
        assertFalse(isSameAlbum(ytCopy(title = "Loveless (feat. Kevin Shields)", year = "2021"), title = "Loveless", artist = "MBV", year = "1991"))
    }

    /**
     * Qobuz names a collaboration "Jay Z and Kanye West" (live 2026-10-03: "Watch The
     * Throne"), where YouTube's search results name only the first artist. And one writes
     * "Echo And The Bunnymen" where the other writes "Echo & the Bunnymen".
     */
    @Test fun `a collaboration matches on its first credited artist, and "and" counts as "&"`() {
        val throne = AlbumSummary("MPREb_t", "Watch The Throne", "JAY-Z", null, "2011")
        assertTrue(isSameAlbum(throne, title = "Watch The Throne", artist = "Jay Z and Kanye West", year = "2011"))
        assertTrue(isSameAlbum(throne, title = "Watch The Throne", artist = "Jay Z & Kanye West", year = "2011"))
        assertTrue(isSameAlbum(throne, title = "Watch The Throne", artist = "Jay Z, Kanye West", year = "2011"))
        val oceanRain = AlbumSummary("MPREb_e", "Ocean Rain", "Echo & the Bunnymen", null, "1984")
        assertTrue(isSameAlbum(oceanRain, title = "Ocean Rain", artist = "Echo And The Bunnymen", year = "1984"))
        // Someone else is still someone else.
        assertFalse(isSameAlbum(throne.copy(artist = "Jay Electronica"), title = "Watch The Throne", artist = "Jay Z and Kanye West"))
    }

    @Test fun `a single is not the album, an EP or an unlabelled release can be`() {
        assertFalse(isSameAlbum(ytCopy(releaseType = "Single"), title = "Loveless", artist = "MBV"))
        assertTrue(isSameAlbum(ytCopy(releaseType = "EP"), title = "Loveless", artist = "MBV"))
        assertTrue(isSameAlbum(ytCopy(releaseType = null), title = "Loveless", artist = "MBV"))
    }

    @Test fun `a single matches only a Qobuz release known to be a single`() {
        val single = ytCopy(releaseType = "Single")
        assertTrue(isSameAlbum(single, title = "Loveless", artist = "MBV", qobuzTrackCount = 1))
        assertTrue(isSameAlbum(single, title = "Loveless", artist = "MBV", qobuzTrackCount = 3))
        assertFalse(isSameAlbum(single, title = "Loveless", artist = "MBV", qobuzTrackCount = 4))
        assertFalse(isSameAlbum(single, title = "Loveless", artist = "MBV", qobuzTrackCount = 0))
        assertFalse(isSameAlbum(single, title = "Loveless", artist = "MBV", qobuzTrackCount = null))
    }

    // ── pickYouTubeCopy: which match comes first ────────────────────────────

    @Test fun `a Qobuz album takes the Album before a same-titled EP listed first`() {
        val ep = ytCopy(id = "MPREb_ep", releaseType = "EP")
        val album = ytCopy(id = "MPREb_album", releaseType = "Album")
        assertEquals("MPREb_album", pickYouTubeCopy(listOf(ep, album), "Loveless", "MBV", "1991", qobuzTrackCount = 11)?.id)
        // Length unknown: YouTube's order stands.
        assertEquals("MPREb_ep", pickYouTubeCopy(listOf(ep, album), "Loveless", "MBV", "1991", qobuzTrackCount = null)?.id)
    }

    @Test fun `a Qobuz single takes an explicit Single before an unlabelled release`() {
        val unlabelled = ytCopy(id = "MPREb_unlabelled", releaseType = null)
        val single = ytCopy(id = "MPREb_single", releaseType = "Single")
        assertEquals("MPREb_single", pickYouTubeCopy(listOf(unlabelled, single), "Loveless", "MBV", "1991", qobuzTrackCount = 1)?.id)
    }

    @Test fun `the match needs a title and an artist on both sides`() {
        val copy = AlbumSummary("MPREb_x", "Loveless", "MBV", null, null)
        assertFalse(isSameAlbum(copy, title = "Loveless", artist = ""))
        assertFalse(isSameAlbum(copy, title = "", artist = "MBV"))
        assertFalse(isSameAlbum(copy.copy(artist = ""), title = "Loveless", artist = ""))
    }
}

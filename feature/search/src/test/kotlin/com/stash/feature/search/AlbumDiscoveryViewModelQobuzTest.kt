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
    ) = AlbumDiscoveryViewModel(
        savedStateHandle = SavedStateHandle(
            mapOf(
                "browseId" to (if (source == AlbumSource.QOBUZ) "123" else "MPRE1"),
                "title" to title,
                "artist" to artist,
                "thumbnailUrl" to null,
                "year" to null,
                "source" to source,
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

    /** The YouTube Music copy of [qobuzDetail]'s "Loveless". */
    private fun ytCopy(
        id: String = "MPREb_yt",
        title: String = "Loveless",
        artist: String = "MBV",
    ) = AlbumSummary(id = id, title = title, artist = artist, thumbnailUrl = "yt-art", year = "1991")

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

    private suspend fun ytFinding(copy: AlbumSummary?): YTMusicApiClient = mock<YTMusicApiClient>().also {
        whenever(it.resolveAlbum(any(), any())).thenReturn(copy)
    }

    private val notAvailable =
        AlbumDiscoveryStatus.Error("This album isn't available in your country.", canRetry = false)
    private val couldNotLoad =
        AlbumDiscoveryStatus.Error("Check your connection and try again.", canRetry = true)

    @Test fun `an album Qobuz doesn't sell here opens its YouTube Music copy`() = runTest {
        val yt = ytFinding(ytCopy())
        val prefetcher = mock<PreviewPrefetcher>()
        val vm = vm(AlbumSource.QOBUZ, notSoldHereCache(), prefetcher = prefetcher, yt = yt, title = "Loveless", artist = "MBV")
        val statuses = mutableListOf<AlbumDiscoveryStatus>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.uiState.collect { statuses += it.status }
        }
        advanceUntilIdle()

        verify(yt).resolveAlbum("Loveless", "MBV")
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
            yt = ytFinding(ytCopy()), title = "Loveless", artist = "MBV",
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
            yt = ytFinding(ytCopy()), title = "Loveless", artist = "MBV",
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
            yt = ytFinding(ytCopy()), title = "Loveless", artist = "MBV",
        )
        advanceUntilIdle()

        assertTrue(vm.isSaved.value)
    }

    @Test fun `with no YouTube copy it says the album isn't available here, without Retry`() = runTest {
        val vm = vm(AlbumSource.QOBUZ, notSoldHereCache(), yt = ytFinding(null), title = "Loveless", artist = "MBV")

        vm.userMessages.test {
            advanceUntilIdle()
            expectNoEvents() // no "tap Retry" snackbar for a failure Retry can't fix
        }
        assertEquals(notAvailable, vm.uiState.value.status)
    }

    /**
     * resolveAlbum falls back to its first hit, and prefers any album whose artist
     * merely CONTAINS the name, so its answer is only taken on a strict match:
     * opening the wrong album is worse than saying this one isn't available.
     */
    @Test fun `a YouTube album by another artist or under another title is not taken`() = runTest {
        val nearMisses = listOf(
            ytCopy(id = "MPREb_other_album", title = "Isn't Anything"), // same artist, another album
            ytCopy(id = "MPREb_tribute", artist = "MBV Tribute Band"), // same title, another artist
        )
        for (nearMiss in nearMisses) {
            val cache = notSoldHereCache()
            val vm = vm(AlbumSource.QOBUZ, cache, yt = ytFinding(nearMiss), title = "Loveless", artist = "MBV")
            advanceUntilIdle()

            assertEquals("for $nearMiss", notAvailable, vm.uiState.value.status)
            verify(cache, never()).get(eq(nearMiss.id), any())
        }
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
            verify(yt, never()).resolveAlbum(any(), any())
        }
    }

    /** Once switched, the screen IS the YouTube album: Retry reloads that, not the doomed Qobuz call. */
    @Test fun `Retry after the switch reloads the YouTube copy without asking Qobuz again`() = runTest {
        val cache = mock<AlbumCache>()
        whenever(cache.get(eq("123"), eq(AlbumSource.QOBUZ)))
            .doSuspendableAnswer { throw QobuzAlbumUnavailableException("123") }
        var ytLoads = 0
        whenever(cache.get(eq("MPREb_yt"), eq(AlbumSource.YOUTUBE))).doSuspendableAnswer {
            if (ytLoads++ == 0) throw IOException("connection reset") else ytLoveless()
        }
        val yt = ytFinding(ytCopy())
        val vm = vm(AlbumSource.QOBUZ, cache, yt = yt, title = "Loveless", artist = "MBV")
        advanceUntilIdle()
        assertEquals(couldNotLoad, vm.uiState.value.status)

        vm.retry(); advanceUntilIdle()

        assertEquals(AlbumDiscoveryStatus.Fresh, vm.uiState.value.status)
        verify(cache, times(1)).get(eq("123"), eq(AlbumSource.QOBUZ))
        verify(yt, times(1)).resolveAlbum(any(), any())
    }

    @Test fun `the match ignores case, punctuation, accents and a bracketed edition`() {
        val superDeluxe = AlbumSummary("MPREb_x", "Rubber Soul (Super Deluxe)", "The Beatles", null, "2026")
        assertTrue(isSameAlbum(superDeluxe, title = "Rubber Soul", artist = "the beatles"))
        val apostrophe = AlbumSummary("MPREb_y", "Don't Stop", "JAY-Z", null, null)
        assertTrue(isSameAlbum(apostrophe, title = "Dont Stop", artist = "Jay Z"))
        // The two catalogs disagree on accents: Qobuz lists "Victoria Monet", YouTube "Victoria Monét".
        val accent = AlbumSummary("MPREb_z", "Frequency Of Love", "Victoria Monét", null, null)
        assertTrue(isSameAlbum(accent, title = "Frequency of Love", artist = "Victoria Monet"))
    }

    @Test fun `the match needs a title and an artist on both sides`() {
        val copy = AlbumSummary("MPREb_x", "Loveless", "MBV", null, null)
        assertFalse(isSameAlbum(copy, title = "Loveless", artist = ""))
        assertFalse(isSameAlbum(copy, title = "", artist = "MBV"))
        assertFalse(isSameAlbum(copy.copy(artist = ""), title = "Loveless", artist = ""))
    }
}

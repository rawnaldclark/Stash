package com.stash.core.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthService
import com.stash.core.data.db.dao.OrphanSweep
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.sync.DownloadJobRegistry
import com.stash.core.data.sync.SyncPreferencesManager
import com.stash.core.data.sync.syncPrefsDataStore
import com.stash.core.model.MusicSource
import com.stash.core.model.SyncMode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #273 end to end through the cleanup's own gate: the real [SyncPreferencesManager]
 * decides, from the sign-ins and the playlists still here, whether
 * [MusicRepositoryImpl.cleanOrphanedMixTracks] reaches the sweep. Before the fix, an
 * account never connected kept its default Accumulate (with no switch to change it),
 * so a one-service library in Refresh never deleted a song that left its playlists.
 */
@RunWith(RobolectricTestRunner::class)
class MusicRepositoryRefreshCleanupTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val tokenManager = mockk<TokenManager>()
    private val playlistDao = mockk<PlaylistDao>(relaxed = true)
    private val trackDao = mockk<TrackDao>(relaxed = true)
    private lateinit var prefs: SyncPreferencesManager
    private lateinit var repo: MusicRepositoryImpl

    @Before fun setUp() {
        // The DataStore outlives each test (see SyncPreferencesManagerTest): start empty.
        runBlocking { context.syncPrefsDataStore.edit { it.clear() } }
        coEvery { trackDao.deleteOrphanedDownloadedTracks() } returns
            OrphanSweep(deleted = listOf(rotatedOut(1L)), undownloaded = emptyList())
        prefs = SyncPreferencesManager(context, tokenManager, playlistDao)
        repo = MusicRepositoryImpl(
            context = context,
            trackDao = trackDao,
            playlistDao = playlistDao,
            syncHistoryDao = mockk(relaxed = true),
            downloadQueueDao = mockk(relaxed = true),
            discoveryQueueDao = mockk(relaxed = true),
            blocklistGuard = mockk(relaxed = true),
            trackMatcher = mockk(relaxed = true),
            stashMixRecipeDao = mockk(relaxed = true),
            downloadNetworkPreference = mockk(relaxed = true),
            streamingPreference = mockk(relaxed = true),
            libraryScopePreference = mockk(relaxed = true),
            localFileOps = mockk(relaxed = true),
            syncPreferencesManager = prefs,
            singleTrackDownloadEnqueuer = mockk(relaxed = true),
            lastFmRecommendationSource = mockk(relaxed = true),
            sharedMixDao = mockk(relaxed = true),
            downloadJobs = DownloadJobRegistry(),
        )
    }

    /** YouTube Music is the only account: signed in, with its playlists here. Spotify was never connected. */
    private fun youTubeOnly(spotifyPlaylistsStillHere: Boolean = false) {
        coEvery { tokenManager.isAuthenticated(AuthService.YOUTUBE_MUSIC) } returns true
        coEvery { tokenManager.isAuthenticated(AuthService.SPOTIFY) } returns false
        coEvery { playlistDao.hasLibraryPlaylists(MusicSource.YOUTUBE) } returns true
        coEvery { playlistDao.hasLibraryPlaylists(MusicSource.SPOTIFY) } returns spotifyPlaylistsStillHere
    }

    private fun rotatedOut(id: Long) = TrackEntity(
        id = id, title = "T$id", artist = "A$id", durationMs = 1000L,
        source = MusicSource.YOUTUBE, isDownloaded = true, filePath = "/music/$id.opus",
        canonicalTitle = "t$id", canonicalArtist = "a$id",
    )

    @Test fun `a one-service library in Refresh deletes the songs that left its playlists`() = runTest {
        youTubeOnly()
        prefs.setYoutubeSyncMode(SyncMode.REFRESH)

        assertEquals(1, repo.cleanOrphanedMixTracks())
        coVerify(exactly = 1) { trackDao.deleteOrphanedDownloadedTracks() }
    }

    @Test fun `a one-service library left on Accumulate deletes nothing`() = runTest {
        youTubeOnly()

        assertEquals(0, repo.cleanOrphanedMixTracks())
        coVerify(exactly = 0) { trackDao.deleteOrphanedDownloadedTracks() }
    }

    @Test fun `a disconnected account whose playlists remain still blocks while it accumulates`() = runTest {
        // Spotify was signed out, but its playlists (and songs) are still in the library on Accumulate.
        youTubeOnly(spotifyPlaylistsStillHere = true)
        prefs.setYoutubeSyncMode(SyncMode.REFRESH)

        assertEquals(0, repo.cleanOrphanedMixTracks())
        coVerify(exactly = 0) { trackDao.deleteOrphanedDownloadedTracks() }
    }
}

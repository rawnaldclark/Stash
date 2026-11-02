package com.stash.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.mapper.toDomain
import com.stash.core.data.sync.DownloadJobRegistry
import com.stash.core.data.sync.SyncPreferencesManager
import com.stash.core.model.DownloadStatus
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * With Refresh's cleanup open (every account that counts is on Refresh, #273), what the user
 * kept must survive it: a song they downloaded by hand that is in no playlist (Now Playing,
 * library, radio: these never join Your Downloads), and the downloaded songs of a playlist they
 * removed from the library ("its songs stay"). A song a mix rotated out still goes.
 * Real Room, real repository; only the Refresh/Accumulate gate is stubbed open.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MusicRepositoryRefreshKeepsUserSongsTest {

    private lateinit var db: StashDatabase
    private lateinit var repo: MusicRepositoryImpl

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StashDatabase::class.java,
        ).allowMainThreadQueries().build()
        val gateOpen = mockk<SyncPreferencesManager> { coEvery { anyAccumulate() } returns false }
        repo = MusicRepositoryImpl(
            context = ApplicationProvider.getApplicationContext(),
            trackDao = db.trackDao(),
            playlistDao = db.playlistDao(),
            syncHistoryDao = mockk(relaxed = true),
            downloadQueueDao = db.downloadQueueDao(),
            discoveryQueueDao = db.discoveryQueueDao(),
            blocklistGuard = mockk(relaxed = true),
            trackMatcher = mockk(relaxed = true),
            stashMixRecipeDao = mockk(relaxed = true),
            downloadNetworkPreference = mockk(relaxed = true),
            streamingPreference = mockk(relaxed = true),
            libraryScopePreference = mockk(relaxed = true),
            localFileOps = mockk(relaxed = true),
            syncPreferencesManager = gateOpen,
            singleTrackDownloadEnqueuer = mockk(relaxed = true),
            lastFmRecommendationSource = mockk(relaxed = true),
            sharedMixDao = mockk(relaxed = true),
            downloadJobs = DownloadJobRegistry(),
        )
    }

    @After fun tearDown() { db.close() }

    private fun downloaded(id: Long, source: MusicSource) = TrackEntity(
        id = id, title = "T$id", artist = "a",
        canonicalTitle = "t$id", canonicalArtist = "a",
        isDownloaded = true, filePath = "/nonexistent/$id.flac",
        source = source,
    )

    @Test fun `hand downloads and a removed playlist's songs survive Refresh's cleanup, a rotated-out song doesn't`() = runTest {
        val trackDao = db.trackDao()
        val playlistDao = db.playlistDao()
        // 1: a radio song downloaded from Now Playing: saved as YouTube, in no playlist.
        trackDao.insert(downloaded(1, MusicSource.YOUTUBE))
        db.downloadQueueDao().insert(
            DownloadQueueEntity(trackId = 1, status = DownloadStatus.COMPLETED, userRequested = true),
        )
        // 2: a downloaded song whose only playlist the user removes from the library.
        trackDao.insert(downloaded(2, MusicSource.SPOTIFY))
        val removed = playlistDao.insert(
            PlaylistEntity(name = "Road trip", source = MusicSource.SPOTIFY, sourceId = "sp-road", type = PlaylistType.CUSTOM),
        )
        playlistDao.insertCrossRef(PlaylistTrackCrossRef(playlistId = removed, trackId = 2, position = 0))
        // 3: a mix song that rotated out, downloaded by the sync (not asked for): Refresh deletes it.
        trackDao.insert(downloaded(3, MusicSource.SPOTIFY))
        db.downloadQueueDao().insert(
            DownloadQueueEntity(trackId = 3, status = DownloadStatus.COMPLETED, userRequested = false),
        )

        repo.removePlaylist(playlistDao.getById(removed)!!.toDomain())
        val cleaned = repo.cleanOrphanedMixTracks()

        assertEquals(1, cleaned)
        assertNull(trackDao.getById(3))
        assertTrue(trackDao.getById(1)!!.isDownloaded)
        assertTrue(trackDao.getById(2)!!.isDownloaded)
        // The removed playlist's song is kept where Search keeps its downloads: Your Downloads.
        val yourDownloads = playlistDao.findBySourceId("stash_downloads_mix")!!
        assertNotNull(playlistDao.getCrossRef(yourDownloads.id, 2))
        assertEquals(1, playlistDao.getById(yourDownloads.id)!!.trackCount)
    }

    @Test fun `removing a playlist leaves its songs that another playlist keeps out of Your Downloads`() = runTest {
        val trackDao = db.trackDao()
        val playlistDao = db.playlistDao()
        trackDao.insert(downloaded(4, MusicSource.SPOTIFY))
        val removed = playlistDao.insert(
            PlaylistEntity(name = "A", source = MusicSource.SPOTIFY, sourceId = "sp-a", type = PlaylistType.CUSTOM),
        )
        val other = playlistDao.insert(
            PlaylistEntity(name = "B", source = MusicSource.SPOTIFY, sourceId = "sp-b", type = PlaylistType.CUSTOM),
        )
        playlistDao.insertCrossRef(PlaylistTrackCrossRef(playlistId = removed, trackId = 4, position = 0))
        playlistDao.insertCrossRef(PlaylistTrackCrossRef(playlistId = other, trackId = 4, position = 0))

        repo.removePlaylist(playlistDao.getById(removed)!!.toDomain())

        assertNull(playlistDao.findBySourceId("stash_downloads_mix"))
        assertEquals(0, repo.cleanOrphanedMixTracks())
    }
}

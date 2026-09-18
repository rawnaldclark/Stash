package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies `ArtistImageDao.distinctArtistNames()` — the candidate set the
 * [com.stash.core.data.sync.workers.ArtistImageBackfillWorker] walks. It must
 * mirror what the Library Artists tab can list:
 *  - downloaded tracks (every mode);
 *  - streamable-only tracks (`is_streamable = 1`);
 *  - still-unchecked stubs (`is_streamable_checked_at IS NULL`) that belong to
 *    a live playlist — membership is the source of truth for Online-mode
 *    playlists, so their artists get photos before the availability worker
 *    has probed them;
 *  - and it must exclude checked-but-unavailable rows and stubs with no live
 *    playlist membership, so nobody pays an InnerTube search for artists the
 *    tab never shows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ArtistImageDaoTest {

    private lateinit var db: StashDatabase
    private lateinit var trackDao: TrackDao
    private lateinit var playlistDao: PlaylistDao
    private lateinit var artistImageDao: ArtistImageDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StashDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        trackDao = db.trackDao()
        playlistDao = db.playlistDao()
        artistImageDao = db.artistImageDao()
    }

    @After fun tearDown() = db.close()

    @Test fun `includes downloaded artists`() = runTest {
        insertDownloaded(id = 1L, artist = "Drake")

        assertEquals(listOf("Drake"), artistImageDao.distinctArtistNames())
    }

    @Test fun `includes streamable-only artists`() = runTest {
        insertStreamableOnly(id = 1L, artist = "Future")

        assertEquals(listOf("Future"), artistImageDao.distinctArtistNames())
    }

    @Test fun `includes unchecked stubs that belong to a live playlist`() = runTest {
        val unchecked = insertUnchecked(id = 1L, artist = "Aarne")
        val playlistId = playlistDao.insert(activePlaylist())
        playlistDao.insertCrossRef(crossRef(playlistId, unchecked, position = 0))

        assertEquals(listOf("Aarne"), artistImageDao.distinctArtistNames())
    }

    @Test fun `excludes unavailable artists`() = runTest {
        insertUnavailable(id = 1L, artist = "Travis")

        assertTrue(artistImageDao.distinctArtistNames().isEmpty())
    }

    @Test fun `excludes unchecked stubs with no live playlist membership`() = runTest {
        insertUnchecked(id = 1L, artist = "Mystery Band")

        assertTrue(artistImageDao.distinctArtistNames().isEmpty())
    }

    @Test fun `excludes membership in an inactive playlist`() = runTest {
        val unchecked = insertUnchecked(id = 1L, artist = "Aarne")
        val playlistId = playlistDao.insert(activePlaylist(isActive = false))
        playlistDao.insertCrossRef(crossRef(playlistId, unchecked, position = 0))

        assertTrue(artistImageDao.distinctArtistNames().isEmpty())
    }

    @Test fun `excludes soft-removed membership`() = runTest {
        val unchecked = insertUnchecked(id = 1L, artist = "Aarne")
        val playlistId = playlistDao.insert(activePlaylist())
        playlistDao.insertCrossRef(crossRef(playlistId, unchecked, position = 0, removed = true))

        assertTrue(artistImageDao.distinctArtistNames().isEmpty())
    }

    @Test fun `dedupes repeated credits`() = runTest {
        insertDownloaded(id = 1L, artist = "Drake")
        insertStreamableOnly(id = 2L, artist = "Drake")

        assertEquals(listOf("Drake"), artistImageDao.distinctArtistNames())
    }

    @Test fun `excludes empty artist credits`() = runTest {
        insertDownloaded(id = 1L, artist = "")

        assertFalse("blank credit must not be resolved", artistImageDao.distinctArtistNames().isNotEmpty())
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private suspend fun insertDownloaded(id: Long, artist: String): Long {
        trackDao.insert(
            TrackEntity(
                id = id,
                title = "Title $id",
                artist = artist,
                album = "Album $id",
                canonicalTitle = "title $id",
                canonicalArtist = artist.lowercase(),
                isDownloaded = true,
                isStreamable = false,
                isStreamableCheckedAt = null,
            )
        )
        return id
    }

    private suspend fun insertStreamableOnly(id: Long, artist: String): Long {
        trackDao.insert(
            TrackEntity(
                id = id,
                title = "Title $id",
                artist = artist,
                album = "Album $id",
                canonicalTitle = "title $id",
                canonicalArtist = artist.lowercase(),
                isDownloaded = false,
                isStreamable = true,
                isStreamableCheckedAt = 1_000_000L,
            )
        )
        return id
    }

    private suspend fun insertUnavailable(id: Long, artist: String): Long {
        trackDao.insert(
            TrackEntity(
                id = id,
                title = "Title $id",
                artist = artist,
                album = "Album $id",
                canonicalTitle = "title $id",
                canonicalArtist = artist.lowercase(),
                isDownloaded = false,
                isStreamable = false,
                isStreamableCheckedAt = 1_000_000L,
            )
        )
        return id
    }

    private suspend fun insertUnchecked(id: Long, artist: String): Long {
        trackDao.insert(
            TrackEntity(
                id = id,
                title = "Title $id",
                artist = artist,
                album = "Album $id",
                canonicalTitle = "title $id",
                canonicalArtist = artist.lowercase(),
                isDownloaded = false,
                isStreamable = false,
                isStreamableCheckedAt = null,
            )
        )
        return id
    }

    private fun activePlaylist(isActive: Boolean = true) = PlaylistEntity(
        name = "Test Playlist",
        source = MusicSource.BOTH,
        sourceId = "test_playlist_${System.nanoTime()}",
        type = PlaylistType.CUSTOM,
        trackCount = 0,
        syncEnabled = true,
        isActive = isActive,
    )

    private fun crossRef(playlistId: Long, trackId: Long, position: Int, removed: Boolean = false) =
        PlaylistTrackCrossRef(
            playlistId = playlistId,
            trackId = trackId,
            position = position,
            addedAt = Instant.EPOCH,
            removedAt = if (removed) Instant.now() else null,
        )
}
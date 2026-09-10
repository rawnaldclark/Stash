package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.SyncHistoryEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Restoring a backup onto a fresh install gives you the library but not the
 * audio: the files are gone, so the integrity sweep resets every downloaded
 * row. Getting the music back then depended on the requeue, which only takes
 * tracks that sit in a sync-enabled, non-mix playlist — on a real 861-track
 * library that was 36 of them. The other 825 were known, listed, and
 * unreachable, with no way to ask for them back.
 *
 * So the sweep now stamps `download_missing_at`, which is what "I had this
 * and the file is gone" means, and a user-requested queue row is exempt from
 * the playlist predicate that the automatic requeue needs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TrackDaoRestorableDownloadsTest {
    private lateinit var db: StashDatabase
    private lateinit var tracks: TrackDao
    private lateinit var queue: DownloadQueueDao
    private lateinit var playlists: PlaylistDao
    private lateinit var syncs: SyncHistoryDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StashDatabase::class.java,
        ).allowMainThreadQueries().build()
        tracks = db.trackDao(); queue = db.downloadQueueDao(); playlists = db.playlistDao()
        syncs = db.syncHistoryDao()
    }

    @After fun tearDown() = db.close()

    private suspend fun downloadedTrack(title: String): Long = tracks.insert(
        TrackEntity(
            title = title,
            artist = "A",
            album = "Al",
            durationMs = 1000,
            source = MusicSource.SPOTIFY,
            isDownloaded = true,
            filePath = "/music/$title.flac",
            fileSizeBytes = 1234,
        ),
    )

    @Test fun `a vanished file becomes restorable, and re-downloading clears it`() = runTest {
        val id = downloadedTrack("gone")
        assertEquals(0, tracks.countRestorableDownloads())

        tracks.resetMissingFiles(listOf(id), now = 5_000L)
        assertEquals(1, tracks.countRestorableDownloads())
        assertEquals(listOf(id), tracks.restorableDownloadIds())

        tracks.markAsDownloaded(id, "/music/gone.flac", 1234)
        assertEquals(0, tracks.countRestorableDownloads())
    }

    @Test fun `deleting a download on purpose does not ask for it back`() = runTest {
        val id = downloadedTrack("deliberate")
        tracks.clearDownloadState(id)
        assertEquals(0, tracks.countRestorableDownloads())
    }

    @Test fun `a track the user asked for is downloadable with no playlist at all`() = runTest {
        val orphan = downloadedTrack("orphan")
        tracks.resetMissingFiles(listOf(orphan), now = 1L)
        queue.insert(DownloadQueueEntity(trackId = orphan, userRequested = true))

        val pending = queue.getAllPendingBySources(listOf("SPOTIFY", "BOTH"))
        assertEquals(listOf(orphan), pending.map { it.trackId })
    }

    @Test fun `without that flag an orphan is still skipped, and a sync run still works`() = runTest {
        val orphan = downloadedTrack("orphan")
        tracks.resetMissingFiles(listOf(orphan), now = 1L)
        queue.insert(DownloadQueueEntity(trackId = orphan))
        assertEquals(emptyList<Long>(), queue.getAllPendingBySources(listOf("SPOTIFY", "BOTH")).map { it.trackId })

        // The untouched path: a sync run queued it AND it sits in a synced,
        // non-mix playlist. Both halves are still required together.
        val inPlaylist = downloadedTrack("member")
        tracks.resetMissingFiles(listOf(inPlaylist), now = 1L)
        val pl = playlists.insert(
            PlaylistEntity(
                name = "Road trip",
                source = MusicSource.SPOTIFY,
                sourceId = "road",
                type = PlaylistType.CUSTOM,
                isActive = true,
                syncEnabled = true,
            ),
        )
        playlists.insertCrossRef(PlaylistTrackCrossRef(playlistId = pl, trackId = inPlaylist, position = 0))
        val run = syncs.insert(SyncHistoryEntity())
        queue.insert(DownloadQueueEntity(trackId = inPlaylist, syncId = run))
        assertEquals(listOf(inPlaylist), queue.getAllPendingBySources(listOf("SPOTIFY", "BOTH")).map { it.trackId })

        // ...and a sync-queued row for a track in NO playlist stays skipped.
        val queuedOrphan = downloadedTrack("queued-orphan")
        queue.insert(DownloadQueueEntity(trackId = queuedOrphan, syncId = run))
        assertEquals(listOf(inPlaylist), queue.getAllPendingBySources(listOf("SPOTIFY", "BOTH")).map { it.trackId })
    }
}

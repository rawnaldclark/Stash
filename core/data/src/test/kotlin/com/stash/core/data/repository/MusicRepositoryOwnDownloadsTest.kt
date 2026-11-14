package com.stash.core.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.common.TrackCanonical
import com.stash.core.data.db.OwnDownloadsBackfill
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.SyncHistoryEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.sync.DownloadJobRegistry
import com.stash.core.data.sync.SingleTrackDownloadEnqueuer
import com.stash.core.data.sync.SyncPreferencesManager
import com.stash.core.model.DownloadStatus
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #761: a song the user downloads by hand (Now Playing, a ⋮ menu, an album or artist page,
 * multi-select, a radio: all [MusicRepositoryImpl.requestDownload]) joins Your Downloads the way a
 * Search download does, so Refresh cleanup never deletes it, Downloads › Clear history or not; and
 * the user's own remove and delete still work. Real Room, real repository; only Refresh's gate
 * (open: every account on Refresh) and the download worker are stubbed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MusicRepositoryOwnDownloadsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: StashDatabase
    private val enqueuer = mockk<SingleTrackDownloadEnqueuer>(relaxed = true)
    private val jobs = DownloadJobRegistry()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, StashDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    // ── Every tap ends in Your Downloads ──────────────────────────────────

    @Test fun `a tap on a song with no queue row links it before it downloads`() = runTest {
        val radioSong = song("Radio Song")

        assertEquals(DownloadRequestResult.QUEUED, repo().requestDownload(radioSong))

        assertEquals(setOf(radioSong), yourDownloadsMembers())
        assertEquals(1, db.playlistDao().findBySourceId(YourDownloadsPlaylist.SOURCE_ID)!!.trackCount)
        coVerify(exactly = 1) { enqueuer.enqueue(any()) }
    }

    @Test fun `a tap on a song whose old row a sync left links it too`() = runTest {
        val playlistSong = song("Playlist Song", source = MusicSource.SPOTIFY)
        member(playlist("sp-discover", PlaylistType.DAILY_MIX), playlistSong)
        db.downloadQueueDao().insert(
            DownloadQueueEntity(trackId = playlistSong, syncId = syncRun(), status = DownloadStatus.FAILED),
        )

        assertEquals(DownloadRequestResult.QUEUED, repo().requestDownload(playlistSong))

        assertEquals(setOf(playlistSong), yourDownloadsMembers())
        assertTrue(db.downloadQueueDao().getByTrackId(playlistSong)!!.userRequested)
    }

    @Test fun `a tap on a song downloading right now links it and leaves the download running`() = runTest {
        val running = song("Running Song")
        val rowId = db.downloadQueueDao().insert(DownloadQueueEntity(trackId = running, syncId = syncRun()))
        db.downloadQueueDao().claimForDownload(rowId)
        val download = Job()
        assertTrue(jobs.claimAndRegister(rowId, download) { true })

        assertEquals(DownloadRequestResult.QUEUED, repo().requestDownload(running))

        assertEquals(setOf(running), yourDownloadsMembers())
        assertTrue(download.isActive)
        download.cancel()
    }

    @Test fun `a tap on a song already on the phone keeps the phone's copy`() = runTest {
        val copy = song("Starting Over", file = "/music/starting-over.flac", source = MusicSource.SPOTIFY)
        val stub = song("Starting Over")

        assertEquals(DownloadRequestResult.ALREADY_ON_PHONE, repo().requestDownload(stub))

        assertEquals(setOf(copy), yourDownloadsMembers())
        coVerify(exactly = 0) { enqueuer.enqueue(any()) }
    }

    @Test fun `a tap whose link can't be written queues nothing`() = runTest {
        // A song deleted between the tap's read and its link (relaxed: no copy on the phone).
        val gone = mockk<TrackDao>(relaxed = true) {
            coEvery { getById(7L) } returns TrackEntity(id = 7L, title = "T", artist = "a", canonicalTitle = "t", canonicalArtist = "a")
        }
        val playlistDao = mockk<com.stash.core.data.db.dao.PlaylistDao>(relaxed = true) {
            coEvery { ensurePlaylistAndActiveCrossRef(any(), 7L) } throws
                android.database.sqlite.SQLiteConstraintException("FOREIGN KEY constraint failed")
        }

        assertEquals(DownloadRequestResult.NOT_QUEUED, repo(trackDao = gone, playlistDao = playlistDao).requestDownload(7L))
        coVerify(exactly = 0) { enqueuer.enqueue(any()) }
    }

    // ── Refresh cleanup keeps them ────────────────────────────────────────

    @Test fun `hand downloads survive Refresh cleanup after Downloads Clear history, a sync's rotated-out song doesn't`() = runTest {
        val repo = repo()
        // A radio song downloaded from Now Playing.
        val radioSong = song("Radio Song")
        // A mix song downloaded from its ⋮; the mix rotates it out later.
        val mix = playlist("sp-daily-1", PlaylistType.DAILY_MIX)
        val mixSong = member(mix, song("Mix Song", source = MusicSource.SPOTIFY))
        // A mix song the sync downloaded, rotated out with it: Refresh is meant to take this one.
        val syncSong = member(mix, song("Sync Song", file = "/music/sync-song.opus", source = MusicSource.SPOTIFY))
        db.downloadQueueDao().insert(
            DownloadQueueEntity(trackId = syncSong, syncId = syncRun(), status = DownloadStatus.COMPLETED),
        )

        assertEquals(DownloadRequestResult.QUEUED, repo.requestDownload(radioSong))
        assertEquals(DownloadRequestResult.QUEUED, repo.requestDownload(mixSong))
        land(radioSong)
        land(mixSong)
        db.playlistDao().clearPlaylistTracks(mix) // the mix rotates
        db.downloadQueueDao().deleteCompleted() // Downloads › Clear history
        assertFalse("the mark the sweep also spares is gone", db.downloadQueueDao().hasUserRequest(radioSong))

        assertEquals(1, repo.cleanOrphanedMixTracks())

        assertTrue(db.trackDao().getById(radioSong)!!.isDownloaded)
        assertTrue(db.trackDao().getById(mixSong)!!.isDownloaded)
        assertNull(db.trackDao().getById(syncSong))
    }

    @Test fun `the user's own remove and delete still work on a hand download`() = runTest {
        val repo = repo()
        val removed = song("Removed")
        val deleted = song("Deleted")
        repo.requestDownload(removed)
        repo.requestDownload(deleted)
        land(removed)
        land(deleted)

        // "Remove download": the file goes, the song stays to stream.
        repo.removeDownload(removed)
        assertFalse(db.trackDao().getById(removed)!!.isDownloaded)

        // Your Downloads › "Delete from library": the song and its file go.
        val yourDownloads = db.playlistDao().findBySourceId(YourDownloadsPlaylist.SOURCE_ID)!!.id
        val summary = repo.removeTrackFromPlaylistAndMaybeDelete(deleted, yourDownloads, alsoBlacklist = false)
        assertEquals(1, summary.deleted)
        assertNull(db.trackDao().getById(deleted))
    }

    // ── The queue's own cleanups never drop a tap ────────────────────────

    @Test fun `a tap on a sync's leftover row survives the cold start's and the next sync's queue cleanups`() = runTest {
        // A Daily Mix the user never switched on: a sync once queued its song and left the row.
        val mixSong = member(playlist("sp-daily-off", PlaylistType.DAILY_MIX), song("Mix Song", source = MusicSource.SPOTIFY))
        val row = db.downloadQueueDao().insert(
            DownloadQueueEntity(trackId = mixSong, syncId = syncRun(), status = DownloadStatus.FAILED, searchQuery = "q"),
        )
        val repo = repo()

        // Tapped while offline: the download hasn't started by the next cold start's cleanup.
        assertEquals(DownloadRequestResult.QUEUED, repo.requestDownload(mixSong))
        db.downloadQueueDao().cancelDownloadsWithNoEnabledPlaylist()
        assertEquals(row, db.downloadQueueDao().getByTrackId(mixSong)?.id)
        assertEquals(listOf(row), db.downloadQueueDao().getAllPendingBySources(listOf("SPOTIFY")).map { it.id })

        // Started, found no lossless copy, waits for one; the next sync reconciles the queue.
        db.downloadQueueDao().updateStatus(row, DownloadStatus.WAITING_FOR_LOSSLESS)
        db.downloadQueueDao().deleteOrphanedQueueEntries()
        assertEquals(DownloadStatus.WAITING_FOR_LOSSLESS, db.downloadQueueDao().getStatusById(row))

        // The user's own Remove download still ends it.
        repo.removeDownload(mixSong)
        assertNull(db.downloadQueueDao().getByTrackId(mixSong))
    }

    // ── Library & Storage's "Download N again" ───────────────────────────

    @Test fun `songs asked back that no playlist holds join Your Downloads, a playlist's stay its own`() = runTest {
        val loose = song("Loose")
        val listed = member(playlist("custom-road", PlaylistType.CUSTOM), song("Listed"))
        val leftOne = song("Left One")
        db.playlistDao().insertCrossRef(
            PlaylistTrackCrossRef(
                playlistId = playlist("custom-old", PlaylistType.CUSTOM),
                trackId = leftOne,
                position = 0,
                removedAt = java.time.Instant.now(),
            ),
        )

        assertEquals(2, repo().keepUnlistedInYourDownloads(listOf(loose, listed, leftOne)))

        assertEquals(setOf(loose, leftOne), yourDownloadsMembers())
    }

    // ── The one-time pass goes before the first sweep ────────────────────

    @Test fun `the sweep waits for the one-time pass, which keeps an old unmarked tap`() = runTest {
        val oldTap = song("Old Tap", file = "/music/old-tap.opus", source = MusicSource.SPOTIFY)
        db.downloadQueueDao().insert(
            DownloadQueueEntity(
                trackId = oldTap,
                status = DownloadStatus.COMPLETED,
                createdAt = java.time.Instant.ofEpochMilli(OwnDownloadsBackfill.UNMARKED_BEFORE_MS - 1),
            ),
        )

        assertEquals(0, repo(backfill = OwnDownloadsBackfill(db.trackDao(), db.playlistDao(), freshPrefs())).cleanOrphanedMixTracks())

        assertTrue(db.trackDao().getById(oldTap)!!.isDownloaded)
        assertEquals(setOf(oldTap), yourDownloadsMembers())
    }

    @Test fun `a failed one-time pass holds the sweep off`() = runTest {
        val rotatedOut = song("Rotated Out", file = "/music/rotated.opus", source = MusicSource.SPOTIFY)
        val failing = mockk<TrackDao>(relaxed = true) {
            coEvery { ownDownloadsOutsideYourDownloads(any(), any()) } throws IllegalStateException("disk I/O error")
        }

        assertEquals(0, repo(backfill = OwnDownloadsBackfill(failing, db.playlistDao(), freshPrefs())).cleanOrphanedMixTracks())

        assertNotNull(db.trackDao().getById(rotatedOut))
    }

    // ── Fixtures ───────────────────────────────────────────────────────────

    /** A row as the library stores it; [file] makes it a download. */
    private suspend fun song(title: String, file: String? = null, source: MusicSource = MusicSource.YOUTUBE): Long =
        db.trackDao().insert(
            TrackEntity(
                title = title,
                artist = "LSD and the Search for God",
                durationMs = 304_000L,
                filePath = file,
                isDownloaded = file != null,
                isStreamable = true,
                source = source,
                canonicalTitle = TrackCanonical.title(title),
                canonicalArtist = TrackCanonical.artist("LSD and the Search for God"),
            ),
        )

    /** The download worker's success, as TrackDownloadWorker writes it: the file, then the row done. */
    private suspend fun land(trackId: Long) {
        val row = db.downloadQueueDao().getByTrackId(trackId)!!
        db.downloadQueueDao().claimForDownload(row.id)
        db.trackDao().markAsDownloaded(trackId, "/music/$trackId.opus", 4_000_000L)
        db.downloadQueueDao().completeIfInProgress(row.id, System.currentTimeMillis())
    }

    private suspend fun playlist(sourceId: String, type: PlaylistType): Long =
        db.playlistDao().insert(PlaylistEntity(name = sourceId, source = MusicSource.SPOTIFY, sourceId = sourceId, type = type))

    private suspend fun member(playlistId: Long, trackId: Long): Long {
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = playlistId, trackId = trackId, position = 0))
        return trackId
    }

    private suspend fun syncRun(): Long = db.syncHistoryDao().insert(SyncHistoryEntity())

    private suspend fun yourDownloadsMembers(): Set<Long> {
        val yourDownloads = db.playlistDao().findBySourceId(YourDownloadsPlaylist.SOURCE_ID) ?: return emptySet()
        return db.playlistDao().getCrossRefsForPlaylist(yourDownloads.id).filter { it.removedAt == null }.map { it.trackId }.toSet()
    }

    private fun freshPrefs(): SharedPreferences =
        context.getSharedPreferences("own-downloads-repo-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    private fun repo(
        trackDao: TrackDao = db.trackDao(),
        playlistDao: com.stash.core.data.db.dao.PlaylistDao = db.playlistDao(),
        backfill: OwnDownloadsBackfill? = null,
    ): MusicRepositoryImpl {
        val refresh = mockk<SyncPreferencesManager>(relaxed = true) { coEvery { anyAccumulate() } returns false }
        return MusicRepositoryImpl(
            context = context,
            trackDao = trackDao,
            playlistDao = playlistDao,
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
            syncPreferencesManager = refresh,
            singleTrackDownloadEnqueuer = enqueuer,
            lastFmRecommendationSource = mockk(relaxed = true),
            sharedMixDao = mockk(relaxed = true),
            downloadJobs = jobs,
            ownDownloadsBackfill = backfill,
        )
    }
}

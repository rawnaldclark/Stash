package com.stash.core.data.library

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthService
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.SyncHistoryEntity
import com.stash.core.data.db.entity.TrackEntity
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Only a sync requeues songs (#532). Library Health's Verify (a reconcile with no sync
 * run) filed rows outside any sync, which nothing but the background drain took: it
 * downloaded them unasked at every app start, Stream-only included. They also hid
 * their songs from the next Download-mode sync's own requeue.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class LibraryReconciliationRequeueTest {

    private lateinit var db: StashDatabase

    // No service connected: the requeue then looks at BOTH-source songs only.
    private val tokens = mockk<TokenManager> {
        coEvery { isAuthenticated(any()) } returns false
        coEvery { hasSoundCloudConnection() } returns false
    }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun tearDown() { db.close() }

    @Test fun `Verify queues nothing`() = runTest {
        val song = missingSong()

        val result = reconciler().reconcile()

        assertEquals(0, result.unqueuedRequeued)
        assertNull(db.downloadQueueDao().getByTrackId(song))
    }

    @Test fun `a sync's reconcile still requeues, with its own sync id`() = runTest {
        val song = missingSong()
        val run = db.syncHistoryDao().insert(SyncHistoryEntity())

        val result = reconciler().reconcile(syncId = run)

        assertEquals(1, result.unqueuedRequeued)
        assertEquals(run, db.downloadQueueDao().getByTrackId(song)!!.syncId)
    }

    @Test fun `reconcile clears a leftover, so the sync requeues its song in the same pass`() = runTest {
        val song = missingSong()
        // What Verify used to file: no sync run, no search query, not asked for.
        db.downloadQueueDao().insert(DownloadQueueEntity(trackId = song))
        val run = db.syncHistoryDao().insert(SyncHistoryEntity())

        val result = reconciler().reconcile(syncId = run)

        assertEquals(1, result.orphansSwept)
        assertEquals(1, result.unqueuedRequeued)
        assertEquals(run, db.downloadQueueDao().getByTrackId(song)!!.syncId)
        assertEquals(
            listOf(DownloadStatus.PENDING.name to 1),
            db.downloadQueueDao().getStatusCounts().map { it.status to it.count },
        )
    }

    /**
     * The sync leaves a song unqueued when its audio is already on the phone as another row, and
     * links that copy instead (DiffWorker, a Download switch). The requeue used to file it again in
     * the same pass, and the second download happened anyway.
     */
    @Test fun `a sync's requeue skips a song already on the phone as another row, and links that copy where it sits`() = runTest {
        val alreadyHere = missingSong(title = "Starting Over", position = 4)
        val copy = db.trackDao().insert(
            TrackEntity(
                title = "Starting Over (Official Audio)", artist = "A", durationMs = 1000L, source = MusicSource.YOUTUBE,
                youtubeId = "handVideo01", filePath = "/music/starting-over.flac", isDownloaded = true,
                canonicalTitle = com.stash.core.common.TrackCanonical.title("Starting Over"), canonicalArtist = "a",
            )
        )
        val notHere = missingSong(title = "Never Heard")
        val run = db.syncHistoryDao().insert(SyncHistoryEntity())

        val result = reconciler().reconcile(syncId = run)

        assertEquals(1, result.unqueuedRequeued)
        assertNull("one file of a song is enough", db.downloadQueueDao().getByTrackId(alreadyHere))
        assertEquals(run, db.downloadQueueDao().getByTrackId(notHere)!!.syncId)

        // Offline, Download mode shows only rows with a file: without the link the song vanished.
        val link = db.playlistDao().getCrossRef(syncedPlaylist(), copy)
        assertNotNull("the phone's copy is in the playlist", link)
        assertNull(link!!.removedAt)
        assertEquals("where the song sits", 4, link.position)
        assertEquals(
            "the playlist's count includes the copy",
            db.trackDao().countByPlaylist(syncedPlaylist(), includeStreamable = true),
            db.playlistDao().getById(syncedPlaylist())!!.trackCount,
        )
    }

    @Test fun `a copy the user took out of the playlist stays out, and the song is still not downloaded twice`() = runTest {
        val alreadyHere = missingSong(title = "Starting Over")
        val copy = db.trackDao().insert(
            TrackEntity(
                title = "Starting Over", artist = "A", durationMs = 1000L, source = MusicSource.YOUTUBE,
                youtubeId = "handVideo01", filePath = "/music/starting-over.flac", isDownloaded = true,
                canonicalTitle = com.stash.core.common.TrackCanonical.title("Starting Over"), canonicalArtist = "a",
            )
        )
        db.playlistDao().insertCrossRef(
            PlaylistTrackCrossRef(playlistId = syncedPlaylist(), trackId = copy, position = 9, removedAt = java.time.Instant.now()),
        )
        val run = db.syncHistoryDao().insert(SyncHistoryEntity())

        reconciler().reconcile(syncId = run)

        assertNull(db.downloadQueueDao().getByTrackId(alreadyHere))
        val link = db.playlistDao().getCrossRef(syncedPlaylist(), copy)!!
        assertNotNull("still removed", link.removedAt)
        assertEquals(9, link.position)
    }

    /**
     * Someone with only Spotify connected, in Download mode, liked a YouTube stream before this fix:
     * Liked Songs (BOTH, switched on) holds it as a Spotify row with no Spotify id. The launch's data
     * fix relabels it YouTube. The sync's requeue and its download step pick rows by connected
     * source, and YouTube isn't one, so without the BOTH arm the like would stop downloading. A
     * YouTube download needs no login.
     */
    @Test fun `Spotify only, Download mode, a liked YouTube stream is relabelled and still requeued and downloaded`() = runTest {
        val spotifyOnly = mockk<TokenManager> {
            coEvery { isAuthenticated(any()) } returns false
            coEvery { isAuthenticated(AuthService.SPOTIFY) } returns true
            coEvery { hasSoundCloudConnection() } returns false
        }
        val likedSongs = db.playlistDao().insert(
            PlaylistEntity(
                name = "Liked Songs", source = MusicSource.BOTH, sourceId = "stash_liked_songs",
                type = PlaylistType.STASH_LIKED, syncEnabled = true,
            )
        )
        // Saved by the old Now Playing as a Spotify song: no Spotify id, a video id.
        val liked = db.trackDao().insert(
            TrackEntity(
                title = "Liked Stream", artist = "A", durationMs = 1000L, source = MusicSource.SPOTIFY,
                youtubeId = "likedVid001",
                canonicalTitle = com.stash.core.common.TrackCanonical.title("Liked Stream"), canonicalArtist = "a",
            )
        )
        db.playlistDao().insertCrossRef(
            PlaylistTrackCrossRef(playlistId = likedSongs, trackId = liked, position = 0, locallyAdded = true),
        )
        // The launch's data fix: the liked row's dot turns red.
        assertEquals(1, db.trackDao().relabelYouTubeRowsClaimingSpotify())
        assertEquals(MusicSource.YOUTUBE, db.trackDao().getById(liked)!!.source)
        val run = db.syncHistoryDao().insert(SyncHistoryEntity())

        val result = LibraryReconciliationUseCase(db.downloadQueueDao(), db.trackDao(), spotifyOnly, db.playlistDao())
            .reconcile(syncId = run)

        assertEquals(1, result.unqueuedRequeued)
        assertEquals(run, db.downloadQueueDao().getByTrackId(liked)!!.syncId)
        // What TrackDownloadWorker asks for with only Spotify connected.
        val taken = db.downloadQueueDao().getAllPendingBySources(listOf("SPOTIFY", "BOTH")).map { it.trackId }
        assertEquals(listOf(liked), taken)
    }

    @Test fun `Spotify only, a YouTube song only in a YouTube playlist still waits for YouTube`() = runTest {
        val spotifyOnly = mockk<TokenManager> {
            coEvery { isAuthenticated(any()) } returns false
            coEvery { isAuthenticated(AuthService.SPOTIFY) } returns true
            coEvery { hasSoundCloudConnection() } returns false
        }
        val youtubePlaylist = db.playlistDao().insert(
            PlaylistEntity(name = "Yt", source = MusicSource.YOUTUBE, sourceId = "PLyt", type = PlaylistType.CUSTOM, syncEnabled = true)
        )
        val song = db.trackDao().insert(
            TrackEntity(
                title = "Yt Song", artist = "A", durationMs = 1000L, source = MusicSource.YOUTUBE, youtubeId = "ytVideo0001",
                canonicalTitle = com.stash.core.common.TrackCanonical.title("Yt Song"), canonicalArtist = "a",
            )
        )
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = youtubePlaylist, trackId = song, position = 0))
        val run = db.syncHistoryDao().insert(SyncHistoryEntity())

        val result = LibraryReconciliationUseCase(db.downloadQueueDao(), db.trackDao(), spotifyOnly, db.playlistDao())
            .reconcile(syncId = run)

        assertEquals(0, result.unqueuedRequeued)
        assertNull(db.downloadQueueDao().getByTrackId(song))
    }

    @Test fun `a copy whose file is gone doesn't count, so its song is requeued`() = runTest {
        val song = missingSong(title = "Starting Over")
        val gone = db.trackDao().insert(
            TrackEntity(
                title = "Starting Over", artist = "A", durationMs = 1000L, source = MusicSource.YOUTUBE,
                youtubeId = "handVideo01", filePath = "/music/gone.flac", isDownloaded = true,
                canonicalTitle = com.stash.core.common.TrackCanonical.title("Starting Over"), canonicalArtist = "a",
            )
        )
        val run = db.syncHistoryDao().insert(SyncHistoryEntity())

        reconciler().reconcile(
            syncId = run,
            checkFileExists = { _, _, _, _, _ -> FileExistenceResult(exists = false) },
        )

        assertEquals(false, db.trackDao().getById(gone)!!.isDownloaded)
        assertEquals(run, db.downloadQueueDao().getByTrackId(song)!!.syncId)
    }

    private fun reconciler() = LibraryReconciliationUseCase(db.downloadQueueDao(), db.trackDao(), tokens, db.playlistDao())

    /** The switched-on playlist [missingSong] puts its songs in; returns its id. */
    private suspend fun syncedPlaylist(): Long =
        db.playlistDao().findBySourceId("custom_synced")?.id ?: db.playlistDao().insert(
            PlaylistEntity(
                name = "Synced", source = MusicSource.BOTH, sourceId = "custom_synced",
                type = PlaylistType.CUSTOM, syncEnabled = true,
            )
        )

    /** An undownloaded song in a synced playlist with no download row: what the requeue looks for. */
    private suspend fun missingSong(title: String = "Missing", position: Int = 0): Long {
        val playlist = syncedPlaylist()
        val song = db.trackDao().insert(
            TrackEntity(
                title = title, artist = "A", durationMs = 1000L, source = MusicSource.BOTH,
                canonicalTitle = com.stash.core.common.TrackCanonical.title(title), canonicalArtist = "a",
            )
        )
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = playlist, trackId = song, position = position))
        return song
    }
}

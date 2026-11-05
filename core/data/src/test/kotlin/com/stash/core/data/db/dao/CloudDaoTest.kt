package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.CloudAccountEntity
import com.stash.core.data.db.entity.CloudFolderEntity
import com.stash.core.data.db.entity.CloudItemEntity
import com.stash.core.data.db.entity.CloudUploadEntity
import com.stash.core.data.db.entity.CloudUploadOrigin
import com.stash.core.data.db.entity.CloudUploadState
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.MusicSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [CloudDao]'s removal rules (stream-only cloud songs go, downloaded ones stay as LOCAL files) and
 * the library predicates the cloud slice changed: a not-downloaded CLOUD song is in the Library,
 * and a downloaded one never joins the lossless-upgrade pool or the orphan sweep.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CloudDaoTest {

    private lateinit var db: StashDatabase
    private lateinit var dao: CloudDao
    private var accountId = 0L
    private var folderId = 0L

    @Before fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.cloudDao()
        accountId = dao.insertAccount(CloudAccountEntity(provider = "onedrive", remoteId = "me", displayName = "Me", connectedAt = 1))
        folderId = dao.insertFolder(CloudFolderEntity(accountId = accountId, itemId = "root!music", name = "Music", path = "/Music", addedAt = 1))
    }

    @After fun tearDown() = db.close()

    private suspend fun cloudSong(itemId: String, downloaded: Boolean = false, generation: Long = 1, format: String = "mp3"): Long {
        val id = dao.insertTrack(
            TrackEntity(
                title = "Song $itemId", artist = "Artist", source = MusicSource.CLOUD,
                fileFormat = format, isDownloaded = downloaded,
                filePath = if (downloaded) "/music/$itemId.$format" else null,
                cloudUri = "stash-cloud://onedrive/$accountId/$itemId?v=c1",
            ),
        )
        dao.insertItem(
            CloudItemEntity(
                trackId = id, accountId = accountId, folderId = folderId, itemId = itemId, name = "$itemId.$format",
                relativePath = "Artist/Album", parentItemId = "p", cTag = "c1", eTag = "e1", size = 1000,
                mimeType = "audio/mpeg", seenGeneration = generation, scannedAt = 1,
            ),
        )
        return id
    }

    // ── Uploads with two Google accounts ─────────────────────────────────

    private suspend fun driveAccount(name: String) =
        dao.insertAccount(CloudAccountEntity(provider = "gdrive", remoteId = name, displayName = name, email = "$name@example.com", connectedAt = 2))

    private suspend fun download(title: String) = dao.insertTrack(
        TrackEntity(title = title, artist = "A", source = MusicSource.SPOTIFY, isDownloaded = true, filePath = "/m/$title.opus", spotifyUri = "spotify:track:$title"),
    )

    private suspend fun upload(track: Long, account: Long, state: CloudUploadState, origin: CloudUploadOrigin = CloudUploadOrigin.MANUAL, queuedAt: Long = 1) =
        dao.putUpload(CloudUploadEntity(track, account, state.name, origin.name, queuedAt, queuedAt))

    @Test fun `the next upload skips accounts that can't take a song, oldest first among the rest`() = runTest {
        val a = driveAccount("a")
        val b = driveAccount("b")
        val oldA = download("old-a")
        val newB = download("new-b")
        val newestB = download("newest-b")
        upload(oldA, a, CloudUploadState.QUEUED, queuedAt = 1)
        upload(newB, b, CloudUploadState.QUEUED, queuedAt = 2)
        upload(newestB, b, CloudUploadState.QUEUED, queuedAt = 3)

        assertEquals(oldA, dao.nextQueuedUpload(emptyList())!!.trackId)
        assertEquals(newB, dao.nextQueuedUpload(listOf(a))!!.trackId)
        assertNull(dao.nextQueuedUpload(listOf(a, b)))

        assertEquals(3, dao.countQueuedUploads(emptyList()))
        assertEquals(2, dao.countQueuedUploads(listOf(a)))
        assertEquals(0, dao.countQueuedUploads(listOf(a, b)))
    }

    @Test fun `back up existing and backup off touch only their own account`() = runTest {
        val a = driveAccount("a")
        val b = driveAccount("b")
        val onA = download("on-a")
        val onB = download("on-b")
        upload(onA, a, CloudUploadState.EXISTING, CloudUploadOrigin.BACKUP)
        upload(onB, b, CloudUploadState.EXISTING, CloudUploadOrigin.BACKUP)

        assertEquals(1, dao.queueExistingUploads(b, 5))
        assertEquals(CloudUploadState.EXISTING.name, dao.getUpload(onA)!!.state)
        assertEquals(CloudUploadState.QUEUED.name, dao.getUpload(onB)!!.state)

        assertEquals(0, dao.unqueueBackupUploads(a, 6))
        assertEquals(CloudUploadState.QUEUED.name, dao.getUpload(onB)!!.state)
        assertEquals(1, dao.unqueueBackupUploads(b, 7))
        assertEquals(CloudUploadState.EXISTING.name, dao.getUpload(onB)!!.state)
    }

    @Test fun `moving backup takes what waits and leaves saves and finished songs`() = runTest {
        val a = driveAccount("a")
        val b = driveAccount("b")
        val existing = download("existing")
        val queuedBackup = download("queued-backup")
        val queuedManual = download("queued-manual")
        val done = download("done")
        val failed = download("failed")
        upload(existing, a, CloudUploadState.EXISTING, CloudUploadOrigin.BACKUP)
        upload(queuedBackup, a, CloudUploadState.QUEUED, CloudUploadOrigin.BACKUP)
        dao.setUploadSession(queuedBackup, "https://session", 10, "md5", 2)
        dao.setUploadProgress(queuedBackup, 5, 2)
        upload(queuedManual, a, CloudUploadState.QUEUED, CloudUploadOrigin.MANUAL)
        upload(done, a, CloudUploadState.DONE, CloudUploadOrigin.BACKUP)
        upload(failed, a, CloudUploadState.FAILED, CloudUploadOrigin.BACKUP)

        assertEquals(2, dao.moveWaitingBackups(b, 9))

        assertEquals(b, dao.getUpload(existing)!!.accountId)
        dao.getUpload(queuedBackup)!!.let {
            assertEquals(b, it.accountId)
            assertEquals(CloudUploadState.QUEUED.name, it.state)
            assertNull(it.sessionUri)
            assertEquals(0L, it.bytesSent)
        }
        assertEquals(a, dao.getUpload(queuedManual)!!.accountId)
        assertEquals(a, dao.getUpload(done)!!.accountId)
        assertEquals(a, dao.getUpload(failed)!!.accountId)
        // Nothing left to move: the same call again does nothing.
        assertEquals(0, dao.moveWaitingBackups(b, 10))
    }

    /** A scan recognised [track] as a file in [account]'s Drive. */
    private suspend fun linkTo(track: Long, account: Long) {
        val folder = dao.insertFolder(CloudFolderEntity(accountId = account, itemId = "saved-$account", name = "Saved", path = "", addedAt = 1))
            .takeIf { it > 0 } ?: dao.getFolders(account).first { it.itemId == "saved-$account" }.id
        dao.linkUpload(
            track,
            CloudItemEntity(
                trackId = track, accountId = account, folderId = folder, itemId = "file-$track", name = "f", relativePath = "",
                parentItemId = null, cTag = "1", eTag = null, size = 1, mimeType = null, seenGeneration = 1, scannedAt = 1,
            ),
        )
    }

    @Test fun `a song linked to a file in one Drive stays on that account whichever one asks`() = runTest {
        val a = driveAccount("a")
        val b = driveAccount("b")
        val linked = download("linked")
        linkTo(linked, a)

        // The switch and the watcher.
        dao.insertUploadsForDownloads(b, CloudUploadState.EXISTING.name, 1)
        assertEquals(a, dao.getUpload(linked)!!.accountId)
        // Moving backup, and "Back up N existing".
        assertEquals(0, dao.moveWaitingBackups(b, 2))
        assertEquals(0, dao.queueExistingUploads(b, 3))
        assertEquals(CloudUploadState.EXISTING.name, dao.getUpload(linked)!!.state)
        // A tap on Save, with a row and without one.
        assertEquals(1, dao.queueUploads(listOf(linked), b, CloudUploadOrigin.MANUAL.name, 4))
        assertEquals(a, dao.getUpload(linked)!!.accountId)
        val linkedNoRow = download("linked-no-row")
        linkTo(linkedNoRow, a)
        assertEquals(1, dao.queueUploads(listOf(linkedNoRow), b, CloudUploadOrigin.MANUAL.name, 5))
        assertEquals(a, dao.getUpload(linkedNoRow)!!.accountId)
    }

    @Test fun `a row left on the wrong account by older data never sends a linked song there`() = runTest {
        val a = driveAccount("a")
        val b = driveAccount("b")
        val linked = download("linked")
        val folder = dao.insertFolder(CloudFolderEntity(accountId = a, itemId = "saved", name = "Saved", path = "", addedAt = 1))
        // Written straight to the table: nothing today can leave a row like this (a link moves it).
        dao.insertItem(
            CloudItemEntity(
                trackId = linked, accountId = a, folderId = folder, itemId = "file", name = "f", relativePath = "",
                parentItemId = null, cTag = "1", eTag = null, size = 1, mimeType = null, seenGeneration = 1, scannedAt = 1,
            ),
        )
        upload(linked, b, CloudUploadState.EXISTING, CloudUploadOrigin.BACKUP)

        assertEquals(0, dao.queueExistingUploads(b, 2))
        assertEquals(CloudUploadState.EXISTING.name, dao.getUpload(linked)!!.state)
    }

    @Test fun `a link takes a song still waiting for another Drive with it, and leaves a finished one`() = runTest {
        val a = driveAccount("a")
        val b = driveAccount("b")
        val waiting = download("waiting")
        val finished = download("finished")
        upload(waiting, b, CloudUploadState.QUEUED, CloudUploadOrigin.BACKUP)
        dao.setUploadSession(waiting, "https://session", 10, "md5", 2)
        upload(finished, b, CloudUploadState.DONE, CloudUploadOrigin.BACKUP)

        linkTo(waiting, a)
        linkTo(finished, a)

        dao.getUpload(waiting)!!.let {
            assertEquals(a, it.accountId)
            assertEquals(CloudUploadState.QUEUED.name, it.state)
            // A session belongs to the account that opened it.
            assertNull(it.sessionUri)
        }
        assertEquals(b, dao.getUpload(finished)!!.accountId)
    }

    @Test fun `saving a song already in another Drive keeps it there, anything else moves`() = runTest {
        val a = driveAccount("a")
        val b = driveAccount("b")
        val done = download("done")
        val failed = download("failed")
        upload(done, a, CloudUploadState.DONE)
        upload(failed, a, CloudUploadState.FAILED)
        dao.setUploadSession(failed, "https://session", 10, "md5", 2)

        assertEquals(2, dao.queueUploads(listOf(done, failed), b, CloudUploadOrigin.MANUAL.name, 5))

        assertEquals(a, dao.getUpload(done)!!.accountId)
        assertEquals(CloudUploadState.QUEUED.name, dao.getUpload(done)!!.state)
        assertEquals(b, dao.getUpload(failed)!!.accountId)
        assertNull(dao.getUpload(failed)!!.sessionUri)
    }

    @Test fun `removing one account leaves the other's uploads and items`() = runTest {
        val a = driveAccount("a")
        val b = driveAccount("b")
        val onA = download("on-a")
        val onB = download("on-b")
        upload(onA, a, CloudUploadState.QUEUED)
        upload(onB, b, CloudUploadState.QUEUED)

        dao.removeAccount(b)

        assertEquals(a, dao.getUpload(onA)!!.accountId)
        assertNull(dao.getUpload(onB))
        // The download itself stays.
        assertTrue(db.trackDao().getById(onB)!!.isDownloaded)
    }

    @Test fun `disconnect deletes stream-only songs and keeps downloaded ones as local files`() = runTest {
        val streamed = cloudSong("a")
        val downloaded = cloudSong("b", downloaded = true)

        val removal = dao.removeAccount(accountId)

        assertEquals(CloudRemoval(deletedTracks = 1, keptAsLocal = 1), removal)
        assertNull(db.trackDao().getById(streamed))
        val kept = db.trackDao().getById(downloaded)!!
        assertEquals(MusicSource.LOCAL, kept.source)
        assertNull(kept.cloudUri)
        assertTrue(kept.isDownloaded)
        assertNull(dao.getAccount(accountId))
        assertTrue(dao.getAllFolders().isEmpty())
        assertEquals(0, dao.countItems(accountId))
    }

    @Test fun `a completed full pass removes only what it didn't see`() = runTest {
        val seen = cloudSong("seen", generation = 2)
        val gone = cloudSong("gone", generation = 1)

        val removal = dao.removeStale(folderId, generation = 2)

        assertEquals(1, removal.deletedTracks)
        assertNull(db.trackDao().getById(gone))
        assertEquals(MusicSource.CLOUD, db.trackDao().getById(seen)!!.source)
        assertEquals(seen, dao.getItem(accountId, "seen")!!.trackId)
    }

    @Test fun `unpicking a folder removes its songs and the folder`() = runTest {
        cloudSong("x")
        dao.removeFolder(folderId)
        assertTrue(dao.getAllFolders().isEmpty())
        assertEquals(0, dao.countItems(accountId))
        assertTrue(dao.accountTrackIds(accountId).isEmpty())
    }

    @Test fun `inserting a file twice keeps one song (a second scan lost the race)`() = runTest {
        val item = CloudItemEntity(
            trackId = 0, accountId = accountId, folderId = folderId, itemId = "dup", name = "d.mp3",
            relativePath = "", parentItemId = null, cTag = "c", eTag = null, size = 1, mimeType = null,
            seenGeneration = 1, scannedAt = 1,
        )
        val track = TrackEntity(title = "D", artist = "A", source = MusicSource.CLOUD, cloudUri = "stash-cloud://onedrive/$accountId/dup")
        val first = dao.insertScanned(track, item)
        val second = dao.insertScanned(track, item)
        assertEquals(first, second)
        assertEquals(listOf(first), dao.accountTrackIds(accountId))
        assertEquals(1, db.trackDao().getAllForIntegrityScan().count { it.source == MusicSource.CLOUD })
    }

    @Test(expected = android.database.sqlite.SQLiteConstraintException::class)
    fun `a second row for the same file is refused, never replaced`() = runTest {
        val id = cloudSong("x")
        val otherTrack = dao.insertTrack(TrackEntity(title = "Other", artist = "A", source = MusicSource.CLOUD))
        dao.insertItem(dao.getItem(accountId, "x")!!.copy(trackId = otherTrack))
        assertEquals(id, dao.getItem(accountId, "x")!!.trackId)
    }

    @Test fun `a re-picked folder is ignored, not duplicated`() = runTest {
        val again = dao.insertFolder(CloudFolderEntity(accountId = accountId, itemId = "root!music", name = "Music", path = "/Music", addedAt = 2))
        assertEquals(-1L, again)
    }

    @Test fun `a scan update keeps the user's state and rewrites what the scan owns`() = runTest {
        val id = cloudSong("u")
        db.trackDao().getById(id)!!.let { db.trackDao().update(it.copy(playCount = 5, stashLikedAt = 9)) }

        dao.updateScannedTrack(
            trackId = id, title = "New", artist = "New Artist", album = "New Album", albumArtist = "",
            durationMs = 1234, fileFormat = "flac", qualityKbps = 900, bitsPerSample = 24, sampleRateHz = 96000,
            albumArtPath = null, cloudUri = "stash-cloud://onedrive/$accountId/u?v=c2", sizeBytes = 2000,
        )

        val t = db.trackDao().getById(id)!!
        assertEquals("New", t.title)
        assertEquals("flac", t.fileFormat)
        assertEquals("stash-cloud://onedrive/$accountId/u?v=c2", t.cloudUri)
        assertEquals(2000L, t.fileSizeBytes)
        assertEquals(5, t.playCount)
        assertEquals(9L, t.stashLikedAt)
    }

    @Test fun `a cloud song is in the Library before it is downloaded, in both modes`() = runTest {
        val id = cloudSong("lib")
        assertTrue(db.trackDao().getLibraryByDateAdded().first().any { it.id == id })
        val online = db.trackDao().getLibraryWithOnlineByDateAdded().first().single { it.id == id }
        assertEquals("stash-cloud://onedrive/$accountId/lib?v=c1", online.cloudUri)
        assertFalse(online.isDownloaded)
        assertTrue(db.trackDao().getAllArtists(includeNotDownloaded = false, libraryOnly = false).first().any { it.artist == "Artist" })
    }

    @Test fun `a downloaded cloud song never joins the lossless upgrade pool or the orphan sweep`() = runTest {
        val id = cloudSong("dl", downloaded = true, format = "mp3")
        assertFalse(db.trackDao().getLosslessUpgradeCandidates(retryBefore = Long.MAX_VALUE).any { it.id == id })
        assertFalse(db.trackDao().selectDeletableOrphans().any { it.id == id })
    }
}

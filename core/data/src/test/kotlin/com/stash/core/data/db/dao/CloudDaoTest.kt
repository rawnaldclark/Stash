package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.CloudAccountEntity
import com.stash.core.data.db.entity.CloudFolderEntity
import com.stash.core.data.db.entity.CloudItemEntity
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

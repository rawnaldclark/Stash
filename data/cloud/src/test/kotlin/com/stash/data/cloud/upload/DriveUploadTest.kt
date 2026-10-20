package com.stash.data.cloud.upload

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.auth.model.ServiceToken
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.dao.CloudDao
import com.stash.core.data.db.entity.CloudAccountEntity
import com.stash.core.data.db.entity.CloudItemEntity
import com.stash.core.data.db.entity.CloudUploadEntity
import com.stash.core.data.db.entity.CloudUploadOrigin
import com.stash.core.data.db.entity.CloudUploadState
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.CloudProvider
import com.stash.core.model.MusicSource
import com.stash.data.cloud.auth.CloudTokenManager
import com.stash.data.cloud.net.CloudHttp
import com.stash.data.cloud.net.RangeFetcher
import com.stash.data.cloud.net.RequestGate
import com.stash.data.cloud.playback.CloudLinkResolver
import com.stash.data.cloud.provider.gdrive.FakeAuthorizer
import com.stash.data.cloud.provider.gdrive.GoogleDriveAuth
import com.stash.data.cloud.provider.gdrive.GoogleDriveProvider
import com.stash.data.cloud.provider.gdrive.InMemoryGooglePickStore
import com.stash.data.cloud.provider.gdrive.MemoryTokenStore
import com.stash.data.cloud.provider.gdrive.driveFolderRow
import com.stash.data.cloud.scan.CloudScanner
import com.stash.data.cloud.tags.Fixtures
import com.stash.data.cloud.tags.Fixtures.ascii
import com.stash.data.cloud.tags.Fixtures.flacBlock
import com.stash.data.cloud.tags.Fixtures.streamInfo
import com.stash.data.cloud.tags.Fixtures.vorbisComments
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Save to Google Drive end to end on the JVM: the real uploader, folder resolver, resumable
 * protocol, DAO linking and Drive scanner, for two "phones" sharing one [FakeDriveStore] (one
 * Google account), each with its own in-memory database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DriveUploadTest {
    private val drive = FakeDriveStore()
    private var now = 0L
    private val gate = RequestGate(requestsPerSecond = 1000, clock = { now }, sleep = { now += it })
    private val phones = mutableListOf<Phone>()

    /** One phone: its database, files, uploader and scanner. */
    inner class Phone(verifyFoldersEvery: Long = 60 * 60_000L) {
        val db: StashDatabase = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries().build()
        val dao: CloudDao = db.cloudDao()
        val files = MemoryAudioFiles()
        val folderCache = MemoryFolderCache()
        val store = MemoryTokenStore()
        private val auth = GoogleDriveAuth(FakeAuthorizer(), drive.client)
        val tokens = CloudTokenManager(store, mapOf(CloudProvider.GOOGLE_DRIVE to auth)).apply { nowSeconds = { 1_000 } }
        val accountId: Long
        val api = DriveUploadApi(CloudHttp(drive.client, gate), tokens)
        val provider = GoogleDriveProvider(true, auth, CloudHttp(drive.client, gate), tokens, dao, InMemoryGooglePickStore(), clock = { 5_000 })
        val uploader: DriveUploader

        init {
            phones += this
            accountId = runBlocking {
                dao.insertAccount(CloudAccountEntity(provider = "gdrive", remoteId = "perm-123", displayName = "Ron", email = "ron@example.com", connectedAt = 0))
                    .also { store.save(CloudTokenManager.keyFor(CloudProvider.GOOGLE_DRIVE, it), ServiceToken("tok1", "ron@example.com", 1_000 + 3_000)) }
            }
            uploader = DriveUploader(
                dao, api, DriveFolders(api, folderCache, clock = { now }, verifyEvery = verifyFoldersEvery),
                ResumableUpload(drive.client, gate, files, chunkSize = ResumableUpload.QUANTUM), files,
                naming = { artist, album, title, ext -> UploadPlace(listOf(artist.lowercase(), album.ifBlank { "singles" }.lowercase()), "${title.lowercase()}.$ext") },
                clock = { 7_000 },
            )
        }

        /** A downloaded song with its file on this phone. */
        suspend fun download(title: String, bytes: ByteArray, album: String = "Album", isrc: String? = null): Long {
            val path = "/music/${title.lowercase()}.flac"
            files.files[path] = bytes
            return db.trackDao().insert(
                TrackEntity(
                    title = title, artist = "Artist", album = album, durationMs = 180_000, filePath = path,
                    fileFormat = "flac", isDownloaded = true, source = MusicSource.SPOTIFY, isrc = isrc,
                    spotifyUri = "spotify:track:${title.lowercase()}",
                ),
            )
        }

        suspend fun queued(trackId: Long): CloudUploadEntity {
            dao.queueUploads(listOf(trackId), accountId, CloudUploadOrigin.MANUAL.name, 1)
            return dao.getUpload(trackId)!!
        }

        suspend fun save(trackId: Long): UploadResult = uploader.upload(queued(trackId))

        fun scanner(): CloudScanner {
            val providers = mapOf(CloudProvider.GOOGLE_DRIVE to provider)
            val ranges = RangeFetcher(drive.client)
            return CloudScanner(dao, providers, CloudLinkResolver(providers), { l, o, n -> ranges.fetch(l.url, o, n, l.headers) }, { _, _ -> null }, clock = { 9_000 },
                localSongs = { UploadKeys.downloadsByKey(dao) },
            )
        }

        suspend fun uploadsFolder() = dao.driveFolderRow(accountId, GoogleDriveProvider.UPLOADS_ITEM_ID, 0)

        suspend fun scanSaved() = scanner().scan(uploadsFolder().id)!!

        fun trackCount(): Int = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM tracks").use { it.moveToFirst(); it.getInt(0) }
    }

    @After fun tearDown() = phones.forEach { it.db.close() }

    private fun flac(title: String, size: Int = 600_000) = Fixtures.bytes {
        ascii("fLaC")
        write(flacBlock(0, streamInfo(44100, 16, 2, 44100L * 180), last = false))
        write(flacBlock(4, vorbisComments("TITLE=$title", "ARTIST=Artist", "ALBUM=Album"), last = true))
        write(ByteArray(size))
    }

    @Test fun `a saved song lands in Stash, artist, album, tagged, and is linked to its own track`() = runTest {
        val phone = Phone()
        val bytes = flac("Alpha")
        val track = phone.download("Alpha", bytes)

        val r = phone.save(track) as UploadResult.Saved

        assertThat(r.reused).isFalse()
        val file = drive.entries.getValue(r.fileId)
        assertThat(file.name).isEqualTo("alpha.flac")
        assertThat(file.bytes).isEqualTo(bytes)
        assertThat(file.appProperties[GoogleDriveProvider.UPLOAD_MARKER_KEY]).isEqualTo("1")
        assertThat(file.appProperties[UploadKeys.TRACK_KEY]).isEqualTo(UploadKeys.trackKey(null, "Artist", "Alpha", 180_000))
        val album = drive.entries.getValue(file.parents.single())
        val artist = drive.entries.getValue(album.parents.single())
        val stash = drive.entries.getValue(artist.parents.single())
        assertThat(listOf(stash.name, artist.name, album.name)).containsExactly("Stash", "artist", "album").inOrder()
        assertThat(stash.parents).containsExactly("root")
        assertThat(stash.appProperties[UploadKeys.FOLDER_KEY]).isEqualTo("1")

        val item = phone.dao.getItemForTrack(track)!!
        assertThat(item.itemId).isEqualTo(r.fileId)
        assertThat(item.folderId).isEqualTo(phone.uploadsFolder().id)
        assertThat(item.cTag).isEqualTo(FakeDriveStore.md5(bytes))
        // Still the same local song, playing its own file.
        val t = phone.dao.getTrack(track)!!
        assertThat(t.source).isEqualTo(MusicSource.SPOTIFY)
        assertThat(t.cloudUri).isNull()
        assertThat(t.filePath).isEqualTo("/music/alpha.flac")
    }

    @Test fun `the Drive scan sees a linked upload as known and never adds it twice`() = runTest {
        val phone = Phone()
        val track = phone.download("Alpha", flac("Alpha"))
        phone.save(track)

        val r = phone.scanSaved()

        assertThat(r.added).isEqualTo(0)
        assertThat(r.unchanged).isEqualTo(1)
        assertThat(phone.trackCount()).isEqualTo(1)
        // A second full pass doesn't sweep it either.
        phone.scanner().scan(phone.uploadsFolder().id, full = true)
        assertThat(phone.dao.getItemForTrack(track)).isNotNull()
    }

    @Test fun `another phone on the same account finds the saved song through its scan`() = runTest {
        val a = Phone()
        a.save(a.download("Alpha", flac("Alpha")))
        val b = Phone()

        val r = b.scanSaved()

        assertThat(r.added).isEqualTo(1)
        val item = b.dao.getItem(b.accountId, drive.files().single().id)!!
        val song = b.dao.getTrack(item.trackId)!!
        assertThat(song.title).isEqualTo("Alpha")
        assertThat(song.source).isEqualTo(MusicSource.CLOUD)
        assertThat(song.isDownloaded).isFalse()
    }

    @Test fun `a song trashed in Drive leaves the other phone, while the uploading phone keeps its own`() = runTest {
        val a = Phone()
        val track = a.download("Alpha", flac("Alpha"))
        a.save(track)
        val b = Phone()
        b.scanSaved()
        drive.trash(drive.files().single().id)

        b.scanner().scan(b.uploadsFolder().id, full = true)
        a.scanner().scan(a.uploadsFolder().id, full = true)

        assertThat(b.trackCount()).isEqualTo(0)
        // Never a local song: only its link goes.
        assertThat(a.dao.getTrack(track)).isNotNull()
        assertThat(a.dao.getItemForTrack(track)).isNull()
    }

    @Test fun `the saved-songs listing pages through every file and skips Stash's folders and others' files`() = runTest {
        val folder = drive.newId("0D")
        repeat(GoogleDriveProvider.UPLOADS_PAGE_SIZE + 5) { i ->
            drive.addFile("s$i.flac", folder, flac("Song $i", size = 10), mapOf(GoogleDriveProvider.UPLOAD_MARKER_KEY to "1"))
        }
        drive.addFile("mine.flac", folder, flac("Not Stash's", size = 10)) // no marker: never listed
        val b = Phone()

        val r = b.scanSaved()

        assertThat(r.added).isEqualTo(GoogleDriveProvider.UPLOADS_PAGE_SIZE + 5)
        assertThat(drive.requests.count { it.url.encodedPath == "/drive/v3/files" && it.method == "GET" }).isEqualTo(2)
    }

    @Test fun `a token for another Google account lists nothing away`() = runTest {
        val a = Phone()
        a.save(a.download("Alpha", flac("Alpha")))
        val b = Phone()
        b.scanSaved()
        drive.permissionId = "someone-else"
        drive.validTokens += "tok2"
        // b's token now answers for another account: the listing must not sweep b's songs.
        b.store.save(CloudTokenManager.keyFor(CloudProvider.GOOGLE_DRIVE, b.accountId), ServiceToken("tok2", "ron@example.com", 1_000 + 3_000))
        assertThrows(com.stash.data.cloud.provider.CloudAuthRequiredException::class.java) {
            runBlocking { b.scanner().scan(b.uploadsFolder().id, full = true) }
        }
        assertThat(b.trackCount()).isEqualTo(1)
    }

    @Test fun `a reinstall or second phone reuses the copy already in Drive instead of uploading again`() = runTest {
        val bytes = flac("Alpha")
        val a = Phone()
        val first = a.save(a.download("Alpha", bytes)) as UploadResult.Saved
        val starts = drive.uploadStarts().size
        val b = Phone()
        val bTrack = b.download("Alpha", bytes)

        val second = b.save(bTrack) as UploadResult.Saved

        assertThat(second.reused).isTrue()
        assertThat(second.fileId).isEqualTo(first.fileId)
        assertThat(drive.uploadStarts()).hasSize(starts)
        assertThat(drive.files()).hasSize(1)
        // Linked on b too: b's scan won't add it as a second song.
        assertThat(b.scanSaved().added).isEqualTo(0)
        assertThat(b.trackCount()).isEqualTo(1)
    }

    @Test fun `the same song with different bytes is uploaded, not matched`() = runTest {
        val a = Phone()
        a.save(a.download("Alpha", flac("Alpha")))
        val b = Phone()

        val r = b.save(b.download("Alpha", flac("Alpha", size = 600_001))) as UploadResult.Saved

        assertThat(r.reused).isFalse()
        assertThat(drive.files()).hasSize(2)
    }

    @Test fun `folders are made once, then remembered`() = runTest {
        val phone = Phone()
        phone.save(phone.download("Alpha", flac("Alpha")))
        phone.save(phone.download("Bravo", flac("Bravo")))

        assertThat(drive.folders("Stash")).hasSize(1)
        assertThat(drive.folders("album")).hasSize(1)
        assertThat(drive.files().map { it.parents.single() }.toSet()).hasSize(1)
        assertThat(phone.folderCache.map.keys).containsExactly(
            "${phone.accountId}:Stash", "${phone.accountId}:Stash/artist", "${phone.accountId}:Stash/artist/album",
        )
    }

    @Test fun `a remembered folder that was trashed is made again`() = runTest {
        val phone = Phone(verifyFoldersEvery = 0)
        phone.save(phone.download("Alpha", flac("Alpha")))
        val oldStash = drive.folders("Stash").single()
        drive.trash(oldStash.id)

        val r = phone.save(phone.download("Bravo", flac("Bravo"))) as UploadResult.Saved

        val live = drive.folders("Stash").single { !it.trashed }
        assertThat(live.id).isNotEqualTo(oldStash.id)
        val album = drive.entries.getValue(drive.entries.getValue(r.fileId).parents.single())
        assertThat(album.trashed).isFalse()
        assertThat(drive.entries.getValue(album.parents.single()).parents).containsExactly(live.id)
    }

    @Test fun `a second phone files into the Stash tree the first one made`() = runTest {
        val a = Phone()
        a.save(a.download("Alpha", flac("Alpha")))
        val b = Phone()
        b.save(b.download("Bravo", flac("Bravo")))
        assertThat(drive.folders("Stash")).hasSize(1)
        assertThat(drive.folders("album")).hasSize(1)
    }

    @Test fun `a saved session resumes from what Drive committed`() = runTest {
        val phone = Phone()
        val bytes = flac("Alpha")
        val track = phone.download("Alpha", bytes)
        val row = phone.queued(track)
        // A first run sent one piece, then the process died.
        drive.putFaults.clear()
        val uri = phone.api.startSession(phone.accountId, "root", "alpha.flac", "audio/flac", bytes.size.toLong(), "k")
        val session = drive.sessions.values.single()
        System.arraycopy(bytes, 0, session.data, 0, ResumableUpload.QUANTUM)
        session.committed = ResumableUpload.QUANTUM.toLong()
        phone.dao.setUploadSession(track, uri, bytes.size.toLong(), FakeDriveStore.md5(bytes), 1)
        drive.requests.clear()

        val r = phone.uploader.upload(phone.dao.getUpload(track)!!) as UploadResult.Saved

        assertThat(drive.uploadStarts()).isEmpty()
        assertThat(drive.pieces().first().header("Content-Range")).startsWith("bytes 262144-")
        assertThat(drive.entries.getValue(r.fileId).bytes).isEqualTo(bytes)
        assertThat(row.trackId).isEqualTo(track)
    }

    @Test fun `a session for other bytes or one Drive forgot is replaced by a new upload`() = runTest {
        val phone = Phone()
        val bytes = flac("Alpha")
        val track = phone.download("Alpha", bytes)
        phone.queued(track)
        val uri = phone.api.startSession(phone.accountId, "root", "alpha.flac", "audio/flac", bytes.size.toLong(), "k")
        drive.sessions.values.single().expired = true
        phone.dao.setUploadSession(track, uri, bytes.size.toLong(), FakeDriveStore.md5(bytes), 1)

        val r = phone.uploader.upload(phone.dao.getUpload(track)!!) as UploadResult.Saved

        assertThat(drive.uploadStarts()).hasSize(2)
        assertThat(drive.entries.getValue(r.fileId).bytes).isEqualTo(bytes)
    }

    @Test fun `a copy that arrives different is deleted and the try fails`() = runTest {
        val phone = Phone()
        drive.corruptNext = true
        assertThrows(com.stash.data.cloud.provider.CloudApiException::class.java) {
            runBlocking { phone.save(phone.download("Alpha", flac("Alpha"))) }
        }
        assertThat(drive.files()).isEmpty()
    }

    @Test fun `a song whose download is gone is skipped`() = runTest {
        val phone = Phone()
        val track = phone.download("Alpha", flac("Alpha"))
        phone.files.files.clear()
        assertThat(phone.save(track)).isEqualTo(UploadResult.Skipped(DriveUploader.FILE_GONE))
        phone.db.openHelper.writableDatabase.execSQL("UPDATE tracks SET is_downloaded = 0 WHERE id = $track")
        assertThat(phone.save(track)).isEqualTo(UploadResult.Skipped(DriveUploader.NOT_DOWNLOADED))
        assertThat(drive.uploadStarts()).isEmpty()
    }

    @Test fun `a full Drive is said, not retried`() = runTest {
        val phone = Phone()
        drive.storageFull = true
        assertThrows(DriveFullException::class.java) {
            runBlocking { phone.save(phone.download("Alpha", flac("Alpha"))) }
        }
    }

    @Test fun `a scan that filed the upload before it was linked gives way to the local song`() = runTest {
        val phone = Phone()
        val track = phone.download("Alpha", flac("Alpha"))
        val folder = phone.uploadsFolder()
        // The scan got there first and made a stream-only Drive song for the file.
        val dup = phone.dao.insertScanned(
            TrackEntity(title = "Alpha", artist = "Artist", source = MusicSource.CLOUD),
            item(phone, folder.id, "1Fdup"),
        )

        val linked = phone.dao.linkUpload(track, item(phone, folder.id, "1Fdup"))

        assertThat(linked).isTrue()
        assertThat(phone.dao.getTrack(dup)).isNull()
        assertThat(phone.dao.getItem(phone.accountId, "1Fdup")!!.trackId).isEqualTo(track)
        assertThat(phone.trackCount()).isEqualTo(1)
    }

    @Test fun `after a disconnect and reconnect the scan links this phone's saved songs again instead of adding them twice`() = runTest {
        val phone = Phone()
        val track = phone.download("Alpha", flac("Alpha"))
        phone.save(track)
        phone.dao.removeAccount(phone.accountId)
        assertThat(phone.dao.getItemForTrack(track)).isNull()
        // Reconnected: a new account row, the same Google account.
        val again = phone.dao.insertAccount(CloudAccountEntity(provider = "gdrive", remoteId = "perm-123", displayName = "Ron", email = "ron@example.com", connectedAt = 1))
        phone.store.save(CloudTokenManager.keyFor(CloudProvider.GOOGLE_DRIVE, again), ServiceToken("tok1", "ron@example.com", 1_000 + 3_000))
        val folder = phone.dao.driveFolderRow(again, GoogleDriveProvider.UPLOADS_ITEM_ID, 0)
        drive.requests.clear()

        val r = phone.scanner().scan(folder.id)!!

        assertThat(r.added).isEqualTo(0)
        assertThat(phone.trackCount()).isEqualTo(1)
        assertThat(phone.dao.getItemForTrack(track)!!.accountId).isEqualTo(again)
        // Known by its tag: no bytes read for it.
        assertThat(drive.requests.none { it.url.queryParameter("alt") == "media" }).isTrue()
    }

    @Test fun `a phone that already downloaded the same song links another phone's copy to it`() = runTest {
        val a = Phone()
        a.save(a.download("Alpha", flac("Alpha"), isrc = "USABC1234567"))
        val b = Phone()
        // b downloaded the same recording on its own (other bytes, same ISRC).
        val bTrack = b.download("Alpha", flac("Alpha", size = 700_000), isrc = "USABC1234567")

        val r = b.scanSaved()

        assertThat(r.added).isEqualTo(0)
        assertThat(b.trackCount()).isEqualTo(1)
        assertThat(b.dao.getItemForTrack(bTrack)!!.itemId).isEqualTo(drive.files().single().id)
    }

    @Test fun `a Drive song someone already used is never deleted to make room for a link`() = runTest {
        val phone = Phone()
        val track = phone.download("Alpha", flac("Alpha"))
        val folder = phone.uploadsFolder()
        val dup = phone.dao.insertScanned(TrackEntity(title = "Alpha", artist = "Artist", source = MusicSource.CLOUD), item(phone, folder.id, "1Fdup"))
        phone.db.openHelper.writableDatabase.execSQL("UPDATE tracks SET stash_liked_at = 5 WHERE id = $dup")

        assertThat(phone.dao.linkUpload(track, item(phone, folder.id, "1Fdup"))).isFalse()
        assertThat(phone.dao.getTrack(dup)).isNotNull()
    }

    @Test fun `a downloaded Drive song is never removed to make room for a link`() = runTest {
        val phone = Phone()
        val track = phone.download("Alpha", flac("Alpha"))
        val folder = phone.uploadsFolder()
        val other = phone.dao.insertScanned(
            TrackEntity(title = "Alpha", artist = "Artist", source = MusicSource.CLOUD, isDownloaded = true, filePath = "/x.flac"),
            item(phone, folder.id, "1Fother"),
        )
        assertThat(phone.dao.linkUpload(track, item(phone, folder.id, "1Fother"))).isFalse()
        assertThat(phone.dao.getTrack(other)).isNotNull()
    }

    @Test fun `queueing counts only new rows and keeps an upload already queued`() = runTest {
        val phone = Phone()
        val track = phone.download("Alpha", flac("Alpha"))
        assertThat(phone.dao.queueUploads(listOf(track), phone.accountId, "MANUAL", 1)).isEqualTo(1)
        assertThat(phone.dao.queueUploads(listOf(track), phone.accountId, "MANUAL", 2)).isEqualTo(0)
        phone.dao.finishUpload(track, CloudUploadState.DONE.name, "1Fx", null, 3)
        // Saving it again re-checks it (a copy still in Drive is only linked again).
        assertThat(phone.dao.queueUploads(listOf(track), phone.accountId, "MANUAL", 4)).isEqualTo(1)
        assertThat(phone.dao.getUpload(track)!!.state).isEqualTo(CloudUploadState.QUEUED.name)
        assertThat(phone.dao.getUpload(track)!!.driveFileId).isEqualTo("1Fx")
    }

    private fun item(phone: Phone, folderId: Long, id: String) = CloudItemEntity(
        trackId = 0, accountId = phone.accountId, folderId = folderId, itemId = id, name = "alpha.flac",
        relativePath = "", parentItemId = null, cTag = "m", eTag = null, size = 1, mimeType = null,
        seenGeneration = 0, scannedAt = 0,
    )
}

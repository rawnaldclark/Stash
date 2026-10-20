package com.stash.data.cloud.scan

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.dao.CloudDao
import com.stash.core.data.db.entity.CloudAccountEntity
import com.stash.core.data.db.entity.CloudFolderEntity
import com.stash.core.data.db.entity.CloudScanStatus
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.CloudProvider
import com.stash.core.model.CloudRef
import com.stash.core.model.MusicSource
import com.stash.data.cloud.auth.ProviderAuth
import com.stash.data.cloud.net.RequestPriority
import com.stash.data.cloud.playback.CloudLinkResolver
import com.stash.data.cloud.provider.CloudEntry
import com.stash.data.cloud.provider.CloudLink
import com.stash.data.cloud.provider.CloudStorageProvider
import com.stash.data.cloud.provider.CursorExpiredException
import com.stash.data.cloud.provider.ScanEntry
import com.stash.data.cloud.provider.ScanPage
import com.stash.data.cloud.provider.ScanRoot
import com.stash.data.cloud.tags.Fixtures
import com.stash.data.cloud.tags.Fixtures.ascii
import com.stash.data.cloud.tags.Fixtures.flacBlock
import com.stash.data.cloud.tags.Fixtures.streamInfo
import com.stash.data.cloud.tags.Fixtures.vorbisComments
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [CloudScanner] against an in-memory database and a scripted provider: full pass, incremental
 * pass (unchanged files cost no reads, changed ones re-read, deletions by path and by id), an
 * expired cursor, sibling covers on a later page, OneDrive-style parent ids.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CloudScannerTest {

    private lateinit var db: StashDatabase
    private lateinit var dao: CloudDao
    private var accountId = 0L
    private var folderId = 0L

    /** url → bytes, and how many ranged reads each url got. */
    private val files = mutableMapOf<String, ByteArray>()
    private val reads = mutableMapOf<String, Int>()
    private val savedArt = mutableListOf<Int>()

    private val provider = ScriptedProvider()

    @Before fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.cloudDao()
        accountId = dao.insertAccount(CloudAccountEntity(provider = "dropbox", remoteId = "dbid:1", displayName = "Me", connectedAt = 0))
        folderId = dao.insertFolder(CloudFolderEntity(accountId = accountId, itemId = "id:root", name = "Music", path = "/Music", addedAt = 0))
    }

    @After fun tearDown() = db.close()

    private fun scanner() = CloudScanner(
        dao = dao,
        providers = mapOf(CloudProvider.DROPBOX to provider),
        links = CloudLinkResolver(mapOf(CloudProvider.DROPBOX to provider)),
        ranges = { link, offset, length ->
            val url = link.url
            reads[url] = (reads[url] ?: 0) + 1
            val b = files.getValue(url)
            b.copyOfRange(offset.toInt(), minOf(b.size.toLong(), offset + length).toInt())
        },
        art = { bytes, _ -> savedArt += bytes.size; "/art/${bytes.size}.jpg" },
        clock = { 1_000 },
    )

    private fun flac(title: String, artist: String, album: String) = Fixtures.bytes {
        ascii("fLaC")
        write(flacBlock(0, streamInfo(44100, 16, 2, 44100L * 180), last = false))
        write(flacBlock(4, vorbisComments("TITLE=$title", "ARTIST=$artist", "ALBUM=$album"), last = true))
        write(ByteArray(2000))
    }

    private fun file(id: String, name: String, rev: String, bytes: ByteArray, folders: List<String>, parentId: String? = null) =
        ScanEntry.File(
            id = id, name = name, size = bytes.size.toLong(), version = rev, eTag = null, mimeType = null,
            parentId = parentId, folders = folders, remotePath = ("/music/" + (folders + name).joinToString("/")).lowercase(),
        ).also { files["https://dl/$id"] = bytes }

    private suspend fun tracks(): List<TrackEntity> = dao.accountTrackIds(accountId).mapNotNull { db.trackDao().getById(it) }

    @Test
    fun `full pass files tagged songs, falls back to folders, waits for a later cover`() = runTest {
        val a = file("id:a", "01 Song A.flac", "r1", flac("Song A", "Band", "Record"), listOf("Band", "Record"))
        val untagged = file("id:b", "02 - Quiet Track.mp3", "r1", ByteArray(500), listOf("Other Band", "Live"))
        val cover = file("id:c", "cover.jpg", "r1", Fixtures.JPEG, listOf("Other Band", "Live"))
        provider.pages = mutableListOf(
            ScanPage(listOf(a, untagged, file("id:t", "notes.txt", "r1", ByteArray(3), emptyList())), "c1", hasMore = true),
            ScanPage(listOf(cover), "c2", hasMore = false),
        )

        val result = scanner().scan(folderId)!!

        assertThat(result.added).isEqualTo(2)
        val byTitle = tracks().associateBy { it.title }
        val songA = byTitle.getValue("Song A")
        assertThat(songA.source).isEqualTo(MusicSource.CLOUD)
        assertThat(songA.artist).isEqualTo("Band")
        assertThat(songA.fileFormat).isEqualTo("flac")
        assertThat(songA.durationMs).isEqualTo(180_000L)
        assertThat(songA.isDownloaded).isFalse()
        assertThat(songA.canonicalTitle).isEmpty() // never matched by words
        assertThat(CloudRef.parse(songA.cloudUri)).isEqualTo(CloudRef(CloudProvider.DROPBOX, accountId, "id:a", "r1"))
        val quiet = byTitle.getValue("Quiet Track")
        assertThat(quiet.artist).isEqualTo("Other Band")
        assertThat(quiet.album).isEqualTo("Live")
        assertThat(quiet.albumArtPath).isEqualTo("/art/${Fixtures.JPEG.size}.jpg")

        val folder = dao.getFolder(folderId)!!
        assertThat(folder.scanCursor).isEqualTo("c2")
        assertThat(folder.cursorIsDelta).isTrue()
        assertThat(folder.fullScan).isFalse()
        assertThat(folder.lastScanStatus).isEqualTo(CloudScanStatus.OK.name)
        assertThat(provider.cursorsAsked).containsExactly(null, "c1").inOrder()
    }

    @Test
    fun `a folder's cover is fetched once and reaches its songs on later pages too`() = runTest {
        val cover = file("id:c", "folder.png", "r1", Fixtures.PNG, listOf("A", "B"))
        val one = file("id:1", "1.mp3", "r1", ByteArray(400), listOf("A", "B"))
        val two = file("id:2", "2.mp3", "r1", ByteArray(400), listOf("A", "B"))
        provider.pages = mutableListOf(
            ScanPage(listOf(cover, one), "c1", hasMore = true),
            ScanPage(listOf(two), "c2", hasMore = false),
        )

        scanner().scan(folderId)

        assertThat(tracks().map { it.albumArtPath }.toSet()).containsExactly("/art/${Fixtures.PNG.size}.jpg")
        assertThat(reads["https://dl/id:c"]).isEqualTo(1)
    }

    @Test
    fun `incremental pass reads only what changed and removes deletions`() = runTest {
        val a = file("id:a", "a.flac", "r1", flac("A", "X", "Y"), listOf("X", "Y"))
        val b = file("id:b", "b.flac", "r1", flac("B", "X", "Y"), listOf("X", "Y"))
        val c = file("id:c", "c.flac", "r1", flac("C", "X", "Y"), listOf("X", "Y"))
        provider.pages = mutableListOf(ScanPage(listOf(a, b, c), "d1", hasMore = false))
        scanner().scan(folderId)
        // c was downloaded meanwhile.
        val cId = dao.getItem(accountId, "id:c")!!.trackId
        db.trackDao().getById(cId)!!.let { db.trackDao().update(it.copy(isDownloaded = true, filePath = "/f/c.flac")) }
        reads.clear()

        val bChanged = file("id:b", "b.flac", "r2", flac("B (Remaster)", "X", "Y"), listOf("X", "Y"))
        provider.pages = mutableListOf(
            ScanPage(
                listOf(bChanged, ScanEntry.Removed(null, "/music/x/y/c.flac"), ScanEntry.Removed(null, "/music/x/y/a.flac")),
                "d2", hasMore = false,
            ),
        )
        val result = scanner().scan(folderId)!!

        assertThat(provider.cursorsAsked.last()).isEqualTo("d1")
        assertThat(reads.keys).containsExactly("https://dl/id:b")
        assertThat(result.unchanged).isEqualTo(0)
        assertThat(result.updated).isEqualTo(1)
        assertThat(result.removed).isEqualTo(2)
        val bRow = db.trackDao().getById(dao.getItem(accountId, "id:b")!!.trackId)!!
        assertThat(bRow.title).isEqualTo("B (Remaster)")
        assertThat(CloudRef.parse(bRow.cloudUri)!!.version).isEqualTo("r2")
        // The downloaded song outlives its cloud file as a local file.
        assertThat(db.trackDao().getById(cId)!!.source).isEqualTo(MusicSource.LOCAL)
        assertThat(dao.getItem(accountId, "id:a")).isNull()
    }

    @Test
    fun `an expired cursor restarts a full pass that drops what's gone`() = runTest {
        provider.pages = mutableListOf(
            ScanPage(listOf(file("id:a", "a.flac", "r1", flac("A", "X", "Y"), emptyList()), file("id:b", "b.flac", "r1", flac("B", "X", "Y"), emptyList())), "d1", false),
        )
        scanner().scan(folderId)
        val genBefore = dao.getFolder(folderId)!!.scanGeneration

        provider.expireNext = true
        provider.pages = mutableListOf(ScanPage(listOf(file("id:a", "a.flac", "r1", flac("A", "X", "Y"), emptyList())), "d9", false))
        val result = scanner().scan(folderId)!!

        assertThat(dao.getFolder(folderId)!!.scanGeneration).isEqualTo(genBefore + 1)
        assertThat(result.unchanged).isEqualTo(1)
        assertThat(result.removed).isEqualTo(1)
        assertThat(dao.getItem(accountId, "id:b")).isNull()
    }

    @Test
    fun `onedrive style parent ids resolve through listed folders and describeFolder`() = runTest {
        provider.described["p:unknown"] = CloudEntry("p:unknown", "Disc Box", true, "/Music/Artist/Disc Box")
        val listedFolders = listOf(
            ScanEntry.Folder("id:root", "Music", "drive-root", null),
            ScanEntry.Folder("p:artist", "Artist", "id:root", null),
            ScanEntry.Folder("p:album", "Album", "p:artist", null),
        )
        val inAlbum = ScanEntry.File("f:1", "01 One.mp3", 400, "c1", null, null, "p:album", null, null).also { files["https://dl/f:1"] = ByteArray(400) }
        val inUnknown = ScanEntry.File("f:2", "02 Two.mp3", 400, "c1", null, null, "p:unknown", null, null).also { files["https://dl/f:2"] = ByteArray(400) }
        provider.pages = mutableListOf(ScanPage(listedFolders + inAlbum + inUnknown, "dl", false))

        scanner().scan(folderId)

        val byTitle = tracks().associateBy { it.title }
        assertThat(byTitle.getValue("One").album).isEqualTo("Album")
        assertThat(byTitle.getValue("One").artist).isEqualTo("Artist")
        assertThat(byTitle.getValue("Two").album).isEqualTo("Disc Box")
        assertThat(dao.getItem(accountId, "f:1")!!.relativePath).isEqualTo("Artist/Album")
    }

    @Test
    fun `a removed folder takes its songs (onedrive reports only the folder)`() = runTest {
        val f = ScanEntry.File("f:1", "x.mp3", 400, "c1", null, null, "p:album", listOf("A"), null).also { files["https://dl/f:1"] = ByteArray(400) }
        provider.pages = mutableListOf(ScanPage(listOf(f), "d1", false))
        scanner().scan(folderId)
        provider.pages = mutableListOf(ScanPage(listOf(ScanEntry.Removed("p:album", null)), "d2", false))
        assertThat(scanner().scan(folderId)!!.removed).isEqualTo(1)
        assertThat(tracks()).isEmpty()
    }

    // ── Renames and moves keep the song (and its likes) ──────────────────

    /** One liked song at /Music/Old/a.flac (Dropbox style: paths, no parent ids). */
    private suspend fun likedSongInOld(): Long {
        provider.pages = mutableListOf(ScanPage(listOf(file("id:a", "a.flac", "r1", flac("A", "X", "Y"), listOf("Old"))), "d1", false))
        scanner().scan(folderId)
        val id = dao.getItem(accountId, "id:a")!!.trackId
        db.trackDao().getById(id)!!.let { db.trackDao().update(it.copy(stashLikedAt = 42)) }
        return id
    }

    private fun movedToNew() = file("id:a", "a.flac", "r1", flac("A", "X", "Y"), listOf("New"))

    private suspend fun assertKeptAt(id: Long, folder: String) {
        val item = dao.getItem(accountId, "id:a")!!
        assertThat(item.trackId).isEqualTo(id)
        assertThat(item.remotePath).isEqualTo("/music/${folder.lowercase()}/a.flac")
        assertThat(item.relativePath).isEqualTo(folder)
        assertThat(db.trackDao().getById(id)!!.stashLikedAt).isEqualTo(42L)
        assertThat(tracks()).hasSize(1)
    }

    @Test
    fun `dropbox folder rename, deletion listed first`() = runTest {
        val id = likedSongInOld()
        provider.pages = mutableListOf(ScanPage(listOf(ScanEntry.Removed(null, "/music/old"), movedToNew()), "d2", false))
        assertThat(scanner().scan(folderId)!!.removed).isEqualTo(0)
        assertKeptAt(id, "New")
    }

    @Test
    fun `dropbox folder rename, files listed first`() = runTest {
        val id = likedSongInOld()
        provider.pages = mutableListOf(ScanPage(listOf(movedToNew(), ScanEntry.Removed(null, "/music/old")), "d2", false))
        scanner().scan(folderId)
        assertKeptAt(id, "New")
    }

    @Test
    fun `dropbox folder rename split across pages`() = runTest {
        val id = likedSongInOld()
        provider.pages = mutableListOf(
            ScanPage(listOf(ScanEntry.Removed(null, "/music/old")), "p1", true),
            ScanPage(listOf(movedToNew()), "d2", false),
        )
        scanner().scan(folderId)
        assertKeptAt(id, "New")
    }

    @Test
    fun `dropbox rename in one scan, an unrelated later delete of the old path takes nothing`() = runTest {
        val id = likedSongInOld()
        provider.pages = mutableListOf(ScanPage(listOf(movedToNew()), "d2", false))
        scanner().scan(folderId)
        provider.pages = mutableListOf(ScanPage(listOf(ScanEntry.Removed(null, "/music/old")), "d3", false))
        assertThat(scanner().scan(folderId)!!.removed).isEqualTo(0)
        assertKeptAt(id, "New")
    }

    private fun oneDriveFile(parent: String, version: String = "c1") =
        ScanEntry.File("f:1", "x.mp3", 400, version, null, null, parent, listOf(parent.removePrefix("p:")), null)
            .also { files["https://dl/f:1"] = ByteArray(400) }

    @Test
    fun `onedrive move then delete of the old folder in a later scan keeps the song`() = runTest {
        provider.pages = mutableListOf(ScanPage(listOf(oneDriveFile("p:old")), "d1", false))
        scanner().scan(folderId)
        val id = dao.getItem(accountId, "f:1")!!.trackId

        provider.pages = mutableListOf(ScanPage(listOf(oneDriveFile("p:new")), "d2", false))
        scanner().scan(folderId)
        assertThat(dao.getItem(accountId, "f:1")!!.parentItemId).isEqualTo("p:new")

        provider.pages = mutableListOf(ScanPage(listOf(ScanEntry.Removed("p:old", null)), "d3", false))
        assertThat(scanner().scan(folderId)!!.removed).isEqualTo(0)
        assertThat(dao.getItem(accountId, "f:1")!!.trackId).isEqualTo(id)
    }

    @Test
    fun `onedrive move and old-folder delete in one page, either order, keep the song`() = runTest {
        provider.pages = mutableListOf(ScanPage(listOf(oneDriveFile("p:old")), "d1", false))
        scanner().scan(folderId)
        val id = dao.getItem(accountId, "f:1")!!.trackId

        provider.pages = mutableListOf(ScanPage(listOf(ScanEntry.Removed("p:old", null), oneDriveFile("p:new")), "d2", false))
        scanner().scan(folderId)
        provider.pages = mutableListOf(ScanPage(listOf(oneDriveFile("p:old2"), ScanEntry.Removed("p:new", null)), "d3", false))
        scanner().scan(folderId)

        assertThat(dao.getItem(accountId, "f:1")!!.trackId).isEqualTo(id)
        assertThat(dao.getItem(accountId, "f:1")!!.parentItemId).isEqualTo("p:old2")
    }

    // ── One bad file never stalls the folder ─────────────────────────────

    @Test
    fun `a file whose link can't be had is filed by its path and the cursor moves on`() = runTest {
        val good = file("id:g", "01 Good.flac", "r1", flac("Good", "X", "Y"), listOf("Band", "Album"))
        val bad = ScanEntry.File("id:bad", "02 Broken.mp3", 500, "r1", null, null, null, listOf("Band", "Album"), "/music/band/album/02 broken.mp3")
        provider.failLinkFor += "id:bad"
        provider.pages = mutableListOf(ScanPage(listOf(bad, good), "d1", false))

        val result = scanner().scan(folderId)!!

        assertThat(result.added).isEqualTo(2)
        assertThat(tracks().map { it.title }).containsExactly("Good", "Broken")
        assertThat(dao.getItem(accountId, "id:bad")!!.cTag).isNull() // read again on the next full pass
        assertThat(dao.getFolder(folderId)!!.scanCursor).isEqualTo("d1")
        assertThat(dao.getFolder(folderId)!!.lastScanStatus).isEqualTo(CloudScanStatus.OK.name)
    }

    @Test
    fun `a full rescan reads again a file whose tags failed, an incremental one doesn't`() = runTest {
        val bad = file("id:bad", "02 Broken.flac", "r1", flac("Fixed Title", "Band", "Album"), listOf("Band", "Album"))
        provider.failLinkFor += "id:bad"
        provider.pages = mutableListOf(ScanPage(listOf(bad), "d1", false))
        scanner().scan(folderId)
        assertThat(tracks().map { it.title }).containsExactly("Broken")

        provider.failLinkFor.clear()
        provider.pages = mutableListOf(ScanPage(emptyList(), "d2", false))
        scanner().scan(folderId) // incremental: nothing changed in the cloud
        assertThat(tracks().map { it.title }).containsExactly("Broken")

        provider.pages = mutableListOf(ScanPage(listOf(bad), "d3", false))
        val result = scanner().scan(folderId, full = true)!!
        assertThat(provider.cursorsAsked.last()).isNull() // a full listing, not from the saved cursor
        assertThat(result.updated).isEqualTo(1)
        assertThat(tracks().map { it.title }).containsExactly("Fixed Title")
        assertThat(dao.getItem(accountId, "id:bad")!!.cTag).isEqualTo("r1")
    }

    @Test
    fun `a connection failure stops the page without moving the cursor`() = runTest {
        val f = file("id:n", "n.flac", "r1", flac("N", "X", "Y"), emptyList())
        provider.pages = mutableListOf(ScanPage(listOf(f), "d1", false))
        val failing = CloudScanner(
            dao, mapOf(CloudProvider.DROPBOX to provider), CloudLinkResolver(mapOf(CloudProvider.DROPBOX to provider)),
            ranges = { _, _, _ -> throw java.net.UnknownHostException("offline") },
            art = { _, _ -> null },
        )
        val thrown = runCatching { failing.scan(folderId) }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(java.net.UnknownHostException::class.java)
        assertThat(dao.getFolder(folderId)!!.scanCursor).isNull()
        assertThat(tracks()).isEmpty()
    }

    // ── Two scans of one folder never run at once ────────────────────────

    @Test
    fun `concurrent scans of one folder make one song per file`() = runTest {
        val a = file("id:a", "a.flac", "r1", flac("A", "X", "Y"), emptyList())
        val b = file("id:b", "b.flac", "r1", flac("B", "X", "Y"), emptyList())
        provider.alwaysPage = ScanPage(listOf(a, b), "d1", false)
        provider.pageDelayMs = 50
        val s = scanner()

        val one = async { s.scan(folderId) }
        val two = async { s.scan(folderId) }
        one.await(); two.await()

        assertThat(tracks()).hasSize(2)
        assertThat(dao.accountTrackIds(accountId)).hasSize(2)
        assertThat(db.trackDao().getAllForIntegrityScan().count { it.source == MusicSource.CLOUD }).isEqualTo(2)
    }

    @Test
    fun `a scan stops when its folder was removed meanwhile`() = runTest {
        provider.pages = mutableListOf(ScanPage(listOf(file("id:a", "a.flac", "r1", flac("A", "X", "Y"), emptyList())), "p1", true))
        provider.onPage = { dao.removeFolder(folderId) }
        assertThat(scanner().scan(folderId)).isNull()
        assertThat(tracks()).isEmpty()
    }

    /** Hands out [pages] in order; throws [CursorExpiredException] once when [expireNext]. */
    private inner class ScriptedProvider : CloudStorageProvider {
        var pages = mutableListOf<ScanPage>()
        var alwaysPage: ScanPage? = null
        var pageDelayMs = 0L
        var onPage: suspend () -> Unit = {}
        val failLinkFor = mutableSetOf<String>()
        var expireNext = false
        val cursorsAsked = mutableListOf<String?>()
        val described = mutableMapOf<String, CloudEntry>()
        override val provider = CloudProvider.DROPBOX
        override val isConfigured = true
        override val auth: ProviderAuth get() = error("unused")
        override val linkTtlMs = 60_000L
        override suspend fun browse(accountId: Long, folderId: String?) = emptyList<CloudEntry>()
        override suspend fun scanPage(accountId: Long, root: ScanRoot, cursor: String?): ScanPage {
            cursorsAsked += cursor
            if (expireNext && cursor != null) {
                expireNext = false
                throw CursorExpiredException("reset")
            }
            if (pageDelayMs > 0) kotlinx.coroutines.delay(pageDelayMs)
            val page = alwaysPage ?: pages.removeAt(0)
            onPage()
            return page
        }
        override suspend fun describeFolder(accountId: Long, folderId: String) = described[folderId]
        override suspend fun link(accountId: Long, itemId: String, priority: RequestPriority): CloudLink {
            if (itemId in failLinkFor) throw com.stash.data.cloud.provider.CloudApiException(200, "no download link")
            return CloudLink("https://dl/$itemId", files["https://dl/$itemId"]?.size?.toLong() ?: 0, null, 0)
        }
    }
}

package com.stash.data.cloud.provider.gdrive

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.auth.model.ServiceToken
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.dao.CloudDao
import com.stash.core.data.db.entity.CloudAccountEntity
import com.stash.core.data.db.entity.CloudFolderEntity
import com.stash.core.model.CloudProvider
import com.stash.core.model.CloudRef
import com.stash.data.cloud.auth.CloudTokenManager
import com.stash.data.cloud.net.CloudHttp
import com.stash.data.cloud.net.RangeFetcher
import com.stash.data.cloud.net.RequestGate
import com.stash.data.cloud.net.RequestPriority
import com.stash.data.cloud.playback.CloudLinkResolver
import com.stash.data.cloud.provider.CloudAuthRequiredException
import com.stash.data.cloud.provider.CloudItemNotFoundException
import com.stash.data.cloud.provider.CloudUnavailableException
import com.stash.data.cloud.scan.CloudScanner
import com.stash.data.cloud.tags.Fixtures
import com.stash.data.cloud.tags.Fixtures.ascii
import com.stash.data.cloud.tags.Fixtures.flacBlock
import com.stash.data.cloud.tags.Fixtures.streamInfo
import com.stash.data.cloud.tags.Fixtures.vorbisComments
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Google Drive end to end on the JVM: picks → scan (ranged tag reads through `alt=media` with the
 * bearer header) → re-check pass (trashed, lost access, edited) → links, through the real
 * scanner, token manager and an in-memory database, against [FakeDrive].
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class GoogleDriveScanTest {
    private lateinit var db: StashDatabase
    private lateinit var dao: CloudDao
    private val drive = FakeDrive()
    private val authorizer = FakeAuthorizer()
    private val store = MemoryTokenStore()
    private val picks = InMemoryGooglePickStore()
    private lateinit var tokens: CloudTokenManager
    private val gate = RequestGate(requestsPerSecond = 1000)
    private lateinit var provider: GoogleDriveProvider
    private var accountId = 0L
    private var folderId = 0L

    @Before fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.cloudDao()
        val auth = GoogleDriveAuth(authorizer, drive.client)
        tokens = CloudTokenManager(store, mapOf(CloudProvider.GOOGLE_DRIVE to auth))
        tokens.nowSeconds = { 1_000 }
        provider = GoogleDriveProvider(
            enabled = true, auth = auth, http = CloudHttp(drive.client, gate),
            tokens = tokens, dao = dao, picks = picks, clock = { 5_000 }, pageSize = 2,
        )
        accountId = dao.insertAccount(CloudAccountEntity(provider = "gdrive", remoteId = "perm-123", displayName = "Ron", email = "ron@example.com", connectedAt = 0))
        folderId = dao.insertFolder(CloudFolderEntity(accountId = accountId, itemId = GoogleDriveProvider.PICKS_ITEM_ID, name = "Picked songs", path = "", addedAt = 0))
        store.save(CloudTokenManager.keyFor(CloudProvider.GOOGLE_DRIVE, accountId), ServiceToken("tok1", "ron@example.com", 1_000 + 3_000))

        drive.add(FakeDrive.File("1AAAAAAAAA", "a.flac", flac("Alpha", "Artist", "Album"), "md5a"))
        drive.add(FakeDrive.File("1BBBBBBBBB", "b.flac", flac("Bravo", "Artist", "Album"), "md5b"))
        drive.add(FakeDrive.File("1CCCCCCCCC", "c.flac", flac("Charlie", "Artist", "Album"), "md5c"))
    }

    @After fun tearDown() = db.close()

    private fun scanner(): CloudScanner {
        val providers = mapOf(CloudProvider.GOOGLE_DRIVE to provider)
        val ranges = RangeFetcher(drive.client)
        return CloudScanner(dao, providers, CloudLinkResolver(providers), { link, offset, length -> ranges.fetch(link.url, offset, length, link.headers) }, { _, _ -> null }, clock = { 5_000 })
    }

    private fun flac(title: String, artist: String, album: String) = Fixtures.bytes {
        ascii("fLaC")
        write(flacBlock(0, streamInfo(44100, 16, 2, 44100L * 180), last = false))
        write(flacBlock(4, vorbisComments("TITLE=$title", "ARTIST=$artist", "ALBUM=$album"), last = true))
        write(ByteArray(2000))
    }

    private suspend fun title(id: String): String? = dao.getItem(accountId, id)?.let { dao.getTrack(it.trackId)?.title }

    private suspend fun scanThreePicks() {
        picks.add(folderId, listOf("1AAAAAAAAA", "1BBBBBBBBB", "1CCCCCCCCC"))
        val r = scanner().scan(folderId)!!
        assertThat(r.added).isEqualTo(3)
    }

    @Test
    fun `picked songs are scanned through alt=media with the bearer header, never a token in a url`() = runTest {
        scanThreePicks()

        assertThat(title("1AAAAAAAAA")).isEqualTo("Alpha")
        assertThat(title("1CCCCCCCCC")).isEqualTo("Charlie")
        val track = dao.getTrack(dao.getItem(accountId, "1BBBBBBBBB")!!.trackId)!!
        assertThat(CloudRef.parse(track.cloudUri)).isEqualTo(CloudRef(CloudProvider.GOOGLE_DRIVE, accountId, "1BBBBBBBBB", "md5b"))

        assertThat(drive.mediaCalls()).isNotEmpty()
        drive.mediaCalls().forEach {
            assertThat(it.header("Authorization")).isEqualTo("Bearer tok1")
            assertThat(it.header("Range")).startsWith("bytes=")
        }
        drive.requests.forEach { assertThat(it.url.toString()).doesNotContain("tok1") }
    }

    @Test
    fun `a pass with nothing new pending becomes a full re-check, and scanned picks leave the pending list`() = runTest {
        scanThreePicks()
        drive.requests.clear()

        val r = scanner().scan(folderId)!!

        assertThat(r.unchanged).isEqualTo(3)
        assertThat(picks.pending(folderId)).isEmpty()
        // Every pick was looked at again; unchanged files' bytes were not read.
        assertThat(drive.metadataCalls().map { it.url.pathSegments.last() }.toSet()).containsExactly("1AAAAAAAAA", "1BBBBBBBBB", "1CCCCCCCCC")
        assertThat(drive.mediaCalls()).isEmpty()
    }

    @Test
    fun `the re-check removes trashed and unshared songs, keeps a downloaded copy, and re-reads an edited one`() = runTest {
        scanThreePicks()
        val downloadedTrack = dao.getItem(accountId, "1CCCCCCCCC")!!.trackId
        db.openHelper.writableDatabase.execSQL("UPDATE tracks SET is_downloaded = 1, file_path = '/music/c.flac' WHERE id = $downloadedTrack")

        drive.files.getValue("1AAAAAAAAA").apply { bytes = flac("Alpha (2026 edit)", "Artist", "Album"); md5 = "md5a2" }
        drive.files.getValue("1BBBBBBBBB").trashed = true
        drive.files.getValue("1CCCCCCCCC").gone = true

        val r = scanner().scan(folderId)!!

        assertThat(r.updated).isEqualTo(1)
        assertThat(r.removed).isEqualTo(2)
        assertThat(title("1AAAAAAAAA")).isEqualTo("Alpha (2026 edit)")
        assertThat(dao.getItem(accountId, "1BBBBBBBBB")).isNull()
        assertThat(dao.getItem(accountId, "1CCCCCCCCC")).isNull()
        // The token's account was checked before anything was removed, once for the whole pass.
        assertThat(drive.aboutCalls()).hasSize(1)
        // Same rule as the other providers: a downloaded song stays, as a local file.
        val kept = dao.getTrack(downloadedTrack)!!
        assertThat(kept.source.name).isEqualTo("LOCAL")
        assertThat(kept.cloudUri).isNull()
    }

    @Test
    fun `a token for another Google account removes nothing and asks for a reconnect`() = runTest {
        scanThreePicks()
        // A silent re-authorization landed on a different account: none of the picks are visible.
        drive.permissionId = "perm-OTHER"
        drive.files.values.forEach { it.gone = true }

        assertThrows(CloudAuthRequiredException::class.java) { kotlinx.coroutines.runBlocking { scanner().scan(folderId) } }

        assertThat(title("1AAAAAAAAA")).isEqualTo("Alpha")
        assertThat(title("1BBBBBBBBB")).isEqualTo("Bravo")
        assertThat(title("1CCCCCCCCC")).isEqualTo("Charlie")
        assertThat(tokens.hasToken(CloudProvider.GOOGLE_DRIVE, accountId)).isFalse()
    }

    @Test
    fun `add more scans only the new picks`() = runTest {
        scanThreePicks()
        scanner().scan(folderId) // settles the pending list
        drive.add(FakeDrive.File("1DDDDDDDDD", "d.flac", flac("Delta", "Artist", "Album"), "md5d"))
        picks.add(folderId, listOf("1DDDDDDDDD"))
        drive.requests.clear()

        val r = scanner().scan(folderId)!!

        assertThat(r.added).isEqualTo(1)
        assertThat(title("1DDDDDDDDD")).isEqualTo("Delta")
        assertThat(drive.metadataCalls().map { it.url.pathSegments.last() }.toSet()).containsExactly("1DDDDDDDDD")
    }

    @Test
    fun `a pick that is not audio, or is already gone, is dropped from the pending list`() = runTest {
        drive.add(FakeDrive.File("1FOLDERXXX", "Albums", ByteArray(0), "", mimeType = "application/vnd.google-apps.folder"))
        picks.add(folderId, listOf("1FOLDERXXX", "1NOSUCHFILE", "1AAAAAAAAA"))

        val r = scanner().scan(folderId)!!

        assertThat(r.added).isEqualTo(1)
        assertThat(picks.pending(folderId)).containsExactly("1AAAAAAAAA")
    }

    @Test
    fun `rate limiting during the re-check stops the pass and removes nothing`() = runTest {
        scanThreePicks()
        drive.files.getValue("1BBBBBBBBB").throttled = true
        drive.files.getValue("1CCCCCCCCC").gone = true

        // Retryable like a dropped connection: the worker pauses and carries on later.
        assertThrows(CloudUnavailableException::class.java) { kotlinx.coroutines.runBlocking { scanner().scan(folderId) } }

        assertThat(title("1CCCCCCCCC")).isEqualTo("Charlie")
        assertThat(title("1BBBBBBBBB")).isEqualTo("Bravo")
        // Every Drive caller waits it out, as with a 429 elsewhere.
        assertThat(gate.remainingBackOffMs()).isGreaterThan(0L)
    }

    @Test
    fun `a slow down while resolving a link is retryable, not a problem with the file`() = runTest {
        drive.files.getValue("1AAAAAAAAA").throttled = true

        val e = assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking { provider.link(accountId, "1AAAAAAAAA", RequestPriority.INTERACTIVE) }
        }

        assertThat(e).isInstanceOf(CloudUnavailableException::class.java)
        assertThat(CloudScanner.isConnectivityFailure(e)).isTrue()
        assertThat(gate.remainingBackOffMs()).isGreaterThan(0L)
    }

    @Test
    fun `a link carries the token only as a header, from metadata size and md5`() = runTest {
        val link = provider.link(accountId, "1AAAAAAAAA", RequestPriority.INTERACTIVE)

        assertThat(link.url).isEqualTo(GoogleDriveProvider.mediaUrl("1AAAAAAAAA"))
        assertThat(link.headers).containsExactly("Authorization", "Bearer tok1")
        assertThat(link.size).isEqualTo(drive.files.getValue("1AAAAAAAAA").bytes.size.toLong())
        assertThat(link.version).isEqualTo("md5a")
        assertThat(link.toString()).doesNotContain("tok1")
        assertThat(link.toString()).doesNotContain("googleapis")
        // The token (stored as expiring at 4 000 s) goes first: the link stops before it does.
        assertThat(link.expiresAtMs).isEqualTo((4_000 - CloudTokenManager.EXPIRY_MARGIN_SECONDS) * 1000)
    }

    @Test
    fun `a rejected token is cleared and replaced by a silent re-authorization`() = runTest {
        drive.validTokens.clear()
        drive.validTokens += "tok2"

        val link = provider.link(accountId, "1AAAAAAAAA", RequestPriority.INTERACTIVE)

        assertThat(link.headers["Authorization"]).isEqualTo("Bearer tok2")
        assertThat(authorizer.silentCalls).containsExactly("ron@example.com" to "tok1")
        assertThat(store.map.values.single().accessToken).isEqualTo("tok2")
        assertThat(store.map.values.single().refreshToken).isEqualTo("ron@example.com")
    }

    @Test
    fun `when Google wants the user again the account needs a reconnect`() = runTest {
        drive.validTokens.clear()
        authorizer.next = null

        assertThrows(CloudAuthRequiredException::class.java) {
            kotlinx.coroutines.runBlocking { provider.link(accountId, "1AAAAAAAAA", RequestPriority.INTERACTIVE) }
        }
        // The dead sign-in is forgotten, so the account card offers "Reconnect".
        assertThat(tokens.hasToken(CloudProvider.GOOGLE_DRIVE, accountId)).isFalse()
    }

    @Test
    fun `Play services busy or offline is a retry later, never a reconnect`() = runTest {
        drive.validTokens.clear()
        authorizer.failure = CloudUnavailableException("Google authorization unavailable (8)")

        assertThrows(CloudUnavailableException::class.java) {
            kotlinx.coroutines.runBlocking { provider.link(accountId, "1AAAAAAAAA", RequestPriority.INTERACTIVE) }
        }
        assertThat(tokens.hasToken(CloudProvider.GOOGLE_DRIVE, accountId)).isTrue()
    }

    @Test
    fun `a sign-in hiccup mid-scan pauses the scan instead of filing songs without tags`() = runTest {
        picks.add(folderId, listOf("1AAAAAAAAA", "1BBBBBBBBB", "1CCCCCCCCC"))
        drive.validTokens.clear()
        authorizer.failure = CloudUnavailableException("Google authorization unavailable (15)")

        assertThrows(CloudUnavailableException::class.java) { kotlinx.coroutines.runBlocking { scanner().scan(folderId) } }

        assertThat(dao.folderItemIds(folderId)).isEmpty()
        assertThat(picks.pending(folderId)).hasSize(3)
    }

    @Test
    fun `a trashed or unshared file has no link`() = runTest {
        drive.files.getValue("1BBBBBBBBB").trashed = true
        drive.files.getValue("1CCCCCCCCC").gone = true
        assertThrows(CloudItemNotFoundException::class.java) { kotlinx.coroutines.runBlocking { provider.link(accountId, "1BBBBBBBBB", RequestPriority.INTERACTIVE) } }
        assertThrows(CloudItemNotFoundException::class.java) { kotlinx.coroutines.runBlocking { provider.link(accountId, "1CCCCCCCCC", RequestPriority.INTERACTIVE) } }
    }

    @Test
    fun `nothing to browse and no folders to describe under drive file`() = runTest {
        assertThat(provider.pickedFiles).isTrue()
        assertThat(provider.browse(accountId, null)).isEmpty()
        assertThat(provider.describeFolder(accountId, "0BParent")).isNull()
    }
}

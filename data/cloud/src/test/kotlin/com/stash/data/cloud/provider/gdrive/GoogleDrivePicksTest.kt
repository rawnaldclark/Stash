package com.stash.data.cloud.provider.gdrive

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.dao.CloudDao
import com.stash.core.data.db.entity.CloudItemEntity
import com.stash.core.data.db.entity.CloudScanStatus
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.CloudProvider
import com.stash.core.model.MusicSource
import com.stash.data.cloud.auth.CloudTokenManager
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Settings flow behind "Add songs from Google Drive": what a picker result does. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class GoogleDrivePicksTest {
    private lateinit var db: StashDatabase
    private lateinit var dao: CloudDao
    private val drive = FakeDrive()
    private val store = MemoryTokenStore()
    private val pickStore = InMemoryGooglePickStore()
    private val scans = mutableListOf<Long>()
    private lateinit var picks: GoogleDrivePicks

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.cloudDao()
        val auth = GoogleDriveAuth(FakeAuthorizer(), drive.client)
        val tokens = CloudTokenManager(store, mapOf(CloudProvider.GOOGLE_DRIVE to auth))
        picks = GoogleDrivePicks(dao, tokens, auth, pickStore, scanNow = { scans += it }, clock = { 42 })
    }

    @After fun tearDown() = db.close()

    @Test
    fun `the first pick connects the account, keeps the token and queues the songs`() = runTest {
        val outcome = picks.accept(GooglePickerResult.Picked("tok1", listOf("1AAAAAAAAA", "1BBBBBBBBB")))

        outcome as GooglePickOutcome.Added
        assertThat(outcome.isNewAccount).isTrue()
        assertThat(outcome.added).isEqualTo(2)
        val account = dao.getAccounts().single()
        assertThat(account.provider).isEqualTo("gdrive")
        assertThat(account.remoteId).isEqualTo("perm-123")
        assertThat(account.email).isEqualTo("ron@example.com")
        // No refresh token on Android: the slot holds the account for silent re-authorization.
        val token = store.map.getValue(CloudTokenManager.keyFor(CloudProvider.GOOGLE_DRIVE, account.id))
        assertThat(token.accessToken).isEqualTo("tok1")
        assertThat(token.refreshToken).isEqualTo("ron@example.com")
        val folder = dao.getFolders(account.id).single { it.itemId == GoogleDriveProvider.PICKS_ITEM_ID }
        // The account's second row: songs Stash saved to this Drive, from any phone, scanned too.
        val saved = dao.getFolders(account.id).single { it.itemId == GoogleDriveProvider.UPLOADS_ITEM_ID }
        assertThat(saved.name).isEqualTo(GoogleDriveProvider.UPLOADS_NAME)
        assertThat(pickStore.pending(folder.id)).containsExactly("1AAAAAAAAA", "1BBBBBBBBB")
        assertThat(scans).containsExactly(saved.id, folder.id)
        assertThat(drive.requests.single().url.encodedPath).isEqualTo("/drive/v3/about")
    }

    @Test
    fun `add more reuses the account and folder and skips songs already in the library`() = runTest {
        picks.accept(GooglePickerResult.Picked("tok1", listOf("1AAAAAAAAA")))
        val account = dao.getAccounts().single()
        val folder = dao.getFolders(account.id).single { it.itemId == GoogleDriveProvider.PICKS_ITEM_ID }
        // 1AAAAAAAAA has been scanned into a song.
        dao.insertScanned(
            TrackEntity(title = "A", artist = "X", source = MusicSource.CLOUD),
            CloudItemEntity(
                trackId = 0, accountId = account.id, folderId = folder.id, itemId = "1AAAAAAAAA", name = "a.flac",
                relativePath = "", parentItemId = null, cTag = "md5a", eTag = null, size = 1, mimeType = null,
                seenGeneration = 1, scannedAt = 1,
            ),
        )
        pickStore.remove(folder.id, listOf("1AAAAAAAAA"))
        scans.clear()
        drive.validTokens += "tok1b"

        val outcome = picks.accept(GooglePickerResult.Picked("tok1b", listOf("1AAAAAAAAA", "1CCCCCCCCC"))) as GooglePickOutcome.Added

        assertThat(outcome.isNewAccount).isFalse()
        assertThat(outcome.added).isEqualTo(1)
        assertThat(outcome.alreadyThere).isEqualTo(1)
        assertThat(dao.getAccounts()).hasSize(1)
        assertThat(dao.getFolders(account.id)).hasSize(2)
        assertThat(pickStore.pending(folder.id)).containsExactly("1CCCCCCCCC")
        assertThat(scans).containsExactly(folder.id)
        assertThat(GoogleDrivePicks.message(outcome)).isEqualTo("Adding 1 song from Google Drive. 1 were already in your library.")
    }

    @Test
    fun `nothing new picked scans only a new account's saved songs`() = runTest {
        val outcome = picks.accept(GooglePickerResult.Picked("tok1", emptyList())) as GooglePickOutcome.Added
        assertThat(outcome.added).isEqualTo(0)
        val account = dao.getAccounts().single()
        assertThat(scans).containsExactly(dao.getFolders(account.id).single { it.itemId == GoogleDriveProvider.UPLOADS_ITEM_ID }.id)
        scans.clear()
        // A known account picking nothing starts nothing.
        picks.accept(GooglePickerResult.Picked("tok1", emptyList()))
        assertThat(scans).isEmpty()
        assertThat(GoogleDrivePicks.message(outcome)).isEqualTo("Nothing was picked.")
    }

    @Test
    fun `a second Google account gets its own row, token and folders`() = runTest {
        picks.accept(GooglePickerResult.Picked("tok1", listOf("1AAAAAAAAA")))
        val first = dao.getAccounts().single()
        drive.permissionId = "perm-456"
        drive.email = "sam@example.com"
        drive.validTokens += "tok2"

        val outcome = picks.accept(GooglePickerResult.Picked("tok2", listOf("1BBBBBBBBB"))) as GooglePickOutcome.Added

        assertThat(outcome.isNewAccount).isTrue()
        val second = dao.getAccounts().single { it.id != first.id }
        assertThat(outcome.accountId).isEqualTo(second.id)
        assertThat(outcome.label).isEqualTo("sam@example.com")
        assertThat(second.email).isEqualTo("sam@example.com")
        assertThat(store.map.getValue(CloudTokenManager.keyFor(CloudProvider.GOOGLE_DRIVE, second.id)).refreshToken).isEqualTo("sam@example.com")
        // The first account's sign-in is left alone.
        assertThat(store.map.getValue(CloudTokenManager.keyFor(CloudProvider.GOOGLE_DRIVE, first.id)).accessToken).isEqualTo("tok1")
        val picked = dao.getFolders(second.id).single { it.itemId == GoogleDriveProvider.PICKS_ITEM_ID }
        assertThat(dao.getFolders(second.id)).hasSize(2)
        assertThat(pickStore.pending(picked.id)).containsExactly("1BBBBBBBBB")
    }

    @Test
    fun `picking an account already connected adds to it instead of making a third`() = runTest {
        picks.accept(GooglePickerResult.Picked("tok1", emptyList()))
        drive.permissionId = "perm-456"
        drive.email = "sam@example.com"
        drive.validTokens += "tok2"
        picks.accept(GooglePickerResult.Picked("tok2", emptyList()))
        val sam = dao.getAccounts().single { it.email == "sam@example.com" }

        val outcome = picks.accept(GooglePickerResult.Picked("tok2", listOf("1CCCCCCCCC"))) as GooglePickOutcome.Added

        assertThat(outcome.isNewAccount).isFalse()
        assertThat(outcome.accountId).isEqualTo(sam.id)
        assertThat(dao.getAccounts()).hasSize(2)
    }

    @Test
    fun `a first account with no email still connects`() = runTest {
        drive.email = null
        val outcome = picks.accept(GooglePickerResult.Picked("tok1", listOf("1AAAAAAAAA")))
        assertThat(outcome).isInstanceOf(GooglePickOutcome.Added::class.java)
        val account = dao.getAccounts().single()
        assertThat(store.map.getValue(CloudTokenManager.keyFor(CloudProvider.GOOGLE_DRIVE, account.id)).refreshToken).isEqualTo(GoogleDriveAuth.NO_ACCOUNT)
    }

    @Test
    fun `a second account with no email is refused and stores nothing`() = runTest {
        picks.accept(GooglePickerResult.Picked("tok1", emptyList()))
        val tokens = store.map.toMap()
        scans.clear()
        drive.permissionId = "perm-456"
        drive.email = null
        drive.validTokens += "tok2"

        val outcome = picks.accept(GooglePickerResult.Picked("tok2", listOf("1BBBBBBBBB")))

        assertThat(outcome).isEqualTo(GooglePickOutcome.AccountUnidentified)
        assertThat(dao.getAccounts()).hasSize(1)
        assertThat(store.map).isEqualTo(tokens)
        assertThat(scans).isEmpty()
    }

    @Test
    fun `no second account next to one with no email`() = runTest {
        drive.email = null
        picks.accept(GooglePickerResult.Picked("tok1", emptyList()))
        drive.permissionId = "perm-456"
        drive.email = "sam@example.com"
        drive.validTokens += "tok2"

        assertThat(picks.accept(GooglePickerResult.Picked("tok2", emptyList()))).isEqualTo(GooglePickOutcome.AccountUnidentified)
        assertThat(dao.getAccounts()).hasSize(1)
    }

    @Test
    fun `a lookup that lost the email keeps the known account's email and silent sign-in`() = runTest {
        picks.accept(GooglePickerResult.Picked("tok1", emptyList()))
        drive.email = null

        picks.accept(GooglePickerResult.Picked("tok1", listOf("1AAAAAAAAA")))

        val account = dao.getAccounts().single()
        assertThat(account.email).isEqualTo("ron@example.com")
        assertThat(store.map.getValue(CloudTokenManager.keyFor(CloudProvider.GOOGLE_DRIVE, account.id)).refreshToken).isEqualTo("ron@example.com")
    }

    /** An account connected earlier, whose [itemId] row a lost sign-in turned red. */
    private suspend fun connectedWithRedRow(itemId: String): Long {
        picks.accept(GooglePickerResult.Picked("tok1", emptyList()))
        val account = dao.getAccounts().single().id
        val row = dao.getFolders(account).single { it.itemId == itemId }
        dao.updateFolder(row.copy(lastScanStatus = CloudScanStatus.ERROR.name, lastScanError = "Sign-in expired. Reconnect the account."))
        scans.clear()
        return row.id
    }

    @Test
    fun `a Reconnect scans the rows a lost sign-in left red, and only those`() = runTest {
        val red = connectedWithRedRow(GoogleDriveProvider.UPLOADS_ITEM_ID)

        picks.accept(GooglePickerResult.Picked("tok1", emptyList()))

        assertThat(scans).containsExactly(red)
    }

    @Test
    fun `a Reconnect that also picks songs scans each row once`() = runTest {
        val red = connectedWithRedRow(GoogleDriveProvider.PICKS_ITEM_ID)

        picks.accept(GooglePickerResult.Picked("tok1", listOf("1AAAAAAAAA")))

        assertThat(scans).containsExactly(red)
    }

    @Test
    fun `a pick with nothing red and nothing new starts no scan`() = runTest {
        picks.accept(GooglePickerResult.Picked("tok1", emptyList()))
        scans.clear()

        picks.accept(GooglePickerResult.Picked("tok1", emptyList()))

        assertThat(scans).isEmpty()
    }

    @Test
    fun `a cancelled or failed picker changes nothing`() = runTest {
        assertThat(picks.accept(GooglePickerResult.Cancelled)).isEqualTo(GooglePickOutcome.Cancelled)
        assertThat(picks.accept(GooglePickerResult.Failed("nope"))).isEqualTo(GooglePickOutcome.Failed("nope"))
        assertThat(dao.getAccounts()).isEmpty()
        assertThat(store.map).isEmpty()
        assertThat(drive.requests).isEmpty()
    }
}

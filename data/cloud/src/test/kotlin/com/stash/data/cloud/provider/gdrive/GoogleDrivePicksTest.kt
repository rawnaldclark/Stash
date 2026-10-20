package com.stash.data.cloud.provider.gdrive

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.dao.CloudDao
import com.stash.core.data.db.entity.CloudItemEntity
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
    fun `a cancelled or failed picker changes nothing`() = runTest {
        assertThat(picks.accept(GooglePickerResult.Cancelled)).isEqualTo(GooglePickOutcome.Cancelled)
        assertThat(picks.accept(GooglePickerResult.Failed("nope"))).isEqualTo(GooglePickOutcome.Failed("nope"))
        assertThat(dao.getAccounts()).isEmpty()
        assertThat(store.map).isEmpty()
        assertThat(drive.requests).isEmpty()
    }
}

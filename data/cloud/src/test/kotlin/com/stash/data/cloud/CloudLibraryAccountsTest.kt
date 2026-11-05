package com.stash.data.cloud

import android.content.Intent
import android.content.IntentSender
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.auth.model.ServiceToken
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.dao.CloudDao
import com.stash.core.data.db.entity.CloudAccountEntity
import com.stash.core.data.db.entity.CloudFolderEntity
import com.stash.core.data.db.entity.CloudItemEntity
import com.stash.core.data.db.entity.CloudUploadState
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.CloudProvider
import com.stash.core.model.MusicSource
import com.stash.data.cloud.auth.CloudTokenManager
import com.stash.data.cloud.provider.gdrive.FakeDrive
import com.stash.data.cloud.provider.gdrive.GoogleAuthorizer
import com.stash.data.cloud.provider.gdrive.GoogleDriveAuth
import com.stash.data.cloud.provider.gdrive.GoogleDrivePicks
import com.stash.data.cloud.provider.gdrive.GooglePickOutcome
import com.stash.data.cloud.provider.gdrive.GooglePickerResult
import com.stash.data.cloud.provider.gdrive.InMemoryGooglePickStore
import com.stash.data.cloud.provider.gdrive.MemoryTokenStore
import com.stash.data.cloud.scan.CloudScanner
import com.stash.data.cloud.upload.CloudUploads
import com.stash.data.cloud.upload.CloudUploadsTest
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The account-level calls wired together, with two Google accounts: [CloudLibrary] with the real
 * DAO, token manager and [CloudUploads]. Only the WorkManager schedulers and the scanner's lock are
 * stand-ins.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CloudLibraryAccountsTest {
    private val runner = CloudUploadsTest.FakeRunner()
    private val settings = CloudUploadsTest.MemorySettings()
    private val store = MemoryTokenStore()
    private val drive = FakeDrive()
    private val authorizer = object : GoogleAuthorizer {
        var result: GooglePickerResult = GooglePickerResult.Cancelled
        val cleared = mutableListOf<String>()
        override suspend fun silentToken(accountName: String?, stale: String?): String? = null
        override suspend fun clearToken(token: String) { cleared += token }
        override suspend fun pickerIntent(accountName: String?): IntentSender = error("not in unit tests")
        override fun pickerResult(data: Intent?): GooglePickerResult = result
    }
    private lateinit var db: StashDatabase
    private lateinit var dao: CloudDao
    private lateinit var uploads: CloudUploads
    private lateinit var library: CloudLibrary
    private var a = 0L
    private var b = 0L

    @Before fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.cloudDao()
        val config = CloudConfig("", "", "com.stash.app", googleDriveEnabled = true)
        uploads = CloudUploads(dao, settings, runner, config)
        val auth = GoogleDriveAuth(authorizer, drive.client)
        val tokens = CloudTokenManager(store, mapOf(CloudProvider.GOOGLE_DRIVE to auth))
        val pickStore = InMemoryGooglePickStore()
        val scanner = mockk<CloudScanner> {
            coEvery { withFolderLock<Any?>(any(), any()) } coAnswers { secondArg<suspend () -> Any?>().invoke() }
        }
        library = CloudLibrary(
            context = ApplicationProvider.getApplicationContext(),
            config = config,
            dao = dao,
            providers = emptyMap(),
            coordinator = mockk(relaxed = true),
            tokens = tokens,
            scheduler = mockk(relaxed = true),
            scanner = scanner,
            googleAuthorizer = authorizer,
            googlePicks = GoogleDrivePicks(dao, tokens, auth, pickStore, scanNow = {}),
            googlePickStore = pickStore,
            uploads = uploads,
        )
        a = account("a", connectedAt = 1)
        b = account("b", connectedAt = 2)
    }

    @After fun tearDown() = db.close()

    private suspend fun account(name: String, connectedAt: Long): Long {
        val id = dao.insertAccount(CloudAccountEntity(provider = "gdrive", remoteId = name, displayName = name, email = "$name@example.com", connectedAt = connectedAt))
        store.save(CloudTokenManager.keyFor(CloudProvider.GOOGLE_DRIVE, id), ServiceToken("tok-$name", "$name@example.com", Long.MAX_VALUE / 2))
        return id
    }

    private suspend fun download(title: String) = db.trackDao().insert(
        TrackEntity(title = title, artist = "A", isDownloaded = true, filePath = "/m/$title.opus", source = MusicSource.SPOTIFY, spotifyUri = "spotify:track:$title"),
    )

    /** A song [accountId]'s scan found: downloaded ones stay when the account goes. */
    private suspend fun cloudSong(accountId: Long, itemId: String, downloaded: Boolean): Long {
        val folder = dao.getFolders(accountId).firstOrNull()?.id
            ?: dao.insertFolder(CloudFolderEntity(accountId = accountId, itemId = "picked", name = "Picked songs", path = "", addedAt = 1))
        return dao.insertScanned(
            TrackEntity(
                title = itemId, artist = "A", source = MusicSource.CLOUD, isDownloaded = downloaded,
                filePath = if (downloaded) "/m/$itemId.flac" else null, cloudUri = "stash-cloud://gdrive/$accountId/$itemId?v=1",
            ),
            CloudItemEntity(
                trackId = 0, accountId = accountId, folderId = folder, itemId = itemId, name = "$itemId.flac",
                relativePath = "", parentItemId = null, cTag = "1", eTag = null, size = 1, mimeType = null,
                seenGeneration = 1, scannedAt = 1,
            ),
        )
    }

    @Test fun `disconnecting one account restarts the other's uploads`() = runTest {
        val mine = download("mine")
        uploads.save(listOf(mine), a)
        uploads.save(listOf(download("theirs")), b)
        val kicks = runner.kicks

        library.disconnect(b)

        assertThat(runner.cancels).isEqualTo(1)
        assertThat(runner.kicks).isEqualTo(kicks + 1)
        assertThat(dao.getAccount(b)).isNull()
        assertThat(dao.getUpload(mine)!!.state).isEqualTo(CloudUploadState.QUEUED.name)
        // The sign-in that stays is untouched; the one that went is forgotten everywhere.
        assertThat(store.map.keys).containsExactly(CloudTokenManager.keyFor(CloudProvider.GOOGLE_DRIVE, a))
        assertThat(authorizer.cleared).containsExactly("tok-b")
    }

    @Test fun `disconnecting one account takes only its songs, and its downloads stay as local files`() = runTest {
        val streamedA = cloudSong(a, "same-file", downloaded = false)
        val streamedB = cloudSong(b, "same-file", downloaded = false)
        val downloadedB = cloudSong(b, "kept", downloaded = true)

        val removal = library.disconnect(b)

        assertThat(removal.deletedTracks).isEqualTo(1)
        assertThat(removal.keptAsLocal).isEqualTo(1)
        // The same Drive file in two accounts is two songs: only that account's copy goes.
        assertThat(db.trackDao().getById(streamedA)).isNotNull()
        assertThat(db.trackDao().getById(streamedB)).isNull()
        assertThat(db.trackDao().getById(downloadedB)!!.source).isEqualTo(MusicSource.LOCAL)
        assertThat(dao.getFolders(a)).hasSize(1)
        assertThat(dao.getFolders(b)).isEmpty()
    }

    @Test fun `disconnecting the account saves go to hands them to the first that remains`() = runTest {
        uploads.setSaveAccount(b)
        assertThat(uploads.saveAccount.first()).isEqualTo(b)

        library.disconnect(b)

        assertThat(uploads.saveAccount.first()).isEqualTo(a)
    }

    @Test fun `disconnecting the last account leaves nothing to save to`() = runTest {
        library.disconnect(a)
        library.disconnect(b)
        assertThat(uploads.saveAccountId()).isNull()
        assertThat(uploads.saveAccount.first()).isNull()
    }

    @Test fun `a pick on a full account clears only that account and starts its uploads`() = runTest {
        // Reconnecting or adding songs to A: "ron@example.com" is what FakeDrive answers, so A's row
        // is found by this remote id.
        dao.updateAccount(dao.getAccount(a)!!.copy(remoteId = drive.permissionId))
        uploads.save(listOf(download("mine")), a)
        settings.fullAccounts.value = setOf(a, b)
        val kicks = runner.kicks
        authorizer.result = GooglePickerResult.Picked("tok1", emptyList())

        val outcome = library.completeGooglePick(null)

        assertThat(outcome).isInstanceOf(GooglePickOutcome.Added::class.java)
        assertThat(settings.fullAccounts.value).containsExactly(b)
        assertThat(runner.kicks).isEqualTo(kicks + 1)
    }

    @Test fun `a refused account starts nothing`() = runTest {
        drive.permissionId = "someone-else"
        drive.email = null
        uploads.save(listOf(download("mine")), a)
        val kicks = runner.kicks
        authorizer.result = GooglePickerResult.Picked("tok1", emptyList())

        val outcome = library.completeGooglePick(null)

        assertThat(outcome).isEqualTo(GooglePickOutcome.AccountUnidentified)
        assertThat(dao.getAccounts()).hasSize(2)
        assertThat(runner.kicks).isEqualTo(kicks)
    }
}

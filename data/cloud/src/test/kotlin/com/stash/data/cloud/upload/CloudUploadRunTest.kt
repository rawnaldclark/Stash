package com.stash.data.cloud.upload

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.auth.model.ServiceToken
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.dao.CloudDao
import com.stash.core.data.db.entity.CloudAccountEntity
import com.stash.core.data.db.entity.CloudUploadOrigin
import com.stash.core.data.db.entity.CloudUploadState
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.CloudProvider
import com.stash.core.model.MusicSource
import com.stash.data.cloud.auth.CloudTokenManager
import com.stash.data.cloud.net.CloudHttp
import com.stash.data.cloud.net.RequestGate
import com.stash.data.cloud.provider.CloudAuthRequiredException
import com.stash.data.cloud.provider.gdrive.FakeAuthorizer
import com.stash.data.cloud.provider.gdrive.GoogleDriveAuth
import com.stash.data.cloud.provider.gdrive.MemoryTokenStore
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A pass over the upload queue with two Google accounts: the real uploader, resumable protocol and
 * token manager, against one [FakeDriveStore] that tells the accounts apart by their tokens.
 *
 * The bug these pin: the pass used to end at the first signed-out or full account, so its oldest
 * song held up every other account's uploads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CloudUploadRunTest {
    private val drive = FakeDriveStore()
    private val settings = CloudUploadsTest.MemorySettings()
    private val files = MemoryAudioFiles()
    private val store = MemoryTokenStore()
    private lateinit var db: StashDatabase
    private lateinit var dao: CloudDao
    private lateinit var uploader: DriveUploader
    private var a = 0L
    private var b = 0L

    @Before fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.cloudDao()
        var now = 0L
        val gate = RequestGate(requestsPerSecond = 1000, clock = { now }, sleep = { now += it })
        val auth = GoogleDriveAuth(FakeAuthorizer(), drive.client)
        val tokens = CloudTokenManager(store, mapOf(CloudProvider.GOOGLE_DRIVE to auth)).apply { nowSeconds = { 1_000 } }
        val api = DriveUploadApi(CloudHttp(drive.client, gate), tokens)
        uploader = DriveUploader(
            dao, api, DriveFolders(api, settings, clock = { now }),
            ResumableUpload(drive.client, gate, files, chunkSize = ResumableUpload.QUANTUM), files,
            naming = { _, _, title, ext -> UploadPlace(listOf("artist"), "${title.lowercase()}.$ext") },
            clock = { 7_000 },
        )
        a = account("a", "tokA")
        b = account("b", "tokB")
        drive.validTokens += setOf("tokA", "tokB")
    }

    @After fun tearDown() = db.close()

    private suspend fun account(name: String, token: String): Long {
        val id = dao.insertAccount(CloudAccountEntity(provider = "gdrive", remoteId = name, displayName = name, email = "$name@example.com", connectedAt = id()))
        store.save(CloudTokenManager.keyFor(CloudProvider.GOOGLE_DRIVE, id), ServiceToken(token, "$name@example.com", 1_000 + 3_000))
        return id
    }

    private var nextId = 1L
    private fun id() = nextId++

    /** A download queued for [accountId], [queuedAt] ordering the queue. */
    private suspend fun queued(title: String, accountId: Long, queuedAt: Long): Long {
        val path = "/music/$title.flac"
        files.files[path] = ByteArray(1_000) { title.hashCode().toByte() }
        val id = db.trackDao().insert(
            TrackEntity(
                title = title, artist = "Artist", durationMs = 1_000, filePath = path, fileFormat = "flac",
                isDownloaded = true, source = MusicSource.SPOTIFY, spotifyUri = "spotify:track:$title",
            ),
        )
        dao.queueUploads(listOf(id), accountId, CloudUploadOrigin.MANUAL.name, queuedAt)
        return id
    }

    private fun pass() = CloudUploadRun(dao, settings, upload = { row, progress -> uploader.upload(row, progress) }, clock = { 9_000 })

    private suspend fun stateOf(trackId: Long) = dao.getUpload(trackId)!!.state

    /** Which accounts' tokens opened an upload, in order. */
    private fun uploadedWith() = drive.uploadStarts().map { it.header("Authorization")!!.removePrefix("Bearer ") }

    @Test fun `a signed-out account's songs wait and the other account's still go`() = runTest {
        // Account A has the oldest song, and it is signed out.
        store.clear(CloudTokenManager.keyFor(CloudProvider.GOOGLE_DRIVE, a))
        val fromA = queued("old-a", a, queuedAt = 1)
        val fromB1 = queued("b1", b, queuedAt = 2)
        val fromB2 = queued("b2", b, queuedAt = 3)

        val outcome = pass().run()

        assertThat(outcome).isEqualTo(CloudUploadRun.Outcome.Finished)
        assertThat(stateOf(fromA)).isEqualTo(CloudUploadState.QUEUED.name)
        assertThat(stateOf(fromB1)).isEqualTo(CloudUploadState.DONE.name)
        assertThat(stateOf(fromB2)).isEqualTo(CloudUploadState.DONE.name)
        assertThat(uploadedWith()).containsExactly("tokB", "tokB")
        // Waiting for a Reconnect isn't "Drive full": its card leads with Reconnect.
        assertThat(settings.fullAccounts.value).isEmpty()
        assertThat(dao.getUpload(fromB1)!!.driveFileId).isNotNull()
    }

    @Test fun `a full Drive pauses only its own account`() = runTest {
        drive.fullTokens += "tokA"
        val fromA1 = queued("a1", a, queuedAt = 1)
        val fromA2 = queued("a2", a, queuedAt = 2)
        val fromB = queued("b1", b, queuedAt = 3)

        val outcome = pass().run()

        assertThat(outcome).isEqualTo(CloudUploadRun.Outcome.Finished)
        assertThat(settings.fullAccounts.value).containsExactly(a)
        assertThat(stateOf(fromA1)).isEqualTo(CloudUploadState.QUEUED.name)
        assertThat(stateOf(fromA2)).isEqualTo(CloudUploadState.QUEUED.name)
        assertThat(stateOf(fromB)).isEqualTo(CloudUploadState.DONE.name)
        // Drive was asked once for A, not once per waiting song.
        assertThat(drive.uploadStarts().count { it.header("Authorization") == "Bearer tokA" }).isEqualTo(1)
    }

    @Test fun `an account already full is not asked again until the user tries again`() = runTest {
        settings.fullAccounts.value = setOf(a)
        val fromA = queued("a1", a, queuedAt = 1)
        val fromB = queued("b1", b, queuedAt = 2)

        pass().run()

        assertThat(stateOf(fromA)).isEqualTo(CloudUploadState.QUEUED.name)
        assertThat(stateOf(fromB)).isEqualTo(CloudUploadState.DONE.name)
        assertThat(uploadedWith()).containsExactly("tokB")
    }

    @Test fun `a lost connection on one account leaves the other going and asks for another run`() = runTest {
        val fromA = queued("a1", a, queuedAt = 1)
        val fromB = queued("b1", b, queuedAt = 2)
        val run = CloudUploadRun(
            dao, settings,
            upload = { row, progress -> if (row.accountId == a) throw IOException("gone") else uploader.upload(row, progress) },
            clock = { 9_000 },
        )

        assertThat(run.run()).isEqualTo(CloudUploadRun.Outcome.RetryLater)

        assertThat(stateOf(fromA)).isEqualTo(CloudUploadState.QUEUED.name)
        assertThat(stateOf(fromB)).isEqualTo(CloudUploadState.DONE.name)
    }

    @Test fun `one account behaves as before when it is signed out or full`() = runTest {
        dao.removeAccount(b)
        val one = queued("a1", a, queuedAt = 1)
        val two = queued("a2", a, queuedAt = 2)

        drive.fullTokens += "tokA"
        assertThat(pass().run()).isEqualTo(CloudUploadRun.Outcome.Finished)
        assertThat(settings.fullAccounts.value).containsExactly(a)
        assertThat(dao.countQueuedUploads(emptyList())).isEqualTo(2)

        settings.fullAccounts.value = emptySet()
        drive.fullTokens.clear()
        store.clear(CloudTokenManager.keyFor(CloudProvider.GOOGLE_DRIVE, a))
        assertThat(pass().run()).isEqualTo(CloudUploadRun.Outcome.Finished)
        assertThat(stateOf(one)).isEqualTo(CloudUploadState.QUEUED.name)
        assertThat(stateOf(two)).isEqualTo(CloudUploadState.QUEUED.name)
    }

    @Test fun `a song Drive won't take fails alone and the queue carries on`() = runTest {
        val bad = queued("bad", a, queuedAt = 1)
        val good = queued("good", a, queuedAt = 2)
        val run = CloudUploadRun(
            dao, settings,
            upload = { row, progress ->
                if (row.trackId == bad) throw com.stash.data.cloud.provider.CloudApiException(400, "no") else uploader.upload(row, progress)
            },
            clock = { 9_000 },
        )

        assertThat(run.run()).isEqualTo(CloudUploadRun.Outcome.Finished)

        assertThat(stateOf(good)).isEqualTo(CloudUploadState.DONE.name)
        assertThat(stateOf(bad)).isEqualTo(CloudUploadState.FAILED.name)
        assertThat(dao.getUpload(bad)!!.attempts).isEqualTo(CloudUploadWorker.MAX_ATTEMPTS)
    }

    @Test fun `a reconnected account's songs go on the next run`() = runTest {
        store.clear(CloudTokenManager.keyFor(CloudProvider.GOOGLE_DRIVE, a))
        val fromA = queued("a1", a, queuedAt = 1)
        pass().run()
        assertThat(stateOf(fromA)).isEqualTo(CloudUploadState.QUEUED.name)

        store.save(CloudTokenManager.keyFor(CloudProvider.GOOGLE_DRIVE, a), ServiceToken("tokA", "a@example.com", 1_000 + 3_000))
        pass().run()

        assertThat(stateOf(fromA)).isEqualTo(CloudUploadState.DONE.name)
    }

    @Test fun `the notification counts only songs that can go`() = runTest {
        store.clear(CloudTokenManager.keyFor(CloudProvider.GOOGLE_DRIVE, a))
        queued("a1", a, queuedAt = 1)
        queued("b1", b, queuedAt = 2)
        queued("b2", b, queuedAt = 3)
        val heard = mutableListOf<Int>()
        val run = CloudUploadRun(
            dao, settings,
            upload = { row, _ ->
                if (row.accountId == a) throw CloudAuthRequiredException(a, "signed out")
                UploadResult.Saved("file${row.trackId}", reused = false)
            },
            onProgress = { _, left, _, _ -> heard += left },
            clock = { 9_000 },
        )

        run.run()

        // The first announcement still counts the song that turns out to be stuck; after that, B's alone.
        assertThat(heard).containsExactly(3, 2, 1).inOrder()
    }
}

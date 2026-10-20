package com.stash.data.cloud.upload

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.dao.CloudDao
import com.stash.core.data.db.entity.CloudAccountEntity
import com.stash.core.data.db.entity.CloudUploadState
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.MusicSource
import com.stash.data.cloud.CloudConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The UI's side of saving to Drive: what a tap queues, the backup switch, and the card's line. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CloudUploadsTest {
    private lateinit var db: StashDatabase
    private lateinit var dao: CloudDao
    private val runner = FakeRunner()
    private val settings = MemorySettings()
    private lateinit var uploads: CloudUploads
    private var account = 0L

    class FakeRunner : CloudUploadRunner {
        var kicks = 0
        var cancels = 0
        override suspend fun kick() { kicks++ }
        override fun cancel() { cancels++ }
        override val running: Flow<Boolean> = flowOf(false)
        override val wifiOnly: Flow<Boolean> = flowOf(true)
    }

    class MemorySettings : CloudUploadSettings, DriveFolderCache by MemoryFolderCache() {
        override val backupAccountId = MutableStateFlow<Long?>(null)
        override suspend fun setBackupAccount(accountId: Long?) { backupAccountId.value = accountId }
        override val driveFull = MutableStateFlow(false)
        override suspend fun setDriveFull(full: Boolean) { driveFull.value = full }
    }

    @Before fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.cloudDao()
        uploads = CloudUploads(dao, settings, runner, CloudConfig("", "", "com.stash.app", googleDriveEnabled = true))
        account = dao.insertAccount(CloudAccountEntity(provider = "gdrive", remoteId = "p", displayName = "Ron", connectedAt = 0))
    }

    @After fun tearDown() = db.close()

    private suspend fun track(title: String, downloaded: Boolean = true, source: MusicSource = MusicSource.SPOTIFY) =
        db.trackDao().insert(
            TrackEntity(
                title = title, artist = "A", isDownloaded = downloaded, source = source,
                filePath = if (downloaded) "/m/$title.opus" else null, spotifyUri = "spotify:track:$title",
            ),
        )

    @Test fun `saving an album queues its downloads and counts the rest`() = runTest {
        val ids = listOf(track("a"), track("b"), track("c", downloaded = false), track("d", downloaded = false), track("e", downloaded = false))

        val r = uploads.save(ids)

        assertThat(r).isEqualTo(SaveRequest(queued = 2, alreadyQueued = 0, notDownloaded = 3))
        assertThat(CloudUploads.message(r)).isEqualTo("Saving 2 songs to Google Drive. 3 songs aren't downloaded yet.")
        assertThat(runner.kicks).isEqualTo(1)
        assertThat(dao.countQueuedUploads()).isEqualTo(2)
    }

    @Test fun `a cloud song is never uploaded`() = runTest {
        val cloud = track("x", source = MusicSource.CLOUD)
        val r = uploads.save(listOf(cloud))
        assertThat(r.queued).isEqualTo(0)
        assertThat(runner.kicks).isEqualTo(0)
    }

    @Test fun `saving again while queued is not a second upload`() = runTest {
        val a = track("a")
        uploads.save(listOf(a))
        val r = uploads.save(listOf(a))
        assertThat(r).isEqualTo(SaveRequest(queued = 0, alreadyQueued = 1, notDownloaded = 0))
        assertThat(CloudUploads.message(r)).isEqualTo("Saving 1 song to Google Drive.")
    }

    @Test fun `nothing downloaded says so plainly`() = runTest {
        val r = uploads.save(listOf(track("a", downloaded = false)))
        assertThat(CloudUploads.message(r)).isEqualTo("Download this song first to save it to Google Drive.")
        assertThat(CloudUploads.message(SaveRequest(0, 0, 4))).isEqualTo("Download these songs first to save them to Google Drive.")
    }

    @Test fun `a save after Drive was full tries again`() = runTest {
        settings.driveFull.value = true
        uploads.save(listOf(track("a")))
        assertThat(settings.driveFull.value).isFalse()
    }

    @Test fun `turning backup on counts today's downloads without sending them`() = runTest {
        track("a"); track("b"); track("c", downloaded = false)

        uploads.setBackup(account, on = true)

        val status = uploads.status(account).first()
        assertThat(status.backupOn).isTrue()
        assertThat(status.existing).isEqualTo(2)
        assertThat(status.queued).isEqualTo(0)
        assertThat(runner.kicks).isEqualTo(0)
        // What the watcher looks for: downloads with no row. None yet...
        assertThat(dao.observeDownloadsWithoutUpload().first()).isEqualTo(0)
        // ...until a new download lands.
        track("d")
        assertThat(dao.observeDownloadsWithoutUpload().first()).isEqualTo(1)
        dao.insertUploadsForDownloads(account, CloudUploadState.QUEUED.name, 5)
        assertThat(uploads.status(account).first().queued).isEqualTo(1)
    }

    @Test fun `back up existing queues them, and turning backup off unqueues only what it queued`() = runTest {
        val a = track("a"); track("b")
        uploads.setBackup(account, on = true)
        val manual = track("m")
        uploads.save(listOf(manual))

        assertThat(uploads.backUpExisting()).isEqualTo(2)
        assertThat(dao.countQueuedUploads()).isEqualTo(3)

        uploads.setBackup(account, on = false)
        assertThat(settings.backupAccountId.value).isNull()
        assertThat(dao.getUpload(a)!!.state).isEqualTo(CloudUploadState.EXISTING.name)
        assertThat(dao.getUpload(manual)!!.state).isEqualTo(CloudUploadState.QUEUED.name)
    }

    @Test fun `disconnecting stops uploads, turns backup off and drops pending rows`() = runTest {
        uploads.setBackup(account, on = true)
        uploads.save(listOf(track("a")))

        uploads.onAccountRemoved(account)
        dao.removeAccount(account)

        assertThat(runner.cancels).isEqualTo(1)
        assertThat(settings.backupAccountId.value).isNull()
        assertThat(dao.countQueuedUploads()).isEqualTo(0)
    }

    @Test fun `the card's status line`() {
        fun s(saved: Int = 0, queued: Int = 0, failed: Int = 0, full: Boolean = false, running: Boolean = false, wifi: Boolean = true, backup: Boolean = true) =
            UploadStatus(backup, saved, queued, 0, failed, full, running, wifi).line
        assertThat(s()).isNull()
        assertThat(s(saved = 124, queued = 3, running = true)).isEqualTo("Backed up 124 songs · uploading 3")
        assertThat(s(queued = 3)).isEqualTo("Waiting for Wi-Fi")
        assertThat(s(queued = 3, wifi = false)).isEqualTo("Waiting for a connection")
        assertThat(s(saved = 1, backup = false)).isEqualTo("Saved 1 song")
        assertThat(s(saved = 5, failed = 2)).isEqualTo("Backed up 5 songs · 2 couldn't be saved")
        assertThat(s(saved = 5, queued = 2, full = true)).isEqualTo("Your Google Drive is full. Free up space, then try again.")
    }

    @Test fun `the track key is stable and prefers the ISRC`() {
        val a = UploadKeys.trackKey(null, "Nina  Simone", "Sinnerman ", 600_400)
        assertThat(a).isEqualTo(UploadKeys.trackKey(null, "nina simone", "sinnerman", 600_999))
        assertThat(a).hasLength(40)
        assertThat(UploadKeys.trackKey("usabc1234567", "x", "y", 1)).isEqualTo(UploadKeys.trackKey("USABC1234567", "z", "w", 2))
        assertThat(UploadKeys.trackKey(null, "x", "y", 1_000)).isNotEqualTo(UploadKeys.trackKey(null, "x", "y", 2_000))
    }
}

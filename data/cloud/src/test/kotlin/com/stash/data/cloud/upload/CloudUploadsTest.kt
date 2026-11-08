package com.stash.data.cloud.upload

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.dao.CloudDao
import com.stash.core.data.db.entity.CloudAccountEntity
import com.stash.core.data.db.entity.CloudItemEntity
import com.stash.core.data.db.entity.CloudUploadEntity
import com.stash.core.data.db.entity.CloudUploadOrigin
import com.stash.core.data.db.entity.CloudUploadState
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.MusicSource
import com.stash.data.cloud.CloudConfig
import com.stash.data.cloud.provider.gdrive.GoogleDriveProvider
import com.stash.data.cloud.provider.gdrive.driveFolderRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
        @Volatile var kicks = 0
        @Volatile var cancels = 0

        /** Runs as the upload is stopped, to see what the queue looks like at that moment. */
        var onCancel: suspend () -> Unit = {}
        override suspend fun kick() { kicks++ }
        override suspend fun cancel() {
            cancels++
            onCancel()
        }
        override val running: Flow<Boolean> = flowOf(false)
        override val wifiOnly: Flow<Boolean> = flowOf(true)
    }

    class MemorySettings : CloudUploadSettings, DriveFolderCache by MemoryFolderCache() {
        override val backupAccountId = MutableStateFlow<Long?>(null)
        override suspend fun setBackupAccount(accountId: Long?) { backupAccountId.value = accountId }
        override val saveAccountId = MutableStateFlow<Long?>(null)
        override suspend fun setSaveAccount(accountId: Long?) { saveAccountId.value = accountId }
        override val fullAccounts = MutableStateFlow<Set<Long>>(emptySet())
        override suspend fun setDriveFull(accountId: Long, full: Boolean) {
            fullAccounts.value = if (full) fullAccounts.value + accountId else fullAccounts.value - accountId
        }
    }

    @Before fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.cloudDao()
        uploads = CloudUploads(dao, settings, runner, CloudConfig("", "", "com.stash.app", googleDriveEnabled = true))
        account = dao.insertAccount(CloudAccountEntity(provider = "gdrive", remoteId = "p", displayName = "Ron", connectedAt = 0))
    }

    @After fun tearDown() {
        uploads.stopWatching()
        db.close()
    }

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
        assertThat(dao.countQueuedUploads(emptyList())).isEqualTo(2)
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
        settings.fullAccounts.value = setOf(account)
        uploads.save(listOf(track("a")))
        assertThat(settings.fullAccounts.value).isEmpty()
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

        assertThat(uploads.backUpExisting(account)).isEqualTo(2)
        assertThat(dao.countQueuedUploads(emptyList())).isEqualTo(3)

        uploads.setBackup(account, on = false)
        assertThat(settings.backupAccountId.value).isNull()
        assertThat(dao.getUpload(a)!!.state).isEqualTo(CloudUploadState.EXISTING.name)
        assertThat(dao.getUpload(manual)!!.state).isEqualTo(CloudUploadState.QUEUED.name)
    }

    @Test fun `disconnecting stops uploads, turns backup off and drops pending rows`() = runTest {
        uploads.setBackup(account, on = true)
        uploads.save(listOf(track("a")))
        val kicks = runner.kicks
        val cancels = runner.cancels

        uploads.removingAccount(account) { dao.removeAccount(account) }

        assertThat(runner.cancels).isEqualTo(cancels + 1)
        assertThat(settings.backupAccountId.value).isNull()
        assertThat(dao.countQueuedUploads(emptyList())).isEqualTo(0)
        // Nothing left to send: no run is started for nothing.
        assertThat(runner.kicks).isEqualTo(kicks)
    }

    // ── Two Google accounts ──────────────────────────────────────────────

    /** Waits on the clock, not the test's virtual one: the watcher runs on real threads. */
    private suspend fun realTime(block: suspend () -> Unit) = withContext(Dispatchers.Default) { block() }

    private suspend fun secondAccount() =
        dao.insertAccount(CloudAccountEntity(provider = "gdrive", remoteId = "p2", displayName = "Sam", email = "sam@example.com", connectedAt = 1))

    private suspend fun rowFor(track: Long) = dao.getUpload(track)!!

    @Test fun `disconnecting one account starts the other's queue again`() = runTest {
        val second = secondAccount()
        val mine = track("a")
        uploads.save(listOf(mine), account)
        uploads.save(listOf(track("b")), second)
        val kicks = runner.kicks

        uploads.removingAccount(second) { dao.removeAccount(second) }

        // The run is stopped (it may be in the middle of the removed account's song)...
        assertThat(runner.cancels).isEqualTo(1)
        // ...and the remaining account's song doesn't wait for the next app start.
        assertThat(runner.kicks).isEqualTo(kicks + 1)
        assertThat(rowFor(mine).state).isEqualTo(CloudUploadState.QUEUED.name)
        assertThat(rowFor(mine).accountId).isEqualTo(account)
        assertThat(dao.countQueuedUploads(emptyList())).isEqualTo(1)
    }

    @Test fun `the uploads start again even when the removal fails`() = runTest {
        val second = secondAccount()
        uploads.save(listOf(track("a")), account)
        val kicks = runner.kicks

        val failed = runCatching { uploads.removingAccount(second) { error("database hiccup") } }

        assertThat(failed.isFailure).isTrue()
        assertThat(runner.kicks).isEqualTo(kicks + 1)
    }

    @Test fun `removing the backup account turns backup off and never moves it to another account`() = runTest {
        val second = secondAccount()
        uploads.setBackup(second, on = true)
        assertThat(uploads.saveAccountId()).isEqualTo(second)

        uploads.removingAccount(second) { dao.removeAccount(second) }

        assertThat(settings.backupAccountId.value).isNull()
        assertThat(settings.saveAccountId.value).isNull()
        assertThat(uploads.saveAccountId()).isEqualTo(account)
        assertThat(uploads.status(account).first().backupOn).isFalse()
    }

    @Test fun `removing another account never starts a surprise upload of what was saved to it`() = runTest {
        // The real watcher: it sees the songs without a row the moment their account goes.
        uploads.start()
        val second = secondAccount()
        val savedThere = track("saved-there")
        uploads.save(listOf(savedThere), second)
        dao.finishUpload(savedThere, CloudUploadState.DONE.name, "file", null, 2)
        uploads.setBackup(account, on = true)
        val kicks = runner.kicks

        uploads.removingAccount(second) { dao.removeAccount(second) }
        realTime { delay(WATCHER_PASS_MS) }

        // Counted on the backup account like any download from before backup, not queued.
        assertThat(rowFor(savedThere).accountId).isEqualTo(account)
        assertThat(rowFor(savedThere).state).isEqualTo(CloudUploadState.EXISTING.name)
        assertThat(dao.observeDownloadsWithoutUpload().first()).isEqualTo(0)
        assertThat(runner.kicks).isEqualTo(kicks)
        assertThat(uploads.status(account).first().existing).isEqualTo(1)

        // The watcher was awake all along: a real new download goes.
        val fresh = track("fresh")
        realTime { withTimeout(WATCHER_LIMIT_MS) { while (dao.getUpload(fresh) == null) delay(50) } }
        assertThat(rowFor(fresh).state).isEqualTo(CloudUploadState.QUEUED.name)
        assertThat(rowFor(savedThere).state).isEqualTo(CloudUploadState.EXISTING.name)
    }

    @Test fun `a removal that fails leaves backup and saves where they were`() = runTest {
        val second = secondAccount()
        uploads.setBackup(second, on = true)

        val failed = runCatching { uploads.removingAccount(second) { error("database hiccup") } }

        assertThat(failed.isFailure).isTrue()
        assertThat(settings.backupAccountId.value).isEqualTo(second)
        assertThat(settings.saveAccountId.value).isEqualTo(second)
    }

    // ── A download a scan linked to a file in one Drive lives there ──────

    /** What a scan of [accountId]'s "Saved from Stash" does when it recognises [trackId]: a link, no upload row. */
    private suspend fun scanLinked(trackId: Long, accountId: Long) {
        val folder = dao.driveFolderRow(accountId, GoogleDriveProvider.UPLOADS_ITEM_ID, 1)
        dao.linkUpload(
            trackId,
            CloudItemEntity(
                trackId = trackId, accountId = accountId, folderId = folder.id, itemId = "file$trackId", name = "f.flac",
                relativePath = "", parentItemId = null, cTag = "1", eTag = null, size = 1, mimeType = null,
                seenGeneration = 1, scannedAt = 1,
            ),
        )
    }

    @Test fun `turning backup on counts a scan-linked download where it lives`() = runTest {
        val second = secondAccount()
        val linked = track("linked")
        scanLinked(linked, account)

        uploads.setBackup(second, on = true)

        assertThat(rowFor(linked).accountId).isEqualTo(account)
        assertThat(uploads.status(second).first().existing).isEqualTo(0)
        assertThat(uploads.backUpExisting(second)).isEqualTo(0)
        assertThat(dao.countQueuedUploads(emptyList())).isEqualTo(0)
    }

    @Test fun `the watcher queues a scan-linked download where it lives`() = runTest {
        val second = secondAccount()
        uploads.setBackup(second, on = true)
        val linked = track("linked")
        scanLinked(linked, account)

        dao.insertUploadsForDownloads(second, CloudUploadState.QUEUED.name, 5)

        assertThat(rowFor(linked).accountId).isEqualTo(account)
    }

    @Test fun `saving a scan-linked download checks it where it lives, never copies it`() = runTest {
        val second = secondAccount()
        val linked = track("linked")
        scanLinked(linked, account)
        uploads.setSaveAccount(second)

        uploads.save(listOf(linked))

        assertThat(rowFor(linked).accountId).isEqualTo(account)
        assertThat(rowFor(linked).state).isEqualTo(CloudUploadState.QUEUED.name)
    }

    @Test fun `moving backup leaves a scan-linked download where it lives`() = runTest {
        val second = secondAccount()
        val linked = track("linked")
        val unlinked = track("unlinked")
        scanLinked(linked, account)
        uploads.setBackup(account, on = true)

        uploads.setSaveAccount(second)

        assertThat(rowFor(linked).accountId).isEqualTo(account)
        assertThat(rowFor(unlinked).accountId).isEqualTo(second)
        assertThat(uploads.status(second).first().existing).isEqualTo(1)
    }

    @Test fun `switching backup off and on for another account during one upload stops that upload first`() = runTest {
        val second = secondAccount()
        uploads.setBackup(account, on = true)
        val uploading = track("uploading")
        dao.insertUploadsForDownloads(account, CloudUploadState.QUEUED.name, 5)
        uploads.save(listOf(track("manual")), account)
        uploads.setBackup(account, on = false)
        uploads.setSaveAccount(second)
        var whereItWasWhenStopped: Long? = null
        runner.onCancel = { whereItWasWhenStopped = rowFor(uploading).accountId }
        val cancels = runner.cancels
        val kicks = runner.kicks

        uploads.setBackup(second, on = true)

        // The file would otherwise finish in the old Drive while its row says the new one.
        assertThat(runner.cancels).isEqualTo(cancels + 1)
        assertThat(whereItWasWhenStopped).isEqualTo(account)
        assertThat(rowFor(uploading).accountId).isEqualTo(second)
        // The hand-saved song that was waiting behind it goes on.
        assertThat(runner.kicks).isEqualTo(kicks + 1)
    }

    @Test fun `saves go to the first connected account until the user chooses`() = runTest {
        val second = secondAccount()
        val a = track("a")
        uploads.save(listOf(a))
        assertThat(rowFor(a).accountId).isEqualTo(account)

        uploads.setSaveAccount(second)

        val b = track("b")
        uploads.save(listOf(b))
        assertThat(rowFor(b).accountId).isEqualTo(second)
        assertThat(uploads.saveAccount.first()).isEqualTo(second)
    }

    @Test fun `with one account the toast says Google Drive, with two it names the account`() = runTest {
        val a = track("a")
        val one = uploads.save(listOf(a))
        assertThat(one.to).isNull()
        assertThat(CloudUploads.message(one)).isEqualTo("Saving 1 song to Google Drive.")

        val second = secondAccount()
        uploads.setSaveAccount(second)
        val two = uploads.save(listOf(track("b"), track("c")))

        assertThat(two.to).isEqualTo("sam@example.com")
        assertThat(CloudUploads.message(two)).isEqualTo("Saving 2 songs to sam@example.com.")
    }

    @Test fun `the choice goes through even when the screen that asked is gone`() = runTest {
        val second = secondAccount()
        uploads.setBackup(account, on = true)
        val job = launch {
            // Cancelled before it runs: what has started must not stop half-way.
            kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!.cancel()
            uploads.setSaveAccount(second)
        }
        job.join()

        // Backup and saves stay together on one account, whichever it is.
        assertThat(settings.backupAccountId.value).isEqualTo(uploads.saveAccountId())
    }

    @Test fun `a chosen account that was removed falls back to the first connected`() = runTest {
        val second = secondAccount()
        uploads.setSaveAccount(second)
        uploads.removingAccount(second) { dao.removeAccount(second) }
        assertThat(uploads.saveAccount.first()).isEqualTo(account)
        assertThat(uploads.saveAccountId()).isEqualTo(account)
    }

    @Test fun `no Drive account means nothing to save to, even with another provider connected`() = runTest {
        dao.removeAccount(account)
        dao.insertAccount(CloudAccountEntity(provider = "dropbox", remoteId = "d", displayName = "Ron", connectedAt = 0))
        assertThat(uploads.saveAccountId()).isNull()
        assertThat(uploads.save(listOf(track("a")))).isEqualTo(SaveRequest(0, 0, 1))
    }

    @Test fun `backup that was already on keeps its account when nothing was chosen yet`() = runTest {
        // A phone from before this choice existed, with backup on a later Drive row.
        val second = secondAccount()
        settings.backupAccountId.value = second
        assertThat(uploads.saveAccountId()).isEqualTo(second)
    }

    @Test fun `a full Drive is per account, and trying again clears only that account`() = runTest {
        val second = secondAccount()
        settings.fullAccounts.value = setOf(account, second)

        assertThat(uploads.status(account).first().driveFull).isTrue()
        assertThat(uploads.status(second).first().driveFull).isTrue()

        uploads.resume(second)

        assertThat(uploads.status(account).first().driveFull).isTrue()
        assertThat(uploads.status(second).first().driveFull).isFalse()

        uploads.save(listOf(track("a")), second)
        assertThat(settings.fullAccounts.value).containsExactly(account)
    }

    @Test fun `trying again does not start a run when only full accounts have songs`() = runTest {
        val second = secondAccount()
        uploads.save(listOf(track("a")), account)
        settings.fullAccounts.value = setOf(account)
        val kicks = runner.kicks

        uploads.resume(second)

        assertThat(runner.kicks).isEqualTo(kicks)
    }

    @Test fun `a song whose copy was trashed in another Drive is back in backup's count at the next app start`() = runTest {
        val second = secondAccount()
        val song = track("song")
        scanLinked(song, second)
        uploads.setBackup(account, on = true)
        // It's in a Drive, so it waits there rather than being counted for sending again.
        assertThat(rowFor(song).accountId).isEqualTo(second)
        assertThat(uploads.status(account).first().existing).isEqualTo(0)

        // The scan finds the copy gone: the link goes, the row stays where it was.
        dao.removeItems(listOf(song))
        assertThat(rowFor(song).accountId).isEqualTo(second)

        val kicks = runner.kicks
        uploads.start()
        realTime { withTimeout(WATCHER_LIMIT_MS) { while (rowFor(song).accountId != account) delay(20) } }

        assertThat(rowFor(song).state).isEqualTo(CloudUploadState.EXISTING.name)
        assertThat(uploads.status(account).first().existing).isEqualTo(1)
        // Counted, not sent: "Back up N existing" is the user's to press.
        assertThat(runner.kicks).isEqualTo(kicks)
    }

    @Test fun `a song still linked to another Drive stays waiting there across an app start`() = runTest {
        val second = secondAccount()
        val song = track("song")
        scanLinked(song, second)
        uploads.setBackup(account, on = true)

        uploads.start()
        // Long enough for the move at start to have run.
        realTime { delay(WATCHER_PASS_MS) }

        assertThat(rowFor(song).accountId).isEqualTo(second)
    }

    @Test fun `moving backup moves what it was waiting to send, and nothing else`() = runTest {
        val second = secondAccount()
        val existing = track("existing")
        uploads.setBackup(account, on = true)
        val queuedByBackup = track("new")
        dao.insertUploadsForDownloads(account, CloudUploadState.QUEUED.name, 5)
        dao.setUploadSession(queuedByBackup, account, "https://session", 10, "md5", 6)
        val manual = track("manual")
        uploads.save(listOf(manual), account)
        val saved = track("saved")
        uploads.save(listOf(saved), account)
        dao.finishUpload(saved, CloudUploadState.DONE.name, "file", null, 7)
        val cancels = runner.cancels
        val kicks = runner.kicks

        uploads.setSaveAccount(second)

        assertThat(settings.backupAccountId.value).isEqualTo(second)
        assertThat(rowFor(existing).accountId).isEqualTo(second)
        assertThat(rowFor(existing).state).isEqualTo(CloudUploadState.EXISTING.name)
        assertThat(rowFor(queuedByBackup).accountId).isEqualTo(second)
        assertThat(rowFor(queuedByBackup).state).isEqualTo(CloudUploadState.QUEUED.name)
        // A session belongs to the account that opened it.
        assertThat(rowFor(queuedByBackup).sessionUri).isNull()
        // The user's own save and what's already in the old Drive stay.
        assertThat(rowFor(manual).accountId).isEqualTo(account)
        assertThat(rowFor(saved).accountId).isEqualTo(account)
        // The run may have been in the middle of a song that moved.
        assertThat(runner.cancels).isEqualTo(cancels + 1)
        assertThat(runner.kicks).isEqualTo(kicks + 1)
    }

    @Test fun `back up existing after a move sends them to the new account only`() = runTest {
        val second = secondAccount()
        val a = track("a")
        val b = track("b")
        uploads.setBackup(account, on = true)
        uploads.setSaveAccount(second)

        assertThat(uploads.status(account).first().existing).isEqualTo(0)
        assertThat(uploads.status(second).first().existing).isEqualTo(2)
        assertThat(uploads.backUpExisting(account)).isEqualTo(0)
        assertThat(uploads.backUpExisting(second)).isEqualTo(2)

        assertThat(rowFor(a).accountId).isEqualTo(second)
        assertThat(rowFor(b).accountId).isEqualTo(second)
        assertThat(rowFor(a).state).isEqualTo(CloudUploadState.QUEUED.name)
    }

    @Test fun `turning backup on for another account brings the waiting downloads along`() = runTest {
        val second = secondAccount()
        val a = track("a")
        uploads.setBackup(account, on = true)
        uploads.setBackup(account, on = false)

        uploads.setSaveAccount(second)
        uploads.setBackup(second, on = true)

        assertThat(rowFor(a).accountId).isEqualTo(second)
        assertThat(uploads.status(second).first().backupOn).isTrue()
    }

    @Test fun `the switch on another account can't turn backup off`() = runTest {
        val second = secondAccount()
        uploads.setBackup(second, on = true)
        val waiting = track("w")
        dao.insertUploadsForDownloads(second, CloudUploadState.QUEUED.name, 5)

        uploads.setBackup(account, on = false)

        assertThat(settings.backupAccountId.value).isEqualTo(second)
        assertThat(rowFor(waiting).state).isEqualTo(CloudUploadState.QUEUED.name)
    }

    @Test fun `turning backup off sends back only that account's queue`() = runTest {
        val second = secondAccount()
        uploads.setBackup(second, on = true)
        val waiting = track("w")
        dao.insertUploadsForDownloads(second, CloudUploadState.QUEUED.name, 5)

        uploads.setBackup(second, on = false)

        assertThat(settings.backupAccountId.value).isNull()
        assertThat(rowFor(waiting).state).isEqualTo(CloudUploadState.EXISTING.name)
    }

    @Test fun `saving again keeps a song in the Drive it is already in`() = runTest {
        val second = secondAccount()
        val a = track("a")
        uploads.save(listOf(a), account)
        dao.finishUpload(a, CloudUploadState.DONE.name, "file", null, 2)

        uploads.setSaveAccount(second)
        uploads.save(listOf(a))

        // Checked again where it is; a song lives in one Drive.
        assertThat(rowFor(a).accountId).isEqualTo(account)
        assertThat(rowFor(a).state).isEqualTo(CloudUploadState.QUEUED.name)
    }

    @Test fun `saving again a hand-saved song still waiting on another account moves it to the chosen one`() = runTest {
        val second = secondAccount()
        val waiting = track("waiting")
        uploads.save(listOf(waiting), account)
        // Ron signed out: one try failed before anything was sent.
        dao.failUploadAttempt(waiting, "signed out", 3, 2)
        uploads.setSaveAccount(second)
        val cancels = runner.cancels

        val r = uploads.save(listOf(waiting))

        rowFor(waiting).let {
            assertThat(it.accountId).isEqualTo(second)
            assertThat(it.state).isEqualTo(CloudUploadState.QUEUED.name)
            assertThat(it.origin).isEqualTo(CloudUploadOrigin.MANUAL.name)
            // A fresh start on Sam's Drive.
            assertThat(it.attempts).isEqualTo(0)
            assertThat(it.error).isNull()
            assertThat(it.sessionUri).isNull()
        }
        assertThat(runner.cancels).isEqualTo(cancels)
        assertThat(r.elsewhere).isEmpty()
        assertThat(CloudUploads.message(r)).isEqualTo("Saving 1 song to sam@example.com.")
    }

    @Test fun `saving again a song part-way through an upload to another account leaves it to finish there`() = runTest {
        val second = secondAccount()
        val sending = track("sending")
        uploads.save(listOf(sending), account)
        dao.setUploadSession(sending, account, "https://session", 10, "md5", 2)
        dao.setUploadProgress(sending, 5, 3)
        uploads.setSaveAccount(second)
        val cancels = runner.cancels

        val r = uploads.save(listOf(sending))

        rowFor(sending).let {
            assertThat(it.accountId).isEqualTo(account)
            assertThat(it.sessionUri).isEqualTo("https://session")
            assertThat(it.bytesSent).isEqualTo(5)
        }
        // Nothing is cancelled.
        assertThat(runner.cancels).isEqualTo(cancels)
        assertThat(r.elsewhere).containsExactly(SavedElsewhere("Ron", waiting = 1, inDrive = 0))
    }

    @Test fun `saving again a song already uploaded to another account leaves it there`() = runTest {
        val second = secondAccount()
        val saved = track("saved")
        uploads.save(listOf(saved), account)
        dao.finishUpload(saved, CloudUploadState.DONE.name, "file", null, 2)
        scanLinked(saved, account)
        uploads.setSaveAccount(second)

        val r = uploads.save(listOf(saved))

        assertThat(rowFor(saved).accountId).isEqualTo(account)
        assertThat(r.elsewhere).containsExactly(SavedElsewhere("Ron", waiting = 0, inDrive = 1))
        // Queued there now, for a check of the copy it has; saving it once more still leaves it there.
        assertThat(rowFor(saved).state).isEqualTo(CloudUploadState.QUEUED.name)
        uploads.save(listOf(saved))
        assertThat(rowFor(saved).accountId).isEqualTo(account)
    }

    @Test fun `a save that lands in three places says each`() = runTest {
        val second = secondAccount()
        val waiting = track("waiting")
        val saved = track("saved")
        uploads.save(listOf(waiting, saved), account)
        // Part-way through an upload to Ron's Drive: it finishes there.
        dao.setUploadSession(waiting, account, "https://session", 10, "md5", 2)
        dao.finishUpload(saved, CloudUploadState.DONE.name, "file", null, 2)
        scanLinked(saved, account)
        val fresh = track("fresh")
        val notDownloaded = track("x", downloaded = false)
        uploads.setSaveAccount(second)

        val r = uploads.save(listOf(waiting, saved, fresh, notDownloaded))

        assertThat(r.to).isEqualTo("sam@example.com")
        assertThat(rowFor(fresh).accountId).isEqualTo(second)
        assertThat(r.elsewhere).containsExactly(SavedElsewhere("Ron", waiting = 1, inDrive = 1))
        assertThat(CloudUploads.message(r)).isEqualTo("Saving 1 song. 1 song isn't downloaded yet.")
    }

    @Test fun `only songs that stayed elsewhere and one not downloaded reads cleanly`() {
        val r = SaveRequest(queued = 0, alreadyQueued = 2, notDownloaded = 1, to = "sam@example.com", elsewhere = listOf(SavedElsewhere("ron@example.com", 2, 0)))
        assertThat(CloudUploads.message(r)).isEqualTo("1 song isn't downloaded yet.")
    }

    @Test fun `with one account nothing is elsewhere and the toast names no account`() = runTest {
        val r = uploads.save(listOf(track("a")))
        assertThat(r.elsewhere).isEmpty()
        assertThat(r.to).isNull()
        assertThat(CloudUploads.message(r)).isEqualTo("Saving 1 song to Google Drive.")
    }

    @Test fun `a song that never made it goes to the account chosen now`() = runTest {
        val second = secondAccount()
        val a = track("a")
        uploads.save(listOf(a), account)
        dao.failUploadAttempt(a, "no", maxAttempts = 1, now = 2)

        uploads.setSaveAccount(second)
        uploads.save(listOf(a))

        assertThat(rowFor(a).accountId).isEqualTo(second)
    }

    @Test fun `rows left counting songs that are in a Drive say backed up after an app start`() = runTest {
        val linked = track("linked")
        val waiting = track("waiting")
        // As the Pixel 5 had them: backup on, both counted as existing, though the first had been
        // relinked to its copy in this Drive by the reconnect's scan.
        settings.setBackupAccount(account)
        dao.putUpload(CloudUploadEntity(linked, account, CloudUploadState.EXISTING.name, CloudUploadOrigin.BACKUP.name, 1, 1))
        dao.putUpload(CloudUploadEntity(waiting, account, CloudUploadState.EXISTING.name, CloudUploadOrigin.BACKUP.name, 1, 1))
        val folder = dao.driveFolderRow(account, GoogleDriveProvider.UPLOADS_ITEM_ID, 1)
        dao.insertItem(
            CloudItemEntity(
                trackId = linked, accountId = account, folderId = folder.id, itemId = "file-linked", name = "f.flac",
                relativePath = "", parentItemId = null, cTag = "1", eTag = null, size = 1, mimeType = null,
                seenGeneration = 1, scannedAt = 1,
            ),
        )
        assertThat(uploads.status(account).first().existing).isEqualTo(2)

        uploads.start()
        realTime { withTimeout(WATCHER_LIMIT_MS) { while (rowFor(linked).state != CloudUploadState.DONE.name) delay(20) } }

        val status = uploads.status(account).first()
        assertThat(status.saved).isEqualTo(1)
        assertThat(status.existing).isEqualTo(1)
        assertThat(rowFor(linked).driveFileId).isEqualTo("file-linked")
        assertThat(rowFor(waiting).state).isEqualTo(CloudUploadState.EXISTING.name)
        // Counted, never sent by itself.
        assertThat(runner.kicks).isEqualTo(0)
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

    companion object {
        /** Long enough for the watcher's 3 s wait after a change to end, and its pass to run. */
        const val WATCHER_PASS_MS = 3_600L

        /** A pass that hasn't happened by now isn't coming. */
        const val WATCHER_LIMIT_MS = 15_000L
    }
}

package com.stash.data.cloud.upload

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.dao.CloudDao
import com.stash.core.data.db.entity.CloudAccountEntity
import com.stash.core.data.db.entity.CloudUploadOrigin
import com.stash.core.data.db.entity.CloudUploadState
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.MusicSource
import com.stash.data.cloud.CloudConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the worker tells WorkManager when a pass ends ([CloudUploadRunTest] covers the pass itself):
 * a lost connection must ask for another run, or the songs wait for the next tap or app start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CloudUploadWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val settings = CloudUploadsTest.MemorySettings()
    private val uploader = mockk<DriveUploader>()
    private lateinit var db: StashDatabase
    private lateinit var dao: CloudDao
    private var track = 0L

    @Before fun setUp() = runTest {
        // The foreground notification's Stop button asks WorkManager for its intent.
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        db = Room.inMemoryDatabaseBuilder(context, StashDatabase::class.java).allowMainThreadQueries().build()
        dao = db.cloudDao()
        val account = dao.insertAccount(CloudAccountEntity(provider = "gdrive", remoteId = "p", displayName = "Ron", connectedAt = 0))
        track = db.trackDao().insert(
            TrackEntity(title = "a", artist = "A", isDownloaded = true, filePath = "/m/a.flac", source = MusicSource.SPOTIFY, spotifyUri = "spotify:track:a"),
        )
        dao.queueUploads(listOf(track), account, CloudUploadOrigin.MANUAL.name, 1)
    }

    @After fun tearDown() = db.close()

    private fun worker(attempts: Int = 0, enabled: Boolean = true) = TestListenableWorkerBuilder.from(context, CloudUploadWorker::class.java)
        .setRunAttemptCount(attempts)
        .setWorkerFactory(
            object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    CloudUploadWorker(appContext, workerParameters, dao, uploader, settings, CloudConfig("", "", "com.stash.app", googleDriveEnabled = enabled))
            },
        )
        .build()


    /** Gives the test app a launcher activity, so "open Stash" has somewhere to go. */
    private fun installLauncher(): android.content.ComponentName {
        val launcher = android.content.ComponentName(context.packageName, "com.stash.app.MainActivity")
        val pm = org.robolectric.Shadows.shadowOf(context.packageManager)
        pm.addOrUpdateActivity(android.content.pm.ActivityInfo().apply { packageName = launcher.packageName; name = launcher.className })
        pm.addIntentFilterForActivity(
            launcher,
            android.content.IntentFilter(android.content.Intent.ACTION_MAIN).apply { addCategory(android.content.Intent.CATEGORY_LAUNCHER) },
        )
        return launcher
    }

    @Test fun `tapping the Saving to Google Drive notification opens Stash`() = runTest {
        val launcher = installLauncher()
        val tap = worker().getForegroundInfo().notification.contentIntent
        assertThat(tap).isNotNull()
        assertThat(org.robolectric.Shadows.shadowOf(tap).savedIntents.single().component).isEqualTo(launcher)
    }

    @Test fun `a lost connection asks WorkManager for another run`() = runTest {
        coEvery { uploader.upload(any(), any()) } throws IOException("gone")

        assertThat(worker().doWork()).isEqualTo(ListenableWorker.Result.retry())

        // Nothing is wrong with the song: it waits, with no attempt counted against it.
        assertThat(dao.getUpload(track)!!.state).isEqualTo(CloudUploadState.QUEUED.name)
        assertThat(dao.getUpload(track)!!.attempts).isEqualTo(0)
    }

    @Test fun `a connection that stays lost stops asking and waits for the next kick`() = runTest {
        coEvery { uploader.upload(any(), any()) } throws IOException("gone")

        assertThat(worker(attempts = 10).doWork()).isEqualTo(ListenableWorker.Result.success())
    }

    @Test fun `a pass that finishes is a success`() = runTest {
        coEvery { uploader.upload(any(), any()) } returns UploadResult.Saved("file", reused = false)

        assertThat(worker().doWork()).isEqualTo(ListenableWorker.Result.success())

        assertThat(dao.getUpload(track)!!.state).isEqualTo(CloudUploadState.DONE.name)
    }

    @Test fun `a build without Google Drive sends nothing`() = runTest {
        assertThat(worker(enabled = false).doWork()).isEqualTo(ListenableWorker.Result.success())

        coVerify(exactly = 0) { uploader.upload(any(), any()) }
    }
}

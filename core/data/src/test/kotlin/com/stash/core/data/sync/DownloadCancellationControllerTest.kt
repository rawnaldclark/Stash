package com.stash.core.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.dao.KEPT_OUT_MARK
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.DownloadStatus
import com.stash.core.model.MusicSource
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Clearing the queue, or cancelling the last song in it, ends a Pause: left on, the Downloads
 * screen said "Paused" over "No downloads in progress", and the next sync's songs waited for a
 * Resume the user had no reason to tap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DownloadCancellationControllerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: StashDatabase
    private val queueState = mockk<DownloadQueueState>(relaxed = true)
    private lateinit var controller: DownloadCancellationController

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = Room.inMemoryDatabaseBuilder(context, StashDatabase::class.java).allowMainThreadQueries().build()
        controller = DownloadCancellationController(context, db.downloadQueueDao(), DownloadJobRegistry(), queueState)
    }

    @After fun tearDown() { db.close() }

    @Test fun `clear queue cancels everything and ends the pause`() = runBlocking {
        val songs = (1..3).map { queue(it) }

        assertEquals(3, controller.cancelAll())

        songs.forEach { assertEquals(DownloadStatus.SKIPPED, db.downloadQueueDao().getStatusById(it)) }
        coVerify(exactly = 1) { queueState.setPaused(false) }
    }

    @Test fun `clear queue only stops the attempt, a cancel on its own keeps the song out`() = runBlocking {
        val one = queue(1)
        val rest = (2..3).map { queue(it) }

        controller.cancel(one)
        controller.cancelAll()

        val dao = db.downloadQueueDao()
        assertEquals(KEPT_OUT_MARK, dao.getById(one)!!.errorMessage)
        rest.forEach { assertNull(dao.getById(it)!!.errorMessage) }
        // A playlist's Download queues Clear queue's songs again, never the one cancelled on its own.
        assertEquals(true, dao.hasRowToLeaveAlone(dao.getById(one)!!.trackId))
        rest.forEach { assertEquals(false, dao.hasRowToLeaveAlone(dao.getById(it)!!.trackId)) }
    }

    @Test fun `clear queue on an empty queue still ends the pause`() = runBlocking {
        assertEquals(0, controller.cancelAll())

        coVerify(exactly = 1) { queueState.setPaused(false) }
    }

    @Test fun `cancelling the last song left ends the pause`() = runBlocking {
        val first = queue(1)
        val last = queue(2)

        controller.cancel(first)
        coVerify(exactly = 0) { queueState.setPaused(any()) }

        controller.cancel(last)
        coVerify(exactly = 1) { queueState.setPaused(false) }
    }

    @Test fun `a cancel that changed nothing leaves the pause alone`() = runBlocking {
        val song = queue(1)
        controller.cancel(song)

        assertEquals(false, controller.cancel(song))
        coVerify(exactly = 1) { queueState.setPaused(false) }
    }

    private suspend fun queue(n: Int): Long {
        val trackId = db.trackDao().insert(
            TrackEntity(
                title = "Song $n", artist = "Artist", durationMs = 1000L, source = MusicSource.YOUTUBE,
                canonicalTitle = "song $n", canonicalArtist = "artist",
            ),
        )
        return db.downloadQueueDao().insert(DownloadQueueEntity(trackId = trackId, userRequested = true, searchQuery = "artist - song $n"))
    }
}

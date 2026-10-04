package com.stash.core.data.sync.workers

import android.content.Context
import androidx.room.withTransaction
import androidx.work.WorkerParameters
import com.stash.core.data.blocklist.FileDeleter
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.dao.TrackBlocklistDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.MusicSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The integrity pass deletes leaked blocked songs, file first. Two rows can
 * share a file (an import that took a downloaded song's file before imports
 * picked a name of their own), so the file goes only with the last row on it.
 */
class BlocklistIntegrityWorkerTest {

    private val database: StashDatabase = mockk(relaxed = true)
    private val trackDao: TrackDao = mockk(relaxed = true)
    private val blocklistDao: TrackBlocklistDao = mockk(relaxed = true)
    private val fileDeleter: FileDeleter = mockk(relaxed = true)
    private val paths = mutableMapOf<Long, String?>()

    @Before
    fun setUp() {
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction(any<suspend () -> Any>()) } coAnswers {
            secondArg<suspend () -> Any>().invoke()
        }
        coEvery { trackDao.countOtherTracksWithFilePath(any(), any()) } answers {
            val path = firstArg<String>()
            val trackId = secondArg<Long>()
            paths.count { (id, p) -> id != trackId && p == path }
        }
        coEvery { trackDao.deleteById(any()) } answers { paths.remove(firstArg()) }
    }

    @After
    fun tearDown() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    @Test
    fun `a blocked song's file stays while a song that is not blocked plays from it`() = runTest {
        paths[1L] = FILE
        paths[2L] = FILE
        coEvery { blocklistDao.getAllKeys() } returns listOf("roberta flack|killing me softly")
        coEvery { trackDao.getAllForIntegrityScan() } returns listOf(
            entity(1L, "roberta flack", "killing me softly"),
            entity(2L, "roberta flack", "killing me softly with his song"),
        )

        worker().doWork()

        coVerify(exactly = 1) { trackDao.deleteById(1L) }
        coVerify(exactly = 0) { fileDeleter.delete(FILE) }
    }

    @Test
    fun `a file two blocked songs share goes with the second`() = runTest {
        paths[1L] = FILE
        paths[2L] = FILE
        coEvery { blocklistDao.getAllKeys() } returns listOf("roberta flack|killing me softly")
        coEvery { trackDao.getAllForIntegrityScan() } returns listOf(
            entity(1L, "roberta flack", "killing me softly"),
            entity(2L, "roberta flack", "killing me softly"),
        )

        worker().doWork()

        coVerify(exactly = 1) { fileDeleter.delete(FILE) }
    }

    private fun entity(id: Long, canonicalArtist: String, canonicalTitle: String) = TrackEntity(
        id = id, title = canonicalTitle, artist = canonicalArtist, source = MusicSource.LOCAL,
        canonicalArtist = canonicalArtist, canonicalTitle = canonicalTitle,
        filePath = FILE, isDownloaded = true,
    )

    private fun worker() = BlocklistIntegrityWorker(
        appContext = mockk<Context>(relaxed = true),
        params = mockk<WorkerParameters>(relaxed = true),
        database = database,
        trackDao = trackDao,
        blocklistDao = blocklistDao,
        playlistDao = mockk(relaxed = true),
        downloadQueueDao = mockk(relaxed = true),
        fileDeleter = fileDeleter,
    )

    private companion object {
        const val FILE = "/music/roberta-flack/singles/killing-me-softly-with-his-song.flac"
    }
}

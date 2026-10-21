package com.stash.data.download.backfill

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.download.files.AlbumArtCache
import com.stash.data.download.files.MetadataEmbedder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Unit tests for [MetadataBackfillWorker].
 *
 * The worker is `@HiltWorker` `@AssistedInject`, but since both assisted
 * params (`Context`, `WorkerParameters`) are positional we can instantiate
 * it directly with [mockk]'d stubs — no Hilt graph or
 * `TestListenableWorkerBuilder` needed. This mirrors the precedent set by
 * [com.stash.data.download.lossless.LosslessRetryWorkerTest].
 *
 * Behaviour under test:
 *  - Empty result set short-circuits to `Result.success()` after marking
 *    started/finished — no work, no DAO writes beyond the count.
 *  - Each healthy row gets a non-zero `setMetadataEmbeddedAt` stamp once
 *    the embed step completes.
 *  - SAF (`content://`) rows stamp `0L` (failure sentinel) AND increment
 *    `safSkipped` — the Home banner reads that counter to explain why
 *    the done count doesn't match the total.
 *  - Missing-file rows stamp `0L` but do NOT increment `safSkipped` —
 *    that counter is reserved for content-URI rows.
 */
class MetadataBackfillWorkerTest {

    private val context: Context = mockk(relaxed = true)
    private val workerParams: WorkerParameters = mockk(relaxed = true)
    private val trackDao: TrackDao = mockk(relaxed = true)
    private val metadataEmbedder: MetadataEmbedder = mockk(relaxed = true)
    private val albumArtCache: AlbumArtCache = mockk(relaxed = true)
    private val backfillState: MetadataBackfillState = mockk(relaxUnitFun = true)

    private val tempFiles = mutableListOf<File>()

    @After
    fun cleanup() {
        tempFiles.forEach { runCatching { it.delete() } }
        tempFiles.clear()
    }

    private fun buildSubject(): MetadataBackfillWorker = MetadataBackfillWorker(
        appContext = context,
        params = workerParams,
        trackDao = trackDao,
        metadataEmbedder = metadataEmbedder,
        albumArtCache = albumArtCache,
        backfillState = backfillState,
    )

    private fun newTempAudio(): File =
        File.createTempFile("backfill", ".opus").also {
            it.writeBytes(ByteArray(64))
            tempFiles.add(it)
        }

    private fun stubEntity(id: Long, filePath: String) = TrackEntity(
        id = id,
        title = "Track $id",
        artist = "Artist $id",
        album = "Album $id",
        filePath = filePath,
        isDownloaded = true,
    )

    @Test
    fun `empty library returns success without doing work`() = runTest {
        every { trackDao.observeTracksNeedingEmbedCount() } returns flowOf(0)
        coEvery { trackDao.getTracksNeedingEmbed(any(), any()) } returns emptyList()

        val result = buildSubject().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify { backfillState.markStarted(0) }
        coVerify { backfillState.markFinished() }
        coVerify(exactly = 0) { trackDao.setMetadataEmbeddedAt(any(), any()) }
        coVerify(exactly = 0) { metadataEmbedder.embedMetadata(any(), any(), any()) }
    }

    @Test
    fun `each row gets a setMetadataEmbeddedAt stamp on success`() = runTest {
        val file1 = newTempAudio()
        val file2 = newTempAudio()
        val rows = listOf(
            stubEntity(id = 1, filePath = file1.absolutePath),
            stubEntity(id = 2, filePath = file2.absolutePath),
        )
        every { trackDao.observeTracksNeedingEmbedCount() } returns flowOf(2)
        coEvery { trackDao.getTracksNeedingEmbed(any(), any()) } returnsMany listOf(rows, emptyList())
        coEvery { albumArtCache.resolveArt(any()) } returns null
        coEvery { metadataEmbedder.embedMetadata(any(), any(), any()) } answers { firstArg() }

        val result = buildSubject().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify { trackDao.setMetadataEmbeddedAt(eq(1L), match { it > 0L }) }
        coVerify { trackDao.setMetadataEmbeddedAt(eq(2L), match { it > 0L }) }
    }

    @Test
    fun `embed failure stamps 0L instead of a success timestamp`() = runTest {
        val file = newTempAudio()
        val rows = listOf(stubEntity(id = 3, filePath = file.absolutePath))
        every { trackDao.observeTracksNeedingEmbedCount() } returns flowOf(1)
        coEvery { trackDao.getTracksNeedingEmbed(any(), any()) } returnsMany listOf(rows, emptyList())
        coEvery { albumArtCache.resolveArt(any()) } returns null
        coEvery { metadataEmbedder.embedMetadata(any(), any(), any()) } throws
            IllegalStateException("ffmpeg failed")

        val result = buildSubject().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 1) { trackDao.setMetadataEmbeddedAt(eq(3L), eq(0L)) }
        coVerify(exactly = 0) { trackDao.setMetadataEmbeddedAt(eq(3L), match { it > 0L }) }
    }

    @Test
    fun `SAF row stamps 0L and increments safSkipped`() = runTest {
        val rows = listOf(
            stubEntity(id = 5, filePath = "content://com.android.externalstorage.documents/blah"),
        )
        every { trackDao.observeTracksNeedingEmbedCount() } returns flowOf(1)
        coEvery { trackDao.getTracksNeedingEmbed(any(), any()) } returnsMany listOf(rows, emptyList())

        buildSubject().doWork()

        coVerify { trackDao.setMetadataEmbeddedAt(eq(5L), eq(0L)) }
        coVerify { backfillState.incrementSafSkipped() }
        coVerify(exactly = 0) { metadataEmbedder.embedMetadata(any(), any(), any()) }
        coVerify(exactly = 0) { albumArtCache.resolveArt(any()) }
    }

    @Test
    fun `missing file stamps 0L without incrementing safSkipped`() = runTest {
        val rows = listOf(stubEntity(id = 9, filePath = "/nope/missing.opus"))
        every { trackDao.observeTracksNeedingEmbedCount() } returns flowOf(1)
        coEvery { trackDao.getTracksNeedingEmbed(any(), any()) } returnsMany listOf(rows, emptyList())

        buildSubject().doWork()

        coVerify { trackDao.setMetadataEmbeddedAt(eq(9L), eq(0L)) }
        coVerify(exactly = 0) { backfillState.incrementSafSkipped() }
        coVerify(exactly = 0) { metadataEmbedder.embedMetadata(any(), any(), any()) }
    }
}

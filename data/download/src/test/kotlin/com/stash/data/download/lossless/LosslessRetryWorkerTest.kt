package com.stash.data.download.lossless

import android.content.Context
import androidx.work.WorkerParameters
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.sync.SingleTrackDownloadEnqueuer
import com.stash.core.model.DownloadStatus
import androidx.work.workDataOf
import com.stash.data.download.lossless.quota.LosslessQuota
import com.stash.data.download.lossless.relay.LosslessDownloadPurpose
import io.mockk.every
import kotlinx.coroutines.currentCoroutineContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.9.17 strict-FLAC: tests the one-shot sweep worker that re-resolves
 * [DownloadStatus.WAITING_FOR_LOSSLESS] rows.
 *
 * Behavior under test:
 *  - Resolved row → row flipped to [DownloadStatus.PENDING] so the
 *    standard download chain picks it up.
 *  - Still-null row → no DB write; row stays in WAITING_FOR_LOSSLESS for
 *    the next trigger.
 *  - Empty deferred set → success, no DB writes.
 *  - Stream-only mode → a sync's row nobody asked for is never resolved.
 *
 * The worker does not download — it only re-resolves and re-queues.
 *
 * Mocks the constructor deps directly (no Hilt), since [LosslessRetryWorker]
 * is a plain class that can be instantiated with stub [WorkerParameters].
 * MockK matches the precedent set by [DownloadManagerDeferTest].
 */
class LosslessRetryWorkerTest {

    private val appContext: Context = mockk(relaxed = true)
    private val workerParams: WorkerParameters = mockk(relaxed = true)
    private val downloadQueueDao: DownloadQueueDao = mockk(relaxed = true)
    private val trackDao: TrackDao = mockk(relaxed = true)
    private val registry: LosslessSourceRegistry = mockk()
    private val enqueuer: SingleTrackDownloadEnqueuer = mockk(relaxed = true)
    private val streamingPreference: StreamingPreference = mockk { coEvery { current() } returns false }
    private var now = 1_792_238_400_000L
    private val quota = LosslessQuota.inMemory { now }
    private val losslessPrefs: LosslessSourcePreferences = mockk {
        coEvery { downloadModeNow() } returns com.stash.core.model.DownloadMode.LOSSLESS
    }
    private val tidalAacResolver: com.stash.data.download.lossless.tidal.TidalAacResolver = mockk()
    private val handoff = ResolvedSourceHandoff()
    private val cursor = RetrySweepCursor.inMemory()

    private fun newWorker(): LosslessRetryWorker = LosslessRetryWorker(
        appContext = appContext,
        params = workerParams,
        downloadQueueDao = downloadQueueDao,
        trackDao = trackDao,
        registry = registry,
        singleTrackDownloadEnqueuer = enqueuer,
        streamingPreference = streamingPreference,
        quota = quota,
        losslessPrefs = losslessPrefs,
        tidalAacResolver = tidalAacResolver,
        handoff = handoff,
        cursor = cursor,
    )

    /** The sweep the scheduler books for the allowance's reset. */
    private fun atReset() {
        every { workerParams.inputData } returns workDataOf(LosslessRetryWorker.KEY_AT_RESET to true)
    }

    // -- The daily allowance's reset sweep ------------------------------------------

    @Test
    fun `the reset sweep re-queues every waiting song without a lookup, so each download makes the only one`() = runTest {
        atReset()
        coEvery { downloadQueueDao.waitingForLosslessTracks(any()) } returns
            listOf(entry(id = 100L, trackId = 1L), entry(id = 101L, trackId = 2L))
        coEvery { trackDao.getById(any()) } answers { stubTrackEntity(firstArg()) }

        val result = newWorker().doWork() as androidx.work.ListenableWorker.Result.Success

        coVerify(exactly = 0) { registry.resolve(any()) }
        coVerify(exactly = 1) { downloadQueueDao.updateStatus(id = 100L, status = DownloadStatus.PENDING) }
        coVerify(exactly = 1) { downloadQueueDao.updateStatus(id = 101L, status = DownloadStatus.PENDING) }
        coVerify(exactly = 1) { enqueuer.enqueue(100L) }
        coVerify(exactly = 1) { enqueuer.enqueue(101L) }
        assertEquals(2, result.outputData.getInt(LosslessRetryWorker.KEY_RESOLVED, -1))
    }

    @Test
    fun `a reset sweep that runs while the day is still spent touches nothing and retries`() = runTest {
        atReset()
        quota.onHeaders("500/500", "${now / 1000 + 120}") // the relays' day has two minutes left
        coEvery { downloadQueueDao.waitingForLosslessTracks(any()) } returns listOf(entry(id = 100L, trackId = 1L))

        val result = newWorker().doWork()

        assertEquals(androidx.work.ListenableWorker.Result.retry(), result)
        coVerify(exactly = 0) { downloadQueueDao.updateStatus(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { enqueuer.enqueue(any()) }
    }

    @Test
    fun `the reset sweep still leaves a song whose audio the user picked`() = runTest {
        atReset()
        coEvery { downloadQueueDao.waitingForLosslessTracks(any()) } returns listOf(entry(id = 100L, trackId = 1L))
        coEvery { trackDao.getById(1L) } returns stubTrackEntity(1L).copy(matchPickedAt = 1_000L)

        newWorker().doWork()

        coVerify(exactly = 0) { enqueuer.enqueue(any()) }
    }

    // -- Download quality ---------------------------------------------------------------------

    private fun mode(mode: com.stash.core.model.DownloadMode) {
        coEvery { losslessPrefs.downloadModeNow() } returns mode
    }

    @Test
    fun `AAC mode asks Tidal for AAC only, re-queues the songs it now has, and never asks for FLAC`() = runTest {
        mode(com.stash.core.model.DownloadMode.AAC)
        coEvery { downloadQueueDao.waitingForLosslessTracks(any()) } returns
            listOf(entry(id = 100L, trackId = 1L), entry(id = 101L, trackId = 2L))
        coEvery { trackDao.getById(any()) } answers { stubTrackEntity(firstArg()) }
        // Tidal has song 1 as AAC now; song 2 still not.
        coEvery { tidalAacResolver.resolve(any(), aacOnly = true, foreground = any()) } answers {
            if (firstArg<com.stash.data.download.lossless.TrackQuery>().title == "Track 1") com.stash.data.download.lossless.tidal.TidalAac.Found(stubSourceResult()) else com.stash.data.download.lossless.tidal.TidalAac.NoAac
        }

        newWorker().doWork()

        coVerify(exactly = 0) { registry.resolve(any()) }
        coVerify(exactly = 2) { tidalAacResolver.resolve(any(), aacOnly = true, foreground = any()) }
        coVerify(exactly = 1) { enqueuer.enqueue(100L) }
        coVerify(exactly = 0) { enqueuer.enqueue(101L) }
    }

    @Test
    fun `AAC mode's reset sweep, run while the day is still spent, touches nothing and retries`() = runTest {
        // Every relay lookup keys off the day's reset, AAC mode's too.
        mode(com.stash.core.model.DownloadMode.AAC)
        atReset()
        quota.onHeaders("500/500", "${now / 1000 + 120}")
        coEvery { downloadQueueDao.waitingForLosslessTracks(any()) } returns listOf(entry(id = 100L, trackId = 1L))

        val result = newWorker().doWork()

        assertEquals(androidx.work.ListenableWorker.Result.retry(), result)
        coVerify(exactly = 0) { enqueuer.enqueue(any()) }
    }

    @Test
    fun `AAC mode's reset sweep re-queues every waiting song without a lookup`() = runTest {
        mode(com.stash.core.model.DownloadMode.AAC)
        atReset()
        coEvery { downloadQueueDao.waitingForLosslessTracks(any()) } returns
            listOf(entry(id = 100L, trackId = 1L), entry(id = 101L, trackId = 2L))
        coEvery { trackDao.getById(any()) } answers { stubTrackEntity(firstArg()) }

        newWorker().doWork()

        coVerify(exactly = 0) { tidalAacResolver.resolve(any(), any(), any()) }
        coVerify(exactly = 1) { enqueuer.enqueue(100L) }
        coVerify(exactly = 1) { enqueuer.enqueue(101L) }
    }

    @Test
    fun `Opus mode re-queues every waiting song as it is, with no lookup`() = runTest {
        mode(com.stash.core.model.DownloadMode.OPUS)
        coEvery { downloadQueueDao.waitingForLosslessTracks(any()) } returns
            listOf(entry(id = 100L, trackId = 1L), entry(id = 101L, trackId = 2L))
        coEvery { trackDao.getById(any()) } answers { stubTrackEntity(firstArg()) }

        newWorker().doWork()

        coVerify(exactly = 0) { registry.resolve(any()) }
        coVerify(exactly = 0) { tidalAacResolver.resolve(any(), any(), any()) }
        coVerify(exactly = 1) { enqueuer.enqueue(100L) }
        coVerify(exactly = 1) { enqueuer.enqueue(101L) }
    }

    @Test
    fun `AAC mode hands what it found to the download, so the relay answers once`() = runTest {
        mode(com.stash.core.model.DownloadMode.AAC)
        coEvery { downloadQueueDao.waitingForLosslessTracks(any()) } returns listOf(entry(id = 100L, trackId = 1L))
        coEvery { trackDao.getById(1L) } returns stubTrackEntity(1L)
        coEvery { tidalAacResolver.resolve(any(), aacOnly = true, foreground = any()) } returns
            com.stash.data.download.lossless.tidal.TidalAac.Found(stubSourceResult())

        newWorker().doWork()

        assertEquals(stubSourceResult(), handoff.take(1L, ResolvedSourceHandoff.Kind.TIDAL_AAC))
    }

    @Test
    fun `Lossless mode hands the found match to the download too`() = runTest {
        coEvery { downloadQueueDao.waitingForLosslessTracks(any()) } returns listOf(entry(id = 100L, trackId = 1L))
        coEvery { trackDao.getById(1L) } returns stubTrackEntity(1L)
        coEvery { registry.resolve(any()) } returns stubSourceResult()

        newWorker().doWork()

        assertEquals(stubSourceResult(), handoff.take(1L, ResolvedSourceHandoff.Kind.LOSSLESS))
    }

    @Test
    fun `a row that waited for a busy Tidal is re-queued with no check, in either mode`() = runTest {
        for (m in listOf(com.stash.core.model.DownloadMode.LOSSLESS, com.stash.core.model.DownloadMode.AAC)) {
            io.mockk.clearMocks(enqueuer, registry, tidalAacResolver, answers = false)
            mode(m)
            coEvery { downloadQueueDao.waitingForLosslessTracks(any()) } returns
                listOf(entry(id = 100L, trackId = 1L).copy(errorMessage = "wait:tidal_busy"))
            coEvery { trackDao.getById(1L) } returns stubTrackEntity(1L)

            newWorker().doWork()

            coVerify(exactly = 1) { enqueuer.enqueue(100L) }
            coVerify(exactly = 0) { registry.resolve(any()) }
            coVerify(exactly = 0) { tidalAacResolver.resolve(any(), any(), any()) }
        }
    }

    @Test
    fun `a row waiting for the reset is left alone while the day is spent`() = runTest {
        quota.onHeaders("500/500", "${now / 1000 + 3600}")
        coEvery { downloadQueueDao.waitingForLosslessTracks(any()) } returns
            listOf(entry(id = 100L, trackId = 1L).copy(errorMessage = "wait:reset"))
        coEvery { trackDao.getById(1L) } returns stubTrackEntity(1L)

        newWorker().doWork()

        coVerify(exactly = 0) { enqueuer.enqueue(any()) }
        coVerify(exactly = 0) { registry.resolve(any()) }
    }

    @Test
    fun `AAC mode stops at a busy Tidal, the resolver having booked the next sweep`() = runTest {
        mode(com.stash.core.model.DownloadMode.AAC)
        coEvery { downloadQueueDao.waitingForLosslessTracks(any()) } returns
            listOf(entry(id = 100L, trackId = 1L), entry(id = 101L, trackId = 2L))
        coEvery { trackDao.getById(any()) } answers { stubTrackEntity(firstArg()) }
        coEvery { tidalAacResolver.resolve(any(), aacOnly = true, foreground = any()) } returns
            com.stash.data.download.lossless.tidal.TidalAac.Unavailable(com.stash.data.download.lossless.tidal.TidalAac.Reason.BUSY, 0L)

        newWorker().doWork()

        coVerify(exactly = 1) { tidalAacResolver.resolve(any(), any(), any()) }
        coVerify(exactly = 0) { enqueuer.enqueue(any()) }
        assertEquals("the next pass starts with the row it stopped at", 99L, cursor.get())
    }

    @Test
    fun `a pass looks up at most MAX_LOOKUPS_PER_PASS rows and the next pass starts after them`() = runTest {
        val max = LosslessRetryWorker.MAX_LOOKUPS_PER_PASS
        val rows = (1L..(max + 10L)).map { entry(id = it, trackId = it) }
        coEvery { downloadQueueDao.waitingForLosslessTracks(any()) } returns rows
        coEvery { trackDao.getById(any()) } answers { stubTrackEntity(firstArg()) }
        val asked = mutableListOf<String>()
        coEvery { registry.resolve(any()) } answers { asked += firstArg<TrackQuery>().title; null }

        newWorker().doWork()
        assertEquals(max, asked.size)
        assertEquals("Track 1", asked.first())

        asked.clear()
        newWorker().doWork()
        // The newer rows come first now, then it wraps round to the oldest.
        assertEquals("Track ${max + 1}", asked.first())
        assertEquals(max, asked.size)
    }

    @Test
    fun `a stop during a lookup propagates instead of being swallowed`() = runTest {
        coEvery { downloadQueueDao.waitingForLosslessTracks(any()) } returns listOf(entry(id = 100L, trackId = 1L))
        coEvery { trackDao.getById(1L) } returns stubTrackEntity(1L)
        coEvery { registry.resolve(any()) } throws kotlinx.coroutines.CancellationException("stopped")

        val thrown = runCatching { newWorker().doWork() }.exceptionOrNull()

        assertTrue(thrown is kotlinx.coroutines.CancellationException)
    }

    @Test
    fun `any other sweep still resolves each row once before re-queuing it`() = runTest {
        coEvery { downloadQueueDao.waitingForLosslessTracks(any()) } returns listOf(entry(id = 100L, trackId = 1L))
        coEvery { trackDao.getById(1L) } returns stubTrackEntity(1L)
        coEvery { registry.resolve(any()) } returns stubSourceResult()

        newWorker().doWork()

        coVerify(exactly = 1) { registry.resolve(any()) }
        coVerify(exactly = 1) { enqueuer.enqueue(100L) }
    }

    @Test
    fun `a re-queued row starts downloading now, not at the next sync`() = runTest {
        coEvery { downloadQueueDao.waitingForLosslessTracks(any()) } returns listOf(entry(id = 100L, trackId = 1L))
        coEvery { trackDao.getById(1L) } returns stubTrackEntity(1L)
        coEvery { registry.resolve(any()) } returns stubSourceResult()

        newWorker().doWork()

        coVerify(exactly = 1) { enqueuer.enqueue(100L) }
    }

    /**
     * Stream-only mode downloads no sync's songs (#474: the sync's own download step refuses them
     * there), and the sweep downloads every row it resolves. So there it takes a sync's row only
     * when the user asked for the song, which the DAO's Stream-only read keeps (#532); the others
     * wait for Download mode, without spending a lossless lookup.
     */
    @Test
    fun `in Stream-only mode a sync's song nobody asked for keeps waiting`() = runTest {
        coEvery { streamingPreference.current() } returns true
        val syncedOnly = entry(id = 100L, trackId = 1L, syncId = 7L)
        val kept = entry(id = 101L, trackId = 2L, syncId = 7L)
        coEvery { downloadQueueDao.waitingForLosslessTracks(false) } returns listOf(syncedOnly, kept)
        coEvery { downloadQueueDao.waitingForLosslessTracks(true) } returns listOf(kept)
        coEvery { trackDao.getById(any()) } answers { stubTrackEntity(firstArg()) }
        coEvery { registry.resolve(any()) } returns stubSourceResult()

        newWorker().doWork()

        coVerify(exactly = 1) { registry.resolve(match { it.title == "Track 2" }) }
        coVerify(exactly = 0) { registry.resolve(match { it.title == "Track 1" }) }
        coVerify(exactly = 0) { enqueuer.enqueue(100L) }
        coVerify(exactly = 1) { enqueuer.enqueue(101L) }
    }

    @Test
    fun `in Download mode the sweep takes a sync's waiting songs too`() = runTest {
        val synced = entry(id = 100L, trackId = 1L, syncId = 7L)
        coEvery { downloadQueueDao.waitingForLosslessTracks(false) } returns listOf(synced)
        coEvery { downloadQueueDao.waitingForLosslessTracks(true) } returns emptyList()
        coEvery { trackDao.getById(1L) } returns stubTrackEntity(1L)
        coEvery { registry.resolve(any()) } returns stubSourceResult()

        newWorker().doWork()

        coVerify(exactly = 1) { enqueuer.enqueue(100L) }
    }

    @Test
    fun `the sweep is a download, and stops at the first paced row and retries later`() = runTest {
        coEvery { downloadQueueDao.waitingForLosslessTracks(any()) } returns listOf(
            entry(id = 100L, trackId = 1L),
            entry(id = 101L, trackId = 2L),
        )
        coEvery { trackDao.getById(any()) } answers { stubTrackEntity(firstArg()) }
        var labelled = false
        coEvery { registry.resolve(any()) } coAnswers {
            val purpose = currentCoroutineContext()[LosslessDownloadPurpose]
            labelled = purpose != null
            purpose?.pacedRetryAfterSec = 3600
            null
        }

        val result = newWorker().doWork()

        assertEquals(androidx.work.ListenableWorker.Result.retry(), result)
        assertEquals(true, labelled)
        coVerify(exactly = 1) { registry.resolve(any()) } // row 101 never tried
        coVerify(exactly = 0) { enqueuer.enqueue(any()) }
    }

    @Test
    fun `resolved row flips to PENDING and reports resolved=1 total=1`() = runTest {
        coEvery { downloadQueueDao.waitingForLosslessTracks(any()) } returns listOf(
            entry(id = 100L, trackId = 1L),
        )
        coEvery { trackDao.getById(1L) } returns stubTrackEntity(1L)
        coEvery { registry.resolve(any()) } returns stubSourceResult()

        val result = newWorker().doWork() as androidx.work.ListenableWorker.Result.Success

        assertEquals(1, result.outputData.getInt(LosslessRetryWorker.KEY_RESOLVED, -1))
        assertEquals(1, result.outputData.getInt(LosslessRetryWorker.KEY_TOTAL, -1))
        coVerify(exactly = 1) {
            downloadQueueDao.updateStatus(
                id = 100L,
                status = DownloadStatus.PENDING,
            )
        }
    }

    @Test
    fun `unresolved row stays WAITING_FOR_LOSSLESS and reports resolved=0 total=1`() = runTest {
        coEvery { downloadQueueDao.waitingForLosslessTracks(any()) } returns listOf(
            entry(id = 100L, trackId = 1L),
        )
        coEvery { trackDao.getById(1L) } returns stubTrackEntity(1L)
        coEvery { registry.resolve(any()) } returns null

        val result = newWorker().doWork() as androidx.work.ListenableWorker.Result.Success

        assertEquals(0, result.outputData.getInt(LosslessRetryWorker.KEY_RESOLVED, -1))
        assertEquals(1, result.outputData.getInt(LosslessRetryWorker.KEY_TOTAL, -1))
        coVerify(exactly = 0) {
            downloadQueueDao.updateStatus(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `empty deferred set returns resolved=0 total=0`() = runTest {
        coEvery { downloadQueueDao.waitingForLosslessTracks(any()) } returns emptyList()

        val result = newWorker().doWork() as androidx.work.ListenableWorker.Result.Success

        assertEquals(0, result.outputData.getInt(LosslessRetryWorker.KEY_RESOLVED, -1))
        assertEquals(0, result.outputData.getInt(LosslessRetryWorker.KEY_TOTAL, -1))
        coVerify(exactly = 0) {
            downloadQueueDao.updateStatus(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `partial match across 3 rows reports resolved=2 total=3`() = runTest {
        coEvery { downloadQueueDao.waitingForLosslessTracks(any()) } returns listOf(
            entry(id = 100L, trackId = 1L),
            entry(id = 101L, trackId = 2L),
            entry(id = 102L, trackId = 3L),
        )
        coEvery { trackDao.getById(1L) } returns stubTrackEntity(1L)
        coEvery { trackDao.getById(2L) } returns stubTrackEntity(2L)
        coEvery { trackDao.getById(3L) } returns stubTrackEntity(3L)
        coEvery { registry.resolve(match { it.title == "Track 1" }) } returns stubSourceResult()
        coEvery { registry.resolve(match { it.title == "Track 2" }) } returns null
        coEvery { registry.resolve(match { it.title == "Track 3" }) } returns stubSourceResult()

        val result = newWorker().doWork() as androidx.work.ListenableWorker.Result.Success

        assertEquals(2, result.outputData.getInt(LosslessRetryWorker.KEY_RESOLVED, -1))
        assertEquals(3, result.outputData.getInt(LosslessRetryWorker.KEY_TOTAL, -1))
        coVerify(exactly = 1) {
            downloadQueueDao.updateStatus(id = 100L, status = DownloadStatus.PENDING)
        }
        coVerify(exactly = 1) {
            downloadQueueDao.updateStatus(id = 102L, status = DownloadStatus.PENDING)
        }
        coVerify(exactly = 0) {
            downloadQueueDao.updateStatus(id = 101L, status = DownloadStatus.PENDING)
        }
    }

    private fun entry(id: Long, trackId: Long, syncId: Long? = null) = DownloadQueueEntity(
        id = id,
        trackId = trackId,
        syncId = syncId,
        status = DownloadStatus.WAITING_FOR_LOSSLESS,
        searchQuery = "test query",
    )

    private fun stubTrackEntity(id: Long) = TrackEntity(
        id = id,
        title = "Track $id",
        artist = "Artist $id",
        album = "Album $id",
        durationMs = 200_000L,
        canonicalTitle = "track $id",
        canonicalArtist = "artist $id",
        isrc = "USRC1700000$id",
    )

    private fun stubSourceResult(): SourceResult = SourceResult(
        sourceId = "squid_qobuz",
        downloadUrl = "https://example.test/file.flac",
        format = AudioFormat(codec = "flac", bitrateKbps = 0),
        confidence = 0.99f,
    )

    // -- #531 review ----------------------------------------------------------------

    @Test
    fun `a song whose audio the user picked never asks the relay`() = runTest {
        coEvery { downloadQueueDao.waitingForLosslessTracks(any()) } returns
            listOf(entry(id = 100L, trackId = 1L), entry(id = 200L, trackId = 2L))
        // Picked in Failed Matches: it downloads that video once Lossy
        // fallback is on. A lookup by title finds the recording the user
        // rejected, and spent the shared relay's quota on every trigger.
        coEvery { trackDao.getById(1L) } returns stubTrackEntity(1L).copy(matchPickedAt = 1_000L)
        coEvery { trackDao.getById(2L) } returns stubTrackEntity(2L)
        coEvery { registry.resolve(any()) } returns stubSourceResult()

        newWorker().doWork()

        coVerify(exactly = 0) { registry.resolve(match { it.title == "Track 1" }) }
        coVerify(exactly = 0) { downloadQueueDao.updateStatus(id = 100L, status = any()) }
        coVerify(exactly = 0) { enqueuer.enqueue(100L) }
        coVerify(exactly = 1) { enqueuer.enqueue(200L) }
    }
}

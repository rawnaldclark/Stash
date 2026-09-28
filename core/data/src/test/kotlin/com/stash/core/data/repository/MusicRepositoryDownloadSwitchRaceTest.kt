package com.stash.core.data.repository

import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The playlist page's Download switch flipped on, then off before on has finished
 * queueing (#474): the last position wins. Plain JVM with mocked DAOs, so each
 * interleaving is pinned by a gate rather than left to real database threads.
 */
class MusicRepositoryDownloadSwitchRaceTest {

    private val trackDao = mockk<TrackDao>(relaxed = true)
    private val playlistDao = mockk<PlaylistDao>(relaxed = true)
    private val downloadQueueDao = mockk<DownloadQueueDao>(relaxed = true)
    private val streamingPreference = mockk<StreamingPreference> { coEvery { current() } returns false }

    /** The playlist's keep_offline, as the fake DAO holds it. */
    private var kept = false

    /** The rows the switch queued and off has not deleted, by track id. */
    private val waiting = mutableListOf<Long>()

    init {
        coEvery { playlistDao.setKeepOffline(PID, any()) } answers { kept = secondArg() }
        coEvery { playlistDao.getById(PID) } answers {
            PlaylistEntity(
                id = PID, name = "Mix", source = MusicSource.BOTH, sourceId = "custom_mix",
                type = PlaylistType.CUSTOM, keepOffline = kept,
            )
        }
        every { trackDao.getByPlaylist(PID, includeStreamable = true) } returns flowOf((1L..3L).map(::track))
        coEvery { downloadQueueDao.hasRowToLeaveAlone(any()) } returns false
        coEvery { downloadQueueDao.insertAll(any()) } answers {
            waiting += firstArg<List<DownloadQueueEntity>>().map { it.trackId }
            emptyList()
        }
        coEvery { downloadQueueDao.cancelWaitingForPlaylist(PID) } answers {
            waiting.size.also { waiting.clear() }
        }
    }

    @Test fun `an off that lands while on is still checking songs wins`() = runTest {
        val onChecking = CompletableDeferred<Unit>()
        val offWritten = CompletableDeferred<Unit>()
        // On pauses in its per-song check until off has flipped the switch.
        coEvery { downloadQueueDao.hasRowToLeaveAlone(any()) } coAnswers {
            onChecking.complete(Unit)
            offWritten.await()
            false
        }
        coEvery { playlistDao.setKeepOffline(PID, false) } answers { kept = false; offWritten.complete(Unit) }
        val repo = repo()

        val on = launch { repo.setPlaylistDownload(PID, on = true) }
        onChecking.await()
        val off = launch { repo.setPlaylistDownload(PID, on = false) }
        joinAll(on, off)

        // Off came last: nothing queued, and no run started only to find nothing.
        assertEquals(emptyList<Long>(), waiting)
        coVerify(exactly = 0) { downloadQueueDao.insertAll(any()) }
        coVerify(exactly = 0) { repo.startDiscoveryDrain(any(), any()) }
    }

    @Test fun `an off that lands while on is inserting waits, then removes what on queued`() = runTest {
        val onInserting = CompletableDeferred<Unit>()
        val insert = CompletableDeferred<Unit>()
        val offWritten = CompletableDeferred<Unit>()
        coEvery { downloadQueueDao.insertAll(any()) } coAnswers {
            onInserting.complete(Unit)
            insert.await()
            waiting += firstArg<List<DownloadQueueEntity>>().map { it.trackId }
            emptyList()
        }
        coEvery { playlistDao.setKeepOffline(PID, false) } answers { kept = false; offWritten.complete(Unit) }
        val repo = repo()

        val on = launch { repo.setPlaylistDownload(PID, on = true) }
        onInserting.await()
        val off = launch { repo.setPlaylistDownload(PID, on = false) }
        offWritten.await()
        // Off has flipped the switch, but waits for on's insert instead of deleting under it.
        coVerify(exactly = 0) { downloadQueueDao.cancelWaitingForPlaylist(any()) }
        insert.complete(Unit)
        joinAll(on, off)

        assertEquals(emptyList<Long>(), waiting)
        coVerifyOrder {
            downloadQueueDao.insertAll(any())
            downloadQueueDao.cancelWaitingForPlaylist(PID)
        }
    }

    private fun track(id: Long) = TrackEntity(
        id = id, title = "T$id", artist = "A", durationMs = 1000L, source = MusicSource.SPOTIFY,
        canonicalTitle = "t$id", canonicalArtist = "a",
    )

    private fun repo(): MusicRepositoryImpl = spyk(
        MusicRepositoryImpl(
            context = mockk(relaxed = true),
            trackDao = trackDao,
            playlistDao = playlistDao,
            syncHistoryDao = mockk(relaxed = true),
            downloadQueueDao = downloadQueueDao,
            discoveryQueueDao = mockk(relaxed = true),
            blocklistGuard = mockk(relaxed = true),
            trackMatcher = mockk(relaxed = true),
            stashMixRecipeDao = mockk(relaxed = true),
            downloadNetworkPreference = mockk(relaxed = true),
            streamingPreference = streamingPreference,
            localFileOps = mockk(relaxed = true),
            syncPreferencesManager = mockk(relaxed = true),
            singleTrackDownloadEnqueuer = mockk(relaxed = true),
            lastFmRecommendationSource = mockk(relaxed = true),
            sharedMixDao = mockk(relaxed = true),
        ),
    ).also { coEvery { it.startDiscoveryDrain(any(), any()) } just Runs }

    private companion object {
        const val PID = 7L
    }
}

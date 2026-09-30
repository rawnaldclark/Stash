package com.stash.data.lyrics

import com.stash.core.common.Clock
import com.stash.core.data.db.dao.LyricsDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.LyricsEntity
import com.stash.core.data.prefs.LyricsPreference
import com.stash.core.data.prefs.LyricsSourcePreference
import com.stash.data.lyrics.sidecar.LyricsSidecarWriter
import com.stash.data.lyrics.source.AppleTtmlLyricsSource
import com.stash.data.lyrics.source.LyricsQuery
import com.stash.data.lyrics.source.LyricsResult
import com.stash.data.lyrics.source.LyricsSource
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsRepositoryTest {

    private val clock = object : Clock { override fun now() = 1_700_000_000_000L }

    private fun appleEnabledPreference(): LyricsPreference = mockk {
        every { sourcePreference } returns flowOf(LyricsSourcePreference.APPLE_MUSIC)
        coEvery { setTtmlUpgradeCursor(any()) } just Runs
    }

    @Test fun `success path - writes row, stamps, invokes sidecar`() = runTest {
        val lrclib = fakeSource("lrclib", LyricsResult("lrclib", "plain", "[00:01.00]plain", false, null, "42"))
        val ytm = fakeSource("innertube", null)
        val lyricsDao = mockk<LyricsDao>()
        val trackDao = mockk<TrackDao>()
        val sidecar = mockk<LyricsSidecarWriter>()
        coEvery { lyricsDao.get(any()) } returns null
        coEvery { lyricsDao.upsert(any()) } just Runs
        coEvery { trackDao.setLyricsFetchedAt(any(), any()) } just Runs
        coEvery { sidecar.write(any(), any()) } just Runs

        val repo = LyricsRepository(listOf(lrclib, ytm), lyricsDao, trackDao, sidecar, clock, appleEnabledPreference())
        val result = repo.resolveAndStore(query(1L))

        assertNotNull(result)
        val capture = slot<LyricsEntity>()
        coVerify { lyricsDao.upsert(capture(capture)) }
        assertEquals(1L, capture.captured.trackId)
        assertEquals("lrclib", capture.captured.source)
        coVerify { trackDao.setLyricsFetchedAt(1L, 1_700_000_000_000L) }
        coVerify { sidecar.write(1L, any()) }
    }

    @Test fun `resolveAndStore carries the existing sync offset forward instead of resetting it`() = runTest {
        val lrclib = fakeSource("lrclib", LyricsResult("lrclib", "plain", "[00:01.00]plain", false, null, "42"))
        val lyricsDao = mockk<LyricsDao>()
        val trackDao = mockk<TrackDao>(relaxed = true)
        val sidecar = mockk<LyricsSidecarWriter>(relaxed = true)
        coEvery { lyricsDao.get(1L) } returns LyricsEntity(
            trackId = 1L, plainText = "old", syncedLrc = null, instrumental = false,
            language = null, source = "lrclib", sourceLyricsId = "old", fetchedAt = 1L,
            syncOffsetMs = -1_500L,
        )
        coEvery { lyricsDao.upsert(any()) } just Runs

        val repo = LyricsRepository(listOf(lrclib), lyricsDao, trackDao, sidecar, clock, appleEnabledPreference())
        repo.resolveAndStore(query(1L))

        val capture = slot<LyricsEntity>()
        coVerify { lyricsDao.upsert(capture(capture)) }
        assertEquals(-1_500L, capture.captured.syncOffsetMs)
    }

    @Test fun `instrumental path - writes row, stamps, does NOT invoke sidecar`() = runTest {
        val lrclib = fakeSource("lrclib", LyricsResult("lrclib", null, null, true, null, "42"))
        val lyricsDao = mockk<LyricsDao>(relaxed = true)
        val trackDao = mockk<TrackDao>(relaxed = true)
        val sidecar = mockk<LyricsSidecarWriter>(relaxed = true)
        val repo = LyricsRepository(listOf(lrclib), lyricsDao, trackDao, sidecar, clock, appleEnabledPreference())
        repo.resolveAndStore(query(1L))
        coVerify(exactly = 0) { sidecar.write(any(), any()) }
        coVerify { trackDao.setLyricsFetchedAt(1L, 1_700_000_000_000L) }
    }

    @Test fun `complete miss - stamps 0L, no row, no sidecar`() = runTest {
        val a = fakeSource("lrclib", null)
        val b = fakeSource("innertube", null)
        val lyricsDao = mockk<LyricsDao>(relaxed = true)
        val trackDao = mockk<TrackDao>(relaxed = true)
        val sidecar = mockk<LyricsSidecarWriter>(relaxed = true)
        val repo = LyricsRepository(listOf(a, b), lyricsDao, trackDao, sidecar, clock, appleEnabledPreference())
        assertNull(repo.resolveAndStore(query(1L)))
        coVerify(exactly = 0) { lyricsDao.upsert(any()) }
        coVerify(exactly = 0) { sidecar.write(any(), any()) }
        coVerify { trackDao.setLyricsFetchedAt(1L, 0L) }
    }

    @Test fun `source failure with no hit - THROWS and does NOT stamp 0L`() = runTest {
        val failing = throwingSource("lrclib", java.io.IOException("lrclib get HTTP 429"))
        val miss = fakeSource("innertube", null)
        val lyricsDao = mockk<LyricsDao>(relaxed = true)
        val trackDao = mockk<TrackDao>(relaxed = true)
        val sidecar = mockk<LyricsSidecarWriter>(relaxed = true)
        val repo = LyricsRepository(listOf(failing, miss), lyricsDao, trackDao, sidecar, clock, appleEnabledPreference())
        try {
            repo.resolveAndStore(query(1L))
            org.junit.Assert.fail("expected the source failure to propagate")
        } catch (e: java.io.IOException) {
            assertEquals("lrclib get HTTP 429", e.message)
        }
        coVerify(exactly = 0) { trackDao.setLyricsFetchedAt(any(), any()) }
        coVerify(exactly = 0) { lyricsDao.upsert(any()) }
    }

    @Test fun `source failure does not block a later source hit`() = runTest {
        val failing = throwingSource("lrclib", java.io.IOException("timeout"))
        val hit = fakeSource("innertube", LyricsResult("innertube", "plain", null, false, null, null))
        val lyricsDao = mockk<LyricsDao>(relaxed = true)
        val trackDao = mockk<TrackDao>(relaxed = true)
        val sidecar = mockk<LyricsSidecarWriter>(relaxed = true)
        val repo = LyricsRepository(listOf(failing, hit), lyricsDao, trackDao, sidecar, clock, appleEnabledPreference())
        assertNotNull(repo.resolveAndStore(query(1L)))
        coVerify { trackDao.setLyricsFetchedAt(1L, 1_700_000_000_000L) }
    }

    @Test fun `resolveTransient propagates failure instead of null`() = runTest {
        val failing = throwingSource("lrclib", java.io.IOException("timeout"))
        val repo = LyricsRepository(
            listOf(failing), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), clock,
            appleEnabledPreference(),
        )
        try {
            repo.resolveTransient(query(0L))
            org.junit.Assert.fail("expected the source failure to propagate")
        } catch (expected: java.io.IOException) {
        }
    }

    @Test fun `source-chain order - first non-null wins`() = runTest {
        val a = fakeSource("lrclib", LyricsResult("lrclib", "p", null, false, null, "1"))
        val b = mockk<LyricsSource>(relaxed = true)
        val lyricsDao = mockk<LyricsDao>(relaxed = true)
        val trackDao = mockk<TrackDao>(relaxed = true)
        val sidecar = mockk<LyricsSidecarWriter>(relaxed = true)
        val repo = LyricsRepository(listOf(a, b), lyricsDao, trackDao, sidecar, clock, appleEnabledPreference())
        repo.resolveAndStore(query(1L))
        coVerify(exactly = 0) { b.resolve(any()) }
    }

    @Test fun `sidecar failure does not unwind Room write`() = runTest {
        val lrclib = fakeSource("lrclib", LyricsResult("lrclib", "p", null, false, null, "1"))
        val lyricsDao = mockk<LyricsDao>(relaxed = true)
        val trackDao = mockk<TrackDao>(relaxed = true)
        val sidecar = mockk<LyricsSidecarWriter>()
        coEvery { sidecar.write(any(), any()) } throws RuntimeException("disk full")
        val repo = LyricsRepository(listOf(lrclib), lyricsDao, trackDao, sidecar, clock, appleEnabledPreference())
        repo.resolveAndStore(query(1L))
        coVerify { lyricsDao.upsert(any()) }
        coVerify { trackDao.setLyricsFetchedAt(1L, 1_700_000_000_000L) }
    }

    @Test fun `LRC_ONLY preference excludes apple-ttml from the chain`() = runTest {
        val apple = mockk<LyricsSource>(relaxed = true) { every { id } returns "apple-ttml" }
        val hit = fakeSource("lrclib", LyricsResult("lrclib", "p", null, false, null, "1"))
        val lyricsDao = mockk<LyricsDao>(relaxed = true)
        val trackDao = mockk<TrackDao>(relaxed = true)
        val sidecar = mockk<LyricsSidecarWriter>(relaxed = true)
        val lrcOnlyPreference = mockk<LyricsPreference> {
            every { sourcePreference } returns flowOf(LyricsSourcePreference.LRC_ONLY)
        }
        val repo = LyricsRepository(listOf(apple, hit), lyricsDao, trackDao, sidecar, clock, lrcOnlyPreference)
        repo.resolveAndStore(query(1L))
        coVerify(exactly = 0) { apple.resolve(any()) }
    }

    @Test fun `upgradeToTtml reports RATE_LIMITED for an HTTP 429 and FAILED for other errors`() = runTest {
        val lyricsDao = mockk<LyricsDao>(relaxed = true)
        val trackDao = mockk<TrackDao>(relaxed = true)
        coEvery { lyricsDao.get(1L) } returns LyricsEntity(
            trackId = 1L, plainText = "p", syncedLrc = null, instrumental = false, language = null,
            source = "lrclib", sourceLyricsId = null, fetchedAt = 1L,
        )
        coEvery { trackDao.getById(1L) } returns mockk(relaxed = true)
        fun repoThrowing(e: Exception) = LyricsRepository(
            listOf(throwingSource(AppleTtmlLyricsSource.SOURCE_ID, e)),
            lyricsDao, trackDao, mockk(relaxed = true), clock, appleEnabledPreference(),
        )

        assertEquals(
            TtmlUpgradeResult.RATE_LIMITED,
            repoThrowing(AppleTtmlLyricsSource.HttpStatusException(429, "lyrics.paxsenix.org")).upgradeToTtml(1L),
        )
        assertEquals(
            TtmlUpgradeResult.FAILED,
            repoThrowing(AppleTtmlLyricsSource.HttpStatusException(503, "lyrics.paxsenix.org")).upgradeToTtml(1L),
        )
    }

    @Test fun `a clean Apple miss stamps ttmlCheckedAt, an Apple error carries the old stamp`() = runTest {
        val lrclib = fakeSource("lrclib", LyricsResult("lrclib", "p", "[00:01.00]p", false, null, "1"))
        val lyricsDao = mockk<LyricsDao>(relaxed = true)
        val trackDao = mockk<TrackDao>(relaxed = true)
        coEvery { lyricsDao.get(1L) } returns LyricsEntity(
            trackId = 1L, plainText = "p", syncedLrc = null, instrumental = false, language = null,
            source = "lrclib", sourceLyricsId = null, fetchedAt = 1L, ttmlCheckedAt = 42L,
        )
        val stored = mutableListOf<LyricsEntity>()
        coEvery { lyricsDao.upsert(capture(stored)) } just Runs
        fun repo(apple: LyricsSource) = LyricsRepository(
            listOf(apple, lrclib), lyricsDao, trackDao, mockk(relaxed = true), clock, appleEnabledPreference(),
        )

        repo(fakeSource(AppleTtmlLyricsSource.SOURCE_ID, null)).resolveAndStore(query(1L))
        assertEquals(1_700_000_000_000L, stored.last().ttmlCheckedAt)

        repo(throwingSource(AppleTtmlLyricsSource.SOURCE_ID, java.io.IOException("down"))).resolveAndStore(query(1L))
        assertEquals(42L, stored.last().ttmlCheckedAt)
    }

    @Test fun `missing-lyrics list resumes after the track the last run reached`() = runTest {
        val lyricsDao = mockk<LyricsDao> { coEvery { trackIdsMissingLyrics() } returns listOf(1L, 2L, 3L, 4L, 5L) }
        fun repoAt(cursor: Long) = LyricsRepository(
            emptyList(), lyricsDao, mockk(relaxed = true), mockk(relaxed = true), clock,
            mockk { every { bulkFetchCursor } returns flowOf(cursor) },
        )

        assertEquals(listOf(4L, 5L, 1L, 2L, 3L), repoAt(3L).trackIdsMissingLyrics())
        // Fresh install (0) and a cursor past every id both mean plain id order.
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), repoAt(0L).trackIdsMissingLyrics())
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), repoAt(99L).trackIdsMissingLyrics())
    }

    @Test fun `fetchLyricsNow moves the cursor before anything else`() = runTest {
        val trackDao = mockk<TrackDao> { coEvery { getById(7L) } returns null }
        val preference = mockk<LyricsPreference> { coEvery { setBulkFetchCursor(any()) } just Runs }
        val repo = LyricsRepository(emptyList(), mockk(), trackDao, mockk(), clock, preference)

        assertEquals(ManualFetchResult.SKIPPED, repo.fetchLyricsNow(7L))
        coVerify(exactly = 1) { preference.setBulkFetchCursor(7L) }
    }

    @Test fun `pending-TTML list resumes after the track the last upgrade pass reached`() = runTest {
        val lyricsDao = mockk<LyricsDao> { coEvery { trackIdsPendingTtml() } returns listOf(1L, 2L, 3L, 4L, 5L) }
        fun repoAt(cursor: Long) = LyricsRepository(
            emptyList(), lyricsDao, mockk(relaxed = true), mockk(relaxed = true), clock,
            mockk {
                every { sourcePreference } returns flowOf(LyricsSourcePreference.APPLE_MUSIC)
                every { ttmlUpgradeCursor } returns flowOf(cursor)
            },
        )

        assertEquals(listOf(4L, 5L, 1L, 2L, 3L), repoAt(3L).trackIdsPendingTtml())
        // Fresh install (0) and a cursor past every id both mean plain id order.
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), repoAt(0L).trackIdsPendingTtml())
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), repoAt(99L).trackIdsPendingTtml())
    }

    @Test fun `upgradeToTtml moves the cursor before anything else`() = runTest {
        // LRC-only returns SKIPPED straight after the cursor write; the strict DAO mocks
        // would throw if anything else ran first.
        val preference = mockk<LyricsPreference> {
            coEvery { setTtmlUpgradeCursor(any()) } just Runs
            every { sourcePreference } returns flowOf(LyricsSourcePreference.LRC_ONLY)
        }
        val repo = LyricsRepository(emptyList(), mockk(), mockk(), mockk(), clock, preference)

        assertEquals(TtmlUpgradeResult.SKIPPED, repo.upgradeToTtml(7L))
        coVerify(exactly = 1) { preference.setTtmlUpgradeCursor(7L) }
    }

    private fun fakeSource(sourceId: String, result: LyricsResult?): LyricsSource = object : LyricsSource {
        override val id = sourceId
        override val displayName = sourceId
        override suspend fun resolve(query: LyricsQuery): LyricsResult? = result
    }

    private fun throwingSource(sourceId: String, e: Exception): LyricsSource = object : LyricsSource {
        override val id = sourceId
        override val displayName = sourceId
        override suspend fun resolve(query: LyricsQuery): LyricsResult? = throw e
    }

    private fun query(id: Long) = LyricsQuery(
        trackId = id, title = "T", artist = "A", album = null, albumArtist = null,
        durationMs = 200_000, youtubeVideoId = null,
    )
}
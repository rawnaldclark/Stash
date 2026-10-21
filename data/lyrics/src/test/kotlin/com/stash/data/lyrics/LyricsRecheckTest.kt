package com.stash.data.lyrics

import com.stash.core.common.Clock
import com.stash.core.data.db.dao.LyricsDao
import com.stash.core.data.db.entity.LyricsEntity
import com.stash.core.data.prefs.LyricsPreference
import com.stash.core.data.prefs.LyricsSourcePreference
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings' "Re-check library" sweep: newest first from a resume point, every row still without
 * word timing (Apple-checked ones too, for the newer sources), Apple asked only where it never
 * answered, and a track the services refused stays first in line.
 */
class LyricsRecheckTest {

    private val clock = object : Clock { override fun now() = 1_700_000_000_000L }

    private fun preference(
        cursor: Long = 0L,
        source: LyricsSourcePreference = LyricsSourcePreference.APPLE_MUSIC,
    ): LyricsPreference = mockk {
        every { sourcePreference } returns flowOf(source)
        every { recheckCursor } returns flowOf(cursor)
        coEvery { setRecheckCursor(any()) } just Runs
        coEvery { finishRecheck(any()) } just Runs
    }

    private fun row(trackId: Long, appleCheckedAt: Long?) = LyricsEntity(
        trackId = trackId, plainText = "plain", syncedLrc = "[00:01.00]plain", instrumental = false,
        language = null, source = "lrclib", sourceLyricsId = "1", fetchedAt = 1L, ttmlCheckedAt = appleCheckedAt,
    )

    private fun repo(dao: LyricsDao, pref: LyricsPreference) =
        spyk(LyricsRepository(emptyList(), dao, mockk(relaxed = true), mockk(relaxed = true), clock, pref))

    @Test fun `a fresh sweep covers every row without word timing, newest first`() = runTest {
        val dao = mockk<LyricsDao> { coEvery { trackIdsWithoutTtml() } returns listOf(9L, 7L, 4L, 2L) }

        val plan = repo(dao, preference(cursor = 0L)).recheckPlan()

        assertEquals(listOf(9L, 7L, 4L, 2L), plan.remaining)
        assertEquals(4, plan.total)
    }

    @Test fun `a resumed sweep carries on below the last track it finished`() = runTest {
        val dao = mockk<LyricsDao> { coEvery { trackIdsWithoutTtml() } returns listOf(9L, 7L, 4L, 2L) }

        val plan = repo(dao, preference(cursor = 7L)).recheckPlan()

        assertEquals(listOf(4L, 2L), plan.remaining)
        assertEquals(4, plan.total)
    }

    @Test fun `nothing to re-check under LRC-only`() = runTest {
        val plan = repo(mockk(), preference(source = LyricsSourcePreference.LRC_ONLY)).recheckPlan()
        assertTrue(plan.remaining.isEmpty())
    }

    @Test fun `a track Apple never answered asks Apple and then the on-demand sources`() = runTest {
        val dao = mockk<LyricsDao>().also { coEvery { it.get(5L) } returns row(5L, appleCheckedAt = null) }
        val pref = preference()
        val repo = repo(dao, pref)
        coEvery { repo.upgradeToTtml(5L, false, false, TtmlUpgradeSources.APPLE_THEN_ON_DEMAND) } returns TtmlUpgradeResult.UPGRADED

        assertEquals(TtmlUpgradeResult.UPGRADED, repo.recheckTrack(5L))
        coVerify(exactly = 1) { pref.setRecheckCursor(5L) }
    }

    @Test fun `a track Apple already missed asks only the on-demand sources`() = runTest {
        val dao = mockk<LyricsDao>().also { coEvery { it.get(5L) } returns row(5L, appleCheckedAt = 123L) }
        val pref = preference()
        val repo = repo(dao, pref)
        coEvery { repo.upgradeToTtml(5L, false, false, TtmlUpgradeSources.ON_DEMAND) } returns TtmlUpgradeResult.NO_TTML

        assertEquals(TtmlUpgradeResult.NO_TTML, repo.recheckTrack(5L))
        coVerify(exactly = 1) { pref.setRecheckCursor(5L) }
    }

    @Test fun `a 429 or an open skip window leaves the track first in line`() = runTest {
        val dao = mockk<LyricsDao>().also { coEvery { it.get(any()) } returns row(5L, appleCheckedAt = 123L) }
        val pref = preference()
        val repo = repo(dao, pref)
        coEvery { repo.upgradeToTtml(5L, false, false, TtmlUpgradeSources.ON_DEMAND) } returns TtmlUpgradeResult.RATE_LIMITED
        coEvery { repo.upgradeToTtml(6L, false, false, TtmlUpgradeSources.ON_DEMAND) } returns TtmlUpgradeResult.PAUSED

        repo.recheckTrack(5L)
        repo.recheckTrack(6L)

        coVerify(exactly = 0) { pref.setRecheckCursor(any()) }
    }

    @Test fun `finishing a sweep stamps the time`() = runTest {
        val pref = preference()
        repo(mockk(), pref).finishRecheck()
        coVerify(exactly = 1) { pref.finishRecheck(1_700_000_000_000L) }
    }
}

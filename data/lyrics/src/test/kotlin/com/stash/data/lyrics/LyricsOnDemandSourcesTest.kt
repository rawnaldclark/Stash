package com.stash.data.lyrics

import com.stash.core.common.Clock
import com.stash.core.data.db.dao.LyricsDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.LyricsEntity
import com.stash.core.data.prefs.LyricsPreference
import com.stash.core.data.prefs.LyricsSourcePreference
import com.stash.data.lyrics.source.AppleTtmlLyricsSource
import com.stash.data.lyrics.source.IsrcLyricsHostBreaker
import com.stash.data.lyrics.source.IsrcTtmlLyricsSource
import com.stash.data.lyrics.source.LyricsQuery
import com.stash.data.lyrics.source.LyricsResult
import com.stash.data.lyrics.source.LyricsSource
import com.stash.data.lyrics.source.SpicyLyricsSource
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Collections

/**
 * The lrc.red / Binimum sources inside [LyricsRepository]: asked last, only for a track being
 * played or just downloaded, at most one follow-up per track, never a write to `tracks.isrc`.
 *
 * runBlocking, not runTest: the follow-up runs detached on the repository's own IO scope.
 */
class LyricsOnDemandSourcesTest {

    private val clock = object : Clock { override fun now() = 1_700_000_000_000L }

    private val ttmlResult = LyricsResult(
        IsrcTtmlLyricsSource.LRC_RED_ID, "Hi", "[00:01.00]Hi", false, null, "USAAA0000001", ttml = "<tt/>",
    )
    private val lrcResult = LyricsResult("lrclib", "Hi", "[00:01.00]Hi", false, null, "42")

    /** A source that records every query and answers with [answer]. */
    private class Recording(
        override val id: String,
        override val onDemandOnly: Boolean = false,
        val answer: suspend (LyricsQuery) -> LyricsResult? = { null },
    ) : LyricsSource {
        override val displayName = id
        val calls: MutableList<LyricsQuery> = Collections.synchronizedList(mutableListOf())
        val asked = CompletableDeferred<Unit>()
        override suspend fun resolve(query: LyricsQuery): LyricsResult? {
            calls += query
            asked.complete(Unit)
            return answer(query)
        }
    }

    private fun preference(pref: LyricsSourcePreference = LyricsSourcePreference.APPLE_MUSIC): LyricsPreference = mockk {
        every { sourcePreference } returns flowOf(pref)
        coEvery { setTtmlUpgradeCursor(any()) } just Runs
    }

    private val stored = Collections.synchronizedList(mutableListOf<LyricsEntity>())

    private fun lyricsDao(previous: LyricsEntity? = null): LyricsDao = mockk(relaxed = true) {
        // The row as last written, else [previous].
        coEvery { get(1L) } answers { stored.lastOrNull() ?: previous }
        coEvery { upsert(any()) } answers { stored += firstArg<LyricsEntity>() }
    }

    private fun trackDao(): TrackDao = mockk(relaxed = true) {
        coEvery { getById(1L) } returns mockk(relaxed = true)
    }

    private fun query(onDemand: Boolean = true) = LyricsQuery(
        trackId = 1L, title = "T", artist = "A", album = null, albumArtist = null,
        durationMs = 200_000, youtubeVideoId = null, isrc = "USAAA0000001", interactive = true, onDemand = onDemand,
    )

    private fun repo(sources: List<LyricsSource>, dao: LyricsDao, tracks: TrackDao = trackDao(), pref: LyricsPreference = preference()) =
        LyricsRepository(sources, dao, tracks, mockk(relaxed = true), clock, pref)

    @Test fun `an existing source's answer is never delayed by the on-demand sources`() = runBlocking {
        // lrc.red would hang forever if the walk waited on it.
        val lrcRed = Recording(IsrcTtmlLyricsSource.LRC_RED_ID, onDemandOnly = true) { awaitCancellation() }
        val apple = Recording(AppleTtmlLyricsSource.SOURCE_ID)
        val lrclib = Recording("lrclib") { lrcResult }

        val result = withTimeout(5_000) { repo(listOf(apple, lrclib, lrcRed), lyricsDao()).resolveAndStore(query()) }

        assertEquals("lrclib", result!!.source)
        // ...and then gets its one detached follow-up try for word timing.
        withTimeout(5_000) { lrcRed.asked.await() }
        assertEquals(1, apple.calls.size)
    }

    @Test fun `only the background follow-up may search for ids, never the first lookup (#671)`() = runBlocking {
        val lrcRed = Recording(IsrcTtmlLyricsSource.LRC_RED_ID, onDemandOnly = true)
        val apple = Recording(AppleTtmlLyricsSource.SOURCE_ID)
        val lrclib = Recording("lrclib") { lrcResult }

        withTimeout(5_000) { repo(listOf(apple, lrclib, lrcRed), lyricsDao()).resolveAndStore(query()) }
        withTimeout(5_000) { lrcRed.asked.await() }

        assertFalse("the first lookup", apple.calls.single().searchForIds)
        assertTrue("the follow-up", lrcRed.calls.single().searchForIds)
    }

    @Test fun `the follow-up upgrades the row to word-synced and never writes tracks isrc`() = runBlocking {
        val lrcRed = Recording(IsrcTtmlLyricsSource.LRC_RED_ID, onDemandOnly = true) { ttmlResult }
        val tracks = trackDao()
        repo(listOf(Recording("lrclib") { lrcResult }, lrcRed), lyricsDao(), tracks).resolveAndStore(query())

        withTimeout(5_000) { while (stored.none { it.ttml != null }) delay(10) }
        val upgraded = stored.last()
        assertEquals(IsrcTtmlLyricsSource.LRC_RED_ID, upgraded.source)
        assertEquals("the ISRC stays in the lyrics row", "USAAA0000001", upgraded.sourceLyricsId)
        coVerify(exactly = 0) { tracks.backfillIsrcIfMissing(any(), any()) }
    }

    @Test fun `when every existing source misses, the on-demand sources are asked last`() = runBlocking {
        val order = Collections.synchronizedList(mutableListOf<String>())
        fun src(id: String, onDemand: Boolean = false, r: LyricsResult? = null) =
            Recording(id, onDemand) { order += id; r }
        val tracks = trackDao()
        val sources = listOf(
            src(AppleTtmlLyricsSource.SOURCE_ID), src("lrclib"), src("kugou"), src("innertube"),
            src(IsrcTtmlLyricsSource.LRC_RED_ID, true), src(IsrcTtmlLyricsSource.BINIMUM_ID, true, ttmlResult),
        )
        val result = repo(sources, lyricsDao(), tracks).resolveAndStore(query())

        assertEquals(
            listOf(AppleTtmlLyricsSource.SOURCE_ID, "lrclib", "kugou", "innertube", IsrcTtmlLyricsSource.LRC_RED_ID, IsrcTtmlLyricsSource.BINIMUM_ID),
            order,
        )
        assertEquals(IsrcTtmlLyricsSource.LRC_RED_ID, result!!.source) // ttmlResult's id
        coVerify(exactly = 0) { tracks.backfillIsrcIfMissing(any(), any()) }
    }

    @Test fun `a library pass never asks the on-demand sources`() = runBlocking {
        val lrcRed = Recording(IsrcTtmlLyricsSource.LRC_RED_ID, onDemandOnly = true) { ttmlResult }
        val tracks = trackDao()

        assertNull(repo(listOf(Recording("lrclib"), lrcRed), lyricsDao(), tracks).resolveAndStore(query(onDemand = false)))
        coVerify { tracks.setLyricsFetchedAt(1L, 0L) }

        // A non-word-synced hit in a pass gets no follow-up either.
        repo(listOf(Recording("lrclib") { lrcResult }, lrcRed), lyricsDao(), tracks).resolveAndStore(query(onDemand = false))
        delay(300)
        assertEquals(0, lrcRed.calls.size)
    }

    @Test fun `an on-demand source failing is a miss, not a retryable error`() = runBlocking {
        val breakerOpen = Recording(IsrcTtmlLyricsSource.LRC_RED_ID, onDemandOnly = true) { throw IsrcLyricsHostBreaker.SkippedException() }
        val tracks = trackDao()
        assertNull(repo(listOf(Recording("lrclib"), breakerOpen), lyricsDao(), tracks).resolveAndStore(query()))
        coVerify { tracks.setLyricsFetchedAt(1L, 0L) }
    }

    @Test fun `the follow-up runs at most once per track, and not for a row already TTML-checked`() = runBlocking {
        val lrcRed = Recording(IsrcTtmlLyricsSource.LRC_RED_ID, onDemandOnly = true)
        val lrclib = Recording("lrclib") { lrcResult }
        val checked = LyricsEntity(1L, "Hi", null, false, null, "lrclib", "42", 1L, ttmlCheckedAt = 42L)

        repo(listOf(lrclib, lrcRed), lyricsDao(previous = checked)).resolveAndStore(query())
        delay(300)
        assertEquals("no re-check of a row the library pass already checked", 0, lrcRed.calls.size)

        stored.clear()
        val r = repo(listOf(lrclib, lrcRed), lyricsDao())
        r.resolveAndStore(query())
        withTimeout(5_000) { lrcRed.asked.await() }
        stored.clear()
        r.resolveAndStore(query(), fresh = true)
        delay(300)
        assertEquals(1, lrcRed.calls.size)
    }

    @Test fun `the library upgrade pass asks Apple only, as on master`() = runBlocking {
        val apple = Recording(AppleTtmlLyricsSource.SOURCE_ID)
        val lrcRed = Recording(IsrcTtmlLyricsSource.LRC_RED_ID, onDemandOnly = true) { ttmlResult }
        val dao = lyricsDao(previous = LyricsEntity(1L, "Hi", null, false, null, "lrclib", "42", 1L))

        assertEquals(TtmlUpgradeResult.NO_TTML, repo(listOf(apple, lrcRed), dao).upgradeToTtml(1L))
        assertEquals(0, lrcRed.calls.size)
        coVerify { dao.markTtmlChecked(1L, any()) }
    }

    @Test fun `the Now Playing action asks Apple then the on-demand sources`() = runBlocking {
        val apple = Recording(AppleTtmlLyricsSource.SOURCE_ID)
        val lrcRed = Recording(IsrcTtmlLyricsSource.LRC_RED_ID, onDemandOnly = true) { ttmlResult }
        val tracks = trackDao()
        val dao = lyricsDao(previous = LyricsEntity(1L, "Hi", null, false, null, "lrclib", "42", 1L))

        val result = repo(listOf(apple, lrcRed), dao, tracks)
            .upgradeToTtml(1L, moveCursor = false, interactive = true, sources = TtmlUpgradeSources.APPLE_THEN_ON_DEMAND)

        assertEquals(TtmlUpgradeResult.UPGRADED, result)
        assertEquals(1, apple.calls.size)
        coVerify(exactly = 0) { tracks.backfillIsrcIfMissing(any(), any()) }
    }

    @Test fun `any HTTP 429 makes the upgrade RATE_LIMITED, even after a skip window`() = runBlocking {
        val previous = LyricsEntity(1L, "Hi", null, false, null, "lrclib", "42", 1L)
        /** Apple fails with [appleFailure], then lrc.red with [onDemandFailure]. */
        fun upgrade(appleFailure: Exception, onDemandFailure: Exception) = runBlocking {
            val sources = listOf(
                Recording(AppleTtmlLyricsSource.SOURCE_ID) { throw appleFailure },
                Recording(IsrcTtmlLyricsSource.LRC_RED_ID, onDemandOnly = true) { throw onDemandFailure },
            )
            repo(sources, lyricsDao(previous)).upgradeToTtml(1L, moveCursor = false, sources = TtmlUpgradeSources.APPLE_THEN_ON_DEMAND)
        }

        assertEquals(
            TtmlUpgradeResult.RATE_LIMITED,
            upgrade(AppleTtmlLyricsSource.AppleSkippedException(), AppleTtmlLyricsSource.HttpStatusException(429, "lrc.red")),
        )
        assertEquals(TtmlUpgradeResult.PAUSED, upgrade(AppleTtmlLyricsSource.AppleSkippedException(), IOException("down")))
        assertEquals(TtmlUpgradeResult.PAUSED, upgrade(IsrcLyricsHostBreaker.SkippedException(), IOException("down")))
        assertEquals(TtmlUpgradeResult.PAUSED, upgrade(SpicyLyricsSource.SkippedException(), IOException("down")))
        assertEquals(TtmlUpgradeResult.FAILED, upgrade(IOException("down"), AppleTtmlLyricsSource.HttpStatusException(503, "lrc.red")))
    }

    @Test fun `an on-demand-only upgrade miss stamps nothing`() = runBlocking {
        val lrcRed = Recording(IsrcTtmlLyricsSource.LRC_RED_ID, onDemandOnly = true)
        val dao = lyricsDao(previous = LyricsEntity(1L, "Hi", null, false, null, "lrclib", "42", 1L))

        assertEquals(TtmlUpgradeResult.NO_TTML, repo(listOf(lrcRed), dao).upgradeToTtml(1L, moveCursor = false, sources = TtmlUpgradeSources.ON_DEMAND))
        coVerify(exactly = 0) { dao.markTtmlChecked(any(), any()) }
    }

    @Test fun `LRC-only drops the on-demand sources too`() = runBlocking {
        val lrcRed = Recording(IsrcTtmlLyricsSource.LRC_RED_ID, onDemandOnly = true) { ttmlResult }
        assertNull(repo(listOf(Recording("lrclib"), lrcRed), lyricsDao(), pref = preference(LyricsSourcePreference.LRC_ONLY)).resolveAndStore(query()))
        assertEquals(0, lrcRed.calls.size)
    }

    // Spicy Lyrics (#837): on-demand, but in its place right after Apple rather than last.

    private val spicyLineResult = LyricsResult(SpicyLyricsSource.SOURCE_ID, "Hi", "[00:01.00]Hi", false, null, "sl-1")

    /** Master's chain with Spicy right after Apple; each source notes its id in [order] when asked. */
    private fun chainWithSpicy(order: MutableList<String>): List<Recording> {
        fun src(id: String, onDemand: Boolean = false, r: LyricsResult? = null) =
            Recording(id, onDemand) { order += id; r }
        return listOf(
            src(AppleTtmlLyricsSource.SOURCE_ID), src(SpicyLyricsSource.SOURCE_ID, onDemand = true),
            src("lrclib", r = lrcResult), src("kugou"), src("innertube"),
            src(IsrcTtmlLyricsSource.LRC_RED_ID, onDemand = true), src(IsrcTtmlLyricsSource.BINIMUM_ID, onDemand = true),
        )
    }

    @Test fun `an on-demand source right after Apple is asked there on demand, never by a pass`() = runBlocking {
        val order = Collections.synchronizedList(mutableListOf<String>())
        val sources = chainWithSpicy(order)

        assertEquals("lrclib", repo(sources, lyricsDao()).resolveAndStore(query())!!.source)
        assertEquals(listOf(AppleTtmlLyricsSource.SOURCE_ID, SpicyLyricsSource.SOURCE_ID, "lrclib"), order.take(3))
        // No word timing: the on-demand sources' one follow-up, Spicy, lrc.red and Binimum in order.
        withTimeout(5_000) { while (order.size < 6) delay(10) }
        assertEquals(
            listOf(SpicyLyricsSource.SOURCE_ID, IsrcTtmlLyricsSource.LRC_RED_ID, IsrcTtmlLyricsSource.BINIMUM_ID),
            order.drop(3),
        )

        val passOrder = Collections.synchronizedList(mutableListOf<String>())
        stored.clear()
        repo(chainWithSpicy(passOrder), lyricsDao()).resolveAndStore(query(onDemand = false))
        delay(300)
        assertEquals(listOf(AppleTtmlLyricsSource.SOURCE_ID, "lrclib"), passOrder)
    }

    @Test fun `a line-synced answer from an on-demand source is no word-sync upgrade`() = runBlocking {
        val previous = LyricsEntity(1L, "Hi", "[00:01.00]Hi", false, null, "lrclib", "42", 1L)
        val spicy = Recording(SpicyLyricsSource.SOURCE_ID, onDemandOnly = true) { spicyLineResult }

        val dao = lyricsDao(previous)
        assertEquals(TtmlUpgradeResult.NO_TTML, repo(listOf(spicy), dao).upgradeToTtml(1L, moveCursor = false, sources = TtmlUpgradeSources.ON_DEMAND))
        assertEquals("the stored lyrics stay", 0, stored.size)

        // ...and the next source still gets its turn.
        val lrcRed = Recording(IsrcTtmlLyricsSource.LRC_RED_ID, onDemandOnly = true) { ttmlResult }
        assertEquals(TtmlUpgradeResult.UPGRADED, repo(listOf(spicy, lrcRed), lyricsDao(previous)).upgradeToTtml(1L, moveCursor = false, sources = TtmlUpgradeSources.ON_DEMAND))
        assertEquals(IsrcTtmlLyricsSource.LRC_RED_ID, stored.last().source)
    }

    @Test fun `the Now Playing action asks Spicy after Apple`() = runBlocking {
        val previous = LyricsEntity(1L, "Hi", "[00:01.00]Hi", false, null, "lrclib", "42", 1L)
        val apple = Recording(AppleTtmlLyricsSource.SOURCE_ID)
        val spicyTtml = ttmlResult.copy(sourceId = SpicyLyricsSource.SOURCE_ID)
        val spicy = Recording(SpicyLyricsSource.SOURCE_ID, onDemandOnly = true) { spicyTtml }

        val result = repo(listOf(apple, spicy, Recording("lrclib")), lyricsDao(previous))
            .upgradeToTtml(1L, moveCursor = false, interactive = true, sources = TtmlUpgradeSources.APPLE_THEN_ON_DEMAND)

        assertEquals(TtmlUpgradeResult.UPGRADED, result)
        assertEquals(1, apple.calls.size)
        assertEquals(SpicyLyricsSource.SOURCE_ID, stored.last().source)
    }
}

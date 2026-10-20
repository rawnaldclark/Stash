package com.stash.core.data.lastfm

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.stash.core.data.lastfm.LastFmEnrichmentBackoff.Pass
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The enrichment workers take the newest candidates first. Tracks a run failed
 * on wait out a back-off, so the same failing tracks can't head (and end)
 * every run while older tracks are never reached.
 */
class LastFmEnrichmentBackoffTest {

    @get:Rule val tmp = TemporaryFolder()

    private val backoff by lazy {
        LastFmEnrichmentBackoff(PreferenceDataStoreFactory.create { File(tmp.root, "backoff.preferences_pb") })
    }

    /** The newest-first candidate list the DAO would answer with, honouring the limit. */
    private val library = (1L..1_000L).toList()
    private val asked = mutableListOf<Int>()
    private val fetch: suspend (Int) -> List<Long> = { limit -> asked += limit; library.take(limit) }

    @Test fun `with nothing backed off the batch is the newest tracks`() = runTest {
        assertEquals((1L..5L).toList(), backoff.candidates(Pass.TAGS, 5, NOW, fetch))
        assertEquals(listOf(5), asked)
    }

    @Test fun `tracks that failed are left out and the batch fills from further down`() = runTest {
        backoff.recordFailures(Pass.TAGS, listOf(1L, 2L, 3L), NOW)

        val batch = backoff.candidates(Pass.TAGS, 5, NOW + 1_000, fetch)

        assertEquals(listOf(4L, 5L, 6L, 7L, 8L), batch)
        assertEquals("asks for as many more rows as are backed off", listOf(8), asked)
    }

    @Test fun `a failed track is a candidate again once its back-off is over`() = runTest {
        backoff.recordFailures(Pass.TAGS, listOf(1L), NOW)

        val later = NOW + LastFmEnrichmentBackoff.BACKOFF_MS

        assertEquals(listOf(1L, 2L), backoff.candidates(Pass.TAGS, 2, later, fetch))
    }

    @Test fun `a failure stamped in the future does not hide the track`() = runTest {
        // The clock moved back since: the entry must not last until it catches up.
        backoff.recordFailures(Pass.TAGS, listOf(1L), NOW + 60_000)

        assertTrue(backoff.backedOff(Pass.TAGS, NOW).isEmpty())
    }

    @Test fun `each pass keeps its own record`() = runTest {
        backoff.recordFailures(Pass.TAGS, listOf(1L), NOW)

        assertEquals(setOf(1L), backoff.backedOff(Pass.TAGS, NOW))
        assertTrue(backoff.backedOff(Pass.TRACK_INFO, NOW).isEmpty())
    }

    @Test fun `the record keeps the newest failures within its cap`() = runTest {
        val max = LastFmEnrichmentBackoff.MAX_ENTRIES.toLong()
        backoff.recordFailures(Pass.TAGS, (1L..max).toList(), NOW)
        backoff.recordFailures(Pass.TAGS, listOf(max + 1), NOW + 1)

        val kept = backoff.backedOff(Pass.TAGS, NOW + 2)

        assertEquals(LastFmEnrichmentBackoff.MAX_ENTRIES, kept.size)
        assertTrue(max + 1 in kept)
    }

    @Test fun `unreadable entries are skipped`() {
        assertEquals(mapOf(7L to 100L), LastFmEnrichmentBackoff.decode(setOf("7:100", "junk", "8:", ":9")))
    }

    @Test fun `the art backfill waits as long as a remembered miss, not a week`() = runTest {
        backoff.recordFailures(Pass.ART_BACKFILL, listOf(1L), NOW)

        assertEquals(setOf(1L), backoff.backedOff(Pass.ART_BACKFILL, NOW + LastFmEnrichmentBackoff.BACKOFF_MS))
        assertTrue(backoff.backedOff(Pass.ART_BACKFILL, NOW + LastFmCachedMiss.TTL_MS).isEmpty())
        assertTrue("its own record", backoff.backedOff(Pass.TAGS, NOW).isEmpty())
    }

    @Test fun `the art backfill remembers more rows than a batch`() = runTest {
        backoff.recordFailures(Pass.ART_BACKFILL, (1L..1_500L).toList(), NOW)

        assertEquals(1_500, backoff.backedOff(Pass.ART_BACKFILL, NOW + 1).size)
    }

    @Test fun `candidate rows of any shape leave out the backed-off ones`() = runTest {
        data class Row(val id: Long, val title: String)
        val rows = (1L..20L).map { Row(it, "t$it") }
        backoff.recordFailures(Pass.ART_BACKFILL, listOf(1L, 2L), NOW)

        val batch = backoff.candidateRows(Pass.ART_BACKFILL, 3, NOW + 1, { it.id }) { limit -> rows.take(limit) }

        assertEquals(listOf(3L, 4L, 5L), batch.map { it.id })
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}

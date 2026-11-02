package com.stash.core.data.home

import com.stash.data.ytmusic.InnerTubeClient
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.TrackSummary
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.IOException
import java.util.Collections
import kotlin.random.Random

/**
 * [BecauseYouLikeRepository] with a fake history and the real Related page (data:ytmusic's `related_never_gonna.json`) for every
 * seed, the rows saved in a temporary folder: when it builds, when it keeps what it has, and what it seeds from.
 */
class BecauseYouLikeRepositoryTest {

    @get:Rule val folder = TemporaryFolder()

    private val day = 86_400_000L
    private var now = 1_793_000_000_000L
    private var today = 20_000L

    private val related: List<TrackSummary> by lazy {
        fun fixture(name: String) = YtFixtures.json(name)
        val inner = mock<InnerTubeClient>()
        runBlocking {
            whenever(inner.next(eq("lYBUbBu4W08"))).thenReturn(fixture("next_never_gonna.json"))
            whenever(inner.browse(eq("MPTRt_dcYZhAh5urI-1"), anyOrNull())).thenReturn(fixture("related_never_gonna.json"))
            YTMusicApiClient(inner).getRelatedSongs("lYBUbBu4W08")!!
        }
    }

    /** The phone's history, as the repository reads it. */
    private class FakeHistory : BecauseHistory {
        val plays = mutableListOf<Pair<Long, Long>>()
        val liked = mutableListOf<Long>()
        val skipped = mutableListOf<Pair<Long, Long>>()
        val library = mutableMapOf<Long, BecauseSeed?>()
        var knownIds = emptySet<String>()
        var blockedIds = emptySet<String>()
        var blocklistReads = 0
        var historyReads = 0
        var eventId = 0L
        var fullPlaysSince = mutableListOf<Long>()
        var skipIds = mutableListOf<Long>()

        override suspend fun plays(sinceMs: Long) = plays.filter { it.second >= sinceMs }.also { historyReads++ }
        override suspend fun likes(limit: Int) = liked.take(limit)
        override suspend fun tired(minSkips: Int, sinceMs: Long) =
            skipped.filter { it.second >= sinceMs }.groupingBy { it.first }.eachCount().filterValues { it >= minSkips }.keys
        override suspend fun seeds(trackIds: List<Long>) = trackIds.mapNotNull { id -> library[id]?.let { id to it } }.toMap()
        override suspend fun known(songs: List<TrackSummary>) = knownIds
        override suspend fun blocked(songs: List<TrackSummary>): Set<String> {
            blocklistReads++
            return songs.filter { it.videoId in blockedIds }.mapTo(HashSet()) { it.videoId }
        }
        override suspend fun lastEventId() = eventId
        override suspend fun lastSkipId() = skipIds.maxOrNull() ?: 0L
        override suspend fun fullPlaysAfter(eventId: Long) = fullPlaysSince.count { it > eventId }
        override suspend fun skipsAfter(skipId: Long) = skipIds.count { it > skipId }

        /** A song played through: a listen event and a play. */
        fun play(trackId: Long, at: Long) {
            plays += trackId to at
            eventId++
            fullPlaysSince += eventId
        }

        fun skip(trackId: Long, at: Long) {
            skipped += trackId to at
            skipIds += (skipIds.maxOrNull() ?: 0L) + 1
        }
    }

    private val history = FakeHistory().apply {
        library[1] = BecauseSeed("v1", "Never Gonna Give You Up", "Rick Astley", "art1")
        library[2] = BecauseSeed("v2", "Take On Me", "a-ha", "art2")
        library[3] = BecauseSeed("v3", "Running Up That Hill", "Kate Bush", null)
        library[4] = null // a local file: no YouTube video, never a seed
        repeat(5) { play(1, now - it * day) }
        repeat(3) { play(2, now - it * day) }
        play(3, now - 2 * day)
        repeat(9) { play(4, now) }
    }

    // A build reads the seeds' pages side by side (on Dispatchers.IO): record them thread-safely
    private val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var answers = true
    /** The seeds whose page doesn't answer, when [answers] is on. */
    private val silent = mutableSetOf<String>()

    private fun file() = HomeShelfStore(folder.root).read(BecauseYouLikeRepository.FILE, BecauseFile.serializer())

    private fun repo(store: HomeShelfStore = HomeShelfStore(folder.root)) = BecauseYouLikeRepository(
        store = store,
        history = history,
        today = { today },
        nowMs = { now },
        related = { videoId ->
            calls += videoId
            if (!answers || videoId in silent) null else related
        },
        random = { Random(1) },
    )

    @Test
    fun `rows are built from the songs played most, each seed's related songs, once a day`() = runTest {
        val repo = repo()
        val rows = repo.shelf()
        assertEquals(setOf("v1", "v2", "v3"), calls.toSet())
        // The same page for each seed: the first row takes the most, the others what's left (or go)
        assertTrue(rows.isNotEmpty())
        assertEquals(BecauseYouLike.CARDS, rows.first().songs.size)
        assertEquals(rows.flatMap { it.songs }.size, rows.flatMap { it.songs }.map { it.videoId }.toSet().size)
        // Seeds come best score first, and a seed is never a card
        assertEquals("v1", rows.first().seed.videoId)
        assertTrue(rows.flatMap { it.songs }.none { it.title.startsWith("Never Gonna Give You Up") })
        // Looked at again the same day: the saved rows, no request
        calls.clear()
        assertEquals(rows, repo.shelf())
        assertEquals(rows, repo().cached())
        assertEquals(emptyList<String>(), calls)
        // A new day builds them again
        today++
        repo.shelf()
        assertTrue(calls.isNotEmpty())
    }

    @Test
    fun `four songs played through since the build build the rows again, three don't`() = runTest {
        val repo = repo()
        repo.shelf()
        calls.clear()
        repeat(3) { history.play(2, now) }
        repo.shelf()
        assertEquals(emptyList<String>(), calls)
        history.play(2, now)
        repo.shelf()
        assertTrue(calls.isNotEmpty())
        // and the count starts again from that build
        calls.clear()
        repo.shelf()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `two skips since the build build the rows again`() = runTest {
        val repo = repo()
        repo.shelf()
        calls.clear()
        history.skip(3, now)
        repo.shelf()
        assertEquals(emptyList<String>(), calls)
        history.skip(5, now)
        repo.shelf()
        assertTrue(calls.isNotEmpty())
    }

    @Test
    fun `a song skipped twice in two weeks is no seed`() = runTest {
        history.skip(1, now - day)
        history.skip(1, now - 2 * day)
        repo().shelf()
        assertTrue("v1" !in calls)
        // Skips older than two weeks don't count against it
        val older = FakeHistory().apply {
            library[1] = history.library[1]
            play(1, now)
            skip(1, now - 20 * day)
            skip(1, now - 21 * day)
        }
        calls.clear()
        BecauseYouLikeRepository(HomeShelfStore(folder.newFolder()), older, { today }, { now }, { calls += it; related }, { Random(1) }).shelf()
        assertEquals(listOf("v1"), calls)
    }

    @Test
    fun `a like seeds without plays, a song without a video never does`() = runTest {
        val likes = FakeHistory().apply {
            library[7] = BecauseSeed("v7", "Africa", "Toto", null)
            library[4] = null
            liked += listOf(4L, 7L)
            repeat(9) { play(4, now) }
        }
        BecauseYouLikeRepository(HomeShelfStore(folder.root), likes, { today }, { now }, { calls += it; related }, { Random(1) }).shelf()
        assertEquals(listOf("v7"), calls)
    }

    @Test
    fun `no answer keeps the rows it had, and the next try waits an hour`() = runTest {
        val repo = repo()
        val rows = repo.shelf()
        today++
        answers = false
        assertEquals(rows, repo.shelf())
        assertTrue(file()!!.day != today)
        // Home's resume a minute later: no request
        calls.clear()
        now += 60_000
        assertEquals(rows, repo.shelf())
        assertEquals(emptyList<String>(), calls)
        // An hour on it tries again, and pages that answer build the day's rows
        now += BecauseYouLikeRepository.RETRY_MS
        answers = true
        repo.shelf()
        assertTrue(calls.isNotEmpty())
        assertEquals(today, file()!!.day)
        // A page that throws is a page that didn't answer
        val throwing = BecauseYouLikeRepository(HomeShelfStore(folder.root), history, { today + 1 }, { now }, { throw IOException("offline") }, { Random(1) })
        assertTrue(throwing.shelf().isNotEmpty())
    }

    @Test
    fun `fewer than half the seeds answering doesn't stamp the day, and keeps the rows Home has`() = runTest {
        val first = repo().shelf()
        assertTrue(first.isNotEmpty())
        today++
        // Two of three seeds' pages don't answer: the one that did would make one row; Home keeps its rows, unstamped
        silent += setOf("v2", "v3")
        assertEquals(first, repo().shelf())
        assertTrue(file()!!.day != today)
        assertTrue(file()!!.failedAt > 0)
        // None answering at all keeps them too
        now += BecauseYouLikeRepository.RETRY_MS
        silent += "v1"
        assertEquals(first, repo().shelf())
        // Two of three answering is enough: stamped
        now += BecauseYouLikeRepository.RETRY_MS
        silent.clear()
        silent += "v3"
        repo().shelf()
        assertEquals(today, file()!!.day)
        assertEquals(0L, file()!!.failedAt)
    }

    @Test
    fun `a short first build shows what it got, unstamped`() = runTest {
        // Nothing saved yet: one seed's row is better than none, and the next try (after the wait) builds again
        silent += setOf("v2", "v3")
        val short = repo().shelf()
        assertTrue(short.isNotEmpty())
        assertEquals(-1L, file()!!.day)
        assertTrue(file()!!.failedAt > 0)
    }

    @Test
    fun `a blocked song is never a card, and the blocklist is read once a build`() = runTest {
        val open = repo().shelf().flatMap { it.songs }
        // Block two of the first row's songs; the next day's build leaves them out
        history.blockedIds = open.take(2).mapTo(HashSet()) { it.videoId }
        history.blocklistReads = 0
        today++
        val rows = repo().shelf()
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.flatMap { it.songs }.none { it.videoId in history.blockedIds })
        assertEquals(1, history.blocklistReads)
    }

    @Test
    fun `with nothing to seed from there are no rows and no request, and the empty day is kept`() = runTest {
        repo().shelf()
        assertTrue(file() != null)
        // The history was cleared: the rows, built from it, go
        val empty = FakeHistory()
        calls.clear()
        val repo = BecauseYouLikeRepository(HomeShelfStore(folder.root), empty, { today + 1 }, { now }, { calls += it; related }, { Random(1) })
        assertEquals(emptyList<BecauseRow>(), repo.shelf())
        assertEquals(emptyList<String>(), calls)
        assertEquals(emptyList<BecauseRow>(), file()!!.rows)
        assertEquals(today + 1, file()!!.day)
        // Looked at again the same day: the saved empty day, the history not read again
        val reads = empty.historyReads
        assertEquals(emptyList<BecauseRow>(), repo.shelf())
        assertEquals(reads, empty.historyReads)
        // Four songs played through: a build again
        repeat(4) { empty.play(9, now) }
        repo.shelf()
        assertEquals(reads + 1, empty.historyReads)
    }

    @Test
    fun `songs you've heard are at most a quarter of a row`() = runTest {
        history.knownIds = related.take(10).map { it.videoId }.toSet()
        val rows = repo().shelf()
        assertTrue(rows.isNotEmpty())
        for (row in rows) {
            val known = row.songs.count { it.videoId in history.knownIds }
            assertTrue(known * 3 <= row.songs.size - known)
        }
    }
}

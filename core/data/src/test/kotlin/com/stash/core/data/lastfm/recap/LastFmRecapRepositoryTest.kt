package com.stash.core.data.lastfm.recap

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.entity.ArtistGenreEntity
import com.stash.core.data.genre.WeightedGenre
import com.stash.core.data.genre.WeightedGenreCodec
import com.stash.core.data.lastfm.LastFmApiException
import com.stash.core.data.lastfm.LastFmRecentTracksPage
import com.stash.core.data.lastfm.LastFmScrobble
import com.stash.core.data.lastfm.LastFmTag
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The Last.fm recap's repository against a fake Last.fm: which periods it counts, paging and
 * caching of scrobbles, the request pacing and rate-limit back-off, and the look-ups.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LastFmRecapRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private val utc: ZoneId = ZoneOffset.UTC

    /** "Now" at the start of each test: 2026-10-19 12:00 UTC. */
    private val start = Instant.parse("2026-10-19T12:00:00Z").toEpochMilli()

    /** Joined Last.fm 2026-04-18 (the owner's account). */
    private val joined = Instant.parse("2026-04-18T15:30:02Z").epochSecond

    private fun sec(iso: String) = Instant.parse(iso).epochSecond

    data class Call(val kind: String, val from: Long, val to: Long, val page: Int, val limit: Int, val sessionKey: String?, val at: Long)

    /** A Last.fm with [scrobbles] in it, paging and counting like the real one. */
    private inner class FakeRemote(private val clock: () -> Long) : LastFmRecapRemote {
        var scrobbles: List<LastFmScrobble> = emptyList()
        var registered: Long? = joined
        val failures = ArrayDeque<Throwable>()
        val durations = HashMap<String, Long?>()
        val tags = HashMap<String, List<LastFmTag>>()
        val calls = ArrayList<Call>()

        override suspend fun recentTracks(user: String, fromSec: Long, toSec: Long, page: Int, limit: Int, sessionKey: String?): Result<LastFmRecentTracksPage> {
            calls += Call("recent", fromSec, toSec, page, limit, sessionKey, clock())
            failures.removeFirstOrNull()?.let { return Result.failure(it) }
            val inRange = scrobbles.filter { it.uts in fromSec..toSec }.sortedByDescending { it.uts }
            val pages = (inRange.size + limit - 1) / limit
            val slice = inRange.drop((page - 1) * limit).take(limit)
            return Result.success(LastFmRecentTracksPage(slice, page, pages, inRange.size))
        }

        override suspend fun registeredAt(user: String): Result<Long?> {
            calls += Call("info", 0, 0, 0, 0, null, clock())
            return Result.success(registered)
        }

        override suspend fun trackDurationMs(artist: String, title: String): Result<Long?> {
            calls += Call("duration:$title", 0, 0, 0, 0, null, clock())
            failures.removeFirstOrNull()?.let { return Result.failure(it) }
            return if (title in durations) Result.success(durations[title]) else Result.failure(LastFmApiException("6", "not found"))
        }

        override suspend fun artistTags(artist: String): Result<List<LastFmTag>> {
            calls += Call("tags:$artist", 0, 0, 0, 0, null, clock())
            return tags[artist]?.let { Result.success(it) } ?: Result.failure(LastFmApiException("6", "not found"))
        }
    }

    private var genreRows: List<ArtistGenreEntity> = emptyList()
    private var genreReads = 0

    private fun TestScope.setup(): Pair<FakeRemote, LastFmRecapRepository> {
        val clock = { start + testScheduler.currentTime }
        val remote = FakeRemote(clock)
        val repo = LastFmRecapRepository(
            remote = remote,
            store = LastFmRecapStore(tmp.root),
            genreRows = { genreReads++; genreRows },
            nowMs = clock,
            io = StandardTestDispatcher(testScheduler),
        )
        return remote to repo
    }

    /** [n] scrobbles spread through [month] (UTC), one every few hours, songs cycling. */
    private fun scrobblesIn(month: YearMonth, n: Int): List<LastFmScrobble> {
        val from = month.atDay(1).atStartOfDay(utc).toEpochSecond()
        val span = month.plusMonths(1).atDay(1).atStartOfDay(utc).toEpochSecond() - from
        return (0 until n).map { i -> LastFmScrobble(from + 60 + i * (span - 120) / n, "Artist ${i % 4}", "Song ${i % 9}") }
    }

    private fun recentCalls(remote: FakeRemote) = remote.calls.filter { it.kind == "recent" }

    // ── Which periods ────────────────────────────────────────────────

    @Test fun `counts the months since joining and the year, one limit-1 call each`() = runTest {
        val (remote, repo) = setup()
        // October's 300 run to the 19th (now): none in the future.
        remote.scrobbles = scrobblesIn(YearMonth.of(2026, 8), 37) + scrobblesIn(YearMonth.of(2026, 9), 743) +
            scrobblesIn(YearMonth.of(2026, 10), 600).filter { it.uts * 1000 <= start }.take(300)
        val totals = repo.availability("rawnclark", utc)
        assertThat(totals.map { it.span.key }).containsExactly(
            "2026-10", "2026-09", "2026-08", "2026-07", "2026-06", "2026-05", "2026-04", "2026",
        ).inOrder()
        assertThat(totals.associate { it.span.key to it.total }).containsAtLeast("2026-10", 300, "2026-09", 743, "2026-08", 37, "2026-07", 0, "2026", 1080)
        assertThat(recentCalls(remote)).hasSize(8)
        assertThat(recentCalls(remote).all { it.limit == 1 && it.page == 1 }).isTrue()
        // The month in progress is asked up to now, never into the future.
        val october = recentCalls(remote).first()
        assertThat(october.to).isEqualTo(start / 1000)
    }

    @Test fun `settled totals are kept for good, the ones in progress for half an hour`() = runTest {
        val (remote, repo) = setup()
        remote.scrobbles = scrobblesIn(YearMonth.of(2026, 9), 50)
        repo.availability("rawnclark", utc)
        val first = remote.calls.size
        repo.availability("rawnclark", utc)
        assertThat(remote.calls.size).isEqualTo(first) // all cached

        advanceTimeBy(LastFmRecapRepository.COUNTS_TTL_MS + 1_000)
        repo.availability("rawnclark", utc)
        // Only October (in progress) and 2026 (in progress) are counted again; September settled
        // on Oct 15 and was fetched after that; registration is never asked again.
        val again = remote.calls.drop(first)
        assertThat(again.map { it.kind }).containsExactly("recent", "recent")
        assertThat(again.map { it.from }).containsExactly(sec("2026-10-01T00:00:00Z"), sec("2026-01-01T00:00:00Z"))
    }

    @Test fun `spans to probe never start before the user joined, and at most ten years`() {
        val months = LastFmRecapRepository.spansToProbe(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 4, 18))
        assertThat(months.first()).isEqualTo(LastFmRecapSpan.Month(YearMonth.of(2026, 10)))
        assertThat(months.filterIsInstance<LastFmRecapSpan.Month>().last()).isEqualTo(LastFmRecapSpan.Month(YearMonth.of(2026, 4)))
        val old = LastFmRecapRepository.spansToProbe(LocalDate.of(2026, 1, 5), LocalDate.of(2005, 3, 1))
        assertThat(old.filterIsInstance<LastFmRecapSpan.Month>()).hasSize(12)
        assertThat(old.filterIsInstance<LastFmRecapSpan.Year>().map { it.year }).isEqualTo((2026 downTo 2017).toList())
        // Unknown registration: this year and last.
        val unknown = LastFmRecapRepository.spansToProbe(LocalDate.of(2026, 10, 19), null)
        assertThat(unknown.filterIsInstance<LastFmRecapSpan.Year>().map { it.year }).containsExactly(2026, 2025).inOrder()
    }

    // ── Scrobbles ────────────────────────────────────────────────────

    @Test fun `a month is read in pages of 200, inside its borders, each scrobble once`() = runTest {
        val (remote, repo) = setup()
        val september = scrobblesIn(YearMonth.of(2026, 9), 450)
        // A scrobble exactly at October's first second belongs to October, a duplicate row is read once.
        remote.scrobbles = september + september.first() + LastFmScrobble(sec("2026-10-01T00:00:00Z"), "X", "Y")
        val read = repo.scrobbles("rawnclark", LastFmRecapSpan.Month(YearMonth.of(2026, 9)), utc)
        assertThat(read).hasSize(450)
        assertThat(read.map { it.uts }).isInOrder()
        val pages = recentCalls(remote)
        assertThat(pages.map { it.page }).containsExactly(1, 2, 3).inOrder()
        assertThat(pages.all { it.limit == LastFmRecapRepository.PAGE_SIZE }).isTrue()
        // An explicit `to` (the month's last second) so pages can't shift.
        assertThat(pages.map { it.to }.distinct()).containsExactly(sec("2026-10-01T00:00:00Z") - 1)
    }

    @Test fun `a settled month is read once, the month in progress again after ten minutes`() = runTest {
        val (remote, repo) = setup()
        remote.scrobbles = scrobblesIn(YearMonth.of(2026, 9), 30) + scrobblesIn(YearMonth.of(2026, 10), 25).filter { it.uts * 1000 < start }
        val sept = LastFmRecapSpan.Month(YearMonth.of(2026, 9))
        val oct = LastFmRecapSpan.Month(YearMonth.of(2026, 10))
        repo.scrobbles("u", sept, utc)
        repo.scrobbles("u", oct, utc)
        val before = remote.calls.size
        repo.scrobbles("u", sept, utc)
        repo.scrobbles("u", oct, utc)
        assertThat(remote.calls.size).isEqualTo(before)

        advanceTimeBy(LastFmRecapRepository.SCROBBLES_TTL_MS + 1_000)
        repo.scrobbles("u", sept, utc)
        assertThat(remote.calls.size).isEqualTo(before)
        repo.scrobbles("u", oct, utc)
        assertThat(recentCalls(remote).last().from).isEqualTo(sec("2026-10-01T00:00:00Z"))
        assertThat(remote.calls.size).isEqualTo(before + 1)
    }

    @Test fun `a year is read as its months, none before joining or still to come`() = runTest {
        val (remote, repo) = setup()
        remote.scrobbles = scrobblesIn(YearMonth.of(2026, 8), 37) + scrobblesIn(YearMonth.of(2026, 9), 40)
        repo.availability("u", utc) // learns the registration date (April)
        remote.calls.clear()
        val progress = ArrayList<Pair<Int, Int>>()
        val year = repo.scrobbles("u", LastFmRecapSpan.Year(2026), utc) { read, expected -> progress += read to expected }
        assertThat(year).hasSize(77)
        val months = recentCalls(remote).map { Instant.ofEpochSecond(it.from).atZone(utc).monthValue }
        // January to March (before joining) and November, December (to come) are never asked.
        assertThat(months).containsExactly(4, 5, 6, 7, 8, 9, 10).inOrder()
        assertThat(progress.last()).isEqualTo(77 to 77)
    }

    @Test fun `the month borders follow the phone's zone`() = runTest {
        val (remote, repo) = setup()
        val tokyo = ZoneId.of("Asia/Tokyo")
        // 2026-09-30 16:00 UTC is already October 1 in Tokyo.
        remote.scrobbles = listOf(LastFmScrobble(sec("2026-09-30T16:00:00Z"), "A", "T"), LastFmScrobble(sec("2026-09-30T14:00:00Z"), "A", "T"))
        val oct = repo.scrobbles("u", LastFmRecapSpan.Month(YearMonth.of(2026, 10)), tokyo)
        assertThat(oct.map { it.uts }).containsExactly(sec("2026-09-30T16:00:00Z"))
    }

    // ── Rate rules and errors ────────────────────────────────────────

    @Test fun `requests are paced at least 220 ms apart`() = runTest {
        val (remote, repo) = setup()
        remote.scrobbles = scrobblesIn(YearMonth.of(2026, 9), 900)
        repo.availability("u", utc)
        repo.scrobbles("u", LastFmRecapSpan.Month(YearMonth.of(2026, 9)), utc)
        val times = remote.calls.map { it.at }
        assertThat(times.size).isGreaterThan(10)
        times.zipWithNext().forEach { (a, b) -> assertThat(b - a).isAtLeast(LastFmRecapRepository.REQUEST_INTERVAL_MS) }
    }

    @Test fun `a rate limit backs off and tries again`() = runTest {
        val (remote, repo) = setup()
        remote.scrobbles = scrobblesIn(YearMonth.of(2026, 9), 30)
        remote.failures += LastFmApiException("29", "rate limit")
        remote.failures += LastFmApiException("29", "rate limit")
        val read = repo.scrobbles("u", LastFmRecapSpan.Month(YearMonth.of(2026, 9)), utc)
        assertThat(read).hasSize(30)
        val t = recentCalls(remote).map { it.at }
        assertThat(t[1] - t[0]).isAtLeast(5_000L)
        assertThat(t[2] - t[1]).isAtLeast(10_000L)
    }

    @Test fun `still rate limited after backing off - busy`() = runTest {
        val (remote, repo) = setup()
        repeat(4) { remote.failures += LastFmApiException("29", "rate limit") }
        val e = runCatching { repo.scrobbles("u", LastFmRecapSpan.Month(YearMonth.of(2026, 9)), utc) }.exceptionOrNull()
        assertThat((e as LastFmRecapException).reason).isEqualTo(LastFmRecapException.Reason.BUSY)
        assertThat(recentCalls(remote)).hasSize(4)
    }

    @Test fun `private, unknown and offline become calm reasons`() = runTest {
        val (remote, repo) = setup()
        assertThat(LastFmRecapRepository.reasonOf(LastFmApiException("17", "login"))).isEqualTo(LastFmRecapException.Reason.PRIVATE)
        assertThat(LastFmRecapRepository.reasonOf(LastFmApiException("6", "no user"))).isEqualTo(LastFmRecapException.Reason.NOT_FOUND)
        assertThat(LastFmRecapRepository.reasonOf(IOException("down"))).isEqualTo(LastFmRecapException.Reason.OFFLINE)
        assertThat(LastFmRecapRepository.reasonOf(IllegalStateException("?"))).isEqualTo(LastFmRecapException.Reason.FAILED)
        remote.failures += LastFmApiException("17", "login")
        try {
            repo.availability("private", utc)
            fail("expected a private profile")
        } catch (e: LastFmRecapException) {
            // registeredAt answers; the first count is refused.
            assertThat(e.reason).isEqualTo(LastFmRecapException.Reason.PRIVATE)
        }
    }

    @Test fun `the connected account's session key reaches every history read`() = runTest {
        val (remote, repo) = setup()
        remote.scrobbles = scrobblesIn(YearMonth.of(2026, 9), 250)
        repo.availability("me", utc, sessionKey = "sk")
        repo.scrobbles("me", LastFmRecapSpan.Month(YearMonth.of(2026, 9)), utc, sessionKey = "sk")
        assertThat(recentCalls(remote).map { it.sessionKey }.distinct()).containsExactly("sk")
        // And none when there is no session (the debug test user).
        remote.calls.clear()
        repo.scrobbles("other", LastFmRecapSpan.Month(YearMonth.of(2026, 8)), utc)
        assertThat(recentCalls(remote).map { it.sessionKey }.distinct()).containsExactly(null)
    }

    // ── Look-ups ─────────────────────────────────────────────────────

    @Test fun `song lengths - cached, capped, and a miss is remembered`() = runTest {
        val (remote, repo) = setup()
        remote.durations["A"] = 200_000L
        remote.durations["B"] = 180_000L
        val asks = listOf(LastFmSongAsk("a", "X", "A"), LastFmSongAsk("b", "X", "B"), LastFmSongAsk("c", "X", "C"))
        assertThat(repo.durations(asks, maxRequests = 2)).containsExactly("a", 200_000L, "b", 180_000L)
        assertThat(remote.calls.map { it.kind }).containsExactly("duration:A", "duration:B").inOrder()
        remote.calls.clear()
        // A and B come from the cache; C is asked now and isn't found (remembered as a miss).
        assertThat(repo.durations(asks)).containsExactly("a", 200_000L, "b", 180_000L)
        assertThat(remote.calls.map { it.kind }).containsExactly("duration:C")
        remote.calls.clear()
        repo.durations(asks)
        assertThat(remote.calls).isEmpty()
    }

    @Test fun `song lengths - a busy Last_fm stops the asking, nothing is remembered`() = runTest {
        val (remote, repo) = setup()
        remote.durations["A"] = 200_000L
        remote.failures += LastFmApiException("29", "rate limit")
        val asks = listOf(LastFmSongAsk("a", "X", "A"), LastFmSongAsk("b", "X", "B"))
        assertThat(repo.durations(asks)).isEmpty()
        assertThat(remote.calls).hasSize(1)
        remote.calls.clear()
        assertThat(repo.durations(asks)).containsExactly("a", 200_000L)
    }

    @Test fun `genres - the shared cache is read, only the artists it lacks are asked`() = runTest {
        val (remote, repo) = setup()
        genreRows = listOf(
            ArtistGenreEntity("Known", WeightedGenreCodec.encode(listOf(WeightedGenre("rock", 1f))), ArtistGenreEntity.STATUS_TAGGED, 1L),
            ArtistGenreEntity("Nothing", "[]", ArtistGenreEntity.STATUS_UNTAGGABLE, 1L),
        )
        remote.tags["New"] = listOf(LastFmTag("shoegaze", 1f), LastFmTag("seen live", 0.9f))
        val index = repo.genreIndex(listOf("Known", "New feat. Someone", "Nothing", "Missing"))
        assertThat(remote.calls.map { it.kind }).containsExactly("tags:New", "tags:Missing").inOrder()
        assertThat(index.genresFor("Known")!!.single().genre).isEqualTo("rock")
        assertThat(index.genresFor("New")!!.map { it.genre }).contains("shoegaze")
        assertThat(index.genresFor("Missing")).isNull()
        // Asked once: the next story reads them from the feature's own cache.
        remote.calls.clear()
        repo.genreIndex(listOf("New", "Missing"))
        assertThat(remote.calls).isEmpty()
        assertThat(genreReads).isEqualTo(2)
    }
}

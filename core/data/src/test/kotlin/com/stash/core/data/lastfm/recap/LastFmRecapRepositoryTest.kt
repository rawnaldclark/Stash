package com.stash.core.data.lastfm.recap

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.entity.ArtistGenreEntity
import com.stash.core.data.genre.WeightedGenre
import com.stash.core.data.genre.WeightedGenreCodec
import com.stash.core.data.lastfm.LastFmApiException
import com.stash.core.data.lastfm.LastFmHttpException
import com.stash.core.data.lastfm.LastFmRateLimitGate
import com.stash.core.data.lastfm.LastFmRateLimitedException
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

    private companion object {
        const val KEY = "read-key"
    }

    data class Call(val kind: String, val from: Long, val to: Long, val page: Int, val limit: Int, val sessionKey: String?, val at: Long)

    /** A Last.fm with [scrobbles] in it, paging and counting like the real one. */
    private inner class FakeRemote(private val clock: () -> Long) : LastFmRecapRemote {
        var scrobbles: List<LastFmScrobble> = emptyList()
        var registered: Long? = joined
        val failures = ArrayDeque<Throwable>()
        val durations = HashMap<String, Long?>()
        val tags = HashMap<String, List<LastFmTag>>()
        val calls = ArrayList<Call>()

        /** Users whose profile hides its listening: unsigned reads answer 17. */
        val privateUsers = HashSet<String>()

        /** Rewrites a page before it's returned (short reads, odd paging). */
        var tamper: (LastFmRecentTracksPage) -> LastFmRecentTracksPage = { it }

        /** A real breaker, fed like the client feeds it: a 29 opens [KEY] for a minute or more. */
        val gate = LastFmRateLimitGate()
        var localRefusals = 0
        var registrationFailure: Throwable? = null

        override suspend fun recentTracks(user: String, fromSec: Long, toSec: Long, page: Int, limit: Int, sessionKey: String?): Result<LastFmRecentTracksPage> {
            // The client refuses locally while every key's breaker is open: no network call.
            if (gate.isOpen(KEY, clock())) {
                localRefusals++
                return Result.failure(LastFmRateLimitedException("all keys throttled"))
            }
            calls += Call("recent", fromSec, toSec, page, limit, sessionKey, clock())
            failures.removeFirstOrNull()?.let { e ->
                if (e is LastFmApiException && e.code == "29") gate.recordRateLimited(KEY, clock())
                return Result.failure(e)
            }
            if (user in privateUsers && sessionKey == null) return Result.failure(LastFmApiException("17", "login required"))
            val inRange = scrobbles.filter { it.uts in fromSec..toSec }.sortedByDescending { it.uts }
            val pages = (inRange.size + limit - 1) / limit
            val slice = inRange.drop((page - 1) * limit).take(limit)
            return Result.success(tamper(LastFmRecentTracksPage(slice, page, pages, inRange.size)))
        }

        override suspend fun registeredAt(user: String): Result<Long?> {
            calls += Call("info", 0, 0, 0, 0, null, clock())
            registrationFailure?.let { return Result.failure(it) }
            return Result.success(registered)
        }

        override fun busyWaitMs(signed: Boolean): Long = (gate.openUntil(KEY) - clock()).coerceAtLeast(0)

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
            elapsedMs = { testScheduler.currentTime },
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

    @Test fun `still rate limited after backing off - busy`() = runTest {
        val (remote, repo) = setup()
        repeat(4) { remote.failures += LastFmApiException("29", "rate limit") }
        val e = runCatching { repo.scrobbles("u", LastFmRecapSpan.Month(YearMonth.of(2026, 9)), utc) }.exceptionOrNull()
        assertThat((e as LastFmRecapException).reason).isEqualTo(LastFmRecapException.Reason.BUSY)
        // Waited out the first minute; the second strike doubles the breaker past what a story waits.
        assertThat(recentCalls(remote)).hasSize(2)
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

    @Test fun `a public profile is read unsigned even with a session`() = runTest {
        val (remote, repo) = setup()
        remote.scrobbles = scrobblesIn(YearMonth.of(2026, 9), 250)
        repo.scrobbles("me", LastFmRecapSpan.Month(YearMonth.of(2026, 9)), utc, sessionKey = "sk")
        assertThat(recentCalls(remote).map { it.sessionKey }.distinct()).containsExactly(null)
    }

    @Test fun `a private profile reads itself signed - a second paced call, later pages signed at once`() = runTest {
        val (remote, repo) = setup()
        remote.privateUsers += "me"
        remote.scrobbles = scrobblesIn(YearMonth.of(2026, 9), 250)
        val read = repo.scrobbles("me", LastFmRecapSpan.Month(YearMonth.of(2026, 9)), utc, sessionKey = "sk")
        assertThat(read).hasSize(250)
        val calls = recentCalls(remote)
        assertThat(calls.map { it.page to it.sessionKey }).containsExactly(1 to null, 1 to "sk", 2 to "sk").inOrder()
        // The fallback is its own request: paced like any other.
        assertThat(calls[1].at - calls[0].at).isAtLeast(LastFmRecapRepository.REQUEST_INTERVAL_MS)
    }

    @Test fun `a private profile without a session is a calm private failure, never signed`() = runTest {
        val (remote, repo) = setup()
        remote.privateUsers += "hidden"
        val e = runCatching { repo.scrobbles("hidden", LastFmRecapSpan.Month(YearMonth.of(2026, 9)), utc) }.exceptionOrNull()
        assertThat((e as LastFmRecapException).reason).isEqualTo(LastFmRecapException.Reason.PRIVATE)
        assertThat(recentCalls(remote).map { it.sessionKey }).containsExactly(null)
    }

    // ── A month is kept for good only when it was read whole ─────────

    private suspend fun TestScope.readSeptemberTwice(remote: FakeRemote, repo: LastFmRecapRepository): Int {
        val sept = LastFmRecapSpan.Month(YearMonth.of(2026, 9))
        repo.scrobbles("u", sept, utc)
        val before = recentCalls(remote).size
        advanceTimeBy(LastFmRecapRepository.SCROBBLES_TTL_MS + 1_000)
        repo.scrobbles("u", sept, utc)
        return recentCalls(remote).size - before
    }

    @Test fun `a whole settled month is not read again`() = runTest {
        val (remote, repo) = setup()
        remote.scrobbles = scrobblesIn(YearMonth.of(2026, 9), 450)
        assertThat(readSeptemberTwice(remote, repo)).isEqualTo(0)
    }

    @Test fun `fewer rows than Last_fm's total - kept briefly, read again`() = runTest {
        val (remote, repo) = setup()
        remote.scrobbles = scrobblesIn(YearMonth.of(2026, 9), 450)
        // Last.fm claims 500 but only 450 come back.
        remote.tamper = { it.copy(total = 500) }
        assertThat(readSeptemberTwice(remote, repo)).isGreaterThan(0)
    }

    @Test fun `an empty page in the middle - kept briefly, read again`() = runTest {
        val (remote, repo) = setup()
        remote.scrobbles = scrobblesIn(YearMonth.of(2026, 9), 450)
        remote.tamper = { if (it.page == 2) it.copy(scrobbles = emptyList()) else it }
        assertThat(readSeptemberTwice(remote, repo)).isGreaterThan(0)
    }

    @Test fun `zero pages for a non-empty total, or no recenttracks at all - kept briefly`() = runTest {
        val (remote, repo) = setup()
        remote.scrobbles = scrobblesIn(YearMonth.of(2026, 9), 30)
        remote.tamper = { it.copy(totalPages = 0) }
        assertThat(readSeptemberTwice(remote, repo)).isGreaterThan(0)

        val (remote2, repo2) = setup()
        remote2.scrobbles = scrobblesIn(YearMonth.of(2026, 9), 30)
        remote2.tamper = { LastFmRecentTracksPage(emptyList(), 1, 0, 0, wellFormed = false) }
        assertThat(readSeptemberTwice(remote2, repo2)).isGreaterThan(0)
    }

    @Test fun `the page cap - kept briefly`() = runTest {
        val (remote, repo) = setup()
        remote.scrobbles = scrobblesIn(YearMonth.of(2026, 9), 3)
        // Claims more pages than the cap allows reading.
        remote.tamper = { it.copy(totalPages = LastFmRecapRepository.MAX_PAGES_PER_MONTH + 1, total = it.total) }
        assertThat(readSeptemberTwice(remote, repo)).isGreaterThan(0)
    }

    @Test fun `whole means at least 98 percent of the total`() {
        assertThat(LastFmRecapRepository.isWhole(98, 100)).isTrue()
        assertThat(LastFmRecapRepository.isWhole(97, 100)).isFalse()
        assertThat(LastFmRecapRepository.isWhole(0, 0)).isTrue()
        assertThat(LastFmRecapRepository.isWhole(10, -1)).isFalse()
    }

    // ── Transient failures, the real breaker, registration ───────────

    @Test fun `transient failures are retried along the ladder`() = runTest {
        val (remote, repo) = setup()
        remote.scrobbles = scrobblesIn(YearMonth.of(2026, 9), 30)
        remote.failures += LastFmApiException("16", "temporarily unavailable")
        remote.failures += LastFmHttpException(502, "Last.fm GET failed: HTTP 502")
        remote.failures += java.net.SocketTimeoutException("timeout")
        val read = repo.scrobbles("u", LastFmRecapSpan.Month(YearMonth.of(2026, 9)), utc)
        assertThat(read).hasSize(30)
        val t = recentCalls(remote).map { it.at }
        assertThat(t).hasSize(4)
        assertThat(t[1] - t[0]).isAtLeast(5_000L)
        assertThat(t[2] - t[1]).isAtLeast(10_000L)
        assertThat(t[3] - t[2]).isAtLeast(20_000L)
        assertThat(LastFmRecapRepository.isTransient(LastFmHttpException(404, "x"))).isFalse()
        assertThat(LastFmRecapRepository.isTransient(LastFmApiException("8", "x"))).isTrue()
        assertThat(LastFmRecapRepository.isTransient(LastFmApiException("11", "x"))).isTrue()
    }

    @Test fun `a rate limit waits for the breaker to reopen instead of retrying into it`() = runTest {
        val (remote, repo) = setup()
        remote.scrobbles = scrobblesIn(YearMonth.of(2026, 9), 30)
        remote.failures += LastFmApiException("29", "rate limit")
        val read = repo.scrobbles("u", LastFmRecapSpan.Month(YearMonth.of(2026, 9)), utc)
        assertThat(read).hasSize(30)
        val t = recentCalls(remote).map { it.at }
        assertThat(t).hasSize(2)
        assertThat(t[1] - t[0]).isAtLeast(LastFmRateLimitGate.BASE_COOLDOWN_MS)
        // Never a request refused locally while the breaker was still open.
        assertThat(remote.localRefusals).isEqualTo(0)
    }

    @Test fun `a breaker open longer than the story can wait - busy at once`() = runTest {
        val (remote, repo) = setup()
        val clock = { start + testScheduler.currentTime }
        // Third strike in a row: open for four minutes.
        repeat(3) { remote.gate.recordRateLimited(KEY, clock()) }
        val e = runCatching { repo.scrobbles("u", LastFmRecapSpan.Month(YearMonth.of(2026, 9)), utc) }.exceptionOrNull()
        assertThat((e as LastFmRecapException).reason).isEqualTo(LastFmRecapException.Reason.BUSY)
        assertThat(remote.localRefusals).isEqualTo(1)
        assertThat(recentCalls(remote)).isEmpty()
    }

    @Test fun `registration that fails reads as unknown for now, a dateless answer is remembered`() = runTest {
        val (remote, repo) = setup()
        remote.registrationFailure = java.io.IOException("down")
        val totals = repo.availability("u", utc)
        // Unknown: this year and last are probed, the row still comes.
        assertThat(totals.map { it.span.key }).contains("2025")
        remote.registrationFailure = null
        remote.registered = null
        repo.availability("u", utc)
        repo.availability("u", utc)
        // Failed once (asked again), then answered "no date" once (remembered).
        assertThat(remote.calls.count { it.kind == "info" }).isEqualTo(2)
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

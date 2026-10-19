package com.stash.core.data.lastfm.recap

import android.os.SystemClock
import android.util.Log
import com.stash.core.data.db.dao.ArtistGenreDao
import com.stash.core.data.db.entity.ArtistGenreEntity
import com.stash.core.data.genre.ArtistGenreIndex
import com.stash.core.data.genre.ArtistGenreKey
import com.stash.core.data.genre.ArtistGenreRepository
import com.stash.core.data.genre.GenreNormalizer
import com.stash.core.data.genre.WeightedGenre
import com.stash.core.data.genre.WeightedGenreCodec
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmApiException
import com.stash.core.data.lastfm.LastFmHttpException
import com.stash.core.data.lastfm.LastFmRateLimitedException
import com.stash.core.data.lastfm.LastFmRecentTracksPage
import com.stash.core.data.lastfm.LastFmScrobble
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** A month or year of Last.fm history and how many scrobbles it holds. */
data class LastFmSpanTotal(val span: LastFmRecapSpan, val total: Int)

/** A song whose length is wanted: [key] is the caller's song key (what the answer map is keyed by). */
data class LastFmSongAsk(val key: String, val artist: String, val title: String)

/** Why Last.fm history couldn't be read, so the screen can say so calmly. */
class LastFmRecapException(val reason: Reason, cause: Throwable? = null) : Exception("Last.fm recap: $reason", cause) {
    enum class Reason {
        /** The profile hides its listening (Last.fm error 17). */
        PRIVATE,

        /** Last.fm doesn't know the user (error 6): renamed or deleted. */
        NOT_FOUND,

        /** No connection. */
        OFFLINE,

        /** Rate limited, still after waiting for it to lift. */
        BUSY,

        /** Anything else (a server error still there after the retries among them). */
        FAILED,
    }
}

/**
 * The Last.fm recap's data: which months/years of a user's Last.fm history have enough scrobbles
 * ([availability]), the scrobbles of one ([scrobbles]), and the song lengths and artist genres
 * the story needs ([durations], [genreIndex]).
 *
 * **Separate from Stash's own listening (owner rule).** It has no access to listening_events,
 * tracks or play counts, so it cannot change them. What it keeps is its own: scrobbles and totals
 * in [LastFmRecapStore]'s files. From the main database it READS artist_genres; and its catalog
 * look-ups (track.getInfo, artist.getTopTags) go through [LastFmApiClient]'s cacheable reads,
 * which store Last.fm's answers in the shared `lastfm_cache` table like every other catalog
 * look-up in the app — catalog data, never listening data.
 *
 * **Last.fm's rate rules.** Every request goes through one [LastFmRecapPacer], at least
 * [REQUEST_INTERVAL_MS] apart (under Last.fm's 5 per second); a signed fallback is its own paced
 * request. History reads retry transient failures (errors 8, 11, 16, HTTP 5xx, timeouts) along
 * [BACKOFF_MS]; a rate limit waits until the client's breaker for those keys reopens
 * ([LastFmRecapRemote.busyWaitMs]) rather than retrying into it. The optional look-ups (song
 * lengths, genres) never wait: they stop and the story uses what it has.
 *
 * **Caching.** Totals and months are kept per user. A month is kept for good only when it was
 * read COMPLETELY after it settled (14 days past its end, [LastFmRecapFreshness]); a short read
 * (fewer rows than Last.fm's total, the page cap, an empty page mid-range, a malformed answer)
 * stays on the short [SCROBBLES_TTL_MS]. A year is read as its months, each stored as it completes.
 */
@Singleton
class LastFmRecapRepository internal constructor(
    private val remote: LastFmRecapRemote,
    private val store: LastFmRecapStore,
    /** artist_genres rows, read only. */
    private val genreRows: suspend () -> List<ArtistGenreEntity>,
    /** Wall clock: what "settled" and the cache ages are measured in. */
    private val nowMs: () -> Long,
    /** Monotonic clock for the pacer. */
    elapsedMs: () -> Long,
    private val io: CoroutineDispatcher,
) {
    @Inject
    constructor(api: LastFmApiClient, store: LastFmRecapStore, artistGenreDao: ArtistGenreDao) : this(
        remote = LastFmRecapApiRemote(api),
        store = store,
        genreRows = { artistGenreDao.observeAll().first() },
        nowMs = System::currentTimeMillis,
        elapsedMs = SystemClock::elapsedRealtime,
        io = Dispatchers.IO,
    )

    private val pacer = LastFmRecapPacer(REQUEST_INTERVAL_MS, elapsedMs)

    /** Serialises the store's read-modify-write of each file. Network calls happen outside it. */
    private val storeLock = Mutex()

    /** Users (lower case) whose profile needed the signed read once: their next pages go signed at once. */
    private val signedUsers: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // ── Which periods ─────────────────────────────────────────────────

    /**
     * Totals for the spans a Past recaps row can offer: the last 12 months (this one included,
     * none before the user joined) and each year from joining (at most [MAX_YEARS], this one "so
     * far" included), newest first. One `limit=1` request per span not cached; the caller keeps
     * those with enough scrobbles.
     *
     * @throws LastFmRecapException when Last.fm can't be read (private, unknown user, offline, busy).
     */
    suspend fun availability(user: String, zone: ZoneId, sessionKey: String? = null): List<LastFmSpanTotal> = withContext(io) {
        val now = nowMs()
        val counts = loadCounts(user, zone)
        val registeredAt = if (counts.registeredChecked) counts.registeredAt else registration(user, zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val registered = registeredAt?.let { Instant.ofEpochSecond(it).atZone(zone).toLocalDate() }
        spansToProbe(today, registered).map { span ->
            val bounds = span.bounds(zone)
            val cached = counts.spans[span.key]?.takeIf {
                LastFmRecapFreshness.isFresh(it.fetchedAt, bounds.last + 1, now, COUNTS_TTL_MS)
            }
            val total = cached?.total ?: run {
                val page = recentPage(user, bounds.first, toSecOf(bounds, now), page = 1, limit = 1, sessionKey = sessionKey)
                // A malformed answer is used once but never remembered.
                if (page.wellFormed) saveCounts(user, zone) { it.copy(spans = it.spans + (span.key to SpanCount(page.total, nowMs()))) }
                page.total
            }
            LastFmSpanTotal(span, total)
        }
    }

    /**
     * When [user] joined Last.fm. Last.fm's answer (a date or none) is remembered; a failed ask
     * reads as "unknown" for now (more spans are probed) instead of costing the whole row, and is
     * asked again next time. An unknown user still fails: nothing else would work either.
     */
    private suspend fun registration(user: String, zone: ZoneId): Long? = try {
        val registered = request(retry = true, signed = false) { remote.registeredAt(user) }
        saveCounts(user, zone) { it.copy(registeredAt = registered, registeredChecked = true) }
        registered
    } catch (e: LastFmRecapException) {
        if (e.reason == LastFmRecapException.Reason.NOT_FOUND) throw e
        Log.i(TAG, "registration date unknown for now (${e.reason})")
        null
    }

    // ── The scrobbles of one period ───────────────────────────────────

    /**
     * Every scrobble in [span] (local calendar month or year in [zone]), oldest first. A year is
     * read month by month; each month comes from the cache when fresh, else from Last.fm in pages
     * of [PAGE_SIZE] (at most [MAX_PAGES_PER_MONTH]) and is stored once read. Months before the
     * user joined, and months still to come, are skipped. [onProgress] gets (read so far,
     * expected total or 0 when unknown). [sessionKey]: the connected account's own key, for its
     * own name only (a private profile reads itself signed).
     *
     * @throws LastFmRecapException when Last.fm can't be read.
     */
    suspend fun scrobbles(
        user: String,
        span: LastFmRecapSpan,
        zone: ZoneId,
        sessionKey: String? = null,
        onProgress: (read: Int, expected: Int) -> Unit = { _, _ -> },
    ): List<LastFmScrobble> = withContext(io) {
        val now = nowMs()
        val counts = loadCounts(user, zone)
        val thisMonth = YearMonth.from(Instant.ofEpochMilli(now).atZone(zone))
        val joined = counts.registeredAt?.let { YearMonth.from(Instant.ofEpochSecond(it).atZone(zone)) }
        val expected = counts.spans[span.key]?.total ?: 0
        val out = ArrayList<LastFmScrobble>()
        for (month in span.months()) {
            if (month > thisMonth || (joined != null && month < joined)) continue
            val read = out.size
            out += monthScrobbles(user, month, zone, now, sessionKey) { pageRead -> onProgress(read + pageRead, expected) }
            onProgress(out.size, expected)
        }
        out.sortBy { it.uts }
        out
    }

    private suspend fun monthScrobbles(
        user: String,
        month: YearMonth,
        zone: ZoneId,
        now: Long,
        sessionKey: String?,
        onPage: (Int) -> Unit,
    ): List<LastFmScrobble> {
        val bounds = LastFmRecapSpan.Month(month).bounds(zone)
        val cached = storeLock.withLock { store.readMonth(user, month) }
            ?.takeIf { it.zone == zone.id && isMonthFresh(it, bounds.last + 1, now) }
        if (cached != null) return cached.scrobbles

        val to = toSecOf(bounds, now)
        val seen = HashSet<Triple<Long, String, String>>()
        val rows = ArrayList<LastFmScrobble>()
        var page = 1
        var totalPages: Int
        var expected = -1
        var complete = true
        do {
            val result = recentPage(user, bounds.first, to, page, PAGE_SIZE, sessionKey)
            if (page == 1) expected = result.total
            // Anything odd about a page makes the month untrustworthy as a permanent answer.
            if (!result.wellFormed) complete = false
            if (result.totalPages == 0 && result.total > 0) complete = false
            if (result.scrobbles.isEmpty() && page < result.totalPages) complete = false
            for (s in result.scrobbles) {
                // Inside the month only (Last.fm's `to` edge), each scrobble once.
                if (s.uts !in bounds) continue
                if (seen.add(Triple(s.uts, s.artist, s.title))) rows += s
            }
            totalPages = result.totalPages
            onPage(rows.size)
            page++
        } while (page <= totalPages && page <= MAX_PAGES_PER_MONTH)
        if (totalPages > MAX_PAGES_PER_MONTH) {
            Log.w(TAG, "$month: stopped at $MAX_PAGES_PER_MONTH pages of $totalPages")
            complete = false
        }
        if (!isWhole(rows.size, expected)) {
            Log.w(TAG, "$month: read ${rows.size} of $expected scrobbles; kept briefly only")
            complete = false
        }
        rows.sortBy { it.uts }
        storeLock.withLock { store.writeMonth(user, month, MonthFile(zone.id, nowMs(), rows, complete)) }
        return rows
    }

    /** A complete month follows the settled rule; an incomplete one only ever lives [SCROBBLES_TTL_MS]. */
    private fun isMonthFresh(file: MonthFile, endExclusiveSec: Long, now: Long): Boolean =
        if (file.complete) {
            LastFmRecapFreshness.isFresh(file.fetchedAt, endExclusiveSec, now, SCROBBLES_TTL_MS)
        } else {
            now - file.fetchedAt in 0 until SCROBBLES_TTL_MS
        }

    /**
     * One page of history, signed when it must be: a connected account whose profile hides its
     * listening (error 17 unsigned) reads its own history with its own [sessionKey]. That second
     * call is its own paced request, and the user's later pages go signed straight away. Without
     * a session key, a private profile stays a calm PRIVATE failure.
     */
    private suspend fun recentPage(user: String, fromSec: Long, toSec: Long, page: Int, limit: Int, sessionKey: String?): LastFmRecentTracksPage {
        val signNow = sessionKey != null && user.lowercase() in signedUsers
        return try {
            request(retry = true, signed = signNow) { remote.recentTracks(user, fromSec, toSec, page, limit, sessionKey.takeIf { signNow }) }
        } catch (e: LastFmRecapException) {
            if (signNow || sessionKey == null || e.reason != LastFmRecapException.Reason.PRIVATE) throw e
            signedUsers += user.lowercase()
            request(retry = true, signed = true) { remote.recentTracks(user, fromSec, toSec, page, limit, sessionKey) }
        }
    }

    // ── Song lengths ──────────────────────────────────────────────────

    /**
     * Song lengths in ms by [LastFmSongAsk.key], from the cache or track.getInfo (through the
     * Stash proxy; the client also keeps the answer in the shared lastfm_cache table), asking
     * about at most [maxRequests] songs not cached. Songs Last.fm has no length for are
     * remembered for [MISS_RETRY_MS]. Never throws: a busy or unreachable Last.fm just leaves
     * songs out (the caller estimates them).
     */
    suspend fun durations(asks: List<LastFmSongAsk>, maxRequests: Int = MAX_LOOKUPS): Map<String, Long> = withContext(io) {
        val now = nowMs()
        val cache = storeLock.withLock { store.readDurations() }.songs.toMutableMap()
        val fresh = HashMap<String, CachedValue<Long>>()
        var asked = 0
        for (ask in asks) {
            val hit = cache[ask.key]
            if (hit != null && (hit.value > 0 || now - hit.fetchedAt < MISS_RETRY_MS)) continue
            if (asked >= maxRequests) break
            asked++
            val ms = try {
                request(retry = false, signed = false) { remote.trackDurationMs(ask.artist, ask.title) } ?: 0L
            } catch (e: LastFmRecapException) {
                when (e.reason) {
                    LastFmRecapException.Reason.NOT_FOUND -> 0L
                    LastFmRecapException.Reason.FAILED -> continue
                    // Busy / offline / private: stop asking, use what we have.
                    else -> break
                }
            }
            fresh[ask.key] = CachedValue(ms.coerceAtLeast(0), nowMs())
        }
        if (fresh.isNotEmpty()) {
            storeLock.withLock { store.writeDurations(DurationsFile(store.readDurations().songs + fresh)) }
            cache += fresh
        }
        asks.mapNotNull { ask -> cache[ask.key]?.value?.takeIf { it > 0 }?.let { ask.key to it } }.toMap()
    }

    // ── Genres ────────────────────────────────────────────────────────

    /**
     * Genres for [artists] (most listened first): the artist_genres cache, READ only, plus the
     * artists it lacks asked of Last.fm (artist.getTopTags, at most [maxRequests]) and normalized
     * the way the genre worker does. Those extra answers live in this feature's own genres.json,
     * never in artist_genres, so Your listening's genres can't move because of the Last.fm recap.
     * Never throws: a failure costs the genres card, not the story.
     */
    suspend fun genreIndex(artists: List<String>, maxRequests: Int = MAX_LOOKUPS): ArtistGenreIndex = withContext(io) {
        val now = nowMs()
        val rows = try {
            genreRows()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "artist_genres unreadable", e)
            emptyList()
        }
        // Artists the shared cache already answered (any status): theirs is the answer.
        val known = rows.mapTo(HashSet()) { it.artistName.lowercase() }
        val cache = storeLock.withLock { store.readGenres() }.artists.toMutableMap()
        val fresh = HashMap<String, CachedValue<List<WeightedGenre>>>()
        val wanted = LinkedHashMap<String, String>() // lower-case key -> key as credited
        for (artist in artists) {
            val key = ArtistGenreKey.of(artist)
            if (key.isNotEmpty() && key.lowercase() !in known) wanted.putIfAbsent(key.lowercase(), key)
        }
        var asked = 0
        for ((lower, key) in wanted) {
            val hit = cache[lower]
            if (hit != null && now - hit.fetchedAt < GENRE_RETRY_MS) continue
            if (asked >= maxRequests) break
            asked++
            val genres = try {
                GenreNormalizer.topGenres(request(retry = false, signed = false) { remote.artistTags(key) })
            } catch (e: LastFmRecapException) {
                when (e.reason) {
                    LastFmRecapException.Reason.NOT_FOUND -> emptyList()
                    LastFmRecapException.Reason.FAILED -> continue
                    else -> break
                }
            }
            fresh[lower] = CachedValue(genres, nowMs())
        }
        if (fresh.isNotEmpty()) {
            storeLock.withLock { store.writeGenres(GenresFile(store.readGenres().artists + fresh)) }
            cache += fresh
        }
        val extra = wanted.mapNotNull { (lower, key) ->
            val hit = cache[lower]?.takeIf { it.value.isNotEmpty() } ?: return@mapNotNull null
            ArtistGenreEntity(key, WeightedGenreCodec.encode(hit.value), ArtistGenreEntity.STATUS_TAGGED, hit.fetchedAt)
        }
        ArtistGenreRepository.toIndex(rows + extra)
    }

    // ── Plumbing ──────────────────────────────────────────────────────

    private suspend fun loadCounts(user: String, zone: ZoneId): CountsFile =
        storeLock.withLock { store.readCounts(user) }?.takeIf { it.zone == zone.id } ?: CountsFile(zone.id)

    /** Read-modify-write of the user's counts under the lock (another span may have just been saved). */
    private suspend fun saveCounts(user: String, zone: ZoneId, change: (CountsFile) -> CountsFile) = storeLock.withLock {
        val current = store.readCounts(user)?.takeIf { it.zone == zone.id } ?: CountsFile(zone.id)
        store.writeCounts(user, change(current))
    }

    /** A request's `to`: the span's last second, or now while it's in progress (pages then can't shift). */
    private fun toSecOf(bounds: LongRange, now: Long): Long = minOf(bounds.last, LastFmRecapFreshness.nowSec(now))

    /**
     * One paced request. With [retry] (history reads): a rate limit waits until the breaker for
     * the keys this read uses reopens ([signed]: the primary key) and tries again, at most
     * [BACKOFF_MS].size times, never waiting longer than [MAX_BUSY_WAIT_MS] at once; a transient
     * failure waits along [BACKOFF_MS]. Without [retry] the first failure gives up.
     */
    private suspend fun <T> request(retry: Boolean, signed: Boolean, block: suspend () -> Result<T>): T {
        var attempt = 0
        while (true) {
            val result = pacer.paced { block() }
            val error = result.exceptionOrNull() ?: return result.getOrThrow()
            if (error is CancellationException) throw error
            val reason = reasonOf(error)
            if (retry && attempt < BACKOFF_MS.size) {
                val wait = when {
                    reason == LastFmRecapException.Reason.BUSY -> {
                        val gate = remote.busyWaitMs(signed)
                        if (gate > MAX_BUSY_WAIT_MS) null else if (gate > 0) gate + GATE_MARGIN_MS else BACKOFF_MS[attempt]
                    }
                    isTransient(error) -> BACKOFF_MS[attempt]
                    else -> null
                }
                if (wait != null) {
                    Log.i(TAG, "Last.fm ${if (reason == LastFmRecapException.Reason.BUSY) "busy" else "hiccup"} (${error.message}); waiting $wait ms")
                    attempt++
                    delay(wait)
                    continue
                }
            }
            throw LastFmRecapException(reason, error)
        }
    }

    companion object {
        private const val TAG = "LastFmRecap"

        /** Minimum gap between requests: at most ~4.5 a second, under Last.fm's 5. */
        const val REQUEST_INTERVAL_MS = 220L

        /** Waits before trying a history read again after a transient failure. */
        val BACKOFF_MS = longArrayOf(5_000L, 10_000L, 20_000L)

        /** A rate limit that lifts later than this gives up ("busy") rather than holding the story. */
        const val MAX_BUSY_WAIT_MS = 75_000L

        /** Slack after the breaker's reopening time, so the retry doesn't race it. */
        const val GATE_MARGIN_MS = 250L

        /** Last.fm's largest page. */
        const val PAGE_SIZE = 200

        /** A month never reads more than this many pages (30,000 scrobbles). */
        const val MAX_PAGES_PER_MONTH = 150

        /** A month whose rows fall short of this share of Last.fm's total is not complete. */
        const val COMPLETE_SHARE = 0.98

        /** Unsettled totals (this month, this year) are re-counted after this long. */
        const val COUNTS_TTL_MS = 30L * 60_000

        /** Unsettled (or incomplete) months are re-read after this long. */
        const val SCROBBLES_TTL_MS = 10L * 60_000

        /** Song lengths / artist genres asked of Last.fm per story at most. */
        const val MAX_LOOKUPS = 20

        /** A song Last.fm had no length for is asked again after this long. */
        const val MISS_RETRY_MS = 30L * 86_400_000

        /** An artist's genres from Last.fm are asked again after this long. */
        const val GENRE_RETRY_MS = 30L * 86_400_000

        /** Years offered at most (this one and the ones before it). */
        const val MAX_YEARS = 10

        /** Months offered: this one and the 11 before it. */
        const val MONTHS = 12

        /** Last.fm's "Login: User required to be logged in": the profile hides its listening. */
        private const val ERROR_LOGIN_REQUIRED = "17"

        /** Last.fm's "Operation failed", "Service offline", "Service temporarily unavailable". */
        private val TRANSIENT_ERRORS = setOf("8", "11", "16")

        /**
         * The spans to count, newest first: the last [MONTHS] months (none before [registered]'s
         * month) then the years from [registered]'s (at most [MAX_YEARS]; when it's unknown, this
         * year and last) to this one.
         */
        internal fun spansToProbe(today: LocalDate, registered: LocalDate?): List<LastFmRecapSpan> {
            val thisMonth = YearMonth.from(today)
            val joined = registered?.let(YearMonth::from)
            val months = (0 until MONTHS).map { thisMonth.minusMonths(it.toLong()) }
                .filter { joined == null || it >= joined }
                .map { LastFmRecapSpan.Month(it) }
            val firstYear = maxOf(registered?.year ?: (today.year - 1), today.year - (MAX_YEARS - 1))
            val years = (today.year downTo firstYear).map { LastFmRecapSpan.Year(it) }
            return months + years
        }

        /** [read] rows are the whole month when Last.fm's [expected] total is known and met (to [COMPLETE_SHARE]). */
        internal fun isWhole(read: Int, expected: Int): Boolean = expected >= 0 && read >= expected * COMPLETE_SHARE

        /** A failure worth trying again soon: Last.fm's transient errors, a 5xx, a timeout. */
        internal fun isTransient(error: Throwable): Boolean = when (error) {
            is SocketTimeoutException -> true
            is LastFmApiException -> error.code in TRANSIENT_ERRORS
            is LastFmHttpException -> error.status >= 500
            else -> false
        }

        /** What a failed read means for the user. */
        internal fun reasonOf(error: Throwable): LastFmRecapException.Reason = when {
            error is LastFmRateLimitedException -> LastFmRecapException.Reason.BUSY
            error is LastFmApiException && error.code == LastFmApiException.RATE_LIMIT_EXCEEDED -> LastFmRecapException.Reason.BUSY
            error is LastFmApiException && error.code == ERROR_LOGIN_REQUIRED -> LastFmRecapException.Reason.PRIVATE
            error is LastFmApiException && error.code == LastFmApiException.NOT_FOUND -> LastFmRecapException.Reason.NOT_FOUND
            error is SocketTimeoutException -> LastFmRecapException.Reason.OFFLINE
            error is IOException -> LastFmRecapException.Reason.OFFLINE
            else -> LastFmRecapException.Reason.FAILED
        }
    }
}

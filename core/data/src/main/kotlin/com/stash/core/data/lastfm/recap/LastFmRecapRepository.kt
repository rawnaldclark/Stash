package com.stash.core.data.lastfm.recap

import android.util.Log
import com.stash.core.data.db.dao.ArtistGenreDao
import com.stash.core.data.db.entity.ArtistGenreEntity
import com.stash.core.data.genre.ArtistGenreIndex
import com.stash.core.data.genre.ArtistGenreKey
import com.stash.core.data.genre.ArtistGenreRepository
import com.stash.core.data.genre.GenreNormalizer
import com.stash.core.data.genre.WeightedGenreCodec
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmApiException
import com.stash.core.data.lastfm.LastFmRateLimitedException
import com.stash.core.data.lastfm.LastFmRecentTracksPage
import com.stash.core.data.lastfm.LastFmScrobble
import com.stash.core.data.lastfm.LastFmTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
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

        /** Rate limited, still after backing off. */
        BUSY,

        /** Anything else. */
        FAILED,
    }
}

/**
 * What the Last.fm recap reads from Last.fm, as an interface so the tests can stand in for it.
 * Every call is an unsigned public read: nothing is ever written to Last.fm (no scrobble, no
 * love), and no session key is used.
 */
interface LastFmRecapRemote {
    /**
     * [sessionKey]: the connected account's own session, only ever passed for that account's own
     * username, so a profile hiding its listening can still read itself (a signed read).
     */
    suspend fun recentTracks(user: String, fromSec: Long, toSec: Long, page: Int, limit: Int, sessionKey: String? = null): Result<LastFmRecentTracksPage>
    suspend fun registeredAt(user: String): Result<Long?>
    suspend fun trackDurationMs(artist: String, title: String): Result<Long?>
    suspend fun artistTags(artist: String): Result<List<LastFmTag>>
}

/**
 * The Last.fm recap's data: which months/years of a user's Last.fm history have enough scrobbles
 * ([availability]), the scrobbles of one ([scrobbles]), and the song lengths and artist genres
 * the story needs ([durations], [genreIndex]).
 *
 * **Separate from Stash's own listening (owner rule).** It reads Last.fm and its own file cache
 * ([LastFmRecapStore]); from the main database it only READS the artist_genres cache. It has no
 * access to listening_events, tracks or play counts, so it cannot change them.
 *
 * **Last.fm's rate rules.** Every request goes through one pacer, at least [REQUEST_INTERVAL_MS]
 * apart (under Last.fm's 5 per second). A rate-limited history read backs off [BACKOFF_MS] before
 * giving up; the optional look-ups (song lengths, genres) don't wait: they stop and the story uses
 * what it has.
 *
 * **Caching.** Totals and months are kept per user; a span fetched after it settled (14 days
 * past its end, [LastFmRecapFreshness]) is kept for good, otherwise for [COUNTS_TTL_MS] /
 * [SCROBBLES_TTL_MS]. A year is read as its months, each stored as it completes.
 */
@Singleton
class LastFmRecapRepository internal constructor(
    private val remote: LastFmRecapRemote,
    private val store: LastFmRecapStore,
    /** artist_genres rows, read only. */
    private val genreRows: suspend () -> List<ArtistGenreEntity>,
    private val nowMs: () -> Long,
    private val io: CoroutineDispatcher,
) {
    @Inject
    constructor(api: LastFmApiClient, store: LastFmRecapStore, artistGenreDao: ArtistGenreDao) : this(
        remote = ApiRemote(api),
        store = store,
        genreRows = { artistGenreDao.observeAll().first() },
        nowMs = System::currentTimeMillis,
        io = Dispatchers.IO,
    )

    private val pacer = Pacer(REQUEST_INTERVAL_MS, nowMs)

    /** Serialises the store's read-modify-write of each file. Network calls happen outside it. */
    private val storeLock = Mutex()

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
        var counts = loadCounts(user, zone)
        if (counts.registeredAt == null) {
            val registered = request(retry = true) { remote.registeredAt(user) }
            if (registered != null) {
                counts = counts.copy(registeredAt = registered)
                saveCounts(user, zone) { it.copy(registeredAt = registered) }
            }
        }
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val registered = counts.registeredAt?.let { Instant.ofEpochSecond(it).atZone(zone).toLocalDate() }
        spansToProbe(today, registered).map { span ->
            val bounds = span.bounds(zone)
            val cached = counts.spans[span.key]?.takeIf {
                LastFmRecapFreshness.isFresh(it.fetchedAt, bounds.last + 1, now, COUNTS_TTL_MS)
            }
            val total = cached?.total ?: run {
                val page = request(retry = true) {
                    remote.recentTracks(user, bounds.first, toSecOf(bounds, now), page = 1, limit = 1, sessionKey = sessionKey)
                }
                saveCounts(user, zone) { it.copy(spans = it.spans + (span.key to SpanCount(page.total, nowMs()))) }
                page.total
            }
            LastFmSpanTotal(span, total)
        }
    }

    // ── The scrobbles of one period ───────────────────────────────────

    /**
     * Every scrobble in [span] (local calendar month or year in [zone]), oldest first. A year is
     * read month by month; each month comes from the cache when fresh, else from Last.fm in pages
     * of [PAGE_SIZE] (at most [MAX_PAGES_PER_MONTH]) and is stored once complete. Months before the
     * user joined, and months still to come, are skipped. [onProgress] gets (read so far,
     * expected total or 0 when unknown).
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
            ?.takeIf { it.zone == zone.id && LastFmRecapFreshness.isFresh(it.fetchedAt, bounds.last + 1, now, SCROBBLES_TTL_MS) }
        if (cached != null) return cached.scrobbles

        val to = toSecOf(bounds, now)
        val seen = HashSet<Triple<Long, String, String>>()
        val rows = ArrayList<LastFmScrobble>()
        var page = 1
        var totalPages: Int
        do {
            val result = request(retry = true) { remote.recentTracks(user, bounds.first, to, page, PAGE_SIZE, sessionKey) }
            for (s in result.scrobbles) {
                // Inside the month only (Last.fm's `to` edge), each scrobble once.
                if (s.uts !in bounds) continue
                if (seen.add(Triple(s.uts, s.artist, s.title))) rows += s
            }
            totalPages = result.totalPages
            onPage(rows.size)
            page++
        } while (page <= totalPages && page <= MAX_PAGES_PER_MONTH)
        if (totalPages > MAX_PAGES_PER_MONTH) Log.w(TAG, "$month: stopped at $MAX_PAGES_PER_MONTH pages of $totalPages")
        rows.sortBy { it.uts }
        storeLock.withLock { store.writeMonth(user, month, MonthFile(zone.id, nowMs(), rows)) }
        return rows
    }

    // ── Song lengths ──────────────────────────────────────────────────

    /**
     * Song lengths in ms by [LastFmSongAsk.key], from the cache or track.getInfo (through the
     * Stash proxy, which caches it too), asking Last.fm about at most [maxRequests] songs not
     * cached. Songs Last.fm has no length for are remembered for [MISS_RETRY_MS]. Never throws:
     * a busy or unreachable Last.fm just leaves songs out (the caller estimates them).
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
                request(retry = false) { remote.trackDurationMs(ask.artist, ask.title) } ?: 0L
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
        val fresh = HashMap<String, CachedValue<List<com.stash.core.data.genre.WeightedGenre>>>()
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
                GenreNormalizer.topGenres(request(retry = false) { remote.artistTags(key) })
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
            val genres = cache[lower]?.value?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            ArtistGenreEntity(key, WeightedGenreCodec.encode(genres), ArtistGenreEntity.STATUS_TAGGED, cache[lower]!!.fetchedAt)
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
     * One paced request. [retry]: a rate limit waits [BACKOFF_MS] between tries before giving
     * up; without it the first rate limit gives up at once.
     */
    private suspend fun <T> request(retry: Boolean, block: suspend () -> Result<T>): T {
        var attempt = 0
        while (true) {
            val result = pacer.paced { block() }
            val error = result.exceptionOrNull() ?: return result.getOrThrow()
            if (error is CancellationException) throw error
            val reason = reasonOf(error)
            if (reason == LastFmRecapException.Reason.BUSY && retry && attempt < BACKOFF_MS.size) {
                Log.i(TAG, "Last.fm busy; waiting ${BACKOFF_MS[attempt]} ms")
                delay(BACKOFF_MS[attempt++])
                continue
            }
            throw LastFmRecapException(reason, error)
        }
    }

    /** Spaces requests at least [intervalMs] apart, whoever makes them. */
    private class Pacer(private val intervalMs: Long, private val nowMs: () -> Long) {
        private val mutex = Mutex()
        private var lastAt: Long? = null

        suspend fun <T> paced(block: suspend () -> T): T {
            mutex.withLock {
                val wait = lastAt?.let { it + intervalMs - nowMs() } ?: 0L
                if (wait > 0) delay(wait)
                lastAt = nowMs()
            }
            return block()
        }
    }

    private class ApiRemote(private val api: LastFmApiClient) : LastFmRecapRemote {
        /** Users whose profile needed the signed read once: their next pages go signed at once. */
        private val signedUsers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        override suspend fun recentTracks(user: String, fromSec: Long, toSec: Long, page: Int, limit: Int, sessionKey: String?): Result<LastFmRecentTracksPage> {
            if (sessionKey != null && user.lowercase() in signedUsers) {
                return api.getUserRecentTracksSigned(sessionKey, user, fromSec, toSec, page, limit)
            }
            val plain = api.getUserRecentTracks(user, fromSec, toSec, page, limit)
            val error = plain.exceptionOrNull()
            // A private profile (17) reading itself: sign with the account's own session.
            if (sessionKey != null && error is LastFmApiException && error.code == ERROR_LOGIN_REQUIRED) {
                signedUsers += user.lowercase()
                return api.getUserRecentTracksSigned(sessionKey, user, fromSec, toSec, page, limit)
            }
            return plain
        }

        override suspend fun registeredAt(user: String) = api.getUserRegisteredAt(user)

        override suspend fun trackDurationMs(artist: String, title: String): Result<Long?> =
            api.getTrackInfo(artist, title).map { it.durationMs }

        override suspend fun artistTags(artist: String): Result<List<LastFmTag>> = api.getArtistTopTags(artist)
    }

    companion object {
        private const val TAG = "LastFmRecap"

        /** Minimum gap between requests: at most ~4.5 a second, under Last.fm's 5. */
        const val REQUEST_INTERVAL_MS = 220L

        /** Waits after a rate limit before trying a history read again. */
        val BACKOFF_MS = longArrayOf(5_000L, 10_000L, 20_000L)

        /** Last.fm's largest page. */
        const val PAGE_SIZE = 200

        /** A month never reads more than this many pages (30,000 scrobbles). */
        const val MAX_PAGES_PER_MONTH = 150

        /** Unsettled totals (this month, this year) are re-counted after this long. */
        const val COUNTS_TTL_MS = 30L * 60_000

        /** Unsettled months are re-read after this long. */
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

        /** What a failed read means for the user. */
        internal fun reasonOf(error: Throwable): LastFmRecapException.Reason = when {
            error is LastFmRateLimitedException -> LastFmRecapException.Reason.BUSY
            error is LastFmApiException && error.code == LastFmApiException.RATE_LIMIT_EXCEEDED -> LastFmRecapException.Reason.BUSY
            error is LastFmApiException && error.code == ERROR_LOGIN_REQUIRED -> LastFmRecapException.Reason.PRIVATE
            error is LastFmApiException && error.code == LastFmApiException.NOT_FOUND -> LastFmRecapException.Reason.NOT_FOUND
            error is IOException -> LastFmRecapException.Reason.OFFLINE
            else -> LastFmRecapException.Reason.FAILED
        }

        /** Last.fm's "Login: User required to be logged in": the profile hides its listening. */
        private const val ERROR_LOGIN_REQUIRED = "17"
    }
}

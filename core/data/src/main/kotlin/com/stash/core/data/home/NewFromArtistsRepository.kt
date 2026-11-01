package com.stash.core.data.home

import android.util.Log
import com.stash.core.common.TrackCanonical
import com.stash.core.data.db.dao.TopArtistRow
import com.stash.core.data.listening.ListeningHistoryRepository
import com.stash.core.data.listening.ListeningRange
import com.stash.core.data.listening.MainArtists
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.AlbumSummary
import com.stash.data.ytmusic.model.ArtistLookup
import com.stash.data.ytmusic.model.ArtistProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** What "New from your artists" saves ([HomeShelfStore]). */
@Serializable
internal data class NewFromArtistsFile(
    /** The local epoch-day of the last build enough artists answered; -1 before the first. Built again the next day. */
    val day: Long,
    /** The row Home shows. */
    val releases: List<AlbumSummary>,
    /** What's known of each artist, by [NewFromArtists.artistKey]. */
    val artists: Map<String, SavedArtist> = emptyMap(),
    /** When a build last fell short (fewer than half the artists answered): the next waits [RETRY_MS] from it. */
    val failedAt: Long = 0,
)

/**
 * One artist as "New from your artists" knows them: their YouTube Music page (or that YouTube Music answered it has
 * none), the day that was found out, and their releases as the page last listed them.
 */
@Serializable
internal data class SavedArtist(
    /** The channel id; null when YouTube Music answered it has no such artist, or the id is to be looked up again. */
    val id: String? = null,
    /** The local epoch-day [id] (or [notFound]) was found out: looked up again [NewFromArtistsRepository.LOOKUP_DAYS] on. */
    val lookedUpDay: Long = 0,
    /** YouTube Music answered: no such artist. Not searched again until [lookedUpDay] is [NewFromArtistsRepository.LOOKUP_DAYS] old. */
    val notFound: Boolean = false,
    /** Their recent releases as the page last listed them: the row keeps them on a day the page fails. */
    val releases: ArtistReleases? = null,
)

/**
 * Builds and keeps Home's "New from your artists" ([NewFromArtists]). Once a day: the first look at Home on a new
 * day builds the row again, any later look reads the saved one. A build reads your top artists from Stash's own
 * listening history (the last three months, topped up from all time when that's fewer than [NewFromArtists.ARTISTS]),
 * finds each one's YouTube Music page and reads it once ([YTMusicApiClient.getArtistPage]: ten artists, at most ten
 * browses a day).
 *
 * Finding a page: a search the first time, and again every [LOOKUP_DAYS] days (or after the page failed while the
 * others loaded: the id may be wrong). Only an exact name match is taken on its own; a loose one ("Bon" for
 * "Bon Iver") is taken only when one of the page's top songs is a song you've played by that artist. A search that
 * answered with no such artist is remembered for [LOOKUP_DAYS] days; one that didn't answer is tried again.
 *
 * Fail-soft: a build stamps the day only when at least half the artists answered; the artists whose page failed
 * keep their saved releases in the row; a build that fell short never replaces a row with an empty one, and the
 * next build waits [RETRY_MS] (Home's resume doesn't refire the requests on every return). With no listening
 * history there's no row, no file and no request.
 */
@Singleton
class NewFromArtistsRepository internal constructor(
    private val store: HomeShelfStore,
    private val today: () -> Long,
    private val nowMs: () -> Long,
    private val topArtistRows: suspend (ListeningRange) -> List<TopArtistRow>,
    private val lookUpArtist: suspend (String) -> ArtistLookup,
    private val artistPage: suspend (String) -> ArtistProfile,
    /** The titles (in [TrackCanonical.title] form) of the songs you've played by an artist (by main act key). */
    private val playedTitles: suspend (String) -> Set<String>,
) {

    @Inject
    constructor(store: HomeShelfStore, history: ListeningHistoryRepository, api: YTMusicApiClient) : this(
        store = store,
        today = history::today,
        nowMs = System::currentTimeMillis,
        topArtistRows = { range -> history.observeTopArtists(history.dayRange(range), SCAN_LIMIT).first() },
        lookUpArtist = api::lookUpArtist,
        artistPage = api::getArtistPage,
        playedTitles = { key ->
            history.observeTopTracks(history.dayRange(ListeningRange.AllTime), SONG_SCAN_LIMIT).first()
                .filter { MainArtists.key(it.track.artist) == key }
                .mapTo(HashSet()) { TrackCanonical.title(it.track.title) }
        },
    )

    private val mutex = Mutex()

    /** The saved row, as it is (yesterday's included): what Home paints before [shelf] answers. */
    suspend fun cached(): List<AlbumSummary> = withContext(Dispatchers.IO) {
        val file = store.read(FILE, NewFromArtistsFile.serializer()) ?: return@withContext emptyList()
        NewFromArtists.stillNew(file.releases, yearOf(today()))
    }

    /**
     * Today's row: the saved one when it was built today (or a build fell short less than [RETRY_MS] ago), else a
     * new build. Calls that overlap share one build. Empty when there's nothing to show.
     */
    suspend fun shelf(): List<AlbumSummary> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val day = today()
            val saved = store.read(FILE, NewFromArtistsFile.serializer())
            val waiting = saved != null && saved.failedAt > 0 && nowMs() - saved.failedAt in 0 until RETRY_MS
            if (saved != null && (saved.day == day || waiting)) {
                return@withContext NewFromArtists.stillNew(saved.releases, yearOf(day))
            }
            build(day, saved)
        }
    }

    /** What one artist's look-up came to. */
    private sealed interface Outcome {
        val saved: SavedArtist?

        /** The page loaded: its releases, and what to save. */
        data class Loaded(val releases: ArtistReleases, override val saved: SavedArtist) : Outcome

        /** YouTube Music answered: no such artist (now, or remembered). */
        data class NotOnYouTube(override val saved: SavedArtist) : Outcome

        /** No answer, the search's or the page's: what was saved stands (but a page id may be dropped, see build). */
        data class Failed(override val saved: SavedArtist?, val pageFailed: Boolean) : Outcome
    }

    private suspend fun build(day: Long, saved: NewFromArtistsFile?): List<AlbumSummary> {
        val artists = yourArtists()
        if (artists.isEmpty()) {
            // No history (or it was cleared): nothing to show, and nothing of it kept
            store.delete(FILE)
            return emptyList()
        }
        val known = saved?.artists.orEmpty()
        val gate = Semaphore(PARALLEL)
        val outcomes = coroutineScope {
            artists.map { artist ->
                val key = NewFromArtists.artistKey(artist.name)
                async { key to gate.withPermit { lookUp(artist, key, known[key], day) } }
            }.awaitAll()
        }
        val anyLoaded = outcomes.any { it.second is Outcome.Loaded }
        val entries = HashMap(known)
        for ((key, outcome) in outcomes) {
            val entry = when {
                // A page that fails while others load may be the wrong page or a gone one: look it up again next time
                outcome is Outcome.Failed && outcome.pageFailed && anyLoaded -> outcome.saved?.copy(id = null)
                else -> outcome.saved
            }
            if (entry != null) entries[key] = entry else entries.remove(key)
        }
        // The row: each loaded page, and the saved releases of an artist whose page didn't answer today
        val pages = outcomes.mapNotNull { (_, outcome) ->
            when (outcome) {
                is Outcome.Loaded -> outcome.releases
                is Outcome.Failed -> outcome.saved?.releases
                is Outcome.NotOnYouTube -> null
            }
        }
        val year = yearOf(day)
        val row = NewFromArtists.pick(pages, year)
        val answered = outcomes.count { it.second !is Outcome.Failed }
        val enough = answered * 2 >= artists.size
        val savedRow = NewFromArtists.stillNew(saved?.releases.orEmpty(), year)
        val shown = if (row.isEmpty() && answered < artists.size && savedRow.isNotEmpty()) savedRow else row
        Log.d(TAG, "built: ${artists.size} artists, $answered answered, ${shown.size} releases${if (enough) "" else " (short: not stamped)"}")
        store.write(
            FILE,
            NewFromArtistsFile.serializer(),
            NewFromArtistsFile(
                day = if (enough) day else saved?.day ?: -1,
                releases = shown,
                artists = entries,
                failedAt = if (enough) 0 else nowMs(),
            ),
        )
        return shown
    }

    /** Your top artists: the last three months first, topped up from all time. */
    private suspend fun yourArtists(): List<YourArtist> {
        val recent = NewFromArtists.topArtists(topArtistRows(ListeningRange.Months3))
        if (recent.size >= NewFromArtists.ARTISTS) return recent
        val keys = recent.mapTo(HashSet()) { NewFromArtists.artistKey(it.name) }
        val more = NewFromArtists.topArtists(topArtistRows(ListeningRange.AllTime), limit = NewFromArtists.ARTISTS * 2)
            .filter { keys.add(NewFromArtists.artistKey(it.name)) }
        return (recent + more).take(NewFromArtists.ARTISTS)
    }

    /** [artist]'s page: found (or remembered), read, and for a loose name match, checked against your plays. */
    private suspend fun lookUp(artist: YourArtist, key: String, saved: SavedArtist?, day: Long): Outcome = try {
        val fresh = saved != null && day - saved.lookedUpDay in 0 until LOOKUP_DAYS
        when {
            fresh && saved!!.notFound -> Outcome.NotOnYouTube(saved)
            fresh && saved!!.id != null -> readPage(artist, key, saved.id!!, saved, verify = false, day = day)
            else -> when (val found = lookUpArtist(artist.name)) {
                ArtistLookup.NoAnswer -> Outcome.Failed(saved, pageFailed = false)
                ArtistLookup.NotFound -> Outcome.NotOnYouTube(SavedArtist(lookedUpDay = day, notFound = true))
                is ArtistLookup.Found -> readPage(
                    artist,
                    key,
                    found.artist.id,
                    SavedArtist(id = found.artist.id, lookedUpDay = day, releases = saved?.releases),
                    verify = !found.exact,
                    day = day,
                )
            }
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.w(TAG, "page of '${artist.name}' failed: ${e.message}")
        Outcome.Failed(saved, pageFailed = true)
    }

    /**
     * [id]'s page as [artist]'s releases. With [verify] (a loose name match), the page is [artist]'s only when one of
     * its top songs is one you've played by them; else YouTube Music has someone else by that name, remembered as
     * "not there" so it isn't searched again for [LOOKUP_DAYS] days.
     */
    private suspend fun readPage(
        artist: YourArtist,
        key: String,
        id: String,
        entry: SavedArtist,
        verify: Boolean,
        day: Long,
    ): Outcome {
        val page = artistPage(id)
        // A blank, shelf-less page is a browse that never answered (YTMusicApiClient's "no answer" profile)
        if (page.name.isBlank() && page.albums.isEmpty() && page.singles.isEmpty()) return Outcome.Failed(entry, pageFailed = true)
        if (verify) {
            val played = playedTitles(key)
            val theirs = page.popular.any { TrackCanonical.title(it.title) in played }
            if (!theirs) {
                Log.d(TAG, "'${artist.name}' loosely matched '${page.name}', none of whose top songs you've played: not theirs")
                return Outcome.NotOnYouTube(SavedArtist(lookedUpDay = day, notFound = true))
            }
        }
        val releases = ArtistReleases(page.name.ifBlank { artist.name }, page.albums, page.singles)
        return Outcome.Loaded(releases, entry.copy(releases = NewFromArtists.recent(releases, yearOf(day))))
    }

    private fun yearOf(day: Long): Int = LocalDate.ofEpochDay(day).year

    internal companion object {
        private const val TAG = "NewFromArtists"
        const val FILE = "new_from_artists"

        /** Raw artist credits read to group into main acts (Your listening reads 10,000; ten acts need far fewer). */
        const val SCAN_LIMIT = 1_000

        /** Your most played songs read to confirm a loose name match (only then). */
        const val SONG_SCAN_LIMIT = 2_000

        /** Artist pages read at once. */
        const val PARALLEL = 3

        /** A channel id, or a "not on YouTube Music", is trusted this many days, then looked up again. */
        const val LOOKUP_DAYS = 30L

        /** After a build that fell short, the next one waits this long (Home's resume would otherwise refire it). */
        const val RETRY_MS = 60 * 60 * 1000L
    }
}

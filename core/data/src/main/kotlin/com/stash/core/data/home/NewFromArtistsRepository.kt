package com.stash.core.data.home

import android.util.Log
import com.stash.core.data.db.dao.TopArtistRow
import com.stash.core.data.listening.ListeningHistoryRepository
import com.stash.core.data.listening.ListeningRange
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.AlbumSummary
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
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** What "New from your artists" saves ([HomeShelfStore]): the row, the day it was made, and each artist's page id. */
@Serializable
internal data class NewFromArtistsFile(
    /** The local epoch-day the row was built: it is built again on the first look the next day. */
    val day: Long,
    val releases: List<AlbumSummary>,
    /** [NewFromArtists.artistKey] to YouTube channel id: a name is searched once, not every day. */
    val artistIds: Map<String, String> = emptyMap(),
)

/**
 * Builds and keeps Home's "New from your artists" ([NewFromArtists]). Once a day: the first look at Home on a new
 * day builds the row again, any later look reads the saved one. A build reads your top artists from Stash's own
 * listening history (the last three months, topped up from all time when that's fewer than [NewFromArtists.ARTISTS]),
 * finds each one's YouTube Music page (a search the first time only; the id is saved) and reads that page once
 * ([YTMusicApiClient.getArtistPage]: ten artists, ten browses a day).
 *
 * Fail-soft: a build where no page answered keeps the row it had (and tries again on the next look); with no
 * listening history there is no row and no request at all.
 */
@Singleton
class NewFromArtistsRepository internal constructor(
    private val store: HomeShelfStore,
    private val today: () -> Long,
    private val topArtistRows: suspend (ListeningRange) -> List<TopArtistRow>,
    private val resolveArtistId: suspend (String) -> String?,
    private val artistPage: suspend (String) -> ArtistProfile,
) {

    @Inject
    constructor(store: HomeShelfStore, history: ListeningHistoryRepository, api: YTMusicApiClient) : this(
        store = store,
        today = history::today,
        topArtistRows = { range -> history.observeTopArtists(history.dayRange(range), SCAN_LIMIT).first() },
        resolveArtistId = { name -> api.resolveArtist(name)?.id },
        artistPage = api::getArtistPage,
    )

    private val mutex = Mutex()

    /** The saved row, as it is (yesterday's included): what Home paints before [shelf] answers. */
    suspend fun cached(): List<AlbumSummary> = withContext(Dispatchers.IO) {
        val file = store.read(FILE, NewFromArtistsFile.serializer()) ?: return@withContext emptyList()
        NewFromArtists.stillNew(file.releases, yearOf(today()))
    }

    /**
     * Today's row: the saved one when it was built today, else a new build. Calls that overlap share one build.
     * Empty when there's nothing to show (no history, too few new releases, every page failed with nothing saved).
     */
    suspend fun shelf(): List<AlbumSummary> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val day = today()
            val saved = store.read(FILE, NewFromArtistsFile.serializer())
            if (saved != null && saved.day == day) return@withContext NewFromArtists.stillNew(saved.releases, yearOf(day))
            build(day, saved)
        }
    }

    private suspend fun build(day: Long, saved: NewFromArtistsFile?): List<AlbumSummary> {
        val artists = yourArtists()
        if (artists.isEmpty()) return emptyList()
        val ids = ConcurrentHashMap(saved?.artistIds.orEmpty())
        val gate = Semaphore(PARALLEL)
        val pages = coroutineScope {
            artists.map { artist ->
                async { gate.withPermit { releasesOf(artist, ids) } }
            }.awaitAll()
        }
        val answered = pages.filterNotNull()
        if (answered.isEmpty()) {
            // Nothing answered (no connection, YouTube down): keep what was there, and don't stamp the day, so the
            // next look tries again. Ids found on the way are kept.
            Log.d(TAG, "no artist page answered; keeping the saved row")
            if (ids != saved?.artistIds.orEmpty()) {
                store.write(FILE, NewFromArtistsFile.serializer(), (saved ?: NewFromArtistsFile(day = -1, releases = emptyList())).copy(artistIds = ids.toMap()))
            }
            return NewFromArtists.stillNew(saved?.releases.orEmpty(), yearOf(day))
        }
        val row = NewFromArtists.pick(answered, yearOf(day))
        Log.d(TAG, "built: ${artists.size} artists, ${answered.size} pages, ${row.size} releases")
        store.write(FILE, NewFromArtistsFile.serializer(), NewFromArtistsFile(day, row, ids.toMap()))
        return row
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

    /** [artist]'s releases from their page, or null when the page couldn't be found or read. */
    private suspend fun releasesOf(artist: YourArtist, ids: MutableMap<String, String>): ArtistReleases? = try {
        val key = NewFromArtists.artistKey(artist.name)
        val id = ids[key] ?: resolveArtistId(artist.name)?.also { ids[key] = it }
        if (id == null) {
            null
        } else {
            val page = artistPage(id)
            // A blank, shelf-less page is a browse that never answered (YTMusicApiClient's "no answer" profile).
            if (page.name.isBlank() && page.albums.isEmpty() && page.singles.isEmpty()) {
                null
            } else {
                ArtistReleases(page.name.ifBlank { artist.name }, page.albums, page.singles)
            }
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.w(TAG, "page of '${artist.name}' failed: ${e.message}")
        null
    }

    private fun yearOf(day: Long): Int = LocalDate.ofEpochDay(day).year

    internal companion object {
        private const val TAG = "NewFromArtists"
        const val FILE = "new_from_artists"

        /** Raw artist credits read to group into main acts (Your listening reads 10,000; ten acts need far fewer). */
        const val SCAN_LIMIT = 1_000

        /** Artist pages read at once. */
        const val PARALLEL = 3
    }
}

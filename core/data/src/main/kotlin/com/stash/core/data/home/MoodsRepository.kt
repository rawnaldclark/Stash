package com.stash.core.data.home

import android.util.Log
import com.stash.core.data.prefs.DataSaver
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.MoodGrids
import com.stash.data.ytmusic.model.MoodPage
import com.stash.data.ytmusic.model.MoodTile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.IOException
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What "Moods & moments" and "Genres" save ([HomeShelfStore]): YouTube's tiles of both grids, the day they were read
 * (-1 before the first answer), and when a read last went unanswered (the next waits [MoodsRepository.RETRY_MS] from
 * it). [withGenres] marks a read that kept the Genres grid: a file saved before genres were kept is read again once,
 * the same day, or Genres would only appear tomorrow.
 */
@Serializable
internal data class MoodsFile(
    val day: Long,
    val tiles: List<MoodTile>,
    val failedAt: Long = 0,
    val genres: List<MoodTile> = emptyList(),
    val withGenres: Boolean = false,
)

/** A tile's two covers ([MoodPage.coverUrls], sized for the tile) and the day they were read. */
@Serializable
internal data class MoodCover(val urls: List<String>, val day: Long)

/** Every tile's saved covers, by the tile's params. */
@Serializable
internal data class MoodCoversFile(val covers: Map<String, MoodCover> = emptyMap())

/**
 * Search's "Moods & moments" and "Genres": YouTube Music's two grids (Chill, Focus, Workout...; Pop, Rock,
 * Hip-hop...), each tile opening a page of YouTube's own songs, playlists and albums for it. Works signed out, so
 * it gives a brand-new Search tab something to play two taps away. Both grids come from one browse of
 * `FEmusic_moods_and_genres`, read once a day and saved; a tile's page is read when it's opened (one browse) and kept
 * in memory for [PAGE_TTL_MS].
 *
 * Fail-soft: no answer keeps the saved tiles, and the next read waits [RETRY_MS] (Search's resume doesn't refire it
 * on every return). An answer without a grid (a reworded page) keeps that grid's saved tiles, stamped for the day so
 * it isn't asked again before tomorrow; with nothing saved, that section stays hidden until then.
 *
 * Each tile shows two covers from its own page ([covers], Mochy Music's tiles). A page is 2.4 to 2.9 MB of JSON, so
 * covers are saved and kept for [COVER_DAYS] days, read for a tile only when the screen asks (the tiles on screen),
 * [COVER_FETCHES] pages at a time, and never on a metered network while Data saver is on ([DataSaver.appliesNow]).
 * Opening a tile refreshes its covers for free, from the page it reads anyway.
 */
@Singleton
class MoodsRepository internal constructor(
    private val store: HomeShelfStore,
    private val today: () -> Long,
    private val now: () -> Long,
    private val fetchGrids: suspend () -> MoodGrids?,
    private val fetchPage: suspend (params: String, title: String) -> MoodPage?,
    private val dataSaverApplies: () -> Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    @Inject
    constructor(store: HomeShelfStore, api: YTMusicApiClient, dataSaver: DataSaver) : this(
        store = store,
        today = { LocalDate.now().toEpochDay() },
        now = System::currentTimeMillis,
        fetchGrids = api::getMoodGrids,
        fetchPage = api::getMoodPage,
        dataSaverApplies = dataSaver::appliesNow,
    )

    private val mutex = Mutex()

    /** A few recently opened pages, by params, with when they were read. */
    private val pages = object : LinkedHashMap<String, Pair<Long, MoodPage>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, MoodPage>>?): Boolean = size > PAGES_KEPT
    }

    /** Serialises the covers file; [coverEntries] holds the copy read from it. */
    private val coversMutex = Mutex()
    private var coverEntries: Map<String, MoodCover>? = null

    /** At most [COVER_FETCHES] pages read for covers at once. A tile opened by hand never waits on it. */
    private val coverGate = Semaphore(COVER_FETCHES)

    /** The saved tiles as they are (yesterday's included): what Search paints before [grids] answers. */
    suspend fun cached(): MoodGrids = withContext(io) {
        store.read(FILE, MoodsFile.serializer())?.let { MoodGrids(it.tiles, it.genres) } ?: MoodGrids.None
    }

    /** Today's tiles: the saved ones when read today, else a new read. Calls that overlap share one read. */
    suspend fun grids(): MoodGrids = mutex.withLock {
        withContext(io) {
            val day = today()
            val saved = store.read(FILE, MoodsFile.serializer())
            val savedGrids = saved?.let { MoodGrids(it.tiles, it.genres) } ?: MoodGrids.None
            if (saved != null && saved.day == day && saved.withGenres) return@withContext savedGrids
            if (saved != null && saved.failedAt > 0 && now() - saved.failedAt in 0 until RETRY_MS) return@withContext savedGrids
            val fresh = fetchGrids()
            if (fresh == null) {
                // No answer: keep what was there, and wait a while before the next try
                Log.d(TAG, "moods page didn't answer; keeping ${savedGrids.moods.size} moods, ${savedGrids.genres.size} genres")
                store.write(FILE, MoodsFile.serializer(), (saved ?: MoodsFile(-1, emptyList())).copy(failedAt = now()))
                return@withContext savedGrids
            }
            // An answer without a grid never empties a section that has tiles: yesterday's still open their pages
            val kept = MoodGrids(
                moods = fresh.moods.ifEmpty { savedGrids.moods },
                genres = fresh.genres.ifEmpty { savedGrids.genres },
            )
            if (fresh.moods.isEmpty()) Log.w(TAG, "moods page answered without its Moods & moments grid; keeping ${kept.moods.size} saved")
            if (fresh.genres.isEmpty()) Log.w(TAG, "moods page answered without its Genres grid; keeping ${kept.genres.size} saved")
            store.write(FILE, MoodsFile.serializer(), MoodsFile(day, kept.moods, genres = kept.genres, withGenres = true))
            kept
        }
    }

    /**
     * The page of the tile opened with [params] ([title] names it until its header does). Throws [IOException]
     * when YouTube didn't answer, so the screen can offer a retry. A page read here also refreshes the tile's covers.
     */
    suspend fun page(params: String, title: String): MoodPage {
        synchronized(pages) { pages[params] }?.let { (at, page) -> if (now() - at < PAGE_TTL_MS) return page }
        val page = withContext(io) { fetchPage(params, title) }
            ?: throw IOException("YouTube Music didn't return the page of '$title'")
        if (page.shelves.isNotEmpty()) {
            synchronized(pages) { pages[params] = now() to page }
            saveCovers(params, page.coverUrls(COVERS_PER_TILE).map(::coverUrl))
        }
        return page
    }

    /** Every tile's saved covers, whatever their age: what the tiles paint at once. */
    suspend fun savedCovers(): Map<String, List<String>> = withContext(io) {
        coversMutex.withLock { entries().mapValues { it.value.urls } }
    }

    /**
     * The covers of the tile opened with [params]: the saved ones while under [COVER_DAYS] days old, else read from
     * its page ([page]) when Data saver allows, at most [COVER_FETCHES] at a time. [stillWanted] is asked once the
     * turn comes: a tile scrolled off screen while it waited costs nothing. Null when nothing was read (Data saver,
     * not wanted any more, or no answer); the saved covers, if any, stay where they are.
     */
    suspend fun covers(params: String, title: String, stillWanted: () -> Boolean = { true }): List<String>? {
        fresh(params)?.let { return it }
        if (dataSaverApplies()) return null
        return coverGate.withPermit {
            // Another read (or the tile opened by hand) may have brought them while this one waited
            fresh(params)?.let { return@withPermit it }
            if (!stillWanted() || dataSaverApplies()) return@withPermit null
            try {
                page(params, title)
                withContext(io) { coversMutex.withLock { entries()[params]?.urls } }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.d(TAG, "no covers for '$title': ${e.message}")
                null
            }
        }
    }

    /** [params]' saved covers when read under [COVER_DAYS] days ago, else null. */
    private suspend fun fresh(params: String): List<String>? = withContext(io) {
        coversMutex.withLock { entries()[params]?.takeIf { today() - it.day in 0 until COVER_DAYS }?.urls }
    }

    /**
     * Saves [urls] as [params]' covers, read today. Covers of tiles no longer in the saved grids (a seasonal mood
     * that rotated out) are dropped with the write.
     */
    private suspend fun saveCovers(params: String, urls: List<String>) = withContext(io) {
        coversMutex.withLock {
            val tiles = store.read(FILE, MoodsFile.serializer())
                ?.let { (it.tiles + it.genres).mapTo(HashSet()) { tile -> tile.params } }
                .orEmpty()
            val kept = entries().filterKeys { tiles.isEmpty() || it in tiles }
            val updated = kept + (params to MoodCover(urls, today()))
            coverEntries = updated
            store.write(COVERS_FILE, MoodCoversFile.serializer(), MoodCoversFile(updated))
        }
    }

    /** The covers file as read once; call with [coversMutex] held. */
    private fun entries(): Map<String, MoodCover> =
        coverEntries ?: (store.read(COVERS_FILE, MoodCoversFile.serializer())?.covers.orEmpty()).also { coverEntries = it }

    internal companion object {
        private const val TAG = "Moods"
        const val FILE = "moods"
        const val COVERS_FILE = "mood_covers"

        /** A page is kept this long: its playlists change by the week, not by the minute. */
        const val PAGE_TTL_MS = 30 * 60_000L
        const val PAGES_KEPT = 6

        /** After a read that went unanswered, the next waits this long. */
        const val RETRY_MS = 60 * 60 * 1000L

        /** Covers are read again after this many days: YouTube's mood and genre playlists change by the week. */
        const val COVER_DAYS = 7L

        /** Pages read for covers at once. Each is 2.4 to 2.9 MB of JSON to parse (Mochy Music reads 3). */
        const val COVER_FETCHES = 2

        /** Covers on a tile: Mochy Music's two, fanned. */
        const val COVERS_PER_TILE = 2

        /** The size a cover is saved at, in pixels (Mochy Music's). Coil's sizing asks for more on a denser screen. */
        private const val COVER_PX = 128

        private val GOOGLE_SIZE = Regex("""=w\d+-h\d+""")
        private val GGPHT_SIZE = Regex("""=s\d+""")

        /**
         * [url] at [COVER_PX]: a tile's cover is 48 dp, and the page's pictures come sized for Now Playing (1024 px).
         * YouTube's picture hosts serve any size; other URLs pass through unchanged.
         */
        fun coverUrl(url: String): String = when {
            ("lh3.googleusercontent.com" in url || "yt3.googleusercontent.com" in url) && GOOGLE_SIZE.containsMatchIn(url) ->
                GOOGLE_SIZE.replace(url, "=w$COVER_PX-h$COVER_PX")
            "yt3.ggpht.com" in url && GGPHT_SIZE.containsMatchIn(url) -> GGPHT_SIZE.replace(url, "=s$COVER_PX")
            else -> url
        }
    }
}

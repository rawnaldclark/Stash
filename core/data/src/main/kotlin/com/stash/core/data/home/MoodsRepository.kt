package com.stash.core.data.home

import android.util.Log
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.MoodPage
import com.stash.data.ytmusic.model.MoodTile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.IOException
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What "Moods & moments" saves ([HomeShelfStore]): YouTube's tiles, the day they were read (-1 before the first
 * answer), and when a read last went unanswered (the next waits [MoodsRepository.RETRY_MS] from it).
 */
@Serializable
internal data class MoodsFile(val day: Long, val tiles: List<MoodTile>, val failedAt: Long = 0)

/**
 * Home's "Moods & moments": YouTube Music's grid of moods (Chill, Focus, Workout...), each opening a page of
 * YouTube's own playlists for it. Works signed out, so it is the shelf that gives a brand-new Home something to
 * play two taps away. The tiles are read once a day (one browse of `FEmusic_moods_and_genres`) and saved; a
 * mood's page is read when it's opened (one browse) and kept in memory for [PAGE_TTL_MS].
 *
 * Fail-soft: no answer keeps the saved tiles, and the next read waits [RETRY_MS] (Home's resume doesn't refire it on
 * every return). An answer without the grid (a reworded page) keeps the saved tiles too, stamped for the day so it
 * isn't asked again before tomorrow; with nothing saved, the shelf stays hidden until then.
 */
@Singleton
class MoodsRepository internal constructor(
    private val store: HomeShelfStore,
    private val today: () -> Long,
    private val now: () -> Long,
    private val fetchTiles: suspend () -> List<MoodTile>?,
    private val fetchPage: suspend (params: String, title: String) -> MoodPage?,
) {

    @Inject
    constructor(store: HomeShelfStore, api: YTMusicApiClient) : this(
        store = store,
        today = { LocalDate.now().toEpochDay() },
        now = System::currentTimeMillis,
        fetchTiles = api::getMoodTiles,
        fetchPage = api::getMoodPage,
    )

    private val mutex = Mutex()

    /** A few recently opened mood pages, by params, with when they were read. */
    private val pages = object : LinkedHashMap<String, Pair<Long, MoodPage>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, MoodPage>>?): Boolean = size > PAGES_KEPT
    }

    /** The saved tiles as they are (yesterday's included): what Home paints before [tiles] answers. */
    suspend fun cached(): List<MoodTile> = withContext(Dispatchers.IO) {
        store.read(FILE, MoodsFile.serializer())?.tiles.orEmpty()
    }

    /** Today's tiles: the saved ones when read today, else a new read. Calls that overlap share one read. */
    suspend fun tiles(): List<MoodTile> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val day = today()
            val saved = store.read(FILE, MoodsFile.serializer())
            if (saved != null && saved.day == day) return@withContext saved.tiles
            if (saved != null && saved.failedAt > 0 && now() - saved.failedAt in 0 until RETRY_MS) return@withContext saved.tiles
            val fresh = fetchTiles()
            if (fresh == null) {
                // No answer: keep what was there, and wait a while before the next try
                Log.d(TAG, "moods page didn't answer; keeping ${saved?.tiles?.size ?: 0} saved tiles")
                store.write(FILE, MoodsFile.serializer(), (saved ?: MoodsFile(-1, emptyList())).copy(failedAt = now()))
                return@withContext saved?.tiles.orEmpty()
            }
            // An answer without the grid never empties a shelf that has tiles: yesterday's moods still open their pages
            val kept = if (fresh.isEmpty()) saved?.tiles.orEmpty() else fresh
            if (fresh.isEmpty()) Log.w(TAG, "moods page answered without its Moods & moments grid; keeping ${kept.size} saved tiles")
            store.write(FILE, MoodsFile.serializer(), MoodsFile(day, kept))
            kept
        }
    }

    /**
     * The page of the mood opened with [params] ([title] names it until its header does). Throws [IOException]
     * when YouTube didn't answer, so the screen can offer a retry.
     */
    suspend fun page(params: String, title: String): MoodPage {
        synchronized(pages) { pages[params] }?.let { (at, page) -> if (now() - at < PAGE_TTL_MS) return page }
        val page = withContext(Dispatchers.IO) { fetchPage(params, title) }
            ?: throw IOException("YouTube Music didn't return the page of '$title'")
        if (page.shelves.isNotEmpty()) synchronized(pages) { pages[params] = now() to page }
        return page
    }

    internal companion object {
        private const val TAG = "Moods"
        const val FILE = "moods"

        /** A mood's page is kept this long: its playlists change by the week, not by the minute. */
        const val PAGE_TTL_MS = 30 * 60_000L
        const val PAGES_KEPT = 6

        /** After a read that went unanswered, the next waits this long. */
        const val RETRY_MS = 60 * 60 * 1000L
    }
}

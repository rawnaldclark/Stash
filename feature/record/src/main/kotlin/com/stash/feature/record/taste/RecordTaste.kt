package com.stash.feature.record.taste

import android.content.Context
import android.util.Log
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.RecordHiddenDao
import com.stash.core.data.db.dao.RecordPlayDao
import com.stash.core.data.db.dao.RecordTasteDao
import com.stash.core.data.db.dao.StashMixRecipeDao
import com.stash.core.data.db.dao.WebLibraryExportDao
import com.stash.core.data.db.entity.RecordTasteKeepEntity
import com.stash.core.data.prefs.RecordInMixesPreference
import com.stash.core.data.prefs.StashMixPreference
import com.stash.core.data.record.MixTaste
import com.stash.core.data.sync.workers.StashMixRefreshWorker
import com.stash.core.data.weblibrary.WebLibraryListens
import com.stash.feature.record.archive.PhoneRows
import com.stash.feature.record.archive.toArchivePlay
import com.stash.feature.record.imports.RecordImportStore
import com.stash.feature.record.imports.RecordWrites
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.CoroutineContext

/**
 * What the record's imports call on the taste, under the record's write lock ([RecordWrites]) they already hold: a commit, the
 * end of an import the app's death interrupted, a Last.fm run, a slice of the lengths pass, and the removals.
 */
interface TasteHooks {
    /**
     * The taste worked out again from the whole record and stored, when there is an import ([importing]: a commit, before its
     * record is written). [kept]: song keys kept beyond the library's (the songs a commit brings in). Never throws but a
     * cancellation: a taste that can't be worked out leaves the last one.
     */
    suspend fun refreshLocked(kept: Collection<String> = emptyList(), importing: Boolean = false)

    /** No taste ([keep]: the songs put back go too). */
    suspend fun clearLocked(keep: Boolean)

    /** Nothing: the tests that bring plays alone. */
    object None : TasteHooks {
        override suspend fun refreshLocked(kept: Collection<String>, importing: Boolean) = Unit
        override suspend fun clearLocked(keep: Boolean) = Unit
    }
}

/**
 * Your record's taste (stash-player `src/lib/spotify/importer.ts` `refreshTaste`, service.ts's triggers, spec §4): the whole
 * history (the Spotify export's plays, the Last.fm scrobbles this phone's plays don't cover, and this phone's own plays) worked
 * out into a taste ([Taste.compute]) and kept in the database (`record_taste`, `record_taste_songs`), where the mixes read it
 * ([com.stash.core.data.record.RecordMixTaste]). Only while there is an import: without one there is no taste at all.
 *
 * Kept (never kept out, never lost) = the library here: the liked songs and the songs of the listener's own playlists (as
 * Library shows them, the imported ones among them), plus at a commit the songs it brings in. Hidden = `record_hidden`. Never
 * kept out = the songs put back (`record_taste_keep`), which aren't kept.
 *
 * Worked out again: after a commit (before its record), an interrupted import's end, a Last.fm run, the scrobbles' removal, a
 * slice of the lengths pass that timed songs (at most every 2 minutes, and at its end), at start when there is an import and no
 * taste yet, and at most once a local day when Home or the record opens ([daily]), and by [RecordTasteWorker] daily. Each
 * under the write lock, so it never reads half an import.
 */
@Singleton
class RecordTaste internal constructor(
    private val store: RecordImportStore,
    private val plays: RecordPlayDao,
    private val library: WebLibraryExportDao,
    private val hidden: RecordHiddenDao,
    private val tastes: RecordTasteDao,
    private val writes: RecordWrites,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val io: CoroutineContext = Dispatchers.IO,
    /** The first taste was stored: Daily Discover is made from it at once when it is empty. */
    private val firstTaste: suspend () -> Unit = {},
) : TasteHooks {

    @Inject constructor(
        @ApplicationContext context: Context,
        store: RecordImportStore,
        plays: RecordPlayDao,
        library: WebLibraryExportDao,
        hidden: RecordHiddenDao,
        tastes: RecordTasteDao,
        writes: RecordWrites,
        recipes: StashMixRecipeDao,
        playlists: PlaylistDao,
        mixes: StashMixPreference,
        inMixes: RecordInMixesPreference,
    ) : this(
        store = store,
        plays = plays,
        library = library,
        hidden = hidden,
        tastes = tastes,
        writes = writes,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        firstTaste = { dailyDiscoverFromTaste(context, recipes, playlists, mixes, inMixes) },
    )

    /** Changes the Settings list follows: a new taste, a song put back, an import made or removed. */
    val changes: Flow<Triple<Long?, Int, Boolean>> = combine(
        tastes.computedAtFlow(),
        tastes.keepKeysFlow().map { it.size },
        store.info.map { it.record != null }.distinctUntilChanged(),
    ) { at, keep, imported -> Triple(at, keep, imported) }

    // ── worked out ──────────────────────────────────────────────────────────────────────────────────────────────────

    override suspend fun refreshLocked(kept: Collection<String>, importing: Boolean) {
        if (!importing && store.info.value.record == null) return
        try {
            withContext(io) { work(kept) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // An OutOfMemoryError among them: the last taste stays, and the import or the run it came after goes on
            Log.w(TAG, "the taste couldn't be worked out: ${e.javaClass.simpleName}", e)
        }
    }

    override suspend fun clearLocked(keep: Boolean) {
        withContext(io + NonCancellable) {
            tastes.clear()
            if (keep) tastes.clearKeep()
        }
    }

    /** The taste again now, under the write lock (after whichever writer holds it), when there is an import. */
    suspend fun refresh() {
        if (store.info.value.record == null) return
        writes.mutex.withLock { refreshLocked() }
    }

    /** At start: an import with no taste yet (one made before the taste came in, or a restored library) gets one. */
    suspend fun ensure() {
        if (store.info.value.record == null || tastes.computedAt() != null) return
        writes.mutex.withLock { if (tastes.computedAt() == null) refreshLocked() }
    }

    /**
     * At most once a local day, when Home or Your record opens (ui/spotify/shared.ts `dailyRefresh`), and by the daily job: the
     * taste again with this phone's newest plays and the library's changes. Nothing without an import.
     */
    fun daily(): kotlinx.coroutines.Job? {
        if (store.info.value.record == null) return null
        val day = Instant.ofEpochMilli(now()).atZone(zone()).toLocalDate().toString()
        if (store.tasteDay == day) return null
        store.tasteDay = day
        return scope.launch { refresh() }
    }

    private suspend fun work(extra: Collection<String>) {
        val began = System.nanoTime()
        val rt = Runtime.getRuntime()
        var peak = rt.totalMemory() - rt.freeMemory()
        fun sample() {
            peak = maxOf(peak, rt.totalMemory() - rt.freeMemory())
        }
        // Held only as long as they're needed: up to a million plays are let go before the rows are built and written
        var input: TasteInput? = TasteInput(ownPlays())
        var after = ""
        var rows = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = plays.pageAfter(after, PAGE)
            if (page.isEmpty()) break
            input!!.add(page.map { it.toArchivePlay() })
            rows += page.size
            if (page.size < PAGE) break
            after = page.last().id
            sample()
        }
        var all: List<TastePlay>? = input!!.plays()
        input = null
        val playCount = all!!.size
        sample()
        val kept = MixTaste.libraryKeys(library)
        kept += extra
        val opts = TasteOptions(now = now(), hidden = hidden.keys().toHashSet(), kept = kept, putBack = tastes.keepKeys().toHashSet())
        val read = System.nanoTime()
        currentCoroutineContext().ensureActive()
        val taste = Taste.compute(all!!, opts)
        all = null
        sample()
        // Daily Discover is asked for once, when the first taste with seed artists lands (a taste with none can't seed it)
        val hadSeeds = tastes.taste()?.let { TasteRows.seedArtistsOf(it.body).isNotEmpty() } ?: false
        tastes.replace(TasteRows.row(taste), TasteRows.songs(taste))
        val done = System.nanoTime()
        Log.i(
            TAG,
            "the taste: ${taste.songs.size} songs from $playCount plays ($rows rows), ${taste.songs.count { it.avoid }} kept out, " +
                "${taste.seedArtists.size} seed artists; read ${(read - began) / 1_000_000} ms, worked out and kept " +
                "${(done - read) / 1_000_000} ms; heap ${peak / MB} MB at most of ${rt.maxMemory() / MB} MB",
        )
        if (!hadSeeds && taste.seedArtists.isNotEmpty()) {
            try {
                firstTaste()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Daily Discover couldn't be asked for after the first taste: ${e.javaClass.simpleName}")
            }
        }
    }

    /**
     * This phone's own plays as the taste reads them (service.ts `live`): the record's Stash source's rows (the same words,
     * cleaned as the export cleans them, the same newest [WebLibraryListens.MAX_PLAYS]), each as a play of the song's length.
     */
    private suspend fun ownPlays(): List<OwnPlay> {
        val at = now()
        val out = ArrayList<OwnPlay>()
        var cursorAt = Long.MAX_VALUE
        var cursorId = Long.MAX_VALUE
        var entries = 0
        pages@ while (entries < WebLibraryListens.MAX_PLAYS) {
            currentCoroutineContext().ensureActive()
            val rows = library.ownPlaysBefore(cursorAt, cursorId, WebLibraryListens.PAGE)
            for (row in rows) {
                cursorAt = row.startedAt
                cursorId = row.id
                val e = WebLibraryListens.play(row) ?: continue
                entries++
                PhoneRows.read(e.t, e.a, e.al, e.s, e.ms, e.d, at, skip = false)?.let { out += OwnPlay(it.t, it.a, it.al, it.s, it.d) }
                if (entries >= WebLibraryListens.MAX_PLAYS) break@pages
            }
            if (rows.size < WebLibraryListens.PAGE) break
        }
        return out
    }

    // ── kept out of your mixes (owner decision D1) ──────────────────────────────────────────────────────────────────

    /**
     * "Kept out of your mixes": the songs the taste keeps out now, each with why, in the taste's order; none put back, none in the
     * library now (a song liked since leaves the list at once). Empty with no import (no taste).
     */
    suspend fun keptOut(): List<KeptOutSong> = withContext(io) {
        if (tastes.computedAt() == null) return@withContext emptyList()
        KeptOut.of(tastes.avoided(), hidden.keys().toHashSet(), tastes.keepKeys().toHashSet() + MixTaste.libraryKeys(library))
    }

    /**
     * Puts [key] back into the mixes (service.ts `putBack`): never kept out again, at once for every mix from its next build, and
     * for the next taste. One write under the write lock (after a writer holding it), only while there is an import; run in the
     * app's scope, so leaving Settings can't cut it in two. Whether it was put back.
     */
    suspend fun putBack(key: String): Boolean = try {
        scope.async(io + NonCancellable) {
            writes.mutex.withLock {
                if (store.info.value.record == null) {
                    false
                } else {
                    tastes.insertKeep(RecordTasteKeepEntity(key, now()))
                    true
                }
            }
        }.await()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "a song couldn't be put back: ${e.javaClass.simpleName}")
        false
    }

    /** Whether a taste is stored (the switch's rebuild has something to change). */
    suspend fun has(): Boolean = tastes.computedAt() != null

    companion object {
        private const val TAG = "YourRecord"
        private const val PAGE = 5_000
        private const val MB = 1024 * 1024

        /**
         * The first taste is in: Daily Discover made at once when it is empty (a first mix from the imported years, before any
         * play here), unless Stash Mixes or "Use your record in mixes" are off.
         */
        internal suspend fun dailyDiscoverFromTaste(
            context: Context,
            recipes: StashMixRecipeDao,
            playlists: PlaylistDao,
            mixes: StashMixPreference,
            inMixes: RecordInMixesPreference,
        ) {
            if (!mixes.current() || !inMixes.current()) return
            val dd = dailyDiscover(recipes) ?: return
            val empty = dd.playlistId?.let { playlists.getOrderedTrackIdsForPlaylist(it).isEmpty() } ?: true
            if (!empty) return
            Log.i(TAG, "the first taste is in and Daily Discover is empty: making it")
            StashMixRefreshWorker.enqueueOneTime(context, dd.id)
        }

        /** The built-in Daily Discover, when it is active. */
        internal suspend fun dailyDiscover(recipes: StashMixRecipeDao) =
            recipes.getActive().firstOrNull { it.isBuiltin && it.name == DAILY_DISCOVER }

        const val DAILY_DISCOVER = "Daily Discover"
    }
}

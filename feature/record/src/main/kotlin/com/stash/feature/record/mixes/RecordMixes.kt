package com.stash.feature.record.mixes

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.stash.core.data.db.dao.RecordPlayDao
import com.stash.core.data.db.dao.RecordTasteDao
import com.stash.core.data.db.dao.WebLibraryExportDao
import com.stash.core.model.weblink.SongIndex
import com.stash.core.model.weblink.SongKey
import com.stash.feature.record.archive.ArchivePlay
import com.stash.feature.record.archive.OwnArchive
import com.stash.feature.record.archive.RecordArchiveSource
import com.stash.feature.record.archive.RecordFlags
import com.stash.feature.record.archive.StashCover
import com.stash.feature.record.archive.toArchivePlay
import com.stash.feature.record.taste.SongStat
import com.stash.feature.record.taste.Taste
import com.stash.feature.record.taste.TasteRows
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.CoroutineContext

/**
 * Your record's mixes now: the taste they are made from (as the record shows it: [HistoryMixes.putBackIn]), every history mix,
 * "This week, years ago", and the local day Home's few are picked for.
 */
class MixesNow(
    val taste: Taste,
    val all: List<HistoryMix>,
    val week: HistoryMix?,
    val day: Long,
    /** Every song of the library here (likes, then the own playlists' songs), as "Not in your Stash yet" looks them up. */
    private val library: SongIndex<Boolean> = SongIndex(),
) {
    /** Home's few today (mixes.ts `homePicks`). */
    val picks: List<HistoryMix> by lazy { HistoryMixes.homePicks(all, week, day) }

    /** The Mixes tab's two lists: this week, the eras oldest first, the single mixes; and the years, oldest first. */
    val tab: Pair<List<HistoryMix>, List<HistoryMix>> by lazy { HistoryMixes.mixesTab(all, week) }

    /** The mix [id] names, when the taste still makes it (mix.ts `findMix`). */
    fun find(id: String): HistoryMix? = if (id == "week") week else all.firstOrNull { it.id == id }

    /** Top artists: the first 10, each with the mix it opens, else none (the row plays their songs). */
    val artists: List<HistoryMixes.ArtistRow> by lazy { HistoryMixes.topArtists(taste) }

    /** Top songs, as the web plays them (their words). */
    val tops: List<MixSong> by lazy { HistoryMixes.topSongs(taste).map(HistoryMixes::songItem) }

    /** "Not in your Stash yet": the lost favourites the library here doesn't hold, as the web plays them (their words). */
    val lost: List<MixSong> by lazy {
        HistoryMixes.notInLibrary(taste.lost, taste.songs.associateBy { it.key }, library).map(HistoryMixes::songItem)
    }

    /** The songs a Top artists row with no mix plays ([HistoryMixes.artistSongs], 50 at most). */
    fun artistSongs(key: String): List<MixSong> = HistoryMixes.artistSongs(taste, key).map(HistoryMixes::songItem)
}

/**
 * Your record's history mixes (stash-player `src/lib/spotify/mixes.ts`, made by Home's shelf, a mix's page and the Mixes tab):
 * from the stored taste, read once per `computed_at` and kept ([stored], off the main thread), as the record shows it: kept out =
 * the stored avoid minus the songs put back minus the library's songs now (liked, in one of the listener's own playlists). A song of
 * a mix that the library holds is the library's own row ([LibrarySong]: it shows your copy and plays it).
 *
 * "This week, years ago" reads the record's plays by time (`record_plays` by `s`, the v71 index) and this phone's own, stacked as
 * the record stacks them (the export's plays, this phone's, then the Last.fm scrobbles they don't cover; each id once): the web
 * reads its archive alone, which holds this phone's plays only when its export was brought there. A deliberate adaptation: the
 * app's record is this phone's plays and the archive together. Those rows are read once per taste and UTC day.
 *
 * Nothing with no taste (no import): the record's mixes need years, which an import brings. "Use your record in mixes" doesn't
 * change them (it is about Stash's other mixes); Remove import takes them away with the taste.
 */
@Singleton
class RecordMixes internal constructor(
    private val tastes: RecordTasteDao,
    /** The record's imported plays that started in [from, to), in no order. */
    private val between: suspend (from: Long, to: Long) -> List<ArchivePlay>,
    /** This phone's own plays and skips, as the record has them ([OwnArchive]). */
    private val own: RecordArchiveSource,
    /** The library here: the liked songs, then each of the listener's own playlists' songs. */
    private val library: suspend () -> List<LibrarySong>,
    /** Something new each time the library may have changed (a like, a playlist's songs). */
    libraryStamp: Flow<Any>,
    /** The library's stamp now ([libraryStamp]'s value): the library read is kept while it holds. */
    private val libraryStampNow: suspend () -> Any = { Any() },
    /** The songs liked in Stash now (`stash_liked_at`: [likes]). */
    private val stashLikes: suspend () -> List<LibrarySong> = { emptyList() },
    /** A debug build writes what it made, for the web's code to be run over the same taste and rows. */
    private val dump: (suspend (String) -> Unit)? = null,
    private val io: CoroutineContext = Dispatchers.IO,
) {
    @Inject constructor(
        tastes: RecordTasteDao,
        plays: RecordPlayDao,
        library: WebLibraryExportDao,
        @ApplicationContext context: Context,
    ) : this(
        tastes = tastes,
        between = { from, to -> plays.between(from, to).map { it.toArchivePlay() } },
        own = OwnArchive(library),
        library = { keptLibrary(library) },
        libraryStamp = library.libraryStamp(),
        libraryStampNow = { library.libraryStampNow() },
        stashLikes = { library.stashLikes().map(::songOf) },
        dump = if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            { text -> File(context.filesDir, DUMP_FILE).writeText(text) }
        } else {
            null
        },
    )

    /** What the mixes follow: a new taste (an import, a removal, a day's work-out, a Last.fm top-up) and a song put back. */
    val changes: Flow<Pair<Long?, Set<String>>> =
        combine(tastes.computedAtFlow(), tastes.keepKeysFlow()) { at, keep -> at to keep.toSet() }.distinctUntilChanged()

    /**
     * The library's changes (a song liked, added to a playlist or taken out): Home's shelf and the Mixes tab make their mixes
     * again (a song liked since plays from your copy and is never kept out), as the web's follow its library. A mix's page doesn't:
     * liking a song there doesn't reshuffle the list under you.
     */
    val libraryChanges: Flow<Any> = libraryStamp.distinctUntilChanged()

    private val lock = Mutex()
    private var cachedTaste: Taste? = null
    private var cachedWeek: WeekRows? = null
    private var cachedLibrary: Library? = null

    /**
     * The library read once per stamp: its songs, their song keys, the index a mix's songs are found in, and every song of it
     * (as "Not in your Stash yet" looks them up).
     */
    private class Library(
        val stamp: Any,
        val songs: List<LibrarySong>,
        val keys: Set<String>,
        val have: Have,
        val index: SongIndex<Boolean>,
    )

    private suspend fun libraryNow(): Library {
        val stamp = libraryStampNow()
        lock.withLock { cachedLibrary?.takeIf { it.stamp == stamp }?.let { return it } }
        val songs = library()
        val keys = songs.mapTo(HashSet()) { SongKey.songKey(it.title, it.artist) }
        return Library(
            stamp = stamp,
            songs = songs,
            keys = keys,
            have = HistoryMixes.haveIndex(songs),
            index = HistoryMixes.libraryIndex(songs),
        ).also { lock.withLock { cachedLibrary = it } }
    }

    /**
     * The songs liked in Stash now (a heart: Now Playing's own, `stash_liked_at`), by `sameSong` as the web's `L.isLiked` finds
     * them, again whenever the library's stamp moves (a Stash like moves it): the hearts of the Mixes tab's and a mix page's rows follow a like at once, while a mix
     * page's list stays as it was made. A song only in a Liked Songs playlist synced from Spotify isn't one: its heart, as Now
     * Playing's, is empty, and a tap likes it in Stash (never on Spotify).
     */
    val likes: Flow<Have> = libraryChanges.map { HistoryMixes.haveIndex(stashLikes()) }.flowOn(io)

    private var dumped: String? = null

    /** The plays "this week, years ago" reads, for one taste on one UTC day. */
    private class WeekRows(val computedAt: Long, val utcDay: Long, val rows: List<ArchivePlay>) {
        fun between(from: Long, to: Long): List<ArchivePlay> = rows.filter { it.s >= from && it.s < to }
    }

    /** The stored taste as the database keeps it, read again only when a new one is written. Null: none (no import). */
    suspend fun stored(): Taste? = withContext(io) {
        lock.withLock {
            val at = tastes.computedAt() ?: return@withLock null.also { cachedTaste = null }
            cachedTaste?.takeIf { it.computedAt == at } ?: run {
                val whole = tastes.whole() ?: return@withLock null
                tasteOf(whole).also { cachedTaste = it }
            }
        }
    }

    /**
     * The mixes for [now] (Home picks by the local day in [zone]; "this week" by the UTC date, as the web reads them), their words
     * in [words]' language (the screen's: the app's own language setting reaches the Activity, not the Application). Null with no
     * taste. The library is read now, so a song liked since plays from your copy and is never kept out.
     */
    suspend fun now(words: MixWords, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): MixesNow? = withContext(io) {
        val stored = stored() ?: return@withContext null
        val library = libraryNow()
        val lib = library.songs
        val putBack = HashSet<String>(tastes.keepKeys())
        putBack += library.keys
        val t = HistoryMixes.putBackIn(stored, putBack)
        val have = library.have
        val all = HistoryMixes.historyMixes(t, have, words)
        var rows: WeekRows? = null
        val week = try {
            val r = weekRows(stored, now)
            rows = r
            HistoryMixes.weekMix(r::between, t, now, have, words)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The web's `weekMix(...).catch(() => null)`: only "this week" is lost, the other mixes still show
            Log.w(TAG, "this week, years ago couldn't be made: ${e.javaClass.simpleName}")
            null
        }
        val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay()
        MixesNow(t, all, week, day, library.index).also { m -> dump?.let { write(it, m, lib, rows, now) } }
    }

    /**
     * The record's plays in each of this week's windows ([HistoryMixes.weekWindows]), stacked as the record stacks them
     * ([com.stash.feature.record.archive.RecordArchive]): the export's plays, this phone's own plays and skips, then the Last.fm
     * scrobbles none of this phone's plays cover ([StashCover]); each id once, the first kept. Sorted by start, then id.
     */
    private suspend fun weekRows(t: Taste, now: Long): WeekRows {
        val utcDay = Math.floorDiv(now, DAY)
        lock.withLock { cachedWeek?.takeIf { it.computedAt == t.computedAt && it.utcDay == utcDay }?.let { return it } }
        val windows = HistoryMixes.weekWindows(t, now)
        val out = ArrayList<ArchivePlay>()
        if (windows.isNotEmpty()) {
            fun inWindow(s: Long) = windows.any { (_, from, to) -> s >= from && s < to }
            val cover = StashCover.Builder()
            val mine = ArrayList<ArchivePlay>()
            own.stream(CHUNK) { rows, _ ->
                for (r in rows) {
                    cover.add(r)
                    if (inWindow(r.s)) mine += r
                }
            }
            val covers = cover.build()
            val ids = HashSet<String>()
            for ((_, from, to) in windows) {
                val imported = between(from, to)
                for (r in imported) if (r.f and RecordFlags.LASTFM == 0 && ids.add(r.id)) out += r
                for (r in mine) if (r.s >= from && r.s < to && ids.add(r.id)) out += r
                for (r in imported) if (r.f and RecordFlags.LASTFM != 0 && !covers.covers(r) && ids.add(r.id)) out += r
            }
        }
        out.sortWith(HistoryMixes.BY_START)
        return WeekRows(t.computedAt, utcDay, out).also { lock.withLock { cachedWeek = it } }
    }

    /** What a debug build made, with what it was made from (scripts/mix-device-check.ts runs the web's code over it). */
    private suspend fun write(dump: suspend (String) -> Unit, m: MixesNow, lib: List<LibrarySong>, rows: WeekRows?, now: Long) {
        val key = "${m.taste.computedAt}|${m.day}|${Math.floorDiv(now, DAY)}|${lib.size}|${m.lost.size}|${m.taste.songs.count { it.avoid }}"
        if (key == dumped) return
        dumped = key
        try {
            val mixJson = { x: HistoryMix ->
                buildJsonObject {
                    put("id", x.id)
                    put("title", x.title)
                    put("sub", x.sub)
                    put("songKeys", JsonArray(x.items.map { JsonPrimitive(it.songKey) }))
                    put("items", JsonArray(x.items.map { JsonPrimitive(it.key) }))
                }
            }
            val text = buildJsonObject {
                put("now", now)
                put("day", m.day)
                put("taste", tasteJson(m.taste))
                put("have", JsonArray(lib.map { l -> buildJsonObject { put("title", l.title); put("artist", l.artist); l.album?.let { put("album", it) }; l.isrc?.let { put("isrc", it) }; put("durationMs", l.durationMs) } }))
                put("rows", JsonArray(rows?.rows.orEmpty().map { r -> buildJsonArray { add(r.id); add(r.t); add(r.a); add(r.al); add(r.sid); add(r.s); add(r.ms); add(r.f) } }))
                put("picks", JsonArray(m.picks.map { JsonPrimitive(it.id) }))
                put("mixes", JsonArray(m.all.map(mixJson)))
                put("week", m.week?.let(mixJson) ?: JsonNull)
                // The rest of the Mixes tab: Top artists (each with the mix it opens), Top songs and "Not in your Stash yet"
                put(
                    "artists",
                    JsonArray(
                        m.artists.map { a ->
                            buildJsonObject {
                                put("key", a.artist.key)
                                put("name", a.artist.name)
                                put("mixId", a.mixId?.let(::JsonPrimitive) ?: JsonNull)
                            }
                        },
                    ),
                )
                put("tops", JsonArray(m.tops.map { JsonPrimitive(it.songKey) }))
                put("lost", JsonArray(m.lost.map { JsonPrimitive(it.songKey) }))
            }
            dump(text.toString())
            Log.d(TAG, "Your record's mixes: ${m.all.size} and this week ${m.week != null}; Home today ${m.picks.map { it.id }}; not in your Stash yet ${m.lost.size}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "the mixes' debug file couldn't be written: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "YourRecord"
        private const val DAY = 86_400_000L
        private const val CHUNK = 5_000

        /** Where a debug build writes what it made (the app's files). */
        const val DUMP_FILE = "record-mixes.json"

        /** The taste from its two tables: the body's lists and the songs in their order. */
        internal fun tasteOf(whole: RecordTasteDao.Whole): Taste {
            val b = TasteRows.readBody(whole.taste.body)
            return Taste(
                v = whole.taste.v, computedAt = whole.taste.computedAt, first = b.first, last = b.last, plays = b.plays,
                listenedMs = b.listenedMs, artists = b.artists,
                songs = whole.songs.map {
                    SongStat(
                        key = it.key, title = it.title, artist = it.artist, album = it.album, sid = it.sid, starts = it.starts,
                        plays = it.plays, full = it.full, chosen = it.chosen, skips = it.skips, years = it.years, listenedMs = it.listenedMs,
                        first = it.first, last = it.last, peak = it.peak, peakAt = it.peakAt, weight = it.weight, depth = it.depth, avoid = it.avoid,
                    )
                },
                eras = b.eras, constants = b.constants, lost = b.lost, obsessions = b.obsessions, found = b.found, years = b.years,
                topArtists = b.topArtists, seedArtists = b.seedArtists, yearTops = b.yearTops,
            )
        }

        /** A taste as the web's JSON (the body's fields, `computedAt`, `v` and the songs). */
        internal fun tasteJson(t: Taste): JsonObject {
            val body = Json.parseToJsonElement(TasteRows.body(t)).jsonObject
            return JsonObject(
                body + mapOf(
                    "v" to JsonPrimitive(t.v),
                    "computedAt" to JsonPrimitive(t.computedAt),
                    "songs" to JsonArray(t.songs.map { Json.encodeToJsonElement(SongStat.serializer(), it) }),
                ),
            )
        }

        /**
         * The library here as the mixes read it (service.ts `kept`, the web's `[...L.likes, ...L.playlists.flatMap(p => p.items)]`):
         * the liked songs, then the songs of the listener's own playlists (as Library shows them), each a row of this phone's.
         */
        internal suspend fun keptLibrary(library: WebLibraryExportDao): List<LibrarySong> {
            val out = ArrayList<LibrarySong>()
            for (l in library.likes()) out += songOf(l.track)
            for (p in library.playlists()) for (i in library.playlistItems(p.id, Int.MAX_VALUE)) out += songOf(i.track)
            return out
        }

        /** A library row as the mixes know it. */
        internal fun songOf(c: WebLibraryExportDao.SongColumns) = LibrarySong(
            c.id, c.title, c.artist, c.album.takeIf { it.isNotEmpty() }, c.isrc?.takeIf { it.isNotEmpty() }, c.durationMs,
            c.albumArtUrl?.takeIf { it.isNotBlank() },
        )

        /** The UTC date of [t] as days since 1970. */
        internal fun utcDay(t: Long): Long = Instant.ofEpochMilli(t).atOffset(ZoneOffset.UTC).toLocalDate().toEpochDay()
    }
}

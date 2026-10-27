package com.stash.feature.record.imports

import android.content.Context
import android.content.SharedPreferences
import com.stash.feature.record.importer.ImportChoice
import com.stash.feature.record.importer.ImportRecord
import com.stash.feature.record.lastfm.ImportScrobbles
import com.stash.feature.record.lastfm.LastfmMark
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** What Your record keeps about its imports between visits (archive.ts `meta`), and the Home card's ×. */
data class ImportInfo(
    /** When the person tapped through to ask Spotify for their data (kept through a removal, as on the web). */
    val asked: Long? = null,
    /** What the imports brought; null before the first, and after Remove import. */
    val record: ImportRecord? = null,
    /** "Press your record" closed with its × (Settings brings it back). */
    val inviteHidden: Boolean = false,
    /** When an import last came to nothing (it failed, or the system stopped it for good): the card says so until it's dismissed or tried again. */
    val unfinished: Long? = null,
    /** Where the Last.fm import stands (archive.ts meta `lastfm`); null: never brought in, or removed. */
    val lastfm: LastfmMark? = null,
    /** The scrobbles' generation, moved on by every removal: a Last.fm run asked for before one does nothing. */
    val lastfmGeneration: Long = 0,
    /** Scrobble rows the lengths pass has timed so far (lengths.ts `LengthsMark.timed`); 0 before it has run. */
    val lengthsTimed: Int = 0,
)

/**
 * Your record's import meta in this phone's preferences (the `your_record` file the Home card's × already lives in): `asked`,
 * the import record, `pending` (an import's plays are being written: finished on the next start if the app died first) with
 * what the person chose to bring into the library ([pendingChoice]: the same is brought in when it is taken up again),
 * `unfinished` (an import that came to nothing, for the card), `stops` (times the system stopped the import running now),
 * the Last.fm import's mark, and the lengths pass's count and time (its songs it couldn't time are in `record_lengths`). The
 * plays themselves are in the database (`record_plays`).
 */
@Singleton
class RecordImportStore internal constructor(private val prefs: SharedPreferences) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))

    private val _info = MutableStateFlow(read())
    val info: StateFlow<ImportInfo> = _info.asStateFlow()

    /** When a commit started writing; null when none is under way. */
    val pending: Long? get() = prefs.getLong(KEY_PENDING, 0L).takeIf { it > 0 }

    fun setAsked(at: Long) = edit { putLong(KEY_ASKED, at) }

    fun setInviteHidden(hidden: Boolean) = edit { putBoolean(KEY_HIDDEN, hidden) }

    /** A commit starts writing at [at] with [choice] (null: none under way any more, and its choice goes with it). */
    fun setPending(at: Long?, choice: ImportChoice = ImportChoice.ALL) {
        prefs.edit().apply {
            if (at == null) {
                PENDING_KEYS.forEach(::remove)
            } else {
                putLong(KEY_PENDING, at)
                putChoice(choice)
            }
        }.commit()
    }

    /** What the commit under way brings into the library (all of it, for a commit written before the choice was kept). */
    val pendingChoice: ImportChoice
        get() {
            val likes = prefs.getBoolean(KEY_CHOICE_LIKES, true)
            val playlists = prefs.getString(KEY_CHOICE_PLAYLISTS, null)?.let { s ->
                if (s.isEmpty()) emptySet() else s.split(',').mapNotNullTo(LinkedHashSet()) { it.toIntOrNull() }
            }
            return ImportChoice(playlists, likes)
        }

    private fun SharedPreferences.Editor.putChoice(c: ImportChoice) {
        putBoolean(KEY_CHOICE_LIKES, c.likes)
        // The playlists' indexes, ',' between (an empty text: none; no key: all)
        if (c.playlists == null) remove(KEY_CHOICE_PLAYLISTS) else putString(KEY_CHOICE_PLAYLISTS, c.playlists.sorted().joinToString(","))
    }

    /** Times the system stopped the import running now (a stopped job runs again; past a few, the import is given up). */
    val stops: Int get() = prefs.getInt(KEY_STOPS, 0)

    /** One more stop; returns the count. */
    fun addStop(): Int = (stops + 1).also { n -> prefs.edit().putInt(KEY_STOPS, n).commit() }

    fun clearStops() {
        if (prefs.contains(KEY_STOPS)) prefs.edit().remove(KEY_STOPS).commit()
    }

    fun setUnfinished(at: Long?) {
        if (at == null && !prefs.contains(KEY_UNFINISHED)) return
        edit { if (at == null) remove(KEY_UNFINISHED) else putLong(KEY_UNFINISHED, at) }
    }

    fun setRecord(r: ImportRecord) = edit { putRecord(r) }

    /** A commit's end (importer.ts `commit`'s last steps): its record written and `pending` cleared in one write. */
    fun finishCommit(r: ImportRecord) = edit {
        putRecord(r)
        PENDING_KEYS.forEach(::remove)
    }

    private fun SharedPreferences.Editor.putRecord(r: ImportRecord) {
        putLong(R_FIRST_AT, r.firstAt)
        putLong(R_LAST_AT, r.lastAt)
        putInt(R_IMPORTS, r.imports)
        putInt(R_PLAYS, r.plays)
        putInt(R_SPOTIFY, r.bySource?.spotify ?: r.plays)
        putInt(R_LASTFM, r.bySource?.lastfm ?: 0)
        putInt(R_STASH, r.bySource?.stash ?: 0)
        putLong(R_FIRST, r.first)
        putLong(R_LAST, r.last)
        putInt(R_PLAYLISTS, r.playlists)
        putInt(R_LIKES, r.likes)
    }

    /** Where the Last.fm import stands, after a page or a top-up. */
    fun setLastfm(m: LastfmMark) = edit {
        putString(L_USER, m.user)
        putLong(L_TO, m.to)
        putInt(L_NEXT, m.next)
        putInt(L_PAGES, m.pages)
        putBoolean(L_DONE, m.done)
        putLong(L_NEWEST, m.newest)
        putInt(L_ADDED, m.added)
        putInt(L_COVERED, m.covered)
        putLong(L_AT, m.at)
        if (m.base != null) putInt(L_BASE, m.base) else remove(L_BASE)
        val stuck = m.stuck
        if (stuck != null) {
            putInt(L_STUCK_PAGE, stuck.page)
            putInt(L_STUCK_RUNS, stuck.runs)
            putLong(L_STUCK_SINCE, stuck.since)
            putLong(L_STUCK_LAST, stuck.last)
            if (stuck.pages != null) putInt(L_STUCK_PAGES, stuck.pages) else remove(L_STUCK_PAGES)
            putBoolean(L_STUCK_TOPUP, stuck.topup)
            putBoolean(L_STUCK_TAIL, stuck.tail)
        } else {
            STUCK_KEYS.forEach(::remove)
        }
        if (m.oldest != null) putLong(L_OLDEST, m.oldest) else remove(L_OLDEST)
        val holes = m.holes
        // "from,to,at,open,fails,next,empty" a hole, ';' between (open: 1 or 0; next and empty empty when not set)
        if (!holes.isNullOrEmpty()) {
            putString(L_HOLES, holes.joinToString(";") { "${it.from},${it.to},${it.at},${if (it.open) 1 else 0},${it.fails},${it.next ?: ""},${it.empty ?: ""}" })
        } else {
            remove(L_HOLES)
        }
        // An earlier build's skipped pages, read into the holes above
        OLD_SKIPPED_KEYS.forEach(::remove)
    }

    /**
     * The lengths pass's lookups this device made lately (by hour, [com.stash.feature.record.lastfm.LookupBudget]'s own words)
     * and when it may ask again after a rate limit: kept through every removal, since they are this device's, not the scrobbles'.
     */
    var lookupBudget: String?
        get() = prefs.getString(KEY_BUDGET, null)
        set(v) {
            prefs.edit().apply { if (v == null) remove(KEY_BUDGET) else putString(KEY_BUDGET, v) }.commit()
        }

    /**
     * Network-waiting runs of the lengths pass in a row held again for want of a network (its backoff), kept across the app's
     * death; this device's, like [lookupBudget].
     */
    var lengthsNetworkHolds: Int
        get() = prefs.getInt(KEY_NETWORK_HOLDS, 0)
        set(v) {
            prefs.edit().apply { if (v > 0) putInt(KEY_NETWORK_HOLDS, v) else remove(KEY_NETWORK_HOLDS) }.apply()
        }

    /** The lengths pass's own count and time ([com.stash.feature.record.lastfm.LengthsMark]'s `timed` and `at`). */
    fun setLengths(timed: Int, at: Long) = edit {
        putInt(LEN_TIMED, timed)
        putLong(LEN_AT, at)
    }

    /** When the lengths pass last ran (epoch s); null before it has. */
    val lengthsAt: Long? get() = if (prefs.contains(LEN_AT)) prefs.getLong(LEN_AT, 0) else null

    /** The scrobbles' generation now ([ImportInfo.lastfmGeneration]). */
    val lastfmGeneration: Long get() = prefs.getLong(KEY_LASTFM_GENERATION, 0)

    /** A removal: the scrobbles' generation moves on (kept through every removal, never cleared). */
    fun nextLastfmGeneration() = edit { putLong(KEY_LASTFM_GENERATION, lastfmGeneration + 1) }

    /** Remove your Last.fm scrobbles: the mark goes with them, and the lengths pass's. */
    fun clearLastfm() = edit {
        LASTFM_KEYS.forEach(::remove)
        LENGTHS_KEYS.forEach(::remove)
    }

    /**
     * Remove import (archive.ts `clear`: every meta but `asked`): the record, the pending mark and the Last.fm import's mark go;
     * when Spotify was asked, and the card's ×, stay.
     */
    fun clearRecord() = edit {
        RECORD_KEYS.forEach(::remove)
        LASTFM_KEYS.forEach(::remove)
        LENGTHS_KEYS.forEach(::remove)
        PENDING_KEYS.forEach(::remove)
    }

    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        // commit, not apply: an import's record and its plays must not part company if the app dies right after
        prefs.edit().apply(block).commit()
        _info.value = read()
    }

    private fun read(): ImportInfo = ImportInfo(
        asked = prefs.getLong(KEY_ASKED, 0L).takeIf { it > 0 },
        record = if (!prefs.contains(R_IMPORTS)) {
            null
        } else {
            ImportRecord(
                firstAt = prefs.getLong(R_FIRST_AT, 0),
                lastAt = prefs.getLong(R_LAST_AT, 0),
                imports = prefs.getInt(R_IMPORTS, 0),
                plays = prefs.getInt(R_PLAYS, 0),
                bySource = ImportRecord.BySource(prefs.getInt(R_SPOTIFY, 0), prefs.getInt(R_LASTFM, 0), prefs.getInt(R_STASH, 0)),
                first = prefs.getLong(R_FIRST, 0),
                last = prefs.getLong(R_LAST, 0),
                playlists = prefs.getInt(R_PLAYLISTS, 0),
                likes = prefs.getInt(R_LIKES, 0),
            )
        },
        inviteHidden = prefs.getBoolean(KEY_HIDDEN, false),
        unfinished = prefs.getLong(KEY_UNFINISHED, 0L).takeIf { it > 0 },
        lastfmGeneration = prefs.getLong(KEY_LASTFM_GENERATION, 0),
        lengthsTimed = prefs.getInt(LEN_TIMED, 0),
        lastfm = prefs.getString(L_USER, null)?.let { user ->
            LastfmMark(
                user = user,
                to = prefs.getLong(L_TO, 0),
                next = prefs.getInt(L_NEXT, 1),
                pages = prefs.getInt(L_PAGES, 0),
                done = prefs.getBoolean(L_DONE, false),
                newest = prefs.getLong(L_NEWEST, 0),
                added = prefs.getInt(L_ADDED, 0),
                covered = prefs.getInt(L_COVERED, 0),
                at = prefs.getLong(L_AT, 0),
                base = if (prefs.contains(L_BASE)) prefs.getInt(L_BASE, 0) else null,
                oldest = if (prefs.contains(L_OLDEST)) prefs.getLong(L_OLDEST, 0) else null,
                // A stuck page kept before its times were starts counting again
                stuck = if (prefs.contains(L_STUCK_PAGE) && prefs.contains(L_STUCK_SINCE) && prefs.contains(L_STUCK_LAST)) {
                    LastfmMark.Stuck(
                        page = prefs.getInt(L_STUCK_PAGE, 0),
                        runs = prefs.getInt(L_STUCK_RUNS, 0),
                        since = prefs.getLong(L_STUCK_SINCE, 0),
                        last = prefs.getLong(L_STUCK_LAST, 0),
                        pages = if (prefs.contains(L_STUCK_PAGES)) prefs.getInt(L_STUCK_PAGES, 0) else null,
                        topup = prefs.getBoolean(L_STUCK_TOPUP, false),
                        tail = prefs.getBoolean(L_STUCK_TAIL, false),
                    )
                } else {
                    null
                },
                holes = (readHoles(prefs) + ImportScrobbles.holesFrom(readOldSkipped(prefs))).ifEmpty { null },
            )
        },
    )

    companion object {
        const val FILE = "your_record"
        private const val KEY_HIDDEN = "invite_hidden"
        private const val KEY_ASKED = "spotify_asked_at"
        private const val KEY_PENDING = "import_pending_at"
        private const val KEY_CHOICE_PLAYLISTS = "import_pending_playlists"
        private const val KEY_CHOICE_LIKES = "import_pending_likes"
        private val PENDING_KEYS = listOf(KEY_PENDING, KEY_CHOICE_PLAYLISTS, KEY_CHOICE_LIKES)
        private const val KEY_STOPS = "import_stops"
        private const val KEY_UNFINISHED = "import_unfinished_at"
        private const val R_FIRST_AT = "import_first_at"
        private const val R_LAST_AT = "import_last_at"
        private const val R_IMPORTS = "import_count"
        private const val R_PLAYS = "import_plays"
        private const val R_SPOTIFY = "import_plays_spotify"
        private const val R_LASTFM = "import_plays_lastfm"
        private const val R_STASH = "import_plays_stash"
        private const val R_FIRST = "import_first_listen"
        private const val R_LAST = "import_last_listen"
        private const val R_PLAYLISTS = "import_playlists"
        private const val R_LIKES = "import_likes"
        private val RECORD_KEYS = listOf(R_FIRST_AT, R_LAST_AT, R_IMPORTS, R_PLAYS, R_SPOTIFY, R_LASTFM, R_STASH, R_FIRST, R_LAST, R_PLAYLISTS, R_LIKES)
        private const val KEY_LASTFM_GENERATION = "lastfm_generation"
        private const val L_USER = "lastfm_user"
        private const val L_TO = "lastfm_to"
        private const val L_NEXT = "lastfm_next"
        private const val L_PAGES = "lastfm_pages"
        private const val L_DONE = "lastfm_done"
        private const val L_NEWEST = "lastfm_newest"
        private const val L_ADDED = "lastfm_added"
        private const val L_COVERED = "lastfm_covered"
        private const val L_AT = "lastfm_at"
        private const val L_BASE = "lastfm_base"
        private const val L_STUCK_PAGE = "lastfm_stuck_page"
        private const val L_STUCK_RUNS = "lastfm_stuck_runs"
        private const val L_STUCK_TOPUP = "lastfm_stuck_topup"
        private const val L_STUCK_SINCE = "lastfm_stuck_since"
        private const val L_STUCK_LAST = "lastfm_stuck_last"
        private const val L_STUCK_PAGES = "lastfm_stuck_pages"
        private const val L_STUCK_TAIL = "lastfm_stuck_tail"
        private const val L_OLDEST = "lastfm_oldest"
        private const val L_HOLES = "lastfm_holes"
        private val STUCK_KEYS = listOf(L_STUCK_PAGE, L_STUCK_RUNS, L_STUCK_SINCE, L_STUCK_LAST, L_STUCK_PAGES, L_STUCK_TOPUP, L_STUCK_TAIL)

        /** An earlier build's skipped pages: by number ("page,to,from,at", ';' between), and before that a bare count. */
        private const val L_OLD_SKIPPED_PAGES = "lastfm_skipped_pages"
        private val OLD_SKIPPED_KEYS = listOf(L_OLD_SKIPPED_PAGES, "lastfm_skipped")
        private val LASTFM_KEYS = listOf(L_USER, L_TO, L_NEXT, L_PAGES, L_DONE, L_NEWEST, L_ADDED, L_COVERED, L_AT, L_BASE, L_OLDEST, L_HOLES) + STUCK_KEYS + OLD_SKIPPED_KEYS

        private fun readHoles(prefs: SharedPreferences): List<LastfmMark.Hole> =
            runCatching { prefs.getString(L_HOLES, null) }.getOrNull()?.split(';')?.mapNotNull { e ->
                // (4 fields as round 3 wrote them; 7 since)
                val f = e.split(',')
                if (f.size != 4 && f.size != 7) return@mapNotNull null
                val from = f[0].toLongOrNull() ?: return@mapNotNull null
                val to = f[1].toLongOrNull() ?: return@mapNotNull null
                val at = f[2].toLongOrNull() ?: return@mapNotNull null
                LastfmMark.Hole(
                    from, to, at, open = f[3] == "1",
                    fails = f.getOrNull(4)?.toIntOrNull() ?: 0, next = f.getOrNull(5)?.toLongOrNull(), empty = f.getOrNull(6)?.toLongOrNull(),
                )
            }.orEmpty()

        private fun readOldSkipped(prefs: SharedPreferences): List<ImportScrobbles.SkippedPage> =
            runCatching { prefs.getString(L_OLD_SKIPPED_PAGES, null) }.getOrNull()?.split(';')?.mapNotNull { e ->
                val f = e.split(',')
                if (f.size != 4) return@mapNotNull null
                val page = f[0].toIntOrNull() ?: return@mapNotNull null
                val to = f[1].toLongOrNull() ?: return@mapNotNull null
                val at = f[3].toLongOrNull() ?: return@mapNotNull null
                ImportScrobbles.SkippedPage(page, to, f[2].toLongOrNull(), at)
            }.orEmpty()
        private const val KEY_BUDGET = "lengths_lookups"
        private const val KEY_NETWORK_HOLDS = "lengths_network_holds"
        private const val LEN_TIMED = "lengths_timed"
        private const val LEN_AT = "lengths_at"
        private val LENGTHS_KEYS = listOf(LEN_TIMED, LEN_AT)
    }
}

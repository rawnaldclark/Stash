package com.stash.feature.record.imports

import android.content.Context
import android.content.SharedPreferences
import com.stash.feature.record.importer.ImportRecord
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
 * the import record, `pending` (an import's plays are being written: finished on the next start if the app died first),
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

    fun setPending(at: Long?) {
        prefs.edit().apply { if (at == null) remove(KEY_PENDING) else putLong(KEY_PENDING, at) }.commit()
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

    fun setRecord(r: ImportRecord) = edit {
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
        remove(KEY_PENDING)
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
            )
        },
    )

    companion object {
        const val FILE = "your_record"
        private const val KEY_HIDDEN = "invite_hidden"
        private const val KEY_ASKED = "spotify_asked_at"
        private const val KEY_PENDING = "import_pending_at"
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
        private val LASTFM_KEYS = listOf(L_USER, L_TO, L_NEXT, L_PAGES, L_DONE, L_NEWEST, L_ADDED, L_COVERED, L_AT, L_BASE)
        private const val LEN_TIMED = "lengths_timed"
        private const val LEN_AT = "lengths_at"
        private val LENGTHS_KEYS = listOf(LEN_TIMED, LEN_AT)
    }
}

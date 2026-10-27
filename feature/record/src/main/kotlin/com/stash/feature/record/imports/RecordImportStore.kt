package com.stash.feature.record.imports

import android.content.Context
import android.content.SharedPreferences
import com.stash.feature.record.importer.ImportRecord
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
)

/**
 * Your record's import meta in this phone's preferences (the `your_record` file the Home card's × already lives in): `asked`,
 * the import record, `pending` (an import's plays are being written: finished on the next start if the app died first),
 * `unfinished` (an import that came to nothing, for the card) and `stops` (times the system stopped the import running now).
 * The plays themselves are in the database (`record_plays`).
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

    /** Remove import: the record and the pending mark go; when Spotify was asked, and the card's ×, stay. */
    fun clearRecord() = edit {
        RECORD_KEYS.forEach(::remove)
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
    }
}

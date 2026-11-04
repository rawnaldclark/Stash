package com.stash.core.data.db

import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import android.util.Log

/** Same tag as [DatabaseBackupManager], so a failed backup reads as one story in shared diagnostics. */
private const val TAG = "BackupManager"

/** The most `integrity_check` rows read and logged: enough to see what's wrong, short enough for one log line. */
private const val MAX_INTEGRITY_PROBLEMS = 20

/** The search index, derived from `tracks` (see [com.stash.core.data.db.entity.TrackFts]). */
private const val SEARCH_INDEX_TABLE = "tracks_fts"

/**
 * A database that failed [DatabaseBackupManager.verifyDatabaseIntegrity]. [message] is the plain sentence the
 * Settings dialog shows; [detail] is what SQLite said, for the log (and so shared diagnostics) only, never the
 * screen.
 */
class DatabaseIntegrityException(
    message: String,
    val detail: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * Runs `PRAGMA integrity_check` on [db] and returns every row that isn't "ok", at most
 * [MAX_INTEGRITY_PROBLEMS] of them (SQLite stops counting there). Empty means the file is sound.
 */
internal fun integrityProblems(db: SQLiteDatabase): List<String> =
    db.rawQuery("PRAGMA integrity_check($MAX_INTEGRITY_PROBLEMS)", null).use { cursor ->
        if (!cursor.moveToFirst()) return@use listOf("integrity_check returned no rows")
        buildList {
            do {
                val row = cursor.getString(0)
                if (!row.equals("ok", ignoreCase = true)) add(row)
            } while (cursor.moveToNext())
        }
    }

/**
 * True when there is at least one problem and every one names `tracks_fts`: only the search index is out of
 * step with `tracks`, and the library itself is sound. Android 15's SQLite (3.44+) reports a stale index as
 * "malformed inverted index for FTS4 table main.tracks_fts" (#838).
 */
internal fun onlySearchIndexProblems(problems: List<String>): Boolean =
    problems.isNotEmpty() && problems.all { it.contains(SEARCH_INDEX_TABLE, ignoreCase = true) }

/**
 * The import's repair for a backup whose only fault is a stale search index (#838). A backup made on schema
 * 67-76 can carry one, since the 66 -> 67 migration changed albums while Room had the index's sync triggers
 * dropped. On Android 15+ it fails the import's integrity check, which runs before the migration chain, so
 * 76 -> 77's own rebuild never gets the chance.
 *
 * When [problems] are all about `tracks_fts` ([onlySearchIndexProblems]), rebuilds it from `tracks` on [db] and
 * returns what `integrity_check` finds afterwards: empty when the repair worked. Otherwise, or when the rebuild
 * itself fails, returns [problems] unchanged, and the caller rejects the file as before.
 *
 * Only ever call this on the throwaway staged copy of a backup, never on the live database: it rewrites the
 * whole index. `tracks_fts` has the same definition in every schema from 1 to 77, so the rebuild works on a
 * backup of any age.
 */
internal fun repairSearchIndexIfOnlyProblem(db: SQLiteDatabase, problems: List<String>, fileName: String): List<String> {
    if (!onlySearchIndexProblems(problems)) return problems
    Log.w(TAG, "only the search index is out of step on $fileName, rebuilding it: ${problems.joinToString(" | ")}")
    try {
        db.execSQL("INSERT INTO $SEARCH_INDEX_TABLE($SEARCH_INDEX_TABLE) VALUES('rebuild')")
    } catch (e: SQLException) {
        Log.e(TAG, "search index rebuild failed on $fileName", e)
        return problems
    }
    val after = integrityProblems(db)
    if (after.isEmpty()) Log.i(TAG, "search index rebuilt on $fileName; integrity_check ok")
    return after
}

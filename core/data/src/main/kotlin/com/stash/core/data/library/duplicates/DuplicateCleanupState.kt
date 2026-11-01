package com.stash.core.data.library.duplicates

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Its own file, so a corrupt write can only reset this state, and a reset only means the cleanup
 * runs once more (it finds nothing the second time).
 */
private val Context.duplicateCleanupDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "duplicate_cleanup",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Whether the one-time [DuplicateCleanup] has run on this install, what it did, and the "Tidied N
 * duplicate songs" notice it leaves for the app to show once.
 *
 * The gate that keeps it to once: startup enqueues the worker only while [isDone] is false, and a
 * run returns at once when it is true. Re-armed ([rearm]) when a backup is restored or merged: a
 * backup made before schema 76 brings the stubs back. Each re-arm starts a new generation and a run
 * marks done only the generation it started under ([markDone]), the same pattern as
 * [com.stash.core.data.library.CanonicalRecomputeState].
 */
@Singleton
class DuplicateCleanupState internal constructor(
    private val store: DataStore<Preferences>,
) {

    @Inject constructor(@ApplicationContext context: Context) : this(context.duplicateCleanupDataStore)

    /** One run: the generation it serves. */
    data class Pass(val generation: Int)

    /** What the last finished run did, for Library Health. */
    data class LastRun(val at: Long, val merged: Int, val removed: Int, val leftForReview: Int)

    suspend fun isDone(): Boolean = isDone(store.data.first())

    /** The run still to do; null when [isDone]. */
    suspend fun pendingPass(): Pass? {
        val prefs = store.data.first()
        return if (isDone(prefs)) null else Pass(prefs[GENERATION] ?: 0)
    }

    /**
     * Marks the cleanup done for [RULE_VERSION] with what it did, unless a re-arm came since [pass]
     * began; queues the notice when it merged anything. In one edit.
     *
     * @return false when re-armed meanwhile: it must run again.
     */
    suspend fun markDone(pass: Pass, summary: DuplicateCleanup.Summary, now: Long): Boolean {
        var marked = false
        store.edit {
            if ((it[GENERATION] ?: 0) == pass.generation) {
                it[DONE_VERSION] = RULE_VERSION
                it[DONE_GENERATION] = pass.generation
                it[LAST_AT] = now
                it[LAST_MERGED] = summary.merged
                it[LAST_REMOVED] = summary.removed
                it[LAST_LEFT] = summary.leftForReview
                if (summary.merged > 0) it[NOTICE] = (it[NOTICE] ?: 0) + summary.merged
                marked = true
            }
        }
        return marked
    }

    /** Asks for a fresh run (a restored or merged backup may hold stubs): a new generation. */
    suspend fun rearm() {
        store.edit { it[GENERATION] = (it[GENERATION] ?: 0) + 1 }
    }

    /** The last finished run, or null before the first. */
    val lastRun: Flow<LastRun?> = store.data.map { prefs ->
        prefs[LAST_AT]?.let { at ->
            LastRun(at = at, merged = prefs[LAST_MERGED] ?: 0, removed = prefs[LAST_REMOVED] ?: 0, leftForReview = prefs[LAST_LEFT] ?: 0)
        }
    }.distinctUntilChanged()

    /**
     * How many duplicate songs the cleanup merged that the user hasn't been told about yet; null
     * when there is nothing to tell. The app shows "Tidied N duplicate songs" once, then calls
     * [noticeShown].
     */
    val pendingNotice: Flow<Int?> = store.data.map { it[NOTICE]?.takeIf { n -> n > 0 } }.distinctUntilChanged()

    suspend fun noticeShown() {
        store.edit { it.remove(NOTICE) }
    }

    private fun isDone(prefs: Preferences): Boolean =
        (prefs[DONE_VERSION] ?: 0) >= RULE_VERSION && (prefs[DONE_GENERATION] ?: 0) == (prefs[GENERATION] ?: 0)

    companion object {
        /** Bump to run the cleanup again on every install (a new certain case to tidy). */
        const val RULE_VERSION = 1

        private val DONE_VERSION = intPreferencesKey("done_rule_version")
        private val GENERATION = intPreferencesKey("generation")
        private val DONE_GENERATION = intPreferencesKey("done_generation")
        private val LAST_AT = longPreferencesKey("last_at")
        private val LAST_MERGED = intPreferencesKey("last_merged")
        private val LAST_REMOVED = intPreferencesKey("last_removed")
        private val LAST_LEFT = intPreferencesKey("last_left_for_review")
        private val NOTICE = intPreferencesKey("notice_pending")

        /** The state over the process-wide DataStore, for code that has a Context but no injection. */
        fun forContext(context: Context): DuplicateCleanupState =
            DuplicateCleanupState(context.applicationContext.duplicateCleanupDataStore)
    }
}

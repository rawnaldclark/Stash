package com.stash.data.download.clips

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Its own file, so a corrupt write can only reset this state, and a reset only means the check
 * runs once more: a song it queued is no longer a downloaded clip, so it finds nothing new.
 */
private val Context.existingClipCheckDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "existing_clip_check",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Where the one-time [ExistingClipCheck] is on this install, and the line it leaves for the app to
 * show once ("Downloading 3 songs again ...").
 *
 *  - done: set when a run reaches the end; startup enqueues the check only until then, and a run
 *    returns at once after it.
 *  - cursor: the last track id whose file was checked, written after each page, so a run the
 *    system stopped (the app killed, the battery low) carries on from there.
 *  - queued: the tracks the check handed back for a download. Written before the song is handed
 *    back, so a run stopped in between checks it again (the cursor is not past it); a set, so a
 *    song checked twice counts once. A later run's end hands each one an earlier run queued and
 *    still waiting to WorkManager again ([com.stash.core.data.files.RedownloadKeepingFile.resumeQueued]);
 *    the run that reaches the end counts them all.
 *  - notice: how many to tell the person about, until the line has been shown.
 */
@Singleton
class ExistingClipCheckState internal constructor(
    private val store: DataStore<Preferences>,
) {

    @Inject constructor(@ApplicationContext context: Context) : this(context.existingClipCheckDataStore)

    suspend fun isDone(): Boolean = store.data.first()[DONE] == true

    /** The last track id already checked; [Long.MIN_VALUE] before the first page. */
    suspend fun cursor(): Long = store.data.first()[CURSOR] ?: Long.MIN_VALUE

    /** Every page up to track [lastId] is checked. */
    suspend fun advance(lastId: Long) {
        store.edit { it[CURSOR] = lastId }
    }

    /** Track [trackId] is about to be handed back for a download. */
    suspend fun recordQueued(trackId: Long) {
        store.edit { it[QUEUED] = (it[QUEUED] ?: emptySet()) + trackId.toString() }
    }

    /** Track [trackId] was not handed back after all (the person removed its download meanwhile). */
    suspend fun forgetQueued(trackId: Long) {
        store.edit { it[QUEUED] = (it[QUEUED] ?: emptySet()) - trackId.toString() }
    }

    /** The tracks this check handed back, across every run. */
    suspend fun queued(): Set<Long> = store.data.first()[QUEUED].orEmpty().mapNotNullTo(HashSet()) { it.toLongOrNull() }

    /** The check reached the end: it never runs again, and the line is left to show when it queued any. */
    suspend fun markDone() {
        store.edit {
            if (it[DONE] == true) return@edit
            it[DONE] = true
            val count = it[QUEUED].orEmpty().size
            if (count > 0) it[NOTICE] = count
        }
    }

    /** How many songs to tell the person are downloading again; null when there is nothing to say. */
    val pendingNotice: Flow<Int?> = store.data.map { prefs -> prefs[NOTICE]?.takeIf { it > 0 } }

    /** The line was shown. */
    suspend fun noticeShown() {
        store.edit { it.remove(NOTICE) }
    }

    private companion object {
        val DONE = booleanPreferencesKey("done_v1")
        val CURSOR = longPreferencesKey("cursor")
        val QUEUED = stringSetPreferencesKey("queued_track_ids")
        val NOTICE = intPreferencesKey("notice")
    }
}

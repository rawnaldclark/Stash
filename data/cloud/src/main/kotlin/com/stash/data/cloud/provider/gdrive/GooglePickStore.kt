package com.stash.data.cloud.provider.gdrive

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google Drive files the user picked that the scan hasn't filed yet, per picks folder row.
 *
 * Only the not-yet-scanned ids live here: once a pick is a library song its `cloud_items` row is
 * the record (so a restored backup, which brings the rows but not this store, keeps its songs).
 * The provider prunes an id once it is scanned, gone, or turned out not to be audio.
 *
 * Suspending: the store is file I/O, kept off the main thread (Settings files a pick from there).
 */
interface GooglePickStore {
    suspend fun pending(folderId: Long): Set<String>
    suspend fun add(folderId: Long, ids: Collection<String>)
    suspend fun remove(folderId: Long, ids: Collection<String>)
    suspend fun clear(folderId: Long)
}

/**
 * [GooglePickStore] in private app preferences (ids only; nothing secret). Writes `commit()` on
 * [Dispatchers.IO] rather than `apply()`: a pick is on disk before the scan it starts runs, and
 * a process death right after "Add songs" can't lose it.
 */
@Singleton
class PrefsGooglePickStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : GooglePickStore {
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val lock = Any()

    override suspend fun pending(folderId: Long): Set<String> = withContext(Dispatchers.IO) {
        synchronized(lock) { prefs.getStringSet(key(folderId), emptySet()).orEmpty().toSet() }
    }

    override suspend fun add(folderId: Long, ids: Collection<String>) = update(folderId) { it + ids }

    override suspend fun remove(folderId: Long, ids: Collection<String>) = update(folderId) { it - ids.toSet() }

    override suspend fun clear(folderId: Long) = update(folderId) { emptySet() }

    private suspend fun update(folderId: Long, change: (Set<String>) -> Set<String>) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val before = prefs.getStringSet(key(folderId), emptySet()).orEmpty().toSet()
            val after = change(before)
            if (after == before) return@synchronized
            // A new set every time: SharedPreferences must not be handed back the set it returned.
            val saved = if (after.isEmpty()) prefs.edit().remove(key(folderId)).commit()
            else prefs.edit().putStringSet(key(folderId), HashSet(after)).commit()
            if (!saved) Log.w(TAG, "Google Drive picks not saved")
        }
    }

    private fun key(folderId: Long) = "picks_$folderId"

    private companion object {
        const val PREFS = "cloud_gdrive_picks"
        const val TAG = "GooglePickStore"
    }
}


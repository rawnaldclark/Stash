package com.stash.data.cloud.provider.gdrive

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google Drive files the user picked that the scan hasn't filed yet, per picks folder row.
 *
 * Only the not-yet-scanned ids live here: once a pick is a library song its `cloud_items` row is
 * the record (so a restored backup, which brings the rows but not this store, keeps its songs).
 * The provider prunes an id once it is scanned, gone, or turned out not to be audio.
 */
interface GooglePickStore {
    fun pending(folderId: Long): Set<String>
    fun add(folderId: Long, ids: Collection<String>)
    fun remove(folderId: Long, ids: Collection<String>)
    fun clear(folderId: Long)
}

/** [GooglePickStore] in private app preferences (ids only; nothing secret). */
@Singleton
class PrefsGooglePickStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : GooglePickStore {
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val lock = Any()

    override fun pending(folderId: Long): Set<String> = synchronized(lock) {
        prefs.getStringSet(key(folderId), emptySet()).orEmpty().toSet()
    }

    override fun add(folderId: Long, ids: Collection<String>) = update(folderId) { it + ids }

    override fun remove(folderId: Long, ids: Collection<String>) = update(folderId) { it - ids.toSet() }

    override fun clear(folderId: Long) = synchronized(lock) { prefs.edit().remove(key(folderId)).apply() }

    private fun update(folderId: Long, change: (Set<String>) -> Set<String>) = synchronized(lock) {
        val before = prefs.getStringSet(key(folderId), emptySet()).orEmpty().toSet()
        val after = change(before)
        if (after == before) return@synchronized
        // A new set every time: SharedPreferences must not be handed back the set it returned.
        if (after.isEmpty()) prefs.edit().remove(key(folderId)).apply()
        else prefs.edit().putStringSet(key(folderId), HashSet(after)).apply()
    }

    private fun key(folderId: Long) = "picks_$folderId"

    private companion object {
        const val PREFS = "cloud_gdrive_picks"
    }
}


package com.stash.core.data.share

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.shareDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "share_preference",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * The optional display name on shared mixes (spec §5), remembered between shares, and Listen Together's
 * "Let guests add songs" (spec 2026-11-04), remembered between sessions.
 */
@Singleton
class SharePreference @Inject constructor(@ApplicationContext private val context: Context) {
    private val nameKey = stringPreferencesKey("display_name")
    private val guestsAddKey = booleanPreferencesKey("listen_guests_add")

    /** The host's "Let guests add songs": on until they turn it off (owner decision 1, 2026-11-04). */
    val guestsAdd: Flow<Boolean> = context.shareDataStore.data.map { it[guestsAddKey] ?: true }.catch { emit(true) }

    suspend fun guestsAddNow(): Boolean = runCatching { guestsAdd.first() }.getOrDefault(true)

    suspend fun setGuestsAdd(on: Boolean) {
        context.shareDataStore.edit { it[guestsAddKey] = on }
    }

    suspend fun displayName(): String? =
        runCatching { context.shareDataStore.data.map { it[nameKey] }.first() }.getOrNull()?.takeIf { it.isNotBlank() }

    suspend fun setDisplayName(name: String?) {
        context.shareDataStore.edit { if (name.isNullOrBlank()) it.remove(nameKey) else it[nameKey] = name.trim().cut(40) }
    }
}

package com.stash.core.data.share

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.shareDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "share_preference",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** The optional display name on shared mixes (spec §5), remembered between shares. */
@Singleton
class SharePreference @Inject constructor(@ApplicationContext private val context: Context) {
    private val nameKey = stringPreferencesKey("display_name")

    suspend fun displayName(): String? =
        runCatching { context.shareDataStore.data.map { it[nameKey] }.first() }.getOrNull()?.takeIf { it.isNotBlank() }

    suspend fun setDisplayName(name: String?) {
        context.shareDataStore.edit { if (name.isNullOrBlank()) it.remove(nameKey) else it[nameKey] = name.trim().cut(40) }
    }
}

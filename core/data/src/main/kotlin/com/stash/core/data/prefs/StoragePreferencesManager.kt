package com.stash.core.data.prefs

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Dedicated DataStore for storage preferences — independent of theme/quality. */
private val Context.storageDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "storage_preferences",
)

/**
 * DataStore-backed implementation of [StoragePreference].
 *
 * Stores the SAF tree URI as its string form so it round-trips cleanly
 * through DataStore (which doesn't support Uri primitives).
 */
@Singleton
class StoragePreferencesManager @Inject constructor(
    @ApplicationContext private val context: Context,
) : StoragePreference {
    private val externalTreeUriKey = stringPreferencesKey("external_tree_uri")

    override val externalTreeUri: Flow<Uri?> = context.storageDataStore.data.map { prefs ->
        prefs[externalTreeUriKey]?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
    }

    override suspend fun setExternalTreeUri(uri: Uri?) {
        context.storageDataStore.edit { prefs ->
            if (uri == null) {
                prefs.remove(externalTreeUriKey)
            } else {
                prefs[externalTreeUriKey] = uri.toString()
            }
        }
    }
}

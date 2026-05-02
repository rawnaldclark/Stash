package com.stash.data.download.lossless.inbox

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** DataStore for the inbox config — separate file from other lossless prefs
 *  so the schema can evolve in isolation. */
private val Context.losslessInboxDataStore by preferencesDataStore(name = "lossless_inbox_prefs")

/**
 * Persists the user's "Lossless Inbox" configuration:
 *
 *  - [inboxFolderUri]: the SAF tree URI the user picked. Stash watches
 *    this folder for newly-arrived audio files.
 *  - [lastScanTimestamp]: epoch millis of the last successful scan, so
 *    [LosslessInboxImporter] doesn't reprocess files it's already seen.
 *
 * Both are reactive Flows so the Settings screen can render the current
 * state without polling. One-shot getters back the importer's
 * `runCatching` flow.
 */
@Singleton
class LosslessInboxPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Reactive folder Uri — null when the user hasn't picked one. */
    val inboxFolderUri: Flow<Uri?> = context.losslessInboxDataStore.data.map { prefs ->
        prefs[KEY_FOLDER]?.takeIf { it.isNotBlank() }?.let(Uri::parse)
    }

    /** One-shot read of the folder URI — null when not configured. */
    suspend fun getInboxFolderUri(): Uri? = inboxFolderUri.first()

    /** Reactive last-scan timestamp; 0 when never scanned. */
    val lastScanTimestamp: Flow<Long> = context.losslessInboxDataStore.data.map { prefs ->
        prefs[KEY_LAST_SCAN] ?: 0L
    }

    suspend fun getLastScanTimestamp(): Long = lastScanTimestamp.first()

    /**
     * Save the user's chosen inbox folder. Caller is responsible for
     * having already taken a persistable URI permission via
     * `ContentResolver.takePersistableUriPermission` — without that, the
     * URI becomes useless after the app is backgrounded. Same gotcha
     * as the existing external-storage SAF flow in Settings.
     */
    suspend fun setInboxFolderUri(uri: Uri?) {
        context.losslessInboxDataStore.edit { prefs ->
            if (uri == null) prefs.remove(KEY_FOLDER)
            else prefs[KEY_FOLDER] = uri.toString()
        }
    }

    /** Records a completed scan so subsequent scans only see newer files. */
    suspend fun setLastScanTimestamp(epochMs: Long) {
        context.losslessInboxDataStore.edit { prefs ->
            prefs[KEY_LAST_SCAN] = epochMs
        }
    }

    /** Clears the configuration entirely (folder + scan timestamp). */
    suspend fun clear() {
        context.losslessInboxDataStore.edit { it.clear() }
    }

    private companion object {
        val KEY_FOLDER = stringPreferencesKey("inbox_folder_uri")
        val KEY_LAST_SCAN = longPreferencesKey("last_scan_epoch_ms")
    }
}

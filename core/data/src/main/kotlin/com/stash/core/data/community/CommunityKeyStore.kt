package com.stash.core.data.community

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

private const val TAG = "CommunityKey"

private val Context.communityDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "community_preference",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** 32 random bytes as base64url, 43 characters: the format the Worker's `validEditKey` accepts. */
internal fun newCommunityKey(): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))

/**
 * This phone's Community key (spec 2026-09-26 §2 Identity); the Worker keeps only its SHA-256. Made on the
 * first post or vote. Reads send it only once it exists, so just reading never makes one.
 */
@Singleton
class CommunityKeyStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val keyPref = stringPreferencesKey("community_key")

    suspend fun existingKey(): String? =
        try { context.communityDataStore.data.first()[keyPref] } catch (e: IOException) { null }

    /** The key, made and saved first when there is none. DataStore runs edits one at a time, so concurrent first uses agree. */
    suspend fun key(): String = existingKey() ?: newCommunityKey().let { k ->
        // Keeps a key that's already stored (the read above can fail on one); unsaved, this action still uses k.
        try {
            context.communityDataStore.edit { if (it[keyPref] == null) it[keyPref] = k }[keyPref] ?: k
        } catch (e: IOException) {
            Log.w(TAG, "community key not saved", e)
            k
        }
    }
}

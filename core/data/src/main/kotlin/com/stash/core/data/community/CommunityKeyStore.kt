package com.stash.core.data.community

import android.content.Context
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Context.communityDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "community_preference",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** 32 random bytes as base64url, 43 characters: the format the Worker's `validEditKey` accepts. */
internal fun newCommunityKey(): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))

/**
 * This phone's Community key (spec 2026-09-26 §2 Identity); the Worker keeps only its SHA-256. Made on the
 * first post, vote or limits check. Reads send it only once it exists, so just reading never makes one.
 */
@Singleton
class CommunityKeyStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val keyPref = stringPreferencesKey("community_key")
    private val lock = Mutex()

    suspend fun existingKey(): String? =
        try { context.communityDataStore.data.first()[keyPref] } catch (e: IOException) { null }

    /** The key, made and saved first when there is none. */
    suspend fun key(): String = lock.withLock {
        existingKey() ?: newCommunityKey().also { k ->
            // Unsaved, this action still works; the next one makes a new key (a new identity).
            try { context.communityDataStore.edit { it[keyPref] = k } } catch (e: IOException) { }
        }
    }
}

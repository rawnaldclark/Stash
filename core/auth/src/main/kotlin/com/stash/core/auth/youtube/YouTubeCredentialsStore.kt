package com.stash.core.auth.youtube

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists user-provided Google Cloud OAuth credentials (Client ID and Client Secret)
 * for the YouTube device-code authorization flow.
 *
 * These values are app-registration identifiers, not user secrets, so plain
 * DataStore preferences are appropriate (no encryption needed). The user enters
 * them once in the Settings screen and they are reused for all subsequent
 * device-code and token-refresh requests.
 */
@Singleton
class YouTubeCredentialsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private val KEY_CLIENT_ID = stringPreferencesKey("yt_client_id")
        private val KEY_CLIENT_SECRET = stringPreferencesKey("yt_client_secret")
    }

    /** Reactive flow of the stored Client ID, empty string if not set. */
    val clientId: Flow<String> = context.youTubeCredentials.data.map { prefs ->
        prefs[KEY_CLIENT_ID] ?: ""
    }

    /** Reactive flow of the stored Client Secret, empty string if not set. */
    val clientSecret: Flow<String> = context.youTubeCredentials.data.map { prefs ->
        prefs[KEY_CLIENT_SECRET] ?: ""
    }

    /**
     * Returns true if both Client ID and Client Secret have been saved.
     */
    suspend fun hasCredentials(): Boolean {
        val prefs = context.youTubeCredentials.data.first()
        val id = prefs[KEY_CLIENT_ID] ?: ""
        val secret = prefs[KEY_CLIENT_SECRET] ?: ""
        return id.isNotBlank() && secret.isNotBlank()
    }

    /**
     * Returns the stored Client ID, or empty string if not set.
     */
    suspend fun getClientId(): String {
        return context.youTubeCredentials.data.first()[KEY_CLIENT_ID] ?: ""
    }

    /**
     * Returns the stored Client Secret, or empty string if not set.
     */
    suspend fun getClientSecret(): String {
        return context.youTubeCredentials.data.first()[KEY_CLIENT_SECRET] ?: ""
    }

    /**
     * Saves the user's Google Cloud OAuth credentials.
     *
     * @param clientId     The OAuth Client ID (TV / limited-input device type).
     * @param clientSecret The OAuth Client Secret paired with [clientId].
     */
    suspend fun saveCredentials(clientId: String, clientSecret: String) {
        context.youTubeCredentials.edit { prefs ->
            prefs[KEY_CLIENT_ID] = clientId.trim()
            prefs[KEY_CLIENT_SECRET] = clientSecret.trim()
        }
    }

    /**
     * Clears the stored credentials.
     */
    suspend fun clearCredentials() {
        context.youTubeCredentials.edit { it.clear() }
    }
}

/** Singleton DataStore instance for YouTube credentials. */
private val Context.youTubeCredentials by preferencesDataStore(name = "youtube_credentials")

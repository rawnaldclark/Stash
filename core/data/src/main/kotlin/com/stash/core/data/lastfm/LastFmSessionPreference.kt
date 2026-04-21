package com.stash.core.data.lastfm

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Persistent (username, sessionKey) pair from a successful Last.fm auth. */
data class LastFmSession(
    val username: String,
    val sessionKey: String,
)

/** Dedicated DataStore for Last.fm session — independent of other prefs. */
private val Context.lastFmDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "lastfm_session",
)

/**
 * DataStore-backed storage for the user's Last.fm session. Session keys
 * do not expire per Last.fm — once stored, the user stays connected
 * until they explicitly disconnect (which calls [clear]).
 */
@Singleton
class LastFmSessionPreference @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val usernameKey = stringPreferencesKey("username")
    private val sessionKeyKey = stringPreferencesKey("session_key")
    private val bannerDismissedKey = booleanPreferencesKey("home_banner_dismissed")

    val session: Flow<LastFmSession?> = context.lastFmDataStore.data.map { prefs ->
        val u = prefs[usernameKey]
        val k = prefs[sessionKeyKey]
        if (u.isNullOrBlank() || k.isNullOrBlank()) null else LastFmSession(u, k)
    }

    /**
     * Whether the user dismissed the Home "Connect Last.fm" nudge banner.
     * Sticky — once set, the banner stays hidden forever (the user can
     * still connect via Settings). If they ever disconnect after
     * connecting, this flag is reset so the banner can come back if they
     * rack up more pending plays without reconnecting.
     */
    val bannerDismissed: Flow<Boolean> = context.lastFmDataStore.data.map { prefs ->
        prefs[bannerDismissedKey] ?: false
    }

    suspend fun save(session: LastFmSession) {
        context.lastFmDataStore.edit {
            it[usernameKey] = session.username
            it[sessionKeyKey] = session.sessionKey
        }
    }

    suspend fun clear() {
        context.lastFmDataStore.edit {
            it.remove(usernameKey)
            it.remove(sessionKeyKey)
            // Also reset the banner-dismissed flag so a user who
            // disconnects and later accumulates more pending plays can
            // see the nudge again.
            it.remove(bannerDismissedKey)
        }
    }

    suspend fun setBannerDismissed(dismissed: Boolean) {
        context.lastFmDataStore.edit { it[bannerDismissedKey] = dismissed }
    }
}

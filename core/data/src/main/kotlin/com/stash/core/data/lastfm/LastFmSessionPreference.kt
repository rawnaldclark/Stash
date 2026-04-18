package com.stash.core.data.lastfm

import android.content.Context
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

    val session: Flow<LastFmSession?> = context.lastFmDataStore.data.map { prefs ->
        val u = prefs[usernameKey]
        val k = prefs[sessionKeyKey]
        if (u.isNullOrBlank() || k.isNullOrBlank()) null else LastFmSession(u, k)
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
        }
    }
}

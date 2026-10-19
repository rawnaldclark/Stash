package com.stash.core.data.lastfm.recap

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stash.core.data.lastfm.LastFmSession
import com.stash.core.data.lastfm.LastFmSessionPreference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A Last.fm username to point the Last.fm recap at for testing, set from adb. DEBUG BUILDS ONLY:
 * the app module binds [None] in release builds (see LastFmRecapDebugModule), so a release build
 * never reads a value even if one were somehow written.
 */
interface LastFmRecapDebugUser {
    val username: Flow<String?>

    /** Release builds: no override. */
    object None : LastFmRecapDebugUser {
        override val username: Flow<String?> = flowOf(null)
    }
}

private val Context.lastFmRecapDebugStore: DataStore<Preferences> by preferencesDataStore(
    name = "lastfm_recap_debug",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * The debug override's storage: its own small DataStore, written only by the debug-only adb
 * receiver (app/src/debug DebugLastFmRecapUser). Holds a public username, never a session key.
 */
@Singleton
class LastFmRecapDebugUserStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : LastFmRecapDebugUser {
    private val key = stringPreferencesKey("username")

    override val username: Flow<String?> =
        context.lastFmRecapDebugStore.data.map { prefs -> prefs[key]?.trim()?.takeIf { it.isNotEmpty() } }

    /** Sets (or, with blank, clears) the test username. */
    suspend fun set(username: String?) {
        context.lastFmRecapDebugStore.edit { prefs ->
            val clean = username?.trim().orEmpty()
            if (clean.isEmpty()) prefs.remove(key) else prefs[key] = clean
        }
    }
}

/**
 * Whose history a Last.fm recap reads. [sessionKey] is the connected account's own session key,
 * set only when [name] IS that account (never for the debug test user), so a profile that hides
 * its listening can still read itself with a signed request.
 */
data class LastFmRecapUser(val name: String, val sessionKey: String?) {
    override fun toString(): String = "LastFmRecapUser($name, session=${sessionKey != null})"
}

/**
 * Whose Last.fm history the Last.fm recap reads: the connected account (Settings → Last.fm), or
 * in a debug build the adb-set test username when there is one. Null = not connected: no Last.fm
 * recap is offered. Reads are unsigned (public profiles); only a private profile reading itself
 * signs with its own session key.
 */
@Singleton
class LastFmRecapAccount internal constructor(
    session: Flow<LastFmSession?>,
    debugUser: LastFmRecapDebugUser,
) {
    @Inject
    constructor(session: LastFmSessionPreference, debugUser: LastFmRecapDebugUser) :
        this(session.session, debugUser)

    val user: Flow<LastFmRecapUser?> =
        combine(session, debugUser.username) { connected, debug ->
            when {
                debug != null -> LastFmRecapUser(debug, sessionKey = null)
                connected != null && connected.username.isNotBlank() -> LastFmRecapUser(connected.username, connected.sessionKey)
                else -> null
            }
        }.distinctUntilChanged()

    val username: Flow<String?> = user.map { it?.name }.distinctUntilChanged()
}

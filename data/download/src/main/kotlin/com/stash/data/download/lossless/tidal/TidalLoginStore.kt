package com.stash.data.download.lossless.tidal

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Context.tidalLoginDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "tidal_login",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** The listener's own Tidal login (access + refresh token). Never logged. */
@Singleton
class TidalLoginStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: TidalLoginClient,
) {
    private val accessKey = stringPreferencesKey("access_token")
    private val refreshKey = stringPreferencesKey("refresh_token")
    private val expiresKey = longPreferencesKey("expires_at_ms")
    private val mutex = Mutex()

    internal var nowMs: () -> Long = System::currentTimeMillis

    /** A login is stored. Fails closed: a read error is "no login", never a crash. */
    val hasLogin: Flow<Boolean> = context.tidalLoginDataStore.data
        .map { !it[refreshKey].isNullOrBlank() }
        .distinctUntilChanged()
        .catch { emit(false) }

    suspend fun hasLoginNow(): Boolean = hasLogin.first()

    private class Creds(val access: String, val refresh: String, val expiresAtMs: Long)

    private suspend fun read(): Creds? {
        val p = try {
            context.tidalLoginDataStore.data.first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        val a = p[accessKey]
        val r = p[refreshKey]
        return if (a.isNullOrBlank() || r.isNullOrBlank()) null else Creds(a, r, p[expiresKey] ?: 0L)
    }

    suspend fun save(t: TidalTokens) {
        context.tidalLoginDataStore.edit {
            it[accessKey] = t.accessToken
            it[refreshKey] = t.refreshToken
            it[expiresKey] = nowMs() + t.expiresInSec * 1000L
        }
    }

    suspend fun clear() {
        context.tidalLoginDataStore.edit { it.clear() }
    }

    /** The access token to send, refreshed first when it ends within [REFRESH_MARGIN_MS]; null with no login. */
    suspend fun accessToken(): String? {
        val c = read() ?: return null
        if (c.expiresAtMs - nowMs() > REFRESH_MARGIN_MS) return c.access
        refreshNow(c.access)
        return read()?.access
    }

    /**
     * Refreshes the login. [stale] is the access token the caller just had refused: if the stored one differs, someone
     * already refreshed and this is true at once. False = no refreshed token (login gone, or Tidal not reachable).
     */
    suspend fun refreshNow(stale: String?): Boolean = mutex.withLock {
        val c = read() ?: return@withLock false
        if (stale != null && c.access != stale) return@withLock true
        when (val r = client.refresh(c.refresh)) {
            is TidalRefresh.Ok -> try {
                save(r.tokens)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            // Tidal rejected the grant: drop the login, and the rung is Stash's relay again.
            TidalRefresh.LoginExpired -> {
                try { clear() } catch (e: CancellationException) { throw e } catch (e: Exception) { /* next refresh tries again */ }
                false
            }
            TidalRefresh.Failed -> false
        }
    }

    private companion object {
        const val REFRESH_MARGIN_MS = 5 * 60_000L
    }
}
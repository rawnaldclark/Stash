package com.stash.data.download.lossless.tidal

import com.stash.core.addons.UserAddonEntry
import com.stash.core.addons.UserAddonKind
import com.stash.core.addons.UserAddons
import com.stash.data.download.lossless.LosslessSourcePreferences
import android.util.Log
import com.stash.data.download.lossless.addon.EncryptedUserAddons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user's own Tidal server (add-ons plan phase 4 §2.10): the sealed
 * [UserAddonKind.OWN_TIDAL_SERVER] entry in [EncryptedUserAddons], which [OwnTidalServerMigration]
 * moves out of the old Custom Tidal server field's plain DataStore keys.
 *
 * Until that move has happened (this build's first start, or a start where the Keystore refused
 * the write), the plain keys still answer, so the server never goes missing in between. The Tidal
 * rung plays it as before: the legacy wire, its token as `X-Access-Token`, first above CD with no
 * Qobuz account of the user's.
 *
 * Read by [TidalWires], [TidalClient], `LosslessAvailability` and Settings' row. Never logs the
 * base (its path can be the access token) or the token.
 */
@Singleton
class OwnTidalServer(
    private val users: UserAddons,
    private val prefs: LosslessSourcePreferences,
    private val removeSealed: suspend () -> Unit = {},
) {
    @Inject constructor(users: EncryptedUserAddons, prefs: LosslessSourcePreferences) :
        this(users, prefs, { users.removeKind(UserAddonKind.OWN_TIDAL_SERVER) })

    /** The server's base and its access token (null: the server runs open, or the token is the base's path). */
    data class Server(val base: String, val token: String?) {
        override fun toString(): String = "Server(token=${if (token != null) "set" else "none"})"
    }

    /**
     * The server's base, or null with none set; live.
     *
     * Its first value waits for the sealed store's first read (review-882 M6). [UserAddons.entries]
     * starts empty until then, and after the move the plain key is gone, so without the wait every
     * cold start first said "no server": a play or a download in a process's first moments (a
     * media-button resume) was ordered as if there were none, and a diagnostics bundle made then
     * named Stash's Tidal instead of the user's server.
     */
    val endpoint: Flow<String?> = flow {
        users.loadedEntries()
        emitAll(combine(users.entries, prefs.bitchordEndpoint) { entries, legacy -> sealed(entries)?.base ?: legacy })
    }.distinctUntilChanged()

    /** The server now, once the sealed store has been read; null with none set. */
    suspend fun now(): Server? =
        sealed(users.loadedEntries()) ?: prefs.bitchordEndpointNow()?.let { Server(it, prefs.bitchordAccessTokenNow()) }

    /**
     * Removes the server and its token, sealed and plain: the Tidal rung goes back to Stash's.
     * False when it could not be saved, and then the server is still set. The Keystore fails for a
     * moment now and then (the seal throws, or the stored list has not opened and the store refuses
     * to write over it); that must not reach a `viewModelScope.launch` with no handler, which is a
     * crash on Remove (review-882 J2).
     *
     * The plain keys go first (review-882 round 2, n3). The other way round, a sealed entry that
     * was removed and plain keys that then failed to clear answered false after doing half, and
     * the next start moved the server back in from those keys. Now a failure of the first step
     * changes nothing, and one of the second leaves the sealed entry, which is the server either way.
     */
    suspend fun remove(): Boolean = try {
        prefs.clearBitchordServer()
        removeSealed()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Class only: a message could quote the server's address or its token.
        Log.w(TAG, "the own Tidal server could not be removed: ${e.javaClass.simpleName}")
        false
    }

    private fun sealed(entries: List<UserAddonEntry>): Server? =
        entries.firstOrNull { it.kind == UserAddonKind.OWN_TIDAL_SERVER && it.enabled }?.let { Server(it.manifestUrl, it.token) }

    private companion object {
        const val TAG = "OwnTidalServer"
    }
}

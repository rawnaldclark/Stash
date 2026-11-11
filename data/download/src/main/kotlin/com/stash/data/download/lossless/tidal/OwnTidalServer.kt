package com.stash.data.download.lossless.tidal

import com.stash.core.addons.UserAddonEntry
import com.stash.core.addons.UserAddonKind
import com.stash.core.addons.UserAddons
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.addon.EncryptedUserAddons
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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

    /** The server's base, or null with none set; live. */
    val endpoint: Flow<String?> =
        combine(users.entries, prefs.bitchordEndpoint) { entries, legacy -> sealed(entries)?.base ?: legacy }
            .distinctUntilChanged()

    /** The server now, once the sealed store has been read; null with none set. */
    suspend fun now(): Server? =
        sealed(users.loadedEntries()) ?: prefs.bitchordEndpointNow()?.let { Server(it, prefs.bitchordAccessTokenNow()) }

    /** Removes the server and its token, sealed and plain: the Tidal rung goes back to Stash's. */
    suspend fun remove() {
        removeSealed()
        prefs.clearBitchordServer()
    }

    private fun sealed(entries: List<UserAddonEntry>): Server? =
        entries.firstOrNull { it.kind == UserAddonKind.OWN_TIDAL_SERVER && it.enabled }?.let { Server(it.manifestUrl, it.token) }
}

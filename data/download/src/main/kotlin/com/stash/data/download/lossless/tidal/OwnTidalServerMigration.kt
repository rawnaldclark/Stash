package com.stash.data.download.lossless.tidal

import android.util.Log
import com.stash.core.addons.InstalledAddon
import com.stash.core.addons.UserAddonEntry
import com.stash.core.addons.UserAddonKind
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.addon.EncryptedUserAddons
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Moves the user's own Tidal server out of the old Custom Tidal server field (add-ons plan phase 4
 * §2.10): the plain `bitchord_endpoint` and `bitchord_access_token` keys become one sealed
 * [UserAddonKind.OWN_TIDAL_SERVER] entry in [EncryptedUserAddons], then both keys go in one edit.
 * Run at every app start until there is nothing left to move; anyone who set a server keeps it
 * without doing anything.
 *
 * Nothing is ever lost: the keys are deleted only after the sealed write succeeded. A Keystore
 * failure leaves them, [OwnTidalServer] keeps answering from them, and the next start tries again.
 * A token a request can't carry (control characters) never worked as a header: the server moves
 * without it, with a fixed log line.
 *
 * A settings restore never brings the keys back (`DatabaseBackupManager` drops them on export and
 * on import), so this never seals a login that came from a backup.
 */
@Singleton
class OwnTidalServerMigration internal constructor(
    private val prefs: LosslessSourcePreferences,
    private val seal: suspend (UserAddonEntry) -> Unit,
) {
    @Inject constructor(prefs: LosslessSourcePreferences, users: EncryptedUserAddons) : this(prefs, { users.put(it) })

    /** What [run] did, for tests. */
    enum class Outcome { NOTHING_TO_MOVE, MOVED, KEPT_FOR_NEXT_START }

    suspend fun run(): Outcome {
        val endpoint = try {
            prefs.bitchordEndpointNow()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "own Tidal server keys unreadable: ${e.javaClass.simpleName}")
            return Outcome.KEPT_FOR_NEXT_START
        } ?: return Outcome.NOTHING_TO_MOVE
        val token = prefs.bitchordAccessTokenNow()
        val carried = token?.takeIf { InstalledAddon.isHeaderSafe(it) }
        if (token != null && carried == null) Log.w(TAG, "own Tidal server token has characters a request can't carry; moved without it")
        try {
            seal(UserAddonEntry(endpoint, carried, enabled = true, kind = UserAddonKind.OWN_TIDAL_SERVER))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never e.message: it could quote the token.
            Log.w(TAG, "own Tidal server not moved (${e.javaClass.simpleName}); kept as it was, tried again next start")
            return Outcome.KEPT_FOR_NEXT_START
        }
        try {
            prefs.clearBitchordServer()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Sealed but the plain keys stayed: the next start seals the same server again
            // (replacing this entry) and deletes them then.
            Log.w(TAG, "own Tidal server sealed, old keys not yet deleted (${e.javaClass.simpleName}); tried again next start")
            return Outcome.KEPT_FOR_NEXT_START
        }
        Log.i(TAG, "own Tidal server moved into add-ons")
        return Outcome.MOVED
    }

    private companion object {
        const val TAG = "OwnTidalServerMigration"
    }
}

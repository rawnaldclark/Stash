package com.stash.core.auth.spotify

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The Spotify account a coroutine acts as: one of the EXTRA accounts (plan 2026-11-04 §2.2),
 * named by its `spotify_accounts` row: the row id and the row's Spotify user id. No element in
 * the context means the main account, exactly as before extras existed.
 *
 * Every per-account read of [com.stash.core.auth.TokenManager] follows it: the access token
 * (and its forced refresh), the sp_dc cookie, the username, the client id, and
 * `isAuthenticated(SPOTIFY)`. So a sync pass wrapped in [withSpotifyAccount] makes every
 * `SpotifyApiClient` call in it act as that extra, with no account parameter threaded through.
 *
 * **The safety rule.** With an extra set, nothing answers with the main account's sign-in: an
 * extra's pass would otherwise read the main's library as the extra's. Without a sign-in of its
 * own every read answers null; with one that has died, the access token and forced refresh answer
 * null and the cookie, username and client id answer with the extra's own stored values (see
 * `TokenManager.getSpotifyAccessToken`). So a pass runs only when the access token is non-null.
 * The rule lives in `TokenManagerImpl` (`actingExtra()` and `extraSignIn()`).
 *
 * **Why the user id too.** The sign-in is stored under the row id, outside the database, so a
 * database restored from a backup (another phone's, or an older one) can give a row id to a
 * different account than the sign-in stored under it. A sign-in whose account isn't [userId]
 * answers null, like a missing one: an extra's pass never reads one account's library as
 * another's.
 *
 * **What it does not reach.** A coroutine context travels into `withContext`, `async` and
 * `launch` children and across dispatchers, but NOT into work started on another scope (an
 * application scope, a WorkManager job, a callback thread that starts its own coroutine). Such
 * work acts as the main account. `SpotifyApiClient` starts nothing on other scopes (checked
 * 2026-11-08), so the element reaches every call it makes.
 *
 * The main account's sign-in, connect, disconnect and `spotifyAuthState` are not affected:
 * they are the main account's whatever is acting.
 *
 * @property rowId The extra account's `spotify_accounts.id`; always positive.
 * @property userId The row's `spotify_accounts.user_id`: the Spotify account its sign-in must be.
 */
class SpotifyAccount(val rowId: Long, val userId: String) : AbstractCoroutineContextElement(SpotifyAccount) {

    init {
        require(rowId > 0) { "an extra Spotify account's row id is positive" }
        require(userId.isNotBlank()) { "an extra Spotify account has a user id" }
    }

    /** The key [SpotifyAccount] is found under in a coroutine context. */
    companion object Key : CoroutineContext.Key<SpotifyAccount>

    /** Whether [account] (whose a stored sign-in is) is this extra's account. Null is no. */
    fun isAccount(account: SpotifyProfile?): Boolean =
        account != null && account.accountKey == SpotifyProfile.accountKeyOf(userId)

    override fun equals(other: Any?): Boolean =
        other is SpotifyAccount && other.rowId == rowId &&
            SpotifyProfile.accountKeyOf(other.userId) == SpotifyProfile.accountKeyOf(userId)

    override fun hashCode(): Int = rowId.hashCode()

    /** Never names the account: logs and crash reports carry only the local row id. */
    override fun toString(): String = "SpotifyAccount(extra #$rowId)"
}

/**
 * Runs [block] acting as the extra Spotify account [account] (see [SpotifyAccount]). Every
 * per-account token read inside it is that account's, and null when its sign-in is missing, dead
 * or another account's, never the main account's.
 */
suspend fun <T> withSpotifyAccount(account: SpotifyAccount, block: suspend CoroutineScope.() -> T): T =
    withContext(account, block)

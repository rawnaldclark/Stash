package com.stash.core.auth.spotify

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The Spotify account a coroutine acts as: one of the EXTRA accounts (plan 2026-11-04 §2.2),
 * named by its `spotify_accounts` row id. No element in the context means the main account,
 * exactly as before extras existed.
 *
 * Every per-account read of [com.stash.core.auth.TokenManager] follows it: the access token
 * (and its forced refresh), the sp_dc cookie, the username, the client id, and
 * `isAuthenticated(SPOTIFY)`. So a sync pass wrapped in [withSpotifyAccount] makes every
 * `SpotifyApiClient` call in it act as that extra, with no account parameter threaded through.
 *
 * **The safety rule.** With an extra set, a missing or dead extra sign-in answers null. It never
 * falls back to the main account's token: an extra's pass would otherwise read the main's library
 * as the extra's. The rule lives in `TokenManagerImpl` (`actingExtra()` and the `extra*` reads).
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
 */
class SpotifyAccount(val rowId: Long) : AbstractCoroutineContextElement(SpotifyAccount) {

    init {
        require(rowId > 0) { "an extra Spotify account's row id is positive" }
    }

    /** The key [SpotifyAccount] is found under in a coroutine context. */
    companion object Key : CoroutineContext.Key<SpotifyAccount>

    override fun equals(other: Any?): Boolean = other is SpotifyAccount && other.rowId == rowId

    override fun hashCode(): Int = rowId.hashCode()

    /** Never names the account: logs and crash reports carry only the local row id. */
    override fun toString(): String = "SpotifyAccount(extra #$rowId)"
}

/**
 * Runs [block] acting as the extra Spotify account [rowId] (see [SpotifyAccount]). Every
 * per-account token read inside it is that account's, and null when its sign-in is missing or
 * dead, never the main account's.
 */
suspend fun <T> withSpotifyAccount(rowId: Long, block: suspend CoroutineScope.() -> T): T =
    withContext(SpotifyAccount(rowId), block)

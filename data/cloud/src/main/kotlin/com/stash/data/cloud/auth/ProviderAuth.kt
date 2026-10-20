package com.stash.data.cloud.auth

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * OAuth 2.0 PKCE (RFC 7636) for a public client: the app ships no secret, so each sign-in
 * proves it started the flow with a one-time verifier whose SHA-256 went out with the request.
 */
object Pkce {
    private val random = SecureRandom()

    /** 64 random bytes as base64url: 86 characters, inside RFC 7636's 43..128. */
    fun newVerifier(): String = randomToken(64)

    /** S256 challenge: base64url(SHA-256(verifier)), no padding. */
    fun challenge(verifier: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /** An unguessable `state` value tying a redirect to the sign-in that asked for it. */
    fun newState(): String = randomToken(24)

    private fun randomToken(bytes: Int): String {
        val b = ByteArray(bytes)
        random.nextBytes(b)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }
}

/** Tokens a provider's token endpoint answered with. [refreshToken] null = keep the old one. */
data class TokenResult(val accessToken: String, val refreshToken: String?, val expiresInSeconds: Long, val scope: String = "")

/** Who signed in. [remoteId] is stable per account (Graph `/me` id, Dropbox `account_id`). */
data class CloudIdentity(val remoteId: String, val displayName: String, val email: String?)

/** What came back to the redirect. */
sealed interface CallbackResult {
    data class Code(val code: String, val state: String) : CallbackResult
    data class Denied(val message: String, val state: String?) : CallbackResult
    /** Not this provider's redirect at all. */
    data object NotMine : CallbackResult
}

/** Thrown by a token endpoint that refuses the refresh token itself (revoked, expired). */
class RefreshRejectedException(message: String) : Exception(message)

/**
 * One provider's sign-in: where to send the browser, how to read what comes back, and its token
 * and identity endpoints. Stateless; [com.stash.data.cloud.auth.CloudAuthCoordinator] keeps the
 * per-attempt state (verifier, state) and [CloudTokenManager] the tokens.
 */
interface ProviderAuth {
    /** The browser URL that starts a sign-in. */
    fun authorizeUrl(state: String, codeChallenge: String): String

    /** Reads a redirect URI; [CallbackResult.NotMine] for anything else. */
    fun parseCallback(uri: String): CallbackResult

    suspend fun exchangeCode(code: String, verifier: String): TokenResult

    /** @throws RefreshRejectedException when the user must sign in again. */
    suspend fun refresh(refreshToken: String): TokenResult

    /**
     * Refresh, told which access token is being replaced. Google Drive needs it: Play services
     * caches the token, so the stale one must be cleared before asking again. Others ignore it.
     */
    suspend fun refresh(refreshToken: String, staleAccessToken: String?): TokenResult = refresh(refreshToken)

    suspend fun identity(accessToken: String): CloudIdentity
}

/** Small URL helpers shared by the providers' auth code. */
internal object Urls {
    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun query(params: List<Pair<String, String>>): String = params.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }

    /** The decoded query parameters of [uri] (first value wins); empty for an unparsable URI. */
    fun queryParams(uri: String): Map<String, String> {
        val raw = runCatching { URI(uri).rawQuery }.getOrNull() ?: return emptyMap()
        val out = linkedMapOf<String, String>()
        raw.split('&').forEach { part ->
            if (part.isEmpty()) return@forEach
            val k = URLDecoder.decode(part.substringBefore('='), "UTF-8")
            val v = URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
            out.putIfAbsent(k, v)
        }
        return out
    }

    /** scheme://host/path of [uri], lower-cased scheme and host, for exact redirect matching. */
    fun base(uri: String): String? = runCatching {
        val u = URI(uri)
        "${u.scheme?.lowercase()}://${u.host?.lowercase() ?: u.authority?.lowercase()}${u.path.orEmpty()}"
    }.getOrNull()
}

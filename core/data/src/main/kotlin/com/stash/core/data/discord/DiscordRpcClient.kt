package com.stash.core.data.discord

import android.util.Log
import com.stash.core.auth.discord.DiscordHeaders
import com.stash.core.auth.discord.DiscordRateLimiter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Posts/clears Discord Rich Presence via the headless-sessions endpoint,
 * authenticated with the connected account's own scraped user token (see
 * DiscordLoginWebView) — not a bot. Ported from echo-discord's RPC.kt +
 * TokenManager.kt with two upstream issues fixed:
 *
 *  - #16 (unsafe API calls): every endpoint moved from v10 to v9 (what the
 *    real client uses — v10 is bot/public-API-only), and every request now
 *    goes through [DiscordHeaders] instead of a bare Authorization header.
 *  - #15 (rate limits): [requestActivity] debounces — a burst of calls (fast
 *    track skipping) collapses to one network call for the LATEST activity —
 *    and every call routes through [DiscordRateLimiter], which also reads
 *    Discord's own Retry-After / X-RateLimit-* signals to back off instead of
 *    repeatedly 429ing.
 *
 * @param onUnauthorized invoked if the stored token stops working (revoked
 *   Discord session, password change, etc.) so the caller can flip the
 *   connection back to NotConnected.
 */
class DiscordRpcClient(
    private val userToken: String,
    private val onUnauthorized: (() -> Unit)? = null,
    private val onNeedsConsent: (() -> Unit)? = null,
) {
    private val http = OkHttpClient()
    private val rateLimiter = DiscordRateLimiter()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var accessToken: String? = null
    @Volatile private var activityToken: String? = null

    private data class PendingUpdate(val activity: DiscordActivity?, val nonce: Long)
    private var nonceCounter = 0L
    private val pending = MutableStateFlow<PendingUpdate?>(null)

    init {
        // collectLatest cancels the in-flight delay for any update superseded
        // before it fires — this is the actual fix for #15's repro (fast
        // skips), not just a retry-after-the-fact patch.
        scope.launch {
            pending.collectLatest { update ->
                if (update == null) return@collectLatest
                delay(DEBOUNCE_MS)
                runCatching { sendNow(update.activity) }
            }
        }
    }

    /** Queue a presence update (or null to clear). Safe to call on every track change. */
    fun requestActivity(activity: DiscordActivity?) {
        pending.value = PendingUpdate(activity, nonceCounter++)
    }

    /**
     * Clears the status, then stops this client for good (disconnect, logout,
     * revoked token). The clear runs first: cancelling straight away would
     * leave "Listening to…" on the profile.
     */
    fun close() {
        scope.launch {
            runCatching { sendNow(null) }
            scope.cancel()
        }
    }

    /**
    * Warms up the OAuth connection immediately after the user links their
    * Discord account, so the one-time consent prompt (and Rich Presence itself)
    * doesn't wait for the first track change to trigger it.
    */
    fun connect() {
        scope.launch { runCatching { getAccessToken() } }
    }

    private suspend fun sendNow(activity: DiscordActivity?) {
        if (activity == null) {
            deleteSession()
        } else {
            runCatching { post(DiscordSession(listOf(activity), activityToken)) }
                .onFailure { Log.w("DiscordRpc", "sendNow failed", it) }
        }
    }

    private suspend fun getAccessToken(): String {
        accessToken?.let { return it }
        val verifier = randomVerifier()
        val challenge = codeChallenge(verifier)

        val authorizeReq = Request.Builder().url(
            "https://discord.com/api/v9/oauth2/authorize" +
                "?client_id=$CLIENT_ID&response_type=code&redirect_uri=$REDIRECT_URI" +
                "&code_challenge=$challenge&code_challenge_method=S256" +
                "&scope=${SCOPES.replace(" ", "%20")}&state=undefined",
        )
        DiscordHeaders.apply(authorizeReq, token = userToken, bearer = false)
        authorizeReq.post(
            JsonObject(mapOf("authorize" to JsonPrimitive(true)))
                .toString().toRequestBody("application/json".toMediaType()),
        )
        rateLimiter.awaitReady()
        // Every response is closed, error paths included, or OkHttp can't reuse the connection.
        val location = http.newCall(authorizeReq.build()).await().use { res ->
            rateLimiter.observe(res)
            if (res.code == 401) { onUnauthorized?.invoke(); throw IllegalStateException("Discord token revoked") }
            if (!res.isSuccessful) throw IllegalStateException("authorize failed: ${res.code}")
            Json.decodeFromString<JsonObject>(res.body?.string() ?: throw IllegalStateException("Empty response body"))["location"]!!.jsonPrimitive.content
        }
        val code = location.substringAfter("code=").substringBefore("&")

        val tokenReq = Request.Builder().url("https://discord.com/api/v9/oauth2/token")
        DiscordHeaders.apply(tokenReq)
        tokenReq.post(
            FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("code", code)
                .add("code_verifier", verifier)
                .add("grant_type", "authorization_code")
                .add("redirect_uri", REDIRECT_URI)
                .build(),
        )
        rateLimiter.awaitReady()
        val newAccess = http.newCall(tokenReq.build()).await().use { tokenRes ->
            rateLimiter.observe(tokenRes)
            if (!tokenRes.isSuccessful) {
                val body = runCatching { tokenRes.body?.string() }.getOrNull()
                if (body?.contains("invalid_grant") == true) onNeedsConsent?.invoke()
                throw IllegalStateException("token exchange failed: ${tokenRes.code} — body: $body")
            }
            Json.decodeFromString<JsonObject>(tokenRes.body?.string() ?: throw IllegalStateException("Empty response body"))["access_token"]!!.jsonPrimitive.content
        }
        accessToken = newAccess
        return newAccess
    }

    private suspend fun post(session: DiscordSession) {
        val auth = getAccessToken()
        val req = Request.Builder().url("https://discord.com/api/v9/users/@me/headless-sessions")
        DiscordHeaders.apply(req, token = auth, bearer = true)
        req.post(Json.encodeToString(DiscordSession.serializer(), session).toRequestBody("application/json".toMediaType()))

        rateLimiter.awaitReady()
        http.newCall(req.build()).await().use { res ->
            rateLimiter.observe(res)
            if (res.code == 401) {
                Log.w("DiscordRpc", "headless-sessions POST got 401 — clearing auth")
                accessToken = null; onUnauthorized?.invoke(); return
            }
            if (!res.isSuccessful) {
                val body = runCatching { res.body?.string() }.getOrNull()
                Log.w("DiscordRpc", "headless-sessions POST failed: ${res.code} ${res.message} — body: $body")
                return // presence is best-effort — never crash playback over it
            }
            activityToken = Json.decodeFromString<JsonObject>(res.body?.string() ?: throw IllegalStateException("Empty response body"))["token"]!!.jsonPrimitive.content
        }
        Log.i("DiscordRpc", "Presence posted successfully")
    }

    private suspend fun deleteSession() {
        val token = activityToken ?: return
        val auth = getAccessToken()
        val req = Request.Builder().url("https://discord.com/api/v9/users/@me/headless-sessions/delete")
        DiscordHeaders.apply(req, token = auth, bearer = true)
        req.post(
            JsonObject(mapOf("token" to JsonPrimitive(token)))
                .toString().toRequestBody("application/json".toMediaType()),
        )
        rateLimiter.awaitReady()
        // Runs on every pause, so an unclosed body here leaked a connection per pause.
        http.newCall(req.build()).await().use { res ->
            rateLimiter.observe(res)
            if (res.isSuccessful) activityToken = null
        }
    }

    companion object {
        private val CLIENT_ID = DiscordRpcConfig.CLIENT_ID
        private val REDIRECT_URI = DiscordRpcConfig.REDIRECT_URI
        private val SCOPES = DiscordRpcConfig.SCOPES
        private const val DEBOUNCE_MS = 1200L
        private const val CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        private fun randomVerifier() = (1..128).map { CHARS.random() }.joinToString("")
        private fun codeChallenge(verifier: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        }

        private suspend fun Call.await(): Response = suspendCoroutine { cont ->
            enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
                override fun onResponse(call: Call, response: Response) = cont.resume(response)
            })
        }
    }
}
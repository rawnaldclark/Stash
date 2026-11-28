package com.stash.data.download.lossless.qbdlx

import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Outcome of a Qobuz account login. */
sealed interface QobuzLoginResult {
    /** Logged in with an eligible (paid) account. Carries the pair to sign its requests with. */
    data class Success(val token: String, val appId: String, val appSecret: String) : QobuzLoginResult
    /** Wrong email/password (HTTP 401). */
    object InvalidCredentials : QobuzLoginResult
    /** Logged in, but the account has no lossless subscription — no FLAC to serve. */
    object FreeAccount : QobuzLoginResult
    /** Network error, couldn't scrape app creds, or an unexpected response. */
    object Error : QobuzLoginResult
}

/**
 * Logs a user into their own Qobuz account and returns a `user_auth_token`.
 *
 * The login call itself isn't signed — it just needs a valid `app_id` (scraped
 * live by [QobuzWebCredentialsClient]). We return the scraped pair alongside the
 * token so the caller stores all three together: the token is guaranteed to be
 * signable afterwards because it was minted under exactly that app_id. Password
 * is MD5-hashed on the wire (Qobuz's scheme, per streamrip).
 *
 * [classify] is pure so every branch is tested without a network.
 */
@Singleton
class QobuzLoginClient @Inject constructor(
    sharedClient: OkHttpClient,
    private val webCredentials: QobuzWebCredentialsClient,
) {
    internal var httpClient: OkHttpClient = sharedClient
    internal var baseUrl: String = ORIGIN
    internal var json: Json = Json { ignoreUnknownKeys = true; isLenient = true }
    /** Seam: how app creds are obtained. Overridden in tests; production scrapes live. */
    internal var fetchCreds: suspend () -> QobuzWebCreds? = { webCredentials.fetch() }

    suspend fun login(email: String, password: String): QobuzLoginResult = withContext(Dispatchers.IO) {
        val creds = fetchCreds() ?: return@withContext QobuzLoginResult.Error
        val url = "$baseUrl/api.json/0.2/user/login".toHttpUrl().newBuilder()
            .addQueryParameter("email", email.trim())
            .addQueryParameter("password", md5(password))
            .addQueryParameter("app_id", creds.appId)
            .build()
        val req = Request.Builder().url(url.toString())
            .header("X-App-Id", creds.appId)
            .header("Accept", "application/json")
            .header("User-Agent", UA)
            .get().build()
        runCatching {
            httpClient.newCall(req).execute().use { resp ->
                classify(resp.code, resp.body?.string().orEmpty(), creds)
            }
        }.getOrDefault(QobuzLoginResult.Error)
    }

    internal fun classify(code: Int, body: String, creds: QobuzWebCreds): QobuzLoginResult {
        if (code == 401) return QobuzLoginResult.InvalidCredentials
        if (code != 200) return QobuzLoginResult.Error
        val parsed = runCatching { json.decodeFromString<QobuzLoginResponse>(body) }.getOrNull()
            ?: return QobuzLoginResult.Error
        val token = parsed.userAuthToken?.takeIf { it.isNotBlank() } ?: return QobuzLoginResult.Error
        // Free accounts authenticate fine but carry no credential parameters — no
        // lossless entitlement (matches streamrip's IneligibleError guard). An
        // empty object counts as free too.
        val params = parsed.user?.credential?.parameters
        if (params.isNullOrEmpty()) return QobuzLoginResult.FreeAccount
        return QobuzLoginResult.Success(token = token, appId = creds.appId, appSecret = creds.appSecret)
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val ORIGIN = "https://www.qobuz.com"
        const val UA = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36"
    }
}

@Serializable
internal data class QobuzLoginResponse(
    @SerialName("user_auth_token") val userAuthToken: String? = null,
    val user: QobuzLoginUser? = null,
)

@Serializable
internal data class QobuzLoginUser(val credential: QobuzLoginCredentialField? = null)

@Serializable
internal data class QobuzLoginCredentialField(
    // Kept as a raw object: presence/non-emptiness is the paid-vs-free signal;
    // the individual fields (lossless_streaming, etc.) don't need modelling.
    val parameters: JsonObject? = null,
)

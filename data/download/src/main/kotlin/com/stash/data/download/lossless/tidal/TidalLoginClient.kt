package com.stash.data.download.lossless.tidal

import android.util.Log
import com.stash.data.download.lossless.relay.LosslessConfigFetcher
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class TidalDeviceCode(val deviceCode: String, val userCode: String, val verificationUri: String, val expiresInSec: Int, val intervalSec: Int) {
    override fun toString() = "TidalDeviceCode"
}

data class TidalTokens(val accessToken: String, val refreshToken: String, val expiresInSec: Long) {
    override fun toString() = "TidalTokens"
}

sealed interface TidalPoll {
    data class Authorized(val tokens: TidalTokens) : TidalPoll
    data object Pending : TidalPoll
    data object Expired : TidalPoll
    data object Failed : TidalPoll
}

sealed interface TidalRefresh {
    data class Ok(val tokens: TidalTokens) : TidalRefresh
    /** Tidal rejected the grant: the login is gone. */
    data object LoginExpired : TidalRefresh
    data object Failed : TidalRefresh
}

@Serializable
internal data class TidalStartDto(
    @SerialName("device_code") val deviceCode: String = "",
    @SerialName("user_code") val userCode: String = "",
    @SerialName("verification_uri") val verificationUri: String = "",
    @SerialName("expires_in") val expiresIn: Int = 300,
    val interval: Int = 5,
)

@Serializable
internal data class TidalTokensDto(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("expires_in") val expiresIn: Long = 604_800,
)

@Serializable
internal data class TidalErrorDto(val error: String = "")

/**
 * A listener's own Tidal sign-in (device code), through Stash's relay (its user/tidal routes): the OAuth client id stays a
 * Worker secret. The relay stores nothing; the tokens come back here and [TidalLoginStore] keeps them.
 * Never logs a body, a code or a token.
 */
@Singleton
class TidalLoginClient @Inject constructor(
    sharedClient: OkHttpClient,
    private val config: LosslessConfigFetcher,
    private val debug: TidalDebugSettings,
) {
    internal var httpClient: OkHttpClient = sharedClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun start(): TidalDeviceCode? {
        val (code, body) = post("start", "{}") ?: return null
        if (code != 200) return null
        val d = runCatching { json.decodeFromString<TidalStartDto>(body) }.getOrNull() ?: return null
        if (d.deviceCode.isBlank() || d.userCode.isBlank() || !d.verificationUri.startsWith("https://")) return null
        return TidalDeviceCode(d.deviceCode, d.userCode, d.verificationUri, d.expiresIn.coerceIn(60, 900), d.interval.coerceIn(4, 30))
    }

    suspend fun poll(deviceCode: String): TidalPoll {
        val (code, body) = post("poll", json.encodeToString(mapOf("device_code" to deviceCode))) ?: return TidalPoll.Failed
        return when (code) {
            200 -> tokens(body)?.let { TidalPoll.Authorized(it) } ?: TidalPoll.Failed
            202 -> TidalPoll.Pending
            410 -> TidalPoll.Expired
            else -> TidalPoll.Failed
        }
    }

    suspend fun refresh(refreshToken: String): TidalRefresh {
        val (code, body) = post("refresh", json.encodeToString(mapOf("refresh_token" to refreshToken))) ?: return TidalRefresh.Failed
        return when {
            code == 200 -> tokens(body, fallbackRefresh = refreshToken)?.let { TidalRefresh.Ok(it) } ?: TidalRefresh.Failed
            code == 401 && runCatching { json.decodeFromString<TidalErrorDto>(body).error }.getOrNull() == "login_expired" -> TidalRefresh.LoginExpired
            else -> TidalRefresh.Failed
        }
    }

    private fun tokens(body: String, fallbackRefresh: String = ""): TidalTokens? {
        val d = runCatching { json.decodeFromString<TidalTokensDto>(body) }.getOrNull() ?: return null
        if (d.accessToken.isBlank()) return null
        return TidalTokens(d.accessToken, d.refreshToken.ifBlank { fallbackRefresh }, d.expiresIn.coerceAtLeast(60))
    }

    /** One POST to the owner relay (the debug override stands in for it); null = no relay configured or no answer. */
    private suspend fun post(path: String, body: String): Pair<Int, String>? = withContext(Dispatchers.IO) {
        val relay = debug.relay(config.tidalRelay.value) ?: return@withContext null
        val url = relay.base.toHttpUrlOrNull()?.newBuilder()?.addPathSegments("user/tidal/$path")?.build() ?: return@withContext null
        val req = Request.Builder().url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json")
            .apply { relay.token?.let { header("X-Access-Token", it) } }
            .build()
        try {
            TidalDebugSettings.clientFor(httpClient, url).newCall(req).execute().use { r ->
                val b = r.body
                if (b == null || b.contentLength() > MAX_BODY) null else r.code to b.string()
            }
        } catch (e: IOException) {
            Log.w(TAG, "tidal login unreachable: ${e.javaClass.simpleName}")
            null
        }
    }

    private companion object {
        const val TAG = "TidalLogin"
        const val MAX_BODY = 64L * 1024
    }
}
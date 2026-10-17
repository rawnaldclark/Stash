package com.stash.core.data.supporter

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stash.core.data.tipjar.SupportersJsonUrl
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** What a step of the Ko-fi email claim came back with; the Settings sheet words each one. */
sealed interface ClaimResult {
    /** `start`: a code is on its way (said for every well-formed email, donor or not). */
    data object CodeSent : ClaimResult

    /** `verify`: the code was right; the supporter token is stored. */
    data object Unlocked : ClaimResult

    /** The email was refused, here or by the tip jar. */
    data object BadEmail : ClaimResult

    /** The code was wrong; the same code request can be tried again. */
    data object BadCode : ClaimResult

    /** Too many wrong codes, or the code expired: ask for a new one. */
    data object StartOver : ClaimResult

    /** Too many code requests; [retryAfterSec] from `Retry-After`, when sent. */
    data class RateLimited(val retryAfterSec: Long?) : ClaimResult

    /** The tip jar has no claim set up (`503 not_configured`, or no tip-jar URL in this build). */
    data object NotConfigured : ClaimResult

    /** No answer (offline, timeout) or one that could not be read. */
    data object Unreachable : ClaimResult
}

private val Context.supporterDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "supporter",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * The Ko-fi supporter entitlement (spec 2026-10-17, "Supporters"): the email claim against the
 * tip-jar Worker and the token it hands back.
 *
 * The claim is two calls: [claimStart] (`POST /claim/email/start`, the tip jar emails a 6-digit
 * code) and [claimVerify] (`POST /claim/email/verify`, a right code returns `{token}` for a Ko-fi
 * donor's email). The token is opaque here: the app does not check its signature (no crypto
 * dependency); the owner's relays do, and their answers ([com.stash.data.download.lossless.quota
 * .LosslessQuota]'s limit) are what the app shows as "supporter". An invalid or revoked token
 * simply reads as the free allowance.
 *
 * Only the owner's relays ever see [token] (`X-Stash-Entitlement`); it carries no personal data.
 * Kept in its own DataStore (no Room migration). Not device-locked by design, so a settings
 * backup carries it to a new phone.
 */
@Singleton
class SupporterRepository(
    private val store: DataStore<Preferences>?,
    sharedClient: OkHttpClient,
    tipJarUrl: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        sharedClient: OkHttpClient,
        @SupportersJsonUrl tipJarUrl: String,
    ) : this(context.supporterDataStore, sharedClient, tipJarUrl)

    /** Short timeouts: a person is waiting on the sheet, and a hung tip jar must say so. */
    private val httpClient: OkHttpClient = sharedClient.newBuilder()
        .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    /**
     * The tip jar's origin. `SUPPORTERS_JSON_URL` names the Worker itself (its root serves the
     * supporters JSON), and the claim routes sit at the root too, so any path is dropped.
     */
    private val origin: HttpUrl? = tipJarUrl.trim().takeIf { it.isNotEmpty() }?.toHttpUrlOrNull()
        ?.newBuilder()?.encodedPath("/")?.query(null)?.fragment(null)?.build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val _token = MutableStateFlow<String?>(null)
    private val loaded = CompletableDeferred<Unit>()

    /** The stored supporter token, or null. Starts null until the stored one is read. */
    val token: StateFlow<String?> = _token.asStateFlow()

    init {
        if (store == null) loaded.complete(Unit) else scope.launch { load(store) }
    }

    /** The token once the stored one (if any) is in memory: what a relay request should carry. */
    suspend fun tokenNow(): String? {
        loaded.await()
        return _token.value
    }

    /**
     * Asks the tip jar to email a code to [email]. [ClaimResult.CodeSent] whether or not the
     * address belongs to a donor: the email itself tells the person (no donor lookup by probing).
     */
    suspend fun claimStart(email: String): ClaimResult {
        val e = normaliseEmail(email) ?: return ClaimResult.BadEmail
        return post(START_PATH, buildJsonObject { put("email", e) }) { code, _ ->
            if (code == 202 || code == 200) ClaimResult.CodeSent else null
        }
    }

    /**
     * Checks [code] for [email]; on success stores the returned token ([ClaimResult.Unlocked]).
     * Does not refresh the quota itself: the caller asks the relays so the status comes from them.
     */
    suspend fun claimVerify(email: String, code: String): ClaimResult {
        val e = normaliseEmail(email) ?: return ClaimResult.BadEmail
        val c = code.filter { it.isDigit() }.takeIf { it.length == CODE_LENGTH } ?: return ClaimResult.BadCode
        return post(VERIFY_PATH, buildJsonObject { put("email", e); put("code", c) }) { status, body ->
            if (status != 200) return@post null
            val t = (body?.get("token") as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            // A token that could not travel as a header would throw out of a relay request later.
            if (t == null || !TOKEN_SHAPE.matches(t)) return@post ClaimResult.Unreachable
            save(t)
            ClaimResult.Unlocked
        }
    }

    /** Removes the token from this device; the relays count this install as free again. */
    suspend fun clear() {
        loaded.await()
        _token.value = null
        store?.let { s -> ioCatching("clear") { s.edit { it.remove(KEY_TOKEN) } } }
    }

    private suspend fun save(t: String) {
        loaded.await()
        _token.value = t
        store?.let { s -> ioCatching("write") { s.edit { it[KEY_TOKEN] = t } } }
    }

    /**
     * One JSON POST to [path]. [onStatus] maps a status it knows (null = not handled); every
     * other answer is sorted by its status and `error` code the same way for both calls.
     */
    private suspend fun post(
        path: String,
        payload: JsonObject,
        onStatus: suspend (Int, JsonObject?) -> ClaimResult?,
    ): ClaimResult = withContext(Dispatchers.IO) {
        val url = origin?.newBuilder()?.encodedPath(path)?.build() ?: return@withContext ClaimResult.NotConfigured
        val req = Request.Builder().url(url)
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        try {
            httpClient.newCall(req).execute().use { r ->
                val body = r.body?.source()?.let { src ->
                    if (src.request(MAX_BODY_BYTES + 1)) null else src.readUtf8()
                }?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
                onStatus(r.code, body)?.let { return@withContext it }
                val error = (body?.get("error") as? JsonPrimitive)?.content
                when {
                    r.code == 400 -> ClaimResult.BadEmail
                    r.code == 401 -> ClaimResult.BadCode
                    r.code == 410 -> ClaimResult.StartOver
                    r.code == 429 && error == "too_many_tries" -> ClaimResult.StartOver
                    r.code == 429 -> ClaimResult.RateLimited(r.header("Retry-After")?.trim()?.toLongOrNull()?.takeIf { it > 0 })
                    r.code == 503 && error == "not_configured" -> ClaimResult.NotConfigured
                    else -> {
                        Log.i(TAG, "claim $path HTTP ${r.code}")
                        ClaimResult.Unreachable
                    }
                }
            }
        } catch (e: IOException) {
            Log.i(TAG, "claim $path unreachable (${e.javaClass.simpleName})")
            ClaimResult.Unreachable
        }
    }

    private suspend fun load(s: DataStore<Preferences>) {
        try {
            ioCatching("read") { s.data.first() }?.get(KEY_TOKEN)
                ?.takeIf { TOKEN_SHAPE.matches(it) }
                // A claim that finished during the read wins over the disk copy.
                ?.let { _token.compareAndSet(null, it) }
        } finally {
            loaded.complete(Unit)
        }
    }

    private inline fun <T> ioCatching(label: String, block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "supporter token $label failed: ${e.javaClass.simpleName}")
        null
    }

    companion object {
        private const val TAG = "SupporterRepository"
        private const val TIMEOUT_S = 15L
        private const val MAX_BODY_BYTES = 16L * 1024
        internal const val START_PATH = "/claim/email/start"
        internal const val VERIFY_PATH = "/claim/email/verify"

        /** Digits in an emailed claim code. */
        const val CODE_LENGTH = 6

        private val KEY_TOKEN = stringPreferencesKey("token")
        private val JSON_MEDIA = "application/json".toMediaType()

        /** Header-safe and bounded (base64url / JWT-style segments). */
        private val TOKEN_SHAPE = Regex("[A-Za-z0-9._~+/=-]{1,4096}")

        /**
         * [raw] trimmed and lower-cased when it looks like an email (one `@`, a dot after it,
         * no spaces, at most 254 chars), else null. The tip jar does the real check.
         */
        internal fun normaliseEmail(raw: String): String? {
            val e = raw.trim().lowercase()
            if (e.length !in 3..254 || e.any { it.isWhitespace() }) return null
            val at = e.indexOf('@')
            if (at <= 0 || at != e.lastIndexOf('@')) return null
            val domain = e.substring(at + 1)
            return e.takeIf { '.' in domain && !domain.startsWith('.') && !domain.endsWith('.') }
        }
    }
}

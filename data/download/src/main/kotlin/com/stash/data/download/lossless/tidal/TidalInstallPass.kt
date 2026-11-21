package com.stash.data.download.lossless.tidal

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stash.core.addons.AddonHostOptions
import com.stash.core.addons.DefaultAddonEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** This install's own pass for Stash's Tidal add-on, and the add-on's origin it was given by (it is sent nowhere else). */
data class HeldPass(val origin: String, val pass: String) {
    override fun toString(): String = "HeldPass(set)"
}

/** The phone's secure hardware vouching for a new key made over [challenge]: its certificate chain (base64 DER, leaf first), or null. */
fun interface InstallAttestation {
    suspend fun chain(challenge: ByteArray): List<String>?
}

/** What the Turnstile check came to, for the check's screen. */
sealed interface CheckOutcome {
    data object Passed : CheckOutcome
    data class Failed(val message: String) : CheckOutcome
}

private val Context.tidalPassDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "tidal_install_pass",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * This install's own pass for Stash's Tidal add-on (anti-leech step 3; the add-on's `POST /register`).
 *
 * The signed config's access token and `relay_key` are in every copy of Stash, so the add-on hands each install
 * its own pass once it shows a proof, and counts it by that. The pass takes the access token's place in the
 * first-party Tidal entry's link ([withPass]; [com.stash.data.download.lossless.addon.SignedConfigDefaultAddons]),
 * so everything that reads the default add-ons asks with it.
 *
 * **The proof, with no one asked:** Android key attestation. The add-on gives a one-time challenge
 * (`POST /register/challenge`); a key made in the phone's secure hardware over it comes with a certificate chain that
 * Google's root vouches for ([KeystoreAttestation]); the add-on checks that it is this app, signed with Stash's key,
 * on a locked phone. Tried after each applied config while there is no pass.
 *
 * **When that is refused** (an unlocked phone, a debug build, a phone without the hardware): [checkPage] names the
 * add-on's Turnstile page, Settings › Add-ons offers "One quick check", and its screen hands the solved token to
 * [registerWithTurnstile].
 *
 * **Until the add-on offers its check** (its `/register` answers 404 or 503), nothing changes: the access token, as
 * before. A pass the add-on refuses (a 401 on the v1 wire, [refused]) is let go and attestation runs again; refused
 * twice within [REFUSED_AGAIN_MS], the check is offered instead. The pass is kept in its own DataStore, never logged;
 * Android backups are off for the app.
 */
@Singleton
class TidalInstallPass internal constructor(
    private val store: DataStore<Preferences>?,
    sharedClient: OkHttpClient,
    private val attestation: InstallAttestation,
    private val userAgent: String,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        sharedClient: OkHttpClient,
        attestation: KeystoreAttestation,
        options: AddonHostOptions,
    ) : this(context.tidalPassDataStore, sharedClient, attestation, options.userAgent)

    private val http: OkHttpClient = sharedClient.newBuilder()
        .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    private val _held = MutableStateFlow<HeldPass?>(null)

    /** The pass this install holds, and the add-on it is for; null: the access token, as before. */
    val held: StateFlow<HeldPass?> = _held.asStateFlow()

    private val _checkPage = MutableStateFlow<String?>(null)

    /** The add-on's Turnstile page (`<origin>/register`) when this phone should pass the check: attestation was refused. */
    val checkPage: StateFlow<String?> = _checkPage.asStateFlow()

    private val loaded = CompletableDeferred<Unit>()
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile private var entries: StateFlow<List<DefaultAddonEntry>> = MutableStateFlow(emptyList())
    @Volatile private var scope: CoroutineScope? = null
    @Volatile private var notBeforeMs = 0L
    @Volatile private var lastRefusedMs = 0L
    @Volatile private var refusedPass: String? = null

    /**
     * Reads the kept pass, then registers whenever [entries] (the default add-ons before the pass goes in) or
     * [applied] (each applied config) moves, as long as there is a first-party Tidal entry and no pass for it.
     */
    fun start(scope: CoroutineScope, entries: StateFlow<List<DefaultAddonEntry>>, applied: Flow<Any?>) {
        bind(scope, entries)
        scope.launch(Dispatchers.IO) {
            combine(entries, applied) { e, _ -> e }.collectLatest { guarded { ensure() } }
        }
    }

    /** [start] without its passes: the kept pass is read, and [ensure] and [refused] work against [entries]. Tests call it. */
    internal fun bind(scope: CoroutineScope, entries: StateFlow<List<DefaultAddonEntry>>) {
        this.scope = scope
        this.entries = entries
        scope.launch(Dispatchers.IO) { load() }
    }

    private suspend fun load() {
        if (store == null) {
            loaded.complete(Unit)
            return
        }
        try {
            val p = store.data.first()
            val origin = p[ORIGIN_KEY]
            val pass = p[PASS_KEY]
            if (origin != null && pass != null && passOk(pass)) _held.value = HeldPass(origin, pass)
        } catch (e: IOException) {
            Log.w(TAG, "kept pass unreadable: ${e.javaClass.simpleName}")
        } finally {
            loaded.complete(Unit)
        }
    }

    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "registration failed: ${e.javaClass.simpleName}")
        }
    }

    /** The first-party Tidal add-on's origin now, from the default add-ons before the pass goes in. */
    private fun target(): String? = entries.value.firstNotNullOfOrNull { e ->
        if (e.enabled && e.firstParty) v1Origin(e.manifestUrl) else null
    }

    /** Registers by attestation when there is a first-party Tidal add-on, no pass for it, and no check waiting. */
    suspend fun ensure() {
        loaded.await()
        lock.withLock {
            val origin = target() ?: return
            val held = _held.value
            if (held != null && held.origin == origin) {
                _checkPage.value = null
                return
            }
            // A pass another add-on gave is never sent here.
            if (held != null) keep(null)
            if (_checkPage.value == pageOf(origin) || nowMs() < notBeforeMs) return
            attest(origin)
        }
    }

    private suspend fun attest(origin: String) {
        val page = pageOf(origin)
        val challenge = when (val r = post("$page/challenge", "{}")) {
            is Answer.Ok -> r.body["challenge"]?.jsonPrimitive?.contentOrNull?.takeIf { CHALLENGE.matches(it) }
                ?: return backoff(RETRY_MS)
            Answer.Off -> return // no check yet: the access token, as before
            is Answer.Busy -> return backoff(r.retryAfterMs ?: RETRY_MS)
            else -> return backoff(RETRY_MS)
        }
        val chain = try {
            attestation.chain(Base64.getUrlDecoder().decode(challenge))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "attestation unavailable: ${e.javaClass.simpleName}")
            null
        }
        if (chain.isNullOrEmpty()) {
            _checkPage.value = page
            return
        }
        val body = buildJsonObject {
            put("app", "android")
            putJsonObject("proof") {
                put("kind", "android-key")
                put("challenge", challenge)
                putJsonArray("chain") { chain.forEach { add(it) } }
            }
        }
        when (val r = post(page, body.toString())) {
            is Answer.Ok -> passOf(r.body)?.let { keep(HeldPass(origin, it)) } ?: backoff(RETRY_MS)
            // A challenge that ran out or was spent says nothing about the phone: a fresh one, after a minute.
            is Answer.Refused -> if (r.reason in CHALLENGE_REASONS) {
                backoff(MIN_BACKOFF_MS)
            } else {
                Log.i(TAG, "attestation refused: ${r.reason ?: "no reason"}; the check is offered")
                _checkPage.value = page
            }
            Answer.Off -> Unit
            is Answer.Busy -> backoff(r.retryAfterMs ?: RETRY_MS)
            Answer.Failed -> backoff(RETRY_MS)
        }
    }

    /** The solved Turnstile check's [token], from the check page's hand-back ([turnstileToken]): the pass, or words. */
    suspend fun registerWithTurnstile(token: String): CheckOutcome {
        loaded.await()
        return lock.withLock {
            val page = _checkPage.value ?: target()?.let(::pageOf) ?: return@withLock CheckOutcome.Failed(NOT_AT_HAND)
            val body = buildJsonObject {
                put("app", "android")
                putJsonObject("proof") {
                    put("kind", "turnstile")
                    put("token", token)
                }
            }
            when (val r = post(page, body.toString())) {
                is Answer.Ok -> passOf(r.body)?.let {
                    keep(HeldPass(page.removeSuffix(REGISTER_PATH), it))
                    CheckOutcome.Passed
                } ?: CheckOutcome.Failed(UNREADABLE)
                is Answer.Refused -> CheckOutcome.Failed("The check didn't go through. Try again.")
                is Answer.Busy -> CheckOutcome.Failed(
                    if (r.tooMany) "Too many phones were added from this network today. Try again tomorrow." else "Stash's Tidal is busy. Try again in a minute.",
                )
                Answer.Off -> CheckOutcome.Failed("Stash's Tidal can't add phones right now. Try again later.")
                Answer.Failed -> CheckOutcome.Failed("Stash's servers couldn't be reached. Try again.")
            }
        }
    }

    /**
     * The v1 wire was refused (401) on a first-party add-on at [base]: when that is this install's pass, it is let
     * go (the access token is used again while the add-on takes it), and attestation runs again, or the check is
     * offered when the pass was refused before within [REFUSED_AGAIN_MS].
     */
    fun refused(base: String) {
        val held = _held.value ?: return
        if (!base.contains("/v1/${held.pass}/")) return
        val now = nowMs()
        val again: Boolean
        synchronized(this) {
            // A search and a stream refused together are one refusal of this pass.
            if (refusedPass == held.pass) return
            refusedPass = held.pass
            again = lastRefusedMs != 0L && now - lastRefusedMs < REFUSED_AGAIN_MS
            lastRefusedMs = now
        }
        val scope = scope ?: return
        scope.launch(Dispatchers.IO) {
            guarded {
                lock.withLock {
                    if (_held.value == held) keep(null)
                    if (again) _checkPage.value = pageOf(held.origin)
                }
                ensure()
            }
        }
    }

    private suspend fun keep(held: HeldPass?) {
        _held.value = held
        if (held != null) _checkPage.value = null
        try {
            store?.edit { p ->
                if (held == null) {
                    p.remove(ORIGIN_KEY)
                    p.remove(PASS_KEY)
                } else {
                    p[ORIGIN_KEY] = held.origin
                    p[PASS_KEY] = held.pass
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "pass not kept: ${e.javaClass.simpleName}")
        }
    }

    private fun backoff(ms: Long) {
        notBeforeMs = nowMs() + ms.coerceIn(MIN_BACKOFF_MS, MAX_BACKOFF_MS)
    }

    private fun passOf(body: JsonObject): String? = body["pass"]?.jsonPrimitive?.contentOrNull?.takeIf(::passOk)

    /** What a /register call came to. */
    private sealed interface Answer {
        data class Ok(val body: JsonObject) : Answer
        data class Refused(val reason: String?) : Answer
        /** 404 or 503 without a retry: the add-on gives no check (yet). */
        data object Off : Answer
        data class Busy(val retryAfterMs: Long?, val tooMany: Boolean) : Answer
        data object Failed : Answer
    }

    private suspend fun post(url: String, body: String): Answer = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .post(body.toRequestBody(JSON_TYPE))
            .build()
        try {
            http.newCall(request).execute().use { r ->
                val text = r.body?.source()?.let { source ->
                    source.request(MAX_ANSWER)
                    source.buffer.readUtf8(minOf(source.buffer.size, MAX_ANSWER))
                }.orEmpty()
                val parsed = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                val error = runCatching { parsed?.get("error")?.jsonObject }.getOrNull()
                val retryAfterMs = r.header("Retry-After")?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.let { it * 1000 }
                val code = runCatching { error?.get("code")?.jsonPrimitive?.contentOrNull }.getOrNull()
                when (r.code) {
                    200 -> parsed?.let { Answer.Ok(it) } ?: Answer.Failed
                    // Only the add-on's own verdict on the proof; any other 403 is a failure like any other.
                    403 -> if (code == PROOF_REFUSED) Answer.Refused(runCatching { error?.get("reason")?.jsonPrimitive?.contentOrNull }.getOrNull()) else Answer.Failed
                    404 -> Answer.Off
                    429 -> Answer.Busy(retryAfterMs, tooMany = true)
                    503 -> if (retryAfterMs != null) Answer.Busy(retryAfterMs, tooMany = false) else Answer.Off
                    else -> Answer.Failed
                }
            }
        } catch (e: IOException) {
            Answer.Failed
        }
    }

    companion object {
        private const val TAG = "TidalInstallPass"
        private const val TIMEOUT_S = 20L
        private const val MAX_ANSWER = 16L * 1024
        private val JSON_TYPE = "application/json".toMediaType()
        private val ORIGIN_KEY = stringPreferencesKey("origin")
        private val PASS_KEY = stringPreferencesKey("pass")
        private val CHALLENGE = Regex("^[A-Za-z0-9_-]{54}$")
        private const val PROOF_REFUSED = "proof_refused"
        private val CHALLENGE_REASONS = setOf("challenge", "challenge_used")
        private const val REGISTER_PATH = "/register"
        private const val HAND_BACK_PATH = "/register/done"
        private const val TURNSTILE_FRAGMENT = "turnstile="
        private const val NOT_AT_HAND = "Stash's Tidal isn't available right now."
        private const val UNREADABLE = "Stash's servers gave an answer Stash can't read."

        /** After a failed or busy registration, the next try waits at least this long (an applied config does not hurry it). */
        const val RETRY_MS = 30 * 60_000L
        private const val MIN_BACKOFF_MS = 60_000L
        private const val MAX_BACKOFF_MS = 24 * 3600_000L

        /** A pass refused again within this long after the last refusal: the check is offered instead of attesting again. */
        const val REFUSED_AGAIN_MS = 3600_000L

        /** Whether [pass] is shaped like one the add-on gives: two base64url parts, at most 400 characters. */
        fun passOk(pass: String): Boolean {
            val parts = pass.split('.')
            return pass.length <= 400 && parts.size == 2 &&
                parts.all { p -> p.isNotEmpty() && p.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' } }
        }

        private fun originOf(u: HttpUrl): String {
            val port = if (u.port != HttpUrl.defaultPort(u.scheme)) ":${u.port}" else ""
            return "${u.scheme}://${u.host}$port"
        }

        private fun pageOf(origin: String): String = "$origin$REGISTER_PATH"

        /** The origin of an add-on face's link (`/v1/<token>/manifest.json`), or null for any other shape. */
        fun v1Origin(manifestUrl: String): String? {
            val u = manifestUrl.trim().toHttpUrlOrNull() ?: return null
            val s = u.pathSegments
            return if (s.size == 3 && s[0] == "v1" && s[1].isNotEmpty() && s[2] == "manifest.json") originOf(u) else null
        }

        /** [manifestUrl] with [held]'s pass where its access token is, when it is the face's link on [held]'s origin. */
        fun withPass(manifestUrl: String, held: HeldPass?): String {
            if (held == null || v1Origin(manifestUrl) != held.origin) return manifestUrl
            val u = manifestUrl.trim().toHttpUrlOrNull() ?: return manifestUrl
            return u.newBuilder().setPathSegment(1, held.pass).build().toString()
        }

        /**
         * The Turnstile token the check page (opened on [page], `<origin>/register`) handed back by navigating to
         * `<origin>/register/done#turnstile=<token>`, or null: another address, another origin, or no token.
         */
        fun turnstileToken(page: String, url: String): String? {
            if (!isHandBack(page, url)) return null
            val fragment = url.substringAfter('#', "").takeIf { it.startsWith(TURNSTILE_FRAGMENT) } ?: return null
            val token = percentDecoded(fragment.removePrefix(TURNSTILE_FRAGMENT)) ?: return null
            return token.takeIf { it.isNotEmpty() && it.length <= 2048 && it.all { c -> c in '!'..'~' } }
        }

        /** Whether [url] is the check page's hand-back: its own origin, `/register/done`. */
        fun isHandBack(page: String, url: String): Boolean {
            val p = page.toHttpUrlOrNull() ?: return false
            val u = url.substringBefore('#').toHttpUrlOrNull() ?: return false
            return originOf(u) == originOf(p) && u.encodedPath == HAND_BACK_PATH
        }

        private fun percentDecoded(text: String): String? {
            val out = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '%') {
                    if (i + 3 > text.length) return null
                    val hex = text.substring(i + 1, i + 3)
                    if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
                    out.write(hex.toInt(16))
                    i += 3
                } else {
                    if (c.code > 127) return null
                    out.write(c.code)
                    i++
                }
            }
            return out.toByteArray().toString(Charsets.UTF_8)
        }
    }
}

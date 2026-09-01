package com.stash.data.download.lossless.relay

import android.util.Log
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** Outcome of one relay mint. */
sealed interface RelayMint {
    /** [sampleRateHz] is already Hz — the relay converts Qobuz's kHz; never multiply here. */
    data class Ok(val url: String, val formatId: Int, val bitDepth: Int, val sampleRateHz: Int) : RelayMint
    /**
     * 404: not streamable / region-locked for the relay's accounts. Ends the
     * router for this track — the next lossless SOURCE, not the next relay base
     * (every base fronts the same catalog). No cooldown: the base is healthy.
     */
    object NoMatch : RelayMint
    /** The base is unavailable right now and has been cooled; try the next base. */
    object Unavailable : RelayMint
}

@Serializable
internal data class RelayFileResponse(
    val url: String? = null,
    @SerialName("format_id") val formatId: Int = 0,
    @SerialName("bit_depth") val bitDepth: Int = 0,
    @SerialName("sample_rate") val sampleRateHz: Int = 0, // Hz — the relay already converted Qobuz's kHz
)

/**
 * Talks to a Stash lossless relay (`GET {base}/v1/qobuz/file`) and OWNS the
 * per-base cooldown: `busy` → 60 s, anything else non-2xx/404, unreachable, or
 * a 200 whose body is unusable (unparseable, no url, or a plaintext one) → 5 min.
 * The body is read BEFORE the status branch, so a 404 whose body dies mid-read
 * cools 5 min rather than returning [RelayMint.NoMatch] — an IOException is an
 * IOException, and a relay that can't finish a response is sick whatever status
 * it opened with.
 * Neither [com.stash.data.download.lossless.LosslessSourceHealthGate]
 * (fixed 5 min, not consulted by the streaming resolver) nor
 * `LosslessSourceHealth` (a miss counter) fit, and because both the streaming
 * and download paths reach a relay through this one @Singleton, the cooldown
 * covers both automatically.
 *
 * `base` arrives already normalised — both write paths (the custom-endpoint
 * preference and the signed runtime config) run it through
 * `LosslessSourcePreferences.normaliseEndpoint`, so this class only has to
 * survive a junk value, not sanitise one.
 */
@Singleton
class LosslessRelayClient @Inject constructor(
    sharedClient: OkHttpClient,
    private val config: LosslessConfigFetcher,
) {
    /**
     * Derived client so the relay's short timeouts don't leak onto the shared
     * one — a relay that hangs must fail over fast, not stall a download.
     */
    internal var httpClient: OkHttpClient = sharedClient.newBuilder()
        .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    /** Test seam for the cooldown clock. */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    private val cooledUntil = ConcurrentHashMap<String, Long>()

    /** True while [base] is inside a cooldown; expired entries are dropped on read. */
    fun isCooled(base: String): Boolean {
        val until = cooledUntil[base] ?: return false
        if (clock() < until) return true
        cooledUntil.remove(base, until)
        return false
    }

    suspend fun mint(base: String, trackId: Long, formatId: Int): RelayMint = withContext(Dispatchers.IO) {
        if (isCooled(base)) return@withContext RelayMint.Unavailable
        // `base` is validated at write time (LosslessSourcePreferences.normaliseEndpoint),
        // but never let a junk value from a future caller throw out of here. Nothing to
        // cool — an unparseable base isn't a sick relay.
        val parsed = "$base/v1/qobuz/file".toHttpUrlOrNull()
        if (parsed == null) {
            Log.w(TAG, "unparseable relay base — skipping")
            return@withContext RelayMint.Unavailable
        }
        val url = parsed.newBuilder()
            .addQueryParameter("track_id", trackId.toString())
            .addQueryParameter("format_id", formatId.toString())
            .build()
        val req = Request.Builder().url(url)
            .header("X-Stash-Version", PROTOCOL_VERSION)
            .header("Accept", "application/json")
            .also { signIfKeyed(it, trackId, formatId) }
            .get().build()
        // The body read stays INSIDE the try: a relay that returns 200 headers and then
        // stalls throws out of string(), and that must cool this base — not escape into
        // the caller's breaker (QbdlxQobuzSource.callLimited trips qbdlx wholesale).
        try {
            httpClient.newCall(req).execute().use { r ->
                val body = r.body?.string().orEmpty()
                when (r.code) {
                    200 -> {
                        val file = runCatching { json.decodeFromString<RelayFileResponse>(body) }.getOrNull()
                        // https only: a relay handing back a plaintext CDN URL is either
                        // misconfigured or being MITM'd — treat the base as sick, don't stream it.
                        val u = file?.url?.takeIf { it.startsWith("https://") }
                        if (u == null) {
                            Log.w(TAG, "relay ${host(base)} 200 with an unusable body — cooling")
                            cool(base, UNAVAILABLE_COOLDOWN_MS)
                            RelayMint.Unavailable
                        } else {
                            // An omitted format_id decodes to 0, and 0 reads as region-locked
                            // downstream (QbdlxApiClient.classify treats < 6 that way) — echo
                            // what we asked for instead.
                            RelayMint.Ok(u, file.formatId.takeIf { it > 0 } ?: formatId, file.bitDepth, file.sampleRateHz)
                        }
                    }
                    404 -> RelayMint.NoMatch
                    503 -> {
                        Log.i(TAG, "relay ${host(base)} busy — cooling ${BUSY_COOLDOWN_MS / 1000}s")
                        cool(base, BUSY_COOLDOWN_MS)
                        RelayMint.Unavailable
                    }
                    else -> {
                        Log.w(TAG, "relay ${host(base)} HTTP ${r.code}: ${body.take(120)} — cooling ${UNAVAILABLE_COOLDOWN_MS / 1000}s")
                        cool(base, UNAVAILABLE_COOLDOWN_MS)
                        RelayMint.Unavailable
                    }
                }
            }
        } catch (e: IOException) {
            // Also covers a body that dies mid-read (callTimeout firing during string()) — that IS a sick relay.
            Log.w(TAG, "relay ${host(base)} unreachable (${e.javaClass.simpleName}) — cooling ${UNAVAILABLE_COOLDOWN_MS / 1000}s")
            cool(base, UNAVAILABLE_COOLDOWN_MS)
            RelayMint.Unavailable
        }
    }

    /**
     * Is this base reachable at all? ANY HTTP reply counts — including 404 — because
     * `/v1/status` is a relay endpoint that nothing serves until the relay ships, and
     * what this button exists to catch is a typo'd host, not an unhealthy relay.
     * Reports reachability, never health. Deliberately does NOT consult or set the
     * cooldowns: a manual test must not cool a base, and a user testing a base that
     * just cooled deserves a real answer.
     */
    suspend fun probe(base: String): Boolean = withContext(Dispatchers.IO) {
        val url = "$base/v1/status".toHttpUrlOrNull() ?: return@withContext false
        val req = Request.Builder().url(url)
            .header("X-Stash-Version", PROTOCOL_VERSION)
            .header("Accept", "application/json")
            .get().build()
        try {
            // No body read: the status line alone answers the only question asked.
            httpClient.newCall(req).execute().use { true }
        } catch (e: IOException) {
            Log.i(TAG, "probe of ${host(base)} failed (${e.javaClass.simpleName})")
            false
        }
    }

    /**
     * Relay access control (spec §5.4). When the signed config carries a
     * `relay_key`, the mint is signed: `X-Stash-Auth` = hex HMAC-SHA256 over
     * `"<install_id>:<track_id>:<format_id>:<unix_ts>"`, with `X-Stash-Install` and
     * `X-Stash-Ts` alongside. The install id sits INSIDE the MAC so a captured
     * header cannot be replayed under another install's allowance; the timestamp
     * lets the relay reject anything outside its ±5 min window.
     *
     * No key → no headers. A user-run custom endpoint built to the public contract
     * needs none, and a relay that wants auth answers an unsigned mint with a
     * non-2xx that [mint] already cools for 5 min.
     *
     * A DataStore failure fetching the install id falls back to a transient id
     * rather than throwing: this runs BEFORE the HTTP try, and an exception here
     * would escape into QbdlxQobuzSource's breaker and trip qbdlx wholesale.
     *
     * ponytail: the ±5 min window assumes an NTP-synced clock; add Date-header
     * skew correction if relay 401s show up in the field.
     */
    private suspend fun signIfKeyed(b: Request.Builder, trackId: Long, formatId: Int) {
        val key = config.relayKey.value ?: return
        val install = runCatching { config.installId() }.getOrElse { UUID.randomUUID().toString() }
        val ts = clock() / 1000
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key.toByteArray(), "HmacSHA256")) }
        val sig = mac.doFinal("$install:$trackId:$formatId:$ts".toByteArray()).joinToString("") { "%02x".format(it) }
        b.header("X-Stash-Install", install).header("X-Stash-Ts", ts.toString()).header("X-Stash-Auth", sig)
    }

    private fun cool(base: String, ms: Long) {
        cooledUntil[base] = clock() + ms
    }

    /** Logs name the host only — a full base can carry an endpoint literal that must never be logged. */
    private fun host(base: String) = base.toHttpUrlOrNull()?.host ?: "?"

    companion object {
        private const val TAG = "LosslessRelay"
        private const val TIMEOUT_S = 8L
        /** Wire-protocol version sent as `X-Stash-Version` (the relay rejects requests without it). */
        const val PROTOCOL_VERSION = "1"
        const val BUSY_COOLDOWN_MS = 60_000L
        const val UNAVAILABLE_COOLDOWN_MS = 5 * 60_000L
    }
}

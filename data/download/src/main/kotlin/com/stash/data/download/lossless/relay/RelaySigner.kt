package com.stash.data.download.lossless.relay

import android.util.Log
import com.stash.core.common.AppVersionProvider
import com.stash.core.data.supporter.SupporterRepository
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import okhttp3.Request
import okhttp3.Response

/**
 * Signs requests to the owner's Tidal relay (spec §5.4): its streams, the allowance endpoint and
 * its quota reads all carry the same install identity and the same learned clock offset.
 *
 * `X-Stash-Auth` = hex HMAC-SHA256 with the signed config's `relay_key` over
 * `"<install_id>:<payload>:<unix_ts>"`, with `X-Stash-Install` and `X-Stash-Ts` alongside. The
 * payload names what is asked (`td:<id>` for a Tidal stream, `quota` for the allowance
 * endpoint). The install id sits INSIDE the MAC so a captured
 * header cannot be replayed under another install's allowance; the timestamp lets the relay
 * reject anything outside its ±5 min window.
 *
 * No key → no headers. A user-run server built to the public contract needs none.
 *
 * Callers pass their own clock (each client has a test seam for it); the offset is the one
 * piece of state, so a skew learned by one caller fixes the others.
 *
 * [entitle] adds the Ko-fi supporter token ([SupporterRepository]) the same way: the owner's
 * relay only, decided by the caller, since a Tidal request may also go to a user's own server.
 */
@Singleton
class RelaySigner @Inject constructor(
    private val config: LosslessConfigFetcher,
    private val supporter: SupporterRepository,
    private val appVersion: AppVersionProvider,
) {
    /**
     * Relay time minus device time, seconds; added to every signed timestamp. In memory only:
     * a clock fixed while the app runs costs one more 401 + retry, and a restart starts at 0.
     */
    @Volatile var clockOffsetSec: Long = 0L
        private set

    /** True when the applied config carries a relay key, i.e. [sign] would sign. */
    val keyed: Boolean get() = config.relayKey.value != null

    /** Adds the running binary's integer version to Stash owner-relay lossless requests. */
    fun appBuild(b: Request.Builder) {
        b.header("X-Stash-App-Build", appVersion.versionCode.toString())
    }

    /**
     * Signs [b] over [payload] at [nowMs] (+ [clockOffsetSec]). Returns true when it signed.
     *
     * A DataStore failure fetching the install id falls back to a transient id rather than
     * throwing: callers run this BEFORE their HTTP try, and an exception here would escape into
     * a source's breaker (QbdlxQobuzSource trips qbdlx wholesale).
     */
    suspend fun sign(b: Request.Builder, payload: String, nowMs: Long): Boolean {
        val key = config.relayKey.value ?: return false
        val install = runCatching { config.installId() }
            // A cancelled request stays cancelled; only a store failure falls back.
            .getOrElse { e -> if (e is CancellationException) throw e else UUID.randomUUID().toString() }
        val ts = nowMs / 1000 + clockOffsetSec
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key.toByteArray(), "HmacSHA256")) }
        val sig = mac.doFinal("$install:$payload:$ts".toByteArray()).joinToString("") { "%02x".format(it) }
        b.header("X-Stash-Install", install).header("X-Stash-Ts", ts.toString()).header("X-Stash-Auth", sig)
        return true
    }

    /**
     * Adds `X-Stash-Entitlement: <token>` to [b] when a supporter token is stored. Call it for
     * the owner's relays only (the signed config's), never a user's own server. The relays
     * check the token; a bad one is counted as free, so nothing here needs to judge it.
     */
    suspend fun entitle(b: Request.Builder) {
        val token = supporter.tokenNow() ?: return
        // The repository only stores header-safe tokens; never let a bad one throw out of a request.
        runCatching { b.header(ENTITLEMENT_HEADER, token) }
    }

    /** True when a supporter token is stored, i.e. [entitle] would add it. */
    suspend fun entitled(): Boolean = supporter.tokenNow() != null

    /**
     * On a 401 carrying `X-Stash-Server-Time` (epoch seconds, sent for signature / timestamp
     * failures), record the device's offset from it. False when the header is absent or junk:
     * the 401 is then handled exactly as before.
     */
    fun adoptServerTime(r: Response, nowMs: Long): Boolean {
        val server = r.header("X-Stash-Server-Time")?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: return false
        clockOffsetSec = server - nowMs / 1000
        Log.i(TAG, "relay clock offset ${clockOffsetSec}s; re-signing")
        return true
    }

    companion object {
        private const val TAG = "RelaySigner"
        const val ENTITLEMENT_HEADER = "X-Stash-Entitlement"

        /** Wire-protocol version sent as `X-Stash-Version` on the owner relay's quota reads. */
        const val PROTOCOL_VERSION = "1"

        /** A relay refusing this build: its `MIN_APP_BUILD` is above it (`app_too_old`). */
        const val APP_TOO_OLD = 426

        /**
         * How long a relay that answered [APP_TOO_OLD] is left alone, streams and downloads alike: only an update fixes
         * it, so asking again sooner (or in another format) is a wasted request.
         */
        const val APP_TOO_OLD_BACKOFF_MS = 60 * 60_000L
    }
}

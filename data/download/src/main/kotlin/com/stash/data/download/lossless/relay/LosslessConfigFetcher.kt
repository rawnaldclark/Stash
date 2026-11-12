package com.stash.data.download.lossless.relay

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stash.core.addons.DefaultAddonEntry
import com.stash.core.addons.HttpAddonClient
import com.stash.data.download.BuildConfig
import com.stash.data.download.lossless.LosslessSourcePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * A Tidal (BitChord) server: an https base and its optional token. [userOwned] = the user's own
 * server from Settings rather than the owner-hosted one from the signed config; only the
 * owner's relay gets install headers and counts the daily lossless allowance.
 */
data class TidalRelay(val base: String, val token: String?, val userOwned: Boolean = false)

/**
 * The signed runtime config. A config from before 2026-11 may still carry `relays` (the retired
 * shared Qobuz relays): the field is no longer read, and [LosslessConfigFetcher]'s JSON ignores
 * unknown keys, so such a config still parses and applies its Tidal fields.
 */
@Serializable
data class LosslessConfig(
    val v: Int = 1,
    @SerialName("updated_at") val updatedAt: Long = 0,
    /**
     * Relay access key (spec §5.4). When present, [RelaySigner] signs every Tidal relay
     * request with it. Delivered here — inside the signed, cached, every-cold-start
     * config — rather than baked into the APK, so it rotates by publishing a new
     * config and is dead on every device within one cold start. Null → unsigned.
     */
    @SerialName("relay_key") val relayKey: String? = null,
    /**
     * Base URL of the owner-hosted BitChord Lossless Relay (Tidal rung), https
     * only; normalised in [parse], so an http or junk value arrives as null. May
     * carry the server's ACCESS_TOKEN as a path prefix. Null → the Tidal rung is
     * off unless the user set their own server.
     */
    @SerialName("tidal_relay_url") val tidalRelayUrl: String? = null,
    /** That server's ACCESS_TOKEN, sent as `X-Access-Token`. Optional; rotates like [relayKey]. */
    @SerialName("tidal_relay_token") val tidalRelayToken: String? = null,
    /**
     * The add-ons every install turns on (add-ons phase 2 §5.3): `[{manifest, enabled, first_party}]`.
     * Kept as raw JSON and read by [defaultAddonEntries], entry by entry, so one malformed entry
     * is dropped alone and can never make the whole config unreadable (which would turn Tidal off).
     * Releases before phase 2 ignore the key (unknown keys are ignored).
     */
    @SerialName("default_addons") val defaultAddons: JsonElement? = null,
) {
    /**
     * [defaultAddons] as entries: https manifest URLs ending in `/manifest.json` with no query or
     * fragment, `enabled` (default true) and `first_party` (default false) booleans when given,
     * at most [MAX_DEFAULT_ADDONS], no duplicates. Anything else is skipped, never repaired.
     */
    fun defaultAddonEntries(): List<DefaultAddonEntry> {
        val list = defaultAddons as? JsonArray ?: return emptyList()
        val seen = HashSet<String>()
        return list.asSequence().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val url = (o["manifest"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: return@mapNotNull null
            if (!isManifestUrl(url) || !seen.add(url)) return@mapNotNull null
            val enabled = flag(o["enabled"]) ?: return@mapNotNull null
            val firstParty = flag(o["first_party"], default = false) ?: return@mapNotNull null
            DefaultAddonEntry(url, enabled = enabled, firstParty = firstParty)
        }.take(MAX_DEFAULT_ADDONS).toList()
    }

    companion object {
        /** The most `default_addons` entries read (the publisher allows no more). */
        const val MAX_DEFAULT_ADDONS = 16

        /** A boolean, [default] when absent; null (skip the entry) when present and not a boolean. */
        private fun flag(v: JsonElement?, default: Boolean = true): Boolean? = when {
            v == null || v is kotlinx.serialization.json.JsonNull -> default
            v is JsonPrimitive && !v.isString -> v.booleanOrNull
            else -> null
        }

        private fun isManifestUrl(url: String): Boolean {
            if (url.length > 2048 || url.any { it.isWhitespace() || it == '\\' } || '?' in url || '#' in url) return false
            if (!url.startsWith("https://") || !url.endsWith("/manifest.json")) return false
            if (HttpAddonClient.baseOf(url) == null) return false
            val u = url.toHttpUrlOrNull() ?: return false
            return u.username.isEmpty() && u.password.isEmpty()
        }
    }
}

private val Context.losslessRelayConfigDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "lossless_relay_config",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * The signed runtime config: Stash's Tidal relay and the key that signs requests to it. The APK
 * ships NO relay hostname: this fetches `<configUrl>` + `<configUrl>.sig` (ECDSA P-256 over the
 * exact JSON bytes), verifies against the baked-in public key, caches the JSON, and exposes
 * [tidalRelay] and [relayKey]. Invalid signature / network failure → the cached copy stays; a
 * config older than the cached one (by `updated_at`) is rejected; no cache → no relay. Both
 * BuildConfig values empty → disabled.
 */
@Singleton
class LosslessConfigFetcher @Inject constructor(
    @ApplicationContext private val context: Context,
    sharedClient: OkHttpClient,
) {
    /** Test seam. */
    internal var configUrl: String = BuildConfig.LOSSLESS_CONFIG_URL

    /** Test seam. */
    internal var publicKeyB64: String = BuildConfig.LOSSLESS_CONFIG_PUBKEY

    /** Test seam. */
    internal var httpClient: OkHttpClient = sharedClient

    /** Test seam: the network [refreshLoop] waits for while offline. */
    internal var network: ConfigNetworkSignal = AndroidConfigNetworkSignal(context)
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonKey = stringPreferencesKey("config_json")

    /**
     * The detached signature the cached config arrived with (base64, as fetched), kept beside it
     * so the cache can be checked again before it is trusted ([trustedCache]). Absent beside a
     * cache written by a build from before 2026-11-11.
     */
    private val sigKey = stringPreferencesKey("config_sig")

    private val _relayKey = MutableStateFlow<String?>(null)
    /** The current relay access key from the applied config; null when unsigned. */
    val relayKey: StateFlow<String?> = _relayKey.asStateFlow()

    private val _tidalRelay = MutableStateFlow<TidalRelay?>(null)

    /** The Tidal (BitChord) server from the signed config, or null. See [LosslessConfig.tidalRelayUrl]. */
    val tidalRelay: StateFlow<TidalRelay?> = _tidalRelay.asStateFlow()

    private val _defaultAddons = MutableStateFlow<List<DefaultAddonEntry>>(emptyList())

    /**
     * The applied config's `default_addons` ([LosslessConfig.defaultAddonEntries]); empty without
     * the key. Their manifest URLs can carry a token: never logged.
     */
    val defaultAddons: StateFlow<List<DefaultAddonEntry>> = _defaultAddons.asStateFlow()

    private val _updatedAt = MutableStateFlow(0L)
    /** `updated_at` (unix seconds) of the applied config; 0 until one is loaded. For the diagnostics bundle. */
    val updatedAt: StateFlow<Long> = _updatedAt.asStateFlow()

    private val _refreshedAt = MutableStateFlow(0L)

    /**
     * Wall-clock time of this process's last [refresh] that applied a fresh, verified config; 0
     * before one. Never moved by the cache or by a failed refresh: Battery Saver's deferral keys
     * on it (StashApplication), so a failed fetch can't count as a recent one.
     */
    val refreshedAt: StateFlow<Long> = _refreshedAt.asStateFlow()

    private val installIdKey = stringPreferencesKey("install_id")

    /**
     * A random id created once per install and persisted beside the config cache.
     * Sent with every signed request so the relay can rate-limit per install. It is a
     * cooperative bucket, not an identity: random, no PII, never leaves the relay
     * request. Created inside a single `edit` so two first-callers can't race two
     * different ids into existence.
     */
    suspend fun installId(): String =
        context.losslessRelayConfigDataStore.edit { p ->
            if (p[installIdKey] == null) p[installIdKey] = UUID.randomUUID().toString()
        }[installIdKey]!!

    val enabled: Boolean get() = configUrl.isNotBlank() && publicKeyB64.isNotBlank()

    /**
     * Populate [tidalRelay], [relayKey] and [defaultAddons] from the cached JSON, if it may be
     * trusted ([trustedCache]). Cheap; call before the first resolve.
     */
    suspend fun loadCached() {
        val cached = trustedCache() ?: return
        parse(cached)?.let { apply(it) }
    }

    /**
     * The cached config, when it may be used (review-881 M9). The first-party trust rule rests on
     * this file: its relay and its `first_party` add-ons get the install signature and the
     * supporter token, so a cache someone else wrote must never be applied.
     *  - **With its signature beside it** (every cache this build writes): only while that
     *    signature still verifies over the cached text against the baked-in key. One that does not
     *    is ignored, as if there were no cache: nothing is applied until a fetch succeeds.
     *  - **With no signature beside it**: a cache written by an older build, which kept none. It
     *    is used as it always was, so an install that updates and then starts offline still has
     *    its Tidal relay. The first successful fetch rewrites cache and signature together.
     *
     * A settings restore no longer writes this file at all (`DatabaseBackupManager`), which was
     * the one way a foreign cache got here; this check is the second lock on the same door.
     */
    private suspend fun trustedCache(): String? {
        val prefs = ioCatching("cache read") { context.losslessRelayConfigDataStore.data.first() } ?: return null
        val text = prefs[jsonKey] ?: return null
        val sig = prefs[sigKey] ?: return text
        if (verify(text.toByteArray(Charsets.UTF_8), sig)) return text
        Log.w(TAG, "the cached lossless config does not match its signature: ignored")
        return null
    }

    /** Fetch + verify + apply. Returns true only when a fresh, valid config was applied. Never throws. */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext false
        val body = ioCatching("config") { getBytes(configUrl) } ?: return@withContext false
        val sig = ioCatching("sig") { String(getBytes("$configUrl.sig")).trim() } ?: return@withContext false
        // Verified over the bytes exactly as fetched — never a re-serialisation.
        if (!verify(body, sig)) {
            Log.w(TAG, "lossless.json signature invalid — keeping the cached copy")
            return@withContext false
        }
        val text = String(body)
        val fresh = parse(text) ?: return@withContext false
        // Rollback floor: a valid signature does not stop whoever serves the URL replaying an old file.
        // A FAILED cache read (missing, unreadable, unparseable, or not matching its signature)
        // yields floor 0 on purpose — fail open: a corrupt or foreign cache must not pin the
        // device to a stale config (a far-future `updated_at` would otherwise reject every real one).
        val cachedUpdatedAt = trustedCache()?.let { parse(it)?.updatedAt } ?: 0L
        if (fresh.updatedAt < cachedUpdatedAt) {
            Log.w(TAG, "lossless config older than the cached copy (${fresh.updatedAt} < $cachedUpdatedAt) — rejected")
            return@withContext false
        }
        apply(fresh)
        // The cache and its signature go in together, in one edit. The signature is over the bytes
        // as fetched; the cache keeps text. They are the same bytes for any UTF-8 body, which is
        // all the publisher writes; a body that isn't would never verify from the cache, so it is
        // applied for this run and not cached.
        if (text.toByteArray(Charsets.UTF_8).contentEquals(body)) {
            ioCatching("cache write") {
                context.losslessRelayConfigDataStore.edit {
                    it[jsonKey] = text
                    it[sigKey] = sig
                }
            }
        } else {
            Log.w(TAG, "lossless config is not UTF-8 text: applied, not cached")
        }
        Log.i(
            TAG,
            "lossless config applied (Tidal relay: ${if (fresh.tidalRelayUrl != null) "yes" else "no"}, " +
                "default add-ons: ${_defaultAddons.value.size})",
        )
        _refreshedAt.value = System.currentTimeMillis()
        true
    }

    private fun apply(cfg: LosslessConfig) {
        _relayKey.value = cfg.relayKey
        _tidalRelay.value = cfg.tidalRelayUrl?.let { TidalRelay(it, cfg.tidalRelayToken?.takeIf { t -> t.isNotBlank() }) }
        _defaultAddons.value = cfg.defaultAddonEntries()
        _updatedAt.value = cfg.updatedAt
    }

    /** Load the cache, then keep the config fresh with [refreshLoop]. */
    fun start(scope: CoroutineScope) {
        if (!enabled) return // both BuildConfig values blank: nothing to fetch, don't spin a coroutine
        scope.launch {
            loadCached()
            refreshLoop { refresh() }
        }
    }

    /**
     * Refresh now, then every [REFRESH_INTERVAL_MS]; after failures, back off ([nextDelayMs]). [refreshOnce] is passed
     * in so tests drive the loop on virtual time. Never returns.
     *
     * Offline, nothing runs on a timer: before each attempt the loop waits for a validated network ([network]) and
     * refreshes as soon as one is back. It used to retry every 15 min for as long as the process lived, each attempt a
     * request that could only fail. The failure count starts over only after a real wait (the earlier failures were on
     * another network); when the network can't be watched, [ConfigNetworkSignal.awaitOnline] returns false at once and
     * the count stands, so the back-off still grows.
     *
     * Every attempt is followed by a delay, so a network signal that says "online" while nothing works (or can't be
     * watched at all) can never make this spin: failures back off 15 min, 30 min, then hourly.
     */
    internal suspend fun refreshLoop(refreshOnce: suspend () -> Boolean): Nothing {
        var failures = 0
        while (true) {
            if (!network.isOnline() && network.awaitOnline()) failures = 0
            failures = if (refreshOnce()) 0 else failures + 1
            delay(nextDelayMs(failures))
        }
    }

    /**
     * The wait after an attempt, given the [failures] in a row so far: 6 h after a good refresh (0), and after a failure
     * (a captive portal, the host down) 15 min, then 30 min, then hourly.
     */
    internal fun nextDelayMs(failures: Int): Long {
        if (failures <= 0) return REFRESH_INTERVAL_MS
        val doublings = (failures - 1).coerceAtMost(MAX_DOUBLINGS)
        return (RETRY_INTERVAL_MS shl doublings).coerceAtMost(MAX_RETRY_INTERVAL_MS)
    }

    internal fun verify(bytes: ByteArray, sigB64: String): Boolean = runCatching {
        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyB64)))
        Signature.getInstance("SHA256withECDSA").run { initVerify(pub); update(bytes); verify(Base64.getDecoder().decode(sigB64)) }
    }.getOrDefault(false)


    /** The config with its Tidal relay URL already normalised; null when the JSON is unusable. */
    private fun parse(text: String): LosslessConfig? = runCatching {
        val cfg = json.decodeFromString<LosslessConfig>(text)
        cfg.copy(tidalRelayUrl = LosslessSourcePreferences.normaliseEndpoint(cfg.tidalRelayUrl))
    }.getOrNull()

    private fun getBytes(url: String): ByteArray {
        val req = Request.Builder().url(url).header("Accept", "*/*").get().build()
        httpClient.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val src = r.body?.source() ?: throw IOException("empty body")
            // request(n) reads until n bytes are buffered or EOF; true = at least n available.
            if (src.request(MAX_CONFIG_BYTES + 1)) throw IOException("body over $MAX_CONFIG_BYTES bytes")
            return src.readByteArray()
        }
    }

    /**
     * Fetching config must never crash the app, so anything short of a
     * cancellation degrades to null — but a cancelled scope has to stay
     * cancelled (same rule as `HomeDiscoveryRepositoryImpl.cached`).
     * [label] names the step; only our own synthetic messages are logged, never a
     * library one (an `UnknownHostException` message carries the host).
     */
    private inline fun <T> ioCatching(label: String, block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val detail = e.message?.takeIf {
            it.startsWith("HTTP ") || it.startsWith("body over") || it == "empty body"
        } ?: e.javaClass.simpleName
        Log.w(TAG, "lossless config $label failed: $detail")
        null
    }

    /**
     * TESTS ONLY (public, not internal, so no test ever calls a mangled name): the cache as a
     * build wrote it. [sig] null is an older build's cache, which kept no signature.
     */
    suspend fun writeCacheForTest(configJson: String, sig: String?) {
        context.losslessRelayConfigDataStore.edit {
            it[jsonKey] = configJson
            if (sig == null) it.remove(sigKey) else it[sigKey] = sig
        }
    }

    internal suspend fun clearForTest() {
        _relayKey.value = null
        _tidalRelay.value = null
        _defaultAddons.value = emptyList()
        context.losslessRelayConfigDataStore.edit { it.clear() }
    }

    private companion object {
        const val TAG = "LosslessConfig"
        const val REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1000L

        /** The first retry after a failure; each further failure in a row doubles it, up to [MAX_RETRY_INTERVAL_MS]. */
        const val RETRY_INTERVAL_MS = 15 * 60 * 1000L
        const val MAX_RETRY_INTERVAL_MS = 60 * 60 * 1000L

        /** Bounds the shift in [nextDelayMs] (the cap is reached after two doublings anyway). */
        const val MAX_DOUBLINGS = 8

        /** A hostile/misconfigured host must not OOM us — an `Error` would escape [ioCatching]. */
        const val MAX_CONFIG_BYTES = 64L * 1024
    }
}

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
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** One relay base from the signed config; lower [priority] is tried first. */
@Serializable
data class RelayEntry(val base: String, val priority: Int = 1)

@Serializable
data class LosslessConfig(
    val v: Int = 1,
    val relays: List<RelayEntry> = emptyList(),
    @SerialName("updated_at") val updatedAt: Long = 0,
    /**
     * Relay access key (spec §5.4). When present, [LosslessRelayClient] signs every
     * mint with it. Delivered here — inside the signed, cached, every-cold-start
     * config — rather than baked into the APK, so it rotates by publishing a new
     * config and is dead on every device within one cold start. Null → unsigned.
     */
    @SerialName("relay_key") val relayKey: String? = null,
)

private val Context.losslessRelayConfigDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "lossless_relay_config",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * The runtime relay list. The APK ships NO relay hostname: this fetches
 * `<configUrl>` + `<configUrl>.sig` (ECDSA P-256 over the exact JSON bytes),
 * verifies against the baked-in public key, caches the JSON, and exposes
 * [relays] sorted by priority. Invalid signature / network failure → the cached
 * copy stays; a config older than the cached one (by `updated_at`) is rejected;
 * no cache → no relays. Both BuildConfig values empty → disabled.
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
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonKey = stringPreferencesKey("config_json")

    private val _relays = MutableStateFlow<List<RelayEntry>>(emptyList())
    val relays: StateFlow<List<RelayEntry>> = _relays.asStateFlow()

    private val _relayKey = MutableStateFlow<String?>(null)
    /** The current relay access key from the applied config; null when unsigned. */
    val relayKey: StateFlow<String?> = _relayKey.asStateFlow()

    private val installIdKey = stringPreferencesKey("install_id")

    /**
     * A random id created once per install and persisted beside the config cache.
     * Sent with every signed mint so the relay can rate-limit per install. It is a
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
     * Populate [relays] from the cached JSON, if any. Cheap; call before the first resolve.
     * The cache is not re-verified: it is only ever written from bytes that passed [verify],
     * into app-private storage (`allowBackup=false`).
     */
    suspend fun loadCached() {
        val cached = readCache() ?: return
        parse(cached)?.let { _relays.value = it.relays; _relayKey.value = it.relayKey }
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
        // A FAILED cache read (missing, unreadable, unparseable) yields floor 0 on
        // purpose — fail open: a corrupt cache must not pin the device to stale relays.
        val cachedUpdatedAt = readCache()?.let { parse(it)?.updatedAt } ?: 0L
        if (fresh.updatedAt < cachedUpdatedAt) {
            Log.w(TAG, "lossless config older than the cached copy (${fresh.updatedAt} < $cachedUpdatedAt) — rejected")
            return@withContext false
        }
        _relays.value = fresh.relays
        _relayKey.value = fresh.relayKey
        ioCatching("cache write") { context.losslessRelayConfigDataStore.edit { it[jsonKey] = text } }
        Log.i(TAG, "lossless config applied: ${fresh.relays.size} relay(s)")
        true
    }

    /** Load the cache, then refresh now and every [REFRESH_INTERVAL_MS] — [RETRY_INTERVAL_MS] after a failure. */
    fun start(scope: CoroutineScope) {
        if (!enabled) return // both BuildConfig values blank: nothing to fetch, don't spin a coroutine
        scope.launch {
            loadCached()
            while (true) {
                val ok = refresh()
                delay(nextDelayMs(ok))
            }
        }
    }

    /** 6 h after a good refresh; a failed one (offline at launch, captive portal) retries in 15 min. */
    internal fun nextDelayMs(refreshed: Boolean): Long = if (refreshed) REFRESH_INTERVAL_MS else RETRY_INTERVAL_MS

    internal fun verify(bytes: ByteArray, sigB64: String): Boolean = runCatching {
        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyB64)))
        Signature.getInstance("SHA256withECDSA").run { initVerify(pub); update(bytes); verify(Base64.getDecoder().decode(sigB64)) }
    }.getOrDefault(false)

    private suspend fun readCache(): String? =
        ioCatching("cache read") { context.losslessRelayConfigDataStore.data.first()[jsonKey] }

    /** The config with its relays already normalised and sorted; null when the JSON is unusable. */
    private fun parse(text: String): LosslessConfig? = runCatching {
        val cfg = json.decodeFromString<LosslessConfig>(text)
        cfg.copy(
            relays = cfg.relays
                .mapNotNull { e -> LosslessSourcePreferences.normaliseEndpoint(e.base)?.let { RelayEntry(it, e.priority) } }
                .sortedBy { it.priority },
        )
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

    internal suspend fun clearForTest() {
        _relays.value = emptyList()
        _relayKey.value = null
        context.losslessRelayConfigDataStore.edit { it.clear() }
    }

    private companion object {
        const val TAG = "LosslessConfig"
        const val REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1000L
        const val RETRY_INTERVAL_MS = 15 * 60 * 1000L

        /** A hostile/misconfigured host must not OOM us — an `Error` would escape [ioCatching]. */
        const val MAX_CONFIG_BYTES = 64L * 1024
    }
}

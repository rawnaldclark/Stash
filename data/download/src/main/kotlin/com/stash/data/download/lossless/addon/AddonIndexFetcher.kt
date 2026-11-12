package com.stash.data.download.lossless.addon

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stash.data.download.BuildConfig
import com.stash.data.download.lossless.relay.LosslessConfigFetcher
import com.stash.data.download.lossless.relay.SignedJson
import com.stash.data.download.lossless.tidal.TidalDebugSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/** The index cache. Never in a backup (`DatabaseBackupManager`): an old copy would lower the rollback floor. */
private val Context.addonIndexDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "addon_index",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * The signed add-on index (add-ons plan phase 4 §1.5): fetched from the address the signed config
 * names (`addon_index`), verified with the key that verifies `lossless.json`, checked to be an
 * index ([AddonIndex.parse], `type` first), held to a rollback floor, and kept across restarts.
 *
 *  - **When:** after every applied config (cold start, then every 6 hours) and when Settings ›
 *    Add-ons opens, at most once in [MIN_FETCH_INTERVAL_MS], with `If-None-Match` so an unchanged
 *    index costs a 304.
 *  - **Floor:** an index older than the kept one (by `updated_at`) is refused, so nobody who serves
 *    the address can replay one from before a block.
 *  - **When it fails** (no network, a bad signature, not an index, too large): the kept copy stays.
 *    With no copy at all there is no official list and no blocklist, and nothing else changes:
 *    Tidal never depends on the index.
 *
 * Debug builds only ([setDebugSource]): another address and another key, for a staging index on the
 * rig. A release build ignores both whatever is stored.
 */
@Singleton
class AddonIndexFetcher internal constructor(
    private val store: DataStore<Preferences>,
    sharedClient: OkHttpClient,
    /** Whether this build honours [setDebugSource]: debuggable builds only. */
    private val debuggable: Boolean,
) {
    @Inject constructor(@ApplicationContext context: Context, sharedClient: OkHttpClient) : this(
        context.addonIndexDataStore,
        sharedClient,
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
    )

    /** Test seam: the key the index is verified with (base64 SPKI DER), the one that verifies `lossless.json`. */
    var publicKeyB64: String = BuildConfig.LOSSLESS_CONFIG_PUBKEY

    /** Test seam. */
    var httpClient: OkHttpClient = sharedClient

    /** Test seam: the clock [MIN_FETCH_INTERVAL_MS] is measured on. */
    var nowMs: () -> Long = System::currentTimeMillis

    private val state = MutableStateFlow<AddonIndex?>(null)

    /** The index in use: the last one verified, this run or an earlier one; null with none. */
    val index: StateFlow<AddonIndex?> = state.asStateFlow()

    /** The address the signed config names (`addon_index`); [start] keeps it current. Also a test seam. */
    @Volatile var configUrl: String? = null

    private val lock = Mutex()
    @Volatile private var cacheRead = false

    /**
     * Loads the kept copy, once. Not verified again: it is only ever written from bytes that passed
     * the signature, into app-private storage that no backup carries.
     */
    suspend fun loadCached() = lock.withLock { loadCachedLocked() }

    private suspend fun loadCachedLocked() {
        if (cacheRead) return
        val text = ioCatching("cache read") { store.data.first()[JSON] }
        val cached = text?.let { runCatching { AddonIndex.parse(it.toByteArray()) }.getOrNull() }
        if (cached != null) {
            state.value = cached
            Log.i(TAG, "cached copy (updated_at=${cached.updatedAt})")
        }
        cacheRead = true
    }

    /** Loads the kept copy, then follows the signed config: every applied config, and its address changing. */
    fun start(scope: CoroutineScope, config: LosslessConfigFetcher) {
        scope.launch(Dispatchers.IO) {
            loadCached()
            combine(config.addonIndex, config.refreshedAt) { url, _ -> url }.collect { url ->
                configUrl = url
                try {
                    refresh()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "refresh failed: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    /**
     * Fetch, verify, check and apply. Returns true only when a fresh, valid index was applied.
     * Never throws. Not [force]d: nothing is asked within [MIN_FETCH_INTERVAL_MS] of the last
     * answer from the same address.
     */
    suspend fun refresh(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            loadCachedLocked()
            val prefs = ioCatching("cache read") { store.data.first() } ?: emptyPreferences()
            val debug = debugSource(prefs)
            val url = debug?.first ?: configUrl ?: return@withLock false
            val key = (debug?.second ?: publicKeyB64).takeIf { it.isNotBlank() } ?: return@withLock false
            val cachedText = prefs[JSON]
            val sameSource = prefs[SOURCE] == url
            val now = nowMs()
            val sinceLast = now - (prefs[FETCHED_AT] ?: 0L)
            // A clock set back (a negative gap) fetches.
            if (!force && sameSource && sinceLast in 0 until MIN_FETCH_INTERVAL_MS) return@withLock false
            val client = url.toHttpUrlOrNull()?.let { TidalDebugSettings.clientFor(httpClient, it) } ?: return@withLock false
            val etag = prefs[ETAG]?.takeIf { cachedText != null && sameSource }
            val got = ioCatching("fetch") { SignedJson.get(client, url, MAX_INDEX_BYTES, etag) } ?: return@withLock false
            if (got !is SignedJson.Fetched.Body) {
                ioCatching("cache write") { store.edit { it[FETCHED_AT] = now } }
                Log.i(TAG, "not changed (304)")
                return@withLock false
            }
            val sig = ioCatching("sig") { String(SignedJson.getBytes(client, "$url.sig", MAX_SIG_BYTES)).trim() } ?: return@withLock false
            // Verified over the bytes exactly as fetched, never a re-serialisation.
            if (!SignedJson.verify(got.bytes, sig, key)) {
                Log.w(TAG, "signature invalid — keeping the cached copy")
                return@withLock false
            }
            val fresh = try {
                AddonIndex.parse(got.bytes)
            } catch (e: AddonIndex.Refused) {
                // A good signature on something that is not an index: a signed lossless.json, or a broken rule.
                Log.w(TAG, "not a valid add-on index (${e.rule}) — rejected")
                return@withLock false
            }
            // Rollback floor: a valid signature does not stop whoever serves the address replaying
            // an old index from before a block. A kept copy that no longer reads gives floor 0 on
            // purpose (fail open): a corrupt cache must not pin the phone to it.
            val floor = cachedText?.let { runCatching { AddonIndex.parse(it.toByteArray()) }.getOrNull()?.updatedAt } ?: 0L
            if (fresh.updatedAt < floor) {
                Log.w(TAG, "older than the cached copy — rejected")
                return@withLock false
            }
            state.value = fresh
            ioCatching("cache write") {
                store.edit {
                    it[JSON] = String(got.bytes, Charsets.UTF_8)
                    it[SOURCE] = url
                    it[FETCHED_AT] = now
                    if (got.etag != null) it[ETAG] = got.etag else it.remove(ETAG)
                }
            }
            Log.i(TAG, "applied (updated_at=${fresh.updatedAt}, ${count(fresh.addons.size, "add-on")}, ${fresh.blocked.size} blocked)")
            true
        }
    }

    /**
     * DEBUG BUILDS ONLY: fetch the index from [url] and verify it with [publicKeyB64] instead of the
     * signed config's address and the app's key, for a staging index on the rig (a local server
     * over `adb reverse`, signed with a throwaway key). Null or blank [url] clears both. Changing
     * the source forgets the kept copy and its floor, so a test never leaves its blocks behind.
     * A release build stores nothing and ignores anything stored. Returns whether it was taken.
     */
    suspend fun setDebugSource(url: String?, publicKeyB64: String?): Boolean {
        if (!debuggable) return false
        val u = url?.trim()?.takeIf { it.isNotEmpty() }
        val k = publicKeyB64?.trim()?.takeIf { it.isNotEmpty() }
        if (u != null && (k == null || !debugUrlAllowed(u))) return false
        lock.withLock {
            store.edit {
                it.remove(JSON)
                it.remove(ETAG)
                it.remove(FETCHED_AT)
                it.remove(SOURCE)
                if (u == null) {
                    it.remove(DEBUG_URL)
                    it.remove(DEBUG_KEY)
                } else {
                    it[DEBUG_URL] = u
                    it[DEBUG_KEY] = k!!
                }
            }
            state.value = null
            cacheRead = true
        }
        Log.i(TAG, if (u == null) "staging source cleared (debug build)" else "staging source set (debug build)")
        return true
    }

    /** The staging source, on a debuggable build that has one stored; null otherwise. */
    private fun debugSource(prefs: Preferences): Pair<String, String>? {
        if (!debuggable) return null
        val url = prefs[DEBUG_URL]?.takeIf(::debugUrlAllowed) ?: return null
        val key = prefs[DEBUG_KEY]?.takeIf { it.isNotBlank() } ?: return null
        return url to key
    }

    /** https anywhere, or http on 127.0.0.1 / localhost (a local server reached over `adb reverse`). */
    private fun debugUrlAllowed(url: String): Boolean {
        val u = url.toHttpUrlOrNull() ?: return false
        return u.isHttps || u.host == "127.0.0.1" || u.host == "localhost"
    }

    private fun count(n: Int, word: String): String = "$n $word${if (n == 1) "" else "s"}"

    /**
     * Fetching the index must never crash the app: anything short of a cancellation is null. Only
     * our own messages are logged, never a library's (an `UnknownHostException` message carries
     * the host).
     */
    private inline fun <T> ioCatching(label: String, block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val detail = e.message?.takeIf { it.startsWith("HTTP ") || it.startsWith("body over") || it == "empty body" } ?: e.javaClass.simpleName
        Log.w(TAG, "$label failed: $detail")
        null
    }

    companion object {
        private const val TAG = "AddonIndex"

        /** At most one fetch in this long from one address (the config's own refresh is every 6 hours). */
        const val MIN_FETCH_INTERVAL_MS = 6 * 60 * 60 * 1000L

        /** The size cap on the index body, as `MAX_CONFIG_BYTES` for `lossless.json`. */
        const val MAX_INDEX_BYTES = AddonIndex.MAX_BYTES.toLong()

        /** A DER P-256 signature in base64 is about 96 characters. */
        private const val MAX_SIG_BYTES = 1024L

        private val JSON = stringPreferencesKey("index_json")
        private val ETAG = stringPreferencesKey("etag")
        private val SOURCE = stringPreferencesKey("source")
        private val FETCHED_AT = longPreferencesKey("fetched_at")
        private val DEBUG_URL = stringPreferencesKey("debug_url")
        private val DEBUG_KEY = stringPreferencesKey("debug_pubkey")
    }
}

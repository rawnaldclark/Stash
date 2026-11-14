package com.stash.data.download.lossless.addon

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stash.core.addons.AddonManifestStore
import com.stash.core.addons.InstalledAddon
import com.stash.core.addons.UserAddonEntry
import com.stash.core.addons.UserAddons
import com.stash.core.auth.crypto.TinkEncryptionManager
import dagger.hilt.android.qualifiers.ApplicationContext
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

private val Context.addonManifestDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "addon_manifests",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

private val Context.userAddonsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "user_addons",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * The registry's last good manifests (add-ons plan phase 2 §5.3), one per entry, so an offline
 * cold start keeps the Tidal add-on and its FLAC. Keyed by the SHA-256 of the manifest URL
 * ([com.stash.core.addons.InTreeAddonRegistry.keyOf]); the values are the add-on's own manifest
 * JSON, which holds no URL or token of ours.
 */
@Singleton
class DataStoreAddonManifestStore internal constructor(
    private val store: DataStore<Preferences>,
) : AddonManifestStore {
    @Inject constructor(@ApplicationContext context: Context) : this(context.addonManifestDataStore)

    override suspend fun read(key: String): String? = store.data.first()[prefKey(key)]

    override suspend fun write(key: String, json: String) {
        val k = prefKey(key)
        if (store.data.first()[k] == json) return
        store.edit { it[k] = json }
    }

    override suspend fun retain(keys: Set<String>) {
        val keep = keys.map { PREFIX + it }.toSet()
        val gone = store.data.first().asMap().keys.map { it.name }.filter { it.startsWith(PREFIX) && it !in keep }
        if (gone.isEmpty()) return
        store.edit { p -> gone.forEach { p.remove(stringPreferencesKey(it)) } }
    }

    private fun prefKey(key: String) = stringPreferencesKey(PREFIX + key)

    private companion object {
        const val PREFIX = "m_"
    }
}

/**
 * The add-ons the user added from a link (plan §5.5), kept encrypted as SPEC.md 6 requires: their
 * manifest URLs can carry a token in the path, and a header token is a password. One Tink
 * (AES-256-GCM, Android Keystore) sealed list in DataStore.
 *
 * **A stored list that does not open is not an empty list** (review-881 M11, review-882 J2). The
 * Keystore fails for a moment now and then (`KeysetRecovery`), and reading that as "no add-ons"
 * let the next Add, Remove or switch seal an empty list over the real one, for good. So:
 *  - **nothing stored**: an empty list;
 *  - **stored, and it did not open** (the key is not answering): the stored list is kept as it
 *    is. [entries] stays empty for now, the read is tried again on the next access
 *    ([loadedEntries], any write), and until it opens every write is refused with
 *    [UnreadableException] rather than written over it;
 *  - **stored, and sealed with a key this phone no longer has** (a fresh seal opens, the stored
 *    list still does not: the keyset was replaced after the Keystore lost its key): that list can
 *    never be read again, so it reads as empty and the next write replaces it. The user adds
 *    them again, as with any other sign-in.
 */
@Singleton
class EncryptedUserAddons internal constructor(
    private val store: DataStore<Preferences>,
    private val sealer: Sealer,
    /** Tests: the clock [RETRY_EVERY_MS] is measured on. */
    private val nowMs: () -> Long = System::currentTimeMillis,
) : UserAddons {
    @Inject constructor(@ApplicationContext context: Context, tink: TinkEncryptionManager) :
        this(context.userAddonsDataStore, TinkSealer(tink))

    /** The stored list is there and could not be opened, so nothing may be written over it. Says nothing of its content. */
    class UnreadableException : java.io.IOException("the stored add-on list could not be opened")

    /** The encryption, a seam for tests. */
    interface Sealer {
        fun seal(plain: ByteArray): ByteArray
        fun open(sealed: ByteArray): ByteArray
    }

    private class TinkSealer(private val tink: TinkEncryptionManager) : Sealer {
        override fun seal(plain: ByteArray): ByteArray = tink.encrypt(plain)
        override fun open(sealed: ByteArray): ByteArray = tink.decrypt(sealed)
    }

    @Serializable
    private data class Stored(val manifest: String, val token: String? = null, val enabled: Boolean = true)

    private val state = MutableStateFlow<List<UserAddonEntry>>(emptyList())
    override val entries: StateFlow<List<UserAddonEntry>> = state.asStateFlow()

    /** The first read has been tried (whether or not it opened). */
    private val loaded = CompletableDeferred<Unit>()

    /** One reader or writer of the stored list at a time. Never held while awaiting [loaded]. */
    private val writeLock = Mutex()

    /**
     * [state] is the stored list: it was read, or nothing is stored, or it is lost for good. False
     * while a stored list has not opened: then [state] is empty and says nothing about what is stored.
     */
    @Volatile private var opened = false

    @Volatile private var lastTryMs = 0L

    /** The stored list is there and has not opened this run: the user's add-ons are missing, not gone. */
    val unreadable: Boolean get() = loaded.isCompleted && !opened

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                writeLock.withLock { tryOpen() }
            } finally {
                loaded.complete(Unit)
            }
        }
    }

    /**
     * [entries] once the stored list has been read. While it has not opened, the read is tried
     * again here, at most every [RETRY_EVERY_MS]: the Keystore's bad moments pass.
     */
    suspend fun loadedEntries(): List<UserAddonEntry> {
        loaded.await()
        if (!opened && nowMs() - lastTryMs >= RETRY_EVERY_MS) writeLock.withLock { if (!opened) tryOpen() }
        return state.value
    }

    /** Adds [entry], or replaces the one with the same manifest URL. */
    suspend fun put(entry: UserAddonEntry) = update { list ->
        require(entry.token == null || InstalledAddon.isHeaderSafe(entry.token!!)) { "token not header-safe" }
        list.filterNot { it.manifestUrl == entry.manifestUrl } + entry
    }

    suspend fun remove(manifestUrl: String) = update { list -> list.filterNot { it.manifestUrl == manifestUrl } }

    suspend fun setEnabled(manifestUrl: String, enabled: Boolean) = update { list ->
        list.map { if (it.manifestUrl == manifestUrl) it.copy(enabled = enabled) else it }
    }

    /**
     * Writes [change] applied to the stored list. Throws [UnreadableException], writing nothing,
     * while a stored list has not opened (it is tried once more first); a failed seal or write
     * throws too and leaves the list as it was.
     */
    private suspend fun update(change: (List<UserAddonEntry>) -> List<UserAddonEntry>) {
        loaded.await()
        writeLock.withLock {
            if (!opened) tryOpen()
            if (!opened) throw UnreadableException()
            val next = change(state.value)
            val blob = Base64.getEncoder().encodeToString(
                sealer.seal(json.encodeToString(LIST, next.map { Stored(it.manifestUrl, it.token, it.enabled) }).toByteArray()),
            )
            store.edit { it[KEY] = blob }
            state.value = next
        }
    }

    /**
     * Reads the stored list into [state] and sets [opened], or leaves [opened] false when a stored
     * list did not open (the class doc's three cases). Under [writeLock].
     */
    private suspend fun tryOpen() {
        lastTryMs = nowMs()
        val blob = try {
            store.data.first()[KEY]
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The file itself could not be read: whatever it holds is still there.
            Log.w(TAG, "user add-ons unreadable: ${e.javaClass.simpleName}")
            return
        }
        if (blob == null) {
            opened = true
            return
        }
        val list = decode(blob)
            // It did not open. That is final only when the key demonstrably works and the stored
            // list still does not open with it; a key that is not answering is tried again later.
            ?: if (keyWorks()) decode(blob) else return
        if (list == null) Log.w(TAG, "user add-ons were sealed with a key this phone no longer has: they must be added again")
        state.value = list.orEmpty()
        opened = true
    }

    /** The entries sealed in [blob], or null when it does not open or is not a list. */
    private fun decode(blob: String): List<UserAddonEntry>? = try {
        val plain = sealer.open(Base64.getDecoder().decode(blob))
        json.decodeFromString(LIST, String(plain)).map { UserAddonEntry(it.manifest, it.token, it.enabled) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Never e.message: it could quote the plaintext.
        Log.w(TAG, "user add-ons could not be opened: ${e.javaClass.simpleName}")
        null
    }

    /** A fresh seal opens again: the key is there and answering. */
    private fun keyWorks(): Boolean = try {
        sealer.open(sealer.seal(PROBE)).contentEquals(PROBE)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    companion object {
        /** While the stored list has not opened, [loadedEntries] tries again at most this often. */
        const val RETRY_EVERY_MS = 5_000L

        private val PROBE = byteArrayOf(0x53, 0x74, 0x61, 0x73, 0x68)
        private const val TAG = "UserAddons"
        private val KEY = stringPreferencesKey("sealed")
        private val LIST = ListSerializer(Stored.serializer())
        private val json = Json { ignoreUnknownKeys = true }
    }
}

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
import com.stash.core.addons.UserAddonKind
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
 * A list that can't be opened any more (the keystore key was lost) reads as empty: the user adds
 * them again, as with any other sign-in.
 */
@Singleton
class EncryptedUserAddons internal constructor(
    private val store: DataStore<Preferences>,
    private val sealer: Sealer,
) : UserAddons {
    @Inject constructor(@ApplicationContext context: Context, tink: TinkEncryptionManager) :
        this(context.userAddonsDataStore, TinkSealer(tink))

    /** The encryption, a seam for tests. */
    interface Sealer {
        fun seal(plain: ByteArray): ByteArray
        fun open(sealed: ByteArray): ByteArray
    }

    private class TinkSealer(private val tink: TinkEncryptionManager) : Sealer {
        override fun seal(plain: ByteArray): ByteArray = tink.encrypt(plain)
        override fun open(sealed: ByteArray): ByteArray = tink.decrypt(sealed)
    }

    /**
     * One entry as sealed. [kind] is [UserAddonKind]'s name; absent (a list sealed before phase 4)
     * is a manifest add-on. A kind this build doesn't know is kept, untouched, but not used, so a
     * newer build's entry survives an older build's writes. [id] and [name]: the add-on's manifest
     * id and name when it was added; [consent]: what the user agreed its server may see
     * (`AddonSees` names); [addedAt]: epoch milliseconds (add-ons plan phase 4 §2.13). All absent
     * in a list sealed before them, and unknown keys are ignored, so blobs read both ways.
     */
    @Serializable
    private data class Stored(
        val manifest: String,
        val token: String? = null,
        val enabled: Boolean = true,
        val kind: String? = null,
        val id: String? = null,
        val name: String? = null,
        val consent: List<String>? = null,
        @kotlinx.serialization.SerialName("added_at") val addedAt: Long? = null,
    )

    private val state = MutableStateFlow<List<UserAddonEntry>>(emptyList())
    override val entries: StateFlow<List<UserAddonEntry>> = state.asStateFlow()

    private val loaded = CompletableDeferred<Unit>()
    private val writeLock = Mutex()

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                state.value = read()
            } finally {
                loaded.complete(Unit)
            }
        }
    }

    /**
     * Adds [entry], or replaces the one of its kind with the same manifest URL. The user's own Tidal
     * server replaces any other of that kind: there is at most one.
     */
    suspend fun put(entry: UserAddonEntry) = update { list ->
        require(entry.token == null || InstalledAddon.isHeaderSafe(entry.token!!)) { "token not header-safe" }
        list.filterNot {
            it.kind == entry.kind && (it.manifestUrl == entry.manifestUrl || entry.kind == UserAddonKind.OWN_TIDAL_SERVER)
        } + entry
    }

    /** Removes the add-on added from [manifestUrl] (never the user's own Tidal server). */
    suspend fun remove(manifestUrl: String) = update { list ->
        list.filterNot { it.kind == UserAddonKind.MANIFEST && it.manifestUrl == manifestUrl }
    }

    /**
     * Puts [entry] where the add-on added from [oldManifestUrl] is, keeping its place in the list
     * (Replace link, phase 4 §2.9). With no such add-on it is added at the end.
     */
    suspend fun replace(oldManifestUrl: String, entry: UserAddonEntry) = update { list ->
        require(entry.token == null || InstalledAddon.isHeaderSafe(entry.token!!)) { "token not header-safe" }
        val at = list.indexOfFirst { it.kind == UserAddonKind.MANIFEST && it.manifestUrl == oldManifestUrl }
        if (at < 0) {
            list.filterNot { it.kind == entry.kind && it.manifestUrl == entry.manifestUrl } + entry
        } else {
            list.mapIndexedNotNull { i, e ->
                when {
                    i == at -> entry
                    e.kind == entry.kind && e.manifestUrl == entry.manifestUrl -> null // the new link was already another entry's
                    else -> e
                }
            }
        }
    }

    /** Records what the user agreed the add-on added from [manifestUrl] may see (phase 4 §2.7). */
    suspend fun setConsent(manifestUrl: String, consent: Set<String>) = update { list ->
        list.map { if (it.kind == UserAddonKind.MANIFEST && it.manifestUrl == manifestUrl) it.copy(consent = consent) else it }
    }

    /** Removes every entry of [kind]. */
    suspend fun removeKind(kind: UserAddonKind) = update { list -> list.filterNot { it.kind == kind } }

    suspend fun setEnabled(manifestUrl: String, enabled: Boolean) = update { list ->
        list.map { if (it.kind == UserAddonKind.MANIFEST && it.manifestUrl == manifestUrl) it.copy(enabled = enabled) else it }
    }

    override suspend fun loadedEntries(): List<UserAddonEntry> {
        loaded.await()
        return state.value
    }

    /** Entries of a kind this build doesn't know, kept as read so a write here never drops them. */
    @Volatile private var unknown: List<Stored> = emptyList()

    /** No kind: a manifest add-on (sealed before phase 4). A name this build doesn't know: null. */
    private fun kindOf(s: Stored): UserAddonKind? =
        if (s.kind == null) UserAddonKind.MANIFEST else UserAddonKind.entries.firstOrNull { it.name == s.kind }

    private suspend fun update(change: (List<UserAddonEntry>) -> List<UserAddonEntry>) = writeLock.withLock {
        loaded.await()
        val next = change(state.value)
        val stored = next.map {
            Stored(
                it.manifestUrl, it.token, it.enabled, it.kind.takeIf { k -> k != UserAddonKind.MANIFEST }?.name,
                it.id, it.name, it.consent?.sorted(), it.addedAt,
            )
        } + unknown
        val blob = Base64.getEncoder().encodeToString(sealer.seal(json.encodeToString(LIST, stored).toByteArray()))
        store.edit { it[KEY] = blob }
        state.value = next
    }

    private suspend fun read(): List<UserAddonEntry> {
        val blob = try {
            store.data.first()[KEY]
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "user add-ons unreadable: ${e.javaClass.simpleName}")
            null
        } ?: return emptyList()
        return try {
            val plain = sealer.open(Base64.getDecoder().decode(blob))
            val all = json.decodeFromString(LIST, String(plain))
            unknown = all.filter { kindOf(it) == null }
            all.mapNotNull { s ->
                kindOf(s)?.let { UserAddonEntry(s.manifest, s.token, s.enabled, it, s.id, s.name, s.consent?.toSet(), s.addedAt) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never e.message: it could quote the plaintext.
            Log.w(TAG, "user add-ons could not be opened: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    private companion object {
        const val TAG = "UserAddons"
        val KEY = stringPreferencesKey("sealed")
        val LIST = ListSerializer(Stored.serializer())
        val json = Json { ignoreUnknownKeys = true }
    }
}

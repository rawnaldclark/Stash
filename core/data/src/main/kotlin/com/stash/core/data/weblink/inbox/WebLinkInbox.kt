package com.stash.core.data.weblink.inbox

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.stash.core.data.weblibrary.ImportSelection
import com.stash.core.data.weblibrary.WebLibraryContent
import com.stash.core.data.weblibrary.WebLibraryImportResult
import com.stash.core.data.weblibrary.WebLibraryImporter
import com.stash.core.data.weblibrary.WebLibraryReadException
import com.stash.core.data.weblibrary.WebLibraryReader
import com.stash.core.data.weblink.Base64Url
import com.stash.core.data.weblink.DeviceAuth
import com.stash.core.data.weblink.InboxListing
import com.stash.core.data.weblink.SyncApi
import com.stash.core.data.weblink.SyncCrypto
import com.stash.core.data.weblink.SyncCryptoException
import com.stash.core.data.weblink.SyncEnvelope
import com.stash.core.data.weblink.SyncErrorCode
import com.stash.core.data.weblink.SyncResult
import com.stash.core.data.weblink.WebLinkConfig
import com.stash.core.data.weblink.WebLinkCopy
import com.stash.core.data.weblink.WebLinkRepository
import com.stash.core.data.weblink.store.LinkIdentity
import com.stash.core.data.weblink.store.LinkedSpace
import com.stash.core.data.weblink.store.WebLinkStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A device this phone can send to: one of its linked browsers, by the name in the label this phone opened. */
data class SendTarget(val id: String, val name: String)

/** A send that arrived here. [content] is null when it can't be read (a newer format, a broken send): it can only be discarded. */
data class IncomingSend(
    val sendId: String,
    val from: String,
    /** "Chrome on Windows", from the sender's label as this phone opened it. */
    val name: String,
    val serverAt: Long,
    val content: WebLibraryContent?,
    /** "3 playlists and 40 likes", or why it can't be read. */
    val summary: String,
)

/** How a send went. */
sealed interface SendOutcome {
    data object Sent : SendOutcome

    data class Failed(val message: String) : SendOutcome
}

/**
 * One-off sends between linked devices (spec §2.3, §6.2; sync-v1 §3.3, §5.5): "Send to Chrome on Windows" from this phone, and
 * what a browser sent here. A send is exactly the `stash-web-library` v1 document the picker would save as a file, gzipped once,
 * cut into slices of at most 768,000 bytes, each sealed under the space's data key at `inbox:<to>:<sendId>:<part>/<count>`
 * (at most 16 parts: a bigger one says "Too big to send. Save it as a file instead." before anything goes up).
 *
 * Receiving: looked for when the app comes to the foreground (at most every 30 s, nothing in the background); each send is
 * fetched part by part, opened, and read with the file's own reader ([WebLibraryReader]), so a send can carry nothing a file
 * couldn't, and its settings are never applied. **Add** merges it exactly as importing the file does, then deletes it on the
 * server; **Not now** keeps it (the server drops it after 7 days) without asking again; **Discard** deletes it. Every store and
 * crypto use is guarded: a passing Keystore failure is "nothing to show", never a crash.
 */
@Singleton
class WebLinkInbox internal constructor(
    private val api: SyncApi,
    private val store: WebLinkStore,
    private val repo: WebLinkRepository,
    private val importer: WebLibraryImporter,
    private val prefs: SharedPreferences,
    private val config: WebLinkConfig,
    private val clock: () -> Long,
    private val partBytes: Int,
) {
    @Inject constructor(
        api: SyncApi,
        store: WebLinkStore,
        repo: WebLinkRepository,
        importer: WebLibraryImporter,
        @ApplicationContext context: Context,
        config: WebLinkConfig,
    ) : this(api, store, repo, importer, context.getSharedPreferences(PREFS, Context.MODE_PRIVATE), config, { android.os.SystemClock.elapsedRealtime() }, SyncCrypto.PART_BYTES)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var lastCheck = Long.MIN_VALUE / 2

    /** Opened sends by id (a complete send never changes). */
    private val opened = HashMap<String, IncomingSend>()

    private val _sends = MutableStateFlow<List<IncomingSend>>(emptyList())

    /** Every send waiting here, newest first (Link Stash on the web lists them). */
    val sends: StateFlow<List<IncomingSend>> = _sends.asStateFlow()

    private val _notice = MutableStateFlow<IncomingSend?>(null)

    /** The send to ask about: the newest readable one that wasn't put off. */
    val notice: StateFlow<IncomingSend?> = _notice.asStateFlow()

    private fun setSends(list: List<IncomingSend>) {
        _sends.value = list
        val later = ids(KEY_LATER)
        _notice.value = list.firstOrNull { it.content != null && it.sendId !in later }
    }

    /** The app came to the foreground. */
    fun onForeground() {
        if (!config.enabled) return
        scope.launch { check(force = false) }
    }

    // -------------------------------------------------------------------------------------------- sending

    /** The browsers this phone can send to, in the order they joined. */
    suspend fun targets(): List<SendTarget> = guard(emptyList()) {
        val me = store.identity()?.deviceId ?: return@guard emptyList()
        store.roster().filter { it.deviceId != me && it.type == "web" }.map { SendTarget(it.deviceId, it.displayName) }
    }

    /**
     * Sends [documentJson] (a `stash-web-library` v1 document) to device [to]. A rotation in the middle settles the key and sends
     * again under a new id; a send cut short is taken back.
     */
    suspend fun send(to: String, documentJson: String): SendOutcome = guard(SendOutcome.Failed(WebLinkCopy.STORE_BUSY)) {
        repeat(2) { attempt ->
            val id = store.identity() ?: return@guard SendOutcome.Failed(NOT_LINKED)
            val sp = store.space() ?: return@guard SendOutcome.Failed(NOT_LINKED)
            val name = store.roster().firstOrNull { it.deviceId == to }?.displayName ?: return@guard SendOutcome.Failed(GONE)
            val sendId = newSendId()
            val envs = try {
                SyncCrypto.sealParts(SyncCrypto.dataKey(sp.k, sp.spaceId), sp.spaceId, sp.epoch, { i, n -> SyncCrypto.Place.inbox(to, sendId, i, n) }, documentJson, partBytes)
            } catch (e: SyncCryptoException) {
                return@guard SendOutcome.Failed(TOO_BIG) // more than 16 parts
            }
            val auth = DeviceAuth(id.deviceId, id.token)
            var put = 0
            var failure: SyncResult<*>? = null
            for ((i, env) in envs.withIndex()) {
                val r = api.putInbox(auth, sp.spaceId, to, sendId, i, envs.size, env)
                if (r !is SyncResult.Ok) {
                    failure = r
                    break
                }
                put++
            }
            if (failure == null) {
                Log.i(TAG, "sent ${documentJson.length} chars in ${envs.size} part(s) to ${to.take(6)}…")
                return@guard SendOutcome.Sent
            }
            if (put > 0) api.deleteInbox(auth, sp.spaceId, sendId) // take back what went up
            val e = failure as? SyncResult.Error
            if (attempt == 0 && e != null && (e.code == SyncErrorCode.ROTATION_DUE || e.code == SyncErrorCode.EPOCH)) {
                repo.refresh() // rotates, or walks the key forward
                return@repeat
            }
            return@guard SendOutcome.Failed(messageOf(failure, name))
        }
        SendOutcome.Failed(TRY_AGAIN)
    }

    private suspend fun messageOf(r: SyncResult<*>, name: String): String = when (r) {
        is SyncResult.Unreachable -> WebLinkCopy.OFFLINE
        is SyncResult.Error -> when {
            r.code == SyncErrorCode.SPACE_FULL || r.code == "too_large" -> TOO_BIG
            r.code == "inbox_full" -> "$name has too many sends waiting. Try again once it has added them."
            r.revoked || r.code == SyncErrorCode.NOT_FOUND -> {
                repo.refresh()
                "$name isn't linked any more."
            }
            r.status == 429 -> "Too many tries. Try again in a few minutes."
            r.status >= 500 -> WebLinkCopy.OFFLINE
            else -> TRY_AGAIN
        }
        is SyncResult.Ok -> TRY_AGAIN
    }

    // -------------------------------------------------------------------------------------------- receiving

    /** Looks for sends (at most every 30 s unless [force]). */
    suspend fun check(force: Boolean = true) {
        lock.withLock {
            val t = clock()
            if (!force && t - lastCheck < CHECK_GAP_MS) return
            lastCheck = t
            look()
        }
    }

    private suspend fun look() = guard(Unit) {
        val id = store.identity()
        val sp = store.space()
        if (id == null || sp == null) {
            opened.clear()
            setSends(emptyList())
            return@guard
        }
        val auth = DeviceAuth(id.deviceId, id.token)
        val listed = when (val r = api.inbox(auth, sp.spaceId)) {
            is SyncResult.Ok -> r.value.sends
            is SyncResult.Error -> {
                if (r.revoked) repo.refresh()
                return@guard // keep what was shown (spec §12: degrades silently)
            }
            is SyncResult.Unreachable -> return@guard
        }
        val done = ids(KEY_DONE)
        val usable = listed.filter { SEND_ID.matches(it.sendId) && it.count in 1..SyncCrypto.MAX_PARTS && it.sendId !in done }
        val names = store.roster().associate { it.deviceId to it.displayName }
        val out = usable.mapNotNull { l -> opened[l.sendId] ?: openSend(auth, sp, id, l, names[l.from] ?: DEFAULT_NAME) }
        opened.keys.retainAll(usable.map { it.sendId }.toSet())
        setSends(out.sortedByDescending { it.serverAt })
    }

    /** Every part, opened in order and read as a library file. Null: try again later (a part or a key not here yet). */
    private suspend fun openSend(auth: DeviceAuth, sp: LinkedSpace, id: LinkIdentity, l: InboxListing, name: String): IncomingSend? {
        val envs = ArrayList<SyncEnvelope>(l.count)
        for (i in 0 until l.count) {
            when (val r = api.inboxPart(auth, sp.spaceId, l.sendId, i)) {
                is SyncResult.Ok -> if (r.value.count == l.count) envs += r.value.env else return null
                else -> return null
            }
        }
        val k = sp.keyFor(envs[0].e)
        val send = if (k == null) {
            if (envs[0].e > sp.epoch) return null // sealed under a key this phone hasn't caught up with yet
            IncomingSend(l.sendId, l.from, name, l.serverAt, null, CANT_READ)
        } else {
            try {
                val text = SyncCrypto.openParts(SyncCrypto.dataKey(k, sp.spaceId), sp.spaceId, { i, n -> SyncCrypto.Place.inbox(id.deviceId, l.sendId, i, n) }, envs)
                val content = WebLibraryReader.read(text, System.currentTimeMillis())
                IncomingSend(l.sendId, l.from, name, l.serverAt, content, describe(content))
            } catch (e: SyncCryptoException) {
                Log.w(TAG, "send ${l.sendId.take(6)}… didn't open: ${e.message}")
                IncomingSend(l.sendId, l.from, name, l.serverAt, null, CANT_READ)
            } catch (e: WebLibraryReadException) {
                IncomingSend(l.sendId, l.from, name, l.serverAt, null, if (e.message == WebLibraryReader.NEWER) WebLinkCopy.UPDATE_APP else CANT_READ)
            }
        }
        opened[l.sendId] = send
        return send
    }

    /** Add: merges the send as a file import does (only [selection]), then deletes it on the server. */
    suspend fun add(sendId: String, selection: ImportSelection = ImportSelection.ALL): WebLibraryImportResult? {
        val send = _sends.value.firstOrNull { it.sendId == sendId } ?: return null
        val content = send.content ?: return null
        val result = importer.import(content, selection, origin = send.from)
        remember(KEY_DONE, sendId)
        forget(sendId)
        deleteOnServer(sendId)
        return result
    }

    /** Not now: kept on the server (7 days), not asked about again; still listed on Link Stash on the web. */
    fun later(sendId: String) {
        remember(KEY_LATER, sendId)
        setSends(_sends.value)
    }

    /** Discard: deleted, nothing added. */
    suspend fun discard(sendId: String) {
        remember(KEY_DONE, sendId)
        forget(sendId)
        deleteOnServer(sendId)
    }

    private fun forget(sendId: String) {
        opened.remove(sendId)
        setSends(_sends.value.filter { it.sendId != sendId })
    }

    private suspend fun deleteOnServer(sendId: String) = guard(Unit) {
        val id = store.identity() ?: return@guard
        val sp = store.space() ?: return@guard
        api.deleteInbox(DeviceAuth(id.deviceId, id.token), sp.spaceId, sendId)
    }

    // -------------------------------------------------------------------------------------------- plumbing

    private fun ids(key: String): Set<String> = prefs.getString(key, "").orEmpty().split('\n').filter { it.isNotEmpty() }.toSet()

    private fun remember(key: String, sendId: String) {
        val list = prefs.getString(key, "").orEmpty().split('\n').filter { it.isNotEmpty() && it != sendId } + sendId
        prefs.edit().putString(key, list.takeLast(MAX_REMEMBERED).joinToString("\n")).apply()
    }

    private suspend inline fun <T> guard(fallback: T, crossinline block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "inbox call failed: ${e.javaClass.simpleName}")
            fallback
        }
    }

    companion object {
        private const val TAG = "WebLinkInbox"
        const val PREFS = "weblink_inbox"
        private const val KEY_LATER = "later"
        private const val KEY_DONE = "done"
        private const val MAX_REMEMBERED = 100
        private const val CHECK_GAP_MS = 30_000L
        private const val DEFAULT_NAME = "Stash on the web"
        private val SEND_ID = Regex("^[A-Za-z0-9_-]{8,64}$")

        const val TOO_BIG = "Too big to send. Save it as a file instead."
        const val NOT_LINKED = "This phone isn't linked any more."
        const val GONE = "That browser isn't linked any more."
        const val TRY_AGAIN = "Couldn't send. Try again."
        const val CANT_READ = "This send can't be read."

        /** `x_` + 16 random bytes in base64url (sync-v1 §1). */
        fun newSendId(): String = "x_" + Base64Url.encode(SyncCrypto.randomBytes(16))

        /** "3 playlists, 40 likes and 120 plays" (only what it holds), or "nothing". */
        fun describe(c: WebLibraryContent): String {
            fun n(count: Int, one: String, many: String) = if (count == 1) "1 $one" else "%,d $many".format(count)
            val parts = buildList {
                if (c.playlists.isNotEmpty()) add(n(c.playlists.size, "playlist", "playlists"))
                if (c.likes.isNotEmpty()) add(n(c.likes.size, "like", "likes"))
                if (c.history.isNotEmpty()) add(n(c.history.size, "play", "plays"))
            }
            return when (parts.size) {
                0 -> "nothing"
                1 -> parts[0]
                else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
            }
        }
    }
}

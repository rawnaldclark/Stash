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
import com.stash.core.data.weblink.SyncApi
import com.stash.core.data.weblink.SyncCrypto
import com.stash.core.data.weblink.SyncCryptoException
import com.stash.core.data.weblink.SyncEnvelope
import com.stash.core.data.weblink.SyncErrorCode
import com.stash.core.data.weblink.SyncResult
import com.stash.core.data.weblink.WebLinkConfig
import com.stash.core.data.weblink.WebLinkCopy
import com.stash.core.data.weblink.WebLinkRepository
import com.stash.core.data.weblink.WebLinkStatus
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

/**
 * A send that arrived here, as the server lists it: nothing in it is downloaded or read until the listener taps Add or Choose
 * ([WebLinkInbox.open]). [problem] is set once it is known it can't be read (too big, a newer format, broken, or an open that
 * didn't finish): then it can only be discarded.
 */
data class IncomingSend(
    val sendId: String,
    val from: String,
    /** "Chrome on Windows", from the sender's label as this phone opened it. */
    val name: String,
    val serverAt: Long,
    /** Its sealed size on the server, as listed. */
    val bytes: Long = 0,
    /** Why it can't be read, or null. */
    val problem: String? = null,
) {
    val readable: Boolean get() = problem == null

    /** What the prompt says it holds before it is opened ("Chrome on Windows sent you music from their library."). */
    val summary: String get() = SUMMARY

    companion object {
        const val SUMMARY = "music from their library"
    }
}

/** A send opened for Add or Choose: what it holds, or why it can't be read. */
sealed interface OpenedSend {
    data class Ok(val send: IncomingSend, val content: WebLibraryContent) : OpenedSend {
        /** "3 playlists and 40 likes". */
        val summary: String get() = WebLinkInbox.describe(content)
    }

    data class Failed(val message: String) : OpenedSend
}

/** What an Add did. */
sealed interface AddOutcome {
    data class Added(val result: WebLibraryImportResult) : AddOutcome

    data class Failed(val message: String) : AddOutcome
}

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
 * Receiving: listed when the app comes to the foreground (at most every 30 s, nothing in the background). A send is downloaded,
 * opened and read only when the listener taps Add or Choose (review B1: opening every listed send automatically let a hostile
 * or huge one run the phone out of memory on every launch). Opening is bounded: at most [MAX_SEND_BYTES] sealed,
 * [MAX_SEND_PLAIN] inflated (sync-v1 §8), and a shape check before the JSON is parsed ([WebLibraryReader.SEND_SHAPE]). The send
 * is marked "trying" on disk before it is inflated, so an open that kills the process is remembered as unreadable and never
 * tried again, and any failure there, an `OutOfMemoryError` included, marks it unreadable. It is read with the file's own
 * reader ([WebLibraryReader]), so a send can carry nothing a file couldn't, and its settings are never applied. **Add** merges
 * it exactly as importing the file does, then deletes it on the server; **Not now** keeps it (the server drops it after 7
 * days) without asking again; **Discard** deletes it. Every store and crypto use is guarded: a passing Keystore failure is
 * "nothing to show", never a crash. Unlinking clears the list at once (review S3).
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

    /** Sends opened this session, by id (a complete send never changes). Guarded by [lock]. */
    private val opened = HashMap<String, OpenedSend.Ok>()

    /**
     * Per browser, a send whose last attempt ended without an answer (it may have landed): the next send to that browser reuses
     * its id, so it replaces that send instead of adding a second one (review S6).
     */
    private val unsure = HashMap<String, String>()

    private val _sends = MutableStateFlow<List<IncomingSend>>(emptyList())

    /** Every send waiting here, newest first (Link Stash on the web lists them). */
    val sends: StateFlow<List<IncomingSend>> = _sends.asStateFlow()

    private val _notice = MutableStateFlow<IncomingSend?>(null)

    /** The send to ask about: the newest readable one that wasn't put off. */
    val notice: StateFlow<IncomingSend?> = _notice.asStateFlow()

    private fun setSends(list: List<IncomingSend>) {
        _sends.value = list
        val later = ids(KEY_LATER)
        _notice.value = list.firstOrNull { it.readable && it.sendId !in later }
    }

    init {
        // Unlinked (here or elsewhere): what was listed belongs to the old link (review S3).
        scope.launch {
            repo.status.collect { st -> if (st == WebLinkStatus.NotLinked) clearAll() }
        }
    }

    /** Forgets every send of the old link: listed, opened, put off, done, unreadable. */
    private suspend fun clearAll() {
        lock.withLock {
            opened.clear()
            unsure.clear()
            setSends(emptyList())
            prefs.edit().remove(KEY_LATER).remove(KEY_DONE).remove(KEY_TRYING).remove(KEY_BAD).commit()
        }
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
            // A document over the receiver's cap would be refused there (sync-v1 §5.5): say so before anything goes up.
            if (documentJson.length > MAX_SEND_PLAIN || documentJson.toByteArray(Charsets.UTF_8).size > MAX_SEND_PLAIN) return@guard SendOutcome.Failed(TOO_BIG)
            val sendId = unsure[to] ?: newSendId()
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
                unsure.remove(to)
                Log.i(TAG, "sent ${documentJson.length} chars in $put part(s) to ${to.take(6)}…")
                return@guard SendOutcome.Sent
            }
            // Take back whatever may have gone up, even when no part was confirmed: a PUT whose answer was lost may have landed.
            val taken = api.deleteInbox(auth, sp.spaceId, sendId)
            val gone = taken is SyncResult.Ok || (taken is SyncResult.Error && taken.code == SyncErrorCode.NOT_FOUND)
            if (failure is SyncResult.Unreachable && !gone) unsure[to] = sendId else unsure.remove(to)
            val e = failure as? SyncResult.Error
            if (attempt == 0 && e != null && e.code == SyncErrorCode.EXISTS) {
                unsure.remove(to) // an earlier send under this id had another size: start a fresh one
                return@repeat
            }
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
        // An open that never finished (the process died while reading it) is unreadable for good: never tried again.
        val trying = ids(KEY_TRYING)
        if (trying.isNotEmpty()) {
            trying.forEach { remember(KEY_BAD, "$it\t$CANT_READ", now = true) }
            prefs.edit().remove(KEY_TRYING).commit()
        }
        val done = ids(KEY_DONE)
        val bad = problems()
        val usable = listed.filter { SEND_ID.matches(it.sendId) && it.count in 1..SyncCrypto.MAX_PARTS && it.sendId !in done }
            .distinctBy { it.sendId }
        val names = store.roster().associate { it.deviceId to it.displayName }
        val out = usable.map { l ->
            val problem = bad[l.sendId] ?: TOO_BIG_TO_OPEN.takeIf { l.bytes > MAX_SEND_BYTES }
            IncomingSend(l.sendId, l.from, names[l.from] ?: DEFAULT_NAME, l.serverAt, l.bytes, problem)
        }
        opened.keys.retainAll(usable.map { it.sendId }.toSet())
        setSends(out.sortedByDescending { it.serverAt })
    }

    /**
     * Downloads, opens and reads a send, for Add or Choose. Bounded and crash-safe (see the class comment): whatever goes wrong
     * while it is read leaves it marked unreadable, so it is never tried again.
     */
    suspend fun open(sendId: String): OpenedSend = withContext(Dispatchers.IO) {
        lock.withLock {
            opened[sendId]?.let { return@withLock it }
            val send = _sends.value.firstOrNull { it.sendId == sendId } ?: return@withLock OpenedSend.Failed(GONE_SEND)
            send.problem?.let { return@withLock OpenedSend.Failed(it) }
            if (send.bytes > MAX_SEND_BYTES) return@withLock fail(send, TOO_BIG_TO_OPEN)
            try {
                openLocked(send)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // An OutOfMemoryError, a StackOverflowError, a store failure while reading it: this send is done for.
                Log.w(TAG, "send ${sendId.take(6)}… can't be read: ${t.javaClass.simpleName}")
                fail(send, CANT_READ)
            }
        }
    }

    private suspend fun openLocked(send: IncomingSend): OpenedSend {
        val id = store.identity() ?: return OpenedSend.Failed(NOT_LINKED)
        val sp = store.space() ?: return OpenedSend.Failed(NOT_LINKED)
        val auth = DeviceAuth(id.deviceId, id.token)
        val listing = when (val r = api.inbox(auth, sp.spaceId)) {
            is SyncResult.Ok -> r.value.sends.firstOrNull { it.sendId == send.sendId } ?: return OpenedSend.Failed(GONE_SEND)
            is SyncResult.Error -> return OpenedSend.Failed(if (r.revoked) NOT_LINKED else WebLinkCopy.OFFLINE)
            is SyncResult.Unreachable -> return OpenedSend.Failed(WebLinkCopy.OFFLINE)
        }
        if (listing.count !in 1..SyncCrypto.MAX_PARTS) return fail(send, CANT_READ)
        if (listing.bytes > MAX_SEND_BYTES) return fail(send, TOO_BIG_TO_OPEN)
        val envs = ArrayList<SyncEnvelope>(listing.count)
        var sealed = 0L
        for (i in 0 until listing.count) {
            when (val r = api.inboxPart(auth, sp.spaceId, send.sendId, i)) {
                is SyncResult.Ok -> {
                    if (r.value.count != listing.count) return fail(send, CANT_READ)
                    sealed += r.value.env.c.length
                    if (sealed > MAX_SEND_BYTES) return fail(send, TOO_BIG_TO_OPEN)
                    envs += r.value.env
                }
                is SyncResult.Error -> return OpenedSend.Failed(if (r.revoked) NOT_LINKED else WebLinkCopy.OFFLINE)
                is SyncResult.Unreachable -> return OpenedSend.Failed(WebLinkCopy.OFFLINE)
            }
        }
        val k = sp.keyFor(envs[0].e)
        if (k == null) {
            // Sealed under a key this phone hasn't caught up with yet: the link catches up, then it can be tried again.
            if (envs[0].e > sp.epoch) {
                repo.refresh()
                return OpenedSend.Failed(TRY_LATER)
            }
            return fail(send, CANT_READ)
        }
        // On disk before anything is inflated or parsed, so a crash here is never repeated on the next launch.
        remember(KEY_TRYING, send.sendId, now = true)
        val result = try {
            val text = SyncCrypto.openParts(
                SyncCrypto.dataKey(k, sp.spaceId), sp.spaceId, { i, n -> SyncCrypto.Place.inbox(id.deviceId, send.sendId, i, n) }, envs, MAX_SEND_PLAIN,
            )
            val content = WebLibraryReader.read(text, System.currentTimeMillis(), WebLibraryReader.SEND_SHAPE)
            OpenedSend.Ok(send, content).also { opened[send.sendId] = it }
        } catch (e: SyncCryptoException) {
            Log.w(TAG, "send ${send.sendId.take(6)}… didn't open: ${e.message}")
            fail(send, if (e.message == "too big") TOO_BIG_TO_OPEN else CANT_READ)
        } catch (e: WebLibraryReadException) {
            fail(
                send,
                when (e.message) {
                    WebLibraryReader.NEWER -> WebLinkCopy.UPDATE_APP
                    WebLibraryReader.TOO_BIG -> TOO_BIG_TO_OPEN
                    else -> CANT_READ
                },
            )
        }
        forgetId(KEY_TRYING, send.sendId)
        return result
    }

    /** Marks [send] unreadable for good (on disk) and shows it as such. */
    private fun fail(send: IncomingSend, problem: String): OpenedSend.Failed {
        remember(KEY_BAD, "${send.sendId}\t$problem", now = true)
        forgetId(KEY_TRYING, send.sendId)
        setSends(_sends.value.map { if (it.sendId == send.sendId) it.copy(problem = problem) else it })
        return OpenedSend.Failed(problem)
    }

    /** Sends known to be unreadable, with why. */
    private fun problems(): Map<String, String> = ids(KEY_BAD).associate { line ->
        val parts = line.split('\t', limit = 2)
        parts[0] to parts.getOrElse(1) { CANT_READ }
    }

    /** Add: opens the send (unless it is already), merges it as a file import does (only [selection]), then deletes it on the server. */
    suspend fun add(sendId: String, selection: ImportSelection = ImportSelection.ALL): AddOutcome {
        val o = when (val r = open(sendId)) {
            is OpenedSend.Ok -> r
            is OpenedSend.Failed -> return AddOutcome.Failed(r.message)
        }
        val result = importer.import(o.content, selection, origin = o.send.from)
        remember(KEY_DONE, sendId)
        forget(sendId)
        deleteOnServer(sendId)
        return AddOutcome.Added(result)
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

    private suspend fun forget(sendId: String) {
        lock.withLock {
            opened.remove(sendId)
            setSends(_sends.value.filter { it.sendId != sendId })
        }
    }

    private suspend fun deleteOnServer(sendId: String) = guard(Unit) {
        val id = store.identity() ?: return@guard
        val sp = store.space() ?: return@guard
        api.deleteInbox(DeviceAuth(id.deviceId, id.token), sp.spaceId, sendId)
    }

    // -------------------------------------------------------------------------------------------- plumbing

    private fun ids(key: String): Set<String> =
        prefs.getString(key, "").orEmpty().split('\n').filter { it.isNotEmpty() }.mapTo(LinkedHashSet()) { if (key == KEY_BAD) it else it.substringBefore('\t') }

    /** Adds [entry] (`<sendId>` or `<sendId>\t<why>`) under [key]; [now]: on disk before this returns (a crash right after keeps it). */
    private fun remember(key: String, entry: String, now: Boolean = false) {
        val id = entry.substringBefore('\t')
        val list = prefs.getString(key, "").orEmpty().split('\n').filter { it.isNotEmpty() && it.substringBefore('\t') != id } + entry
        val e = prefs.edit().putString(key, list.takeLast(MAX_REMEMBERED).joinToString("\n"))
        if (now) e.commit() else e.apply()
    }

    private fun forgetId(key: String, sendId: String) {
        val list = prefs.getString(key, "").orEmpty().split('\n').filter { it.isNotEmpty() && it.substringBefore('\t') != sendId }
        prefs.edit().putString(key, list.joinToString("\n")).commit()
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
        private const val KEY_TRYING = "trying"
        private const val KEY_BAD = "bad"

        /** A send's stored size this phone downloads at most: 16 parts of 1 MiB (sync-v1 §5.5). */
        const val MAX_SEND_BYTES = 16L * 1024 * 1024

        /** A send's JSON this phone inflates at most, to read it in memory (sync-v1 §8). */
        const val MAX_SEND_PLAIN = 16 * 1024 * 1024
        private const val MAX_REMEMBERED = 100
        private const val CHECK_GAP_MS = 30_000L
        private const val DEFAULT_NAME = "Stash on the web"
        private val SEND_ID = Regex("^[A-Za-z0-9_-]{8,64}$")

        const val TOO_BIG = "Too big to send. Save it as a file instead."
        const val NOT_LINKED = "This phone isn't linked any more."
        const val GONE = "That browser isn't linked any more."
        const val TRY_AGAIN = "Couldn't send. Try again."
        const val CANT_READ = "This send can't be read."
        /** Every refusal of a send that is too big or too dense to read says what any unreadable send says (sync-v1 §5.5). */
        const val TOO_BIG_TO_OPEN = CANT_READ
        const val GONE_SEND = "That send isn't here any more."
        const val TRY_LATER = "This send can't be opened yet. Try again in a moment."

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

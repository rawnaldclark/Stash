package com.stash.core.data.weblink.handoff

import android.util.Log
import com.stash.core.data.weblink.DeviceAuth
import com.stash.core.data.weblink.StoredSlot
import com.stash.core.data.weblink.SyncApi
import com.stash.core.data.weblink.SyncCrypto
import com.stash.core.data.weblink.SyncCryptoException
import com.stash.core.data.weblink.SyncErrorCode
import com.stash.core.data.weblink.SyncResult
import com.stash.core.data.weblink.WebLinkRepository
import com.stash.core.data.weblink.store.LinkedSpace
import com.stash.core.data.weblink.store.WebLinkStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How a publish went. Only [Sent] means the other devices can see it. */
sealed interface PublishOutcome {
    data class Sent(val serverAt: Long) : PublishOutcome
    data object NotLinked : PublishOutcome

    /** Never reached the server: worth one retry when the network comes back. */
    data object Offline : PublishOutcome

    /** `429`: wait [retryAfterMs] before writing again. */
    data class RateLimited(val retryAfterMs: Long) : PublishOutcome

    /** The server said no for good (space full, too large, …): dropped. */
    data class Refused(val code: String) : PublishOutcome
}

/** Another device's published state, opened under the data key. [serverAt] is the server's stamp of the slot. */
data class PublishedState(val device: String, val deviceName: String, val serverAt: Long, val now: StashNow)

/**
 * What `GET …/slots/now` held: the states this phone could open, its own slot's stamp ([ownServerAt]; the reader's "own last
 * playback" in server time), and [updateNeeded] when a newer format arrived ("Update Stash to keep syncing").
 */
data class NowRead(
    val me: String,
    val serverTime: Long,
    val states: List<PublishedState>,
    val ownServerAt: Long?,
    val updateNeeded: Boolean = false,
)

/** The handoff side of the link (spec §8, sync-v1 §5.1): seals and writes this phone's slots, reads and opens the others'. */
interface HandoffChannel {
    /** This phone is in a space (a cheap local read: no network). */
    suspend fun linked(): Boolean

    suspend fun publish(now: StashNow, queue: StashQueue?): PublishOutcome

    /** Null when not linked or the server can't be reached. */
    suspend fun readNow(): NowRead?

    /** [device]'s queue, or null (none published, unreadable, offline). */
    suspend fun readQueue(device: String): StashQueue?
}

/**
 * [HandoffChannel] over the stash-sync Worker. A `409 epoch` / `rotation_due` or `401 revoked` goes through
 * [WebLinkRepository.refresh] (catch up on the key, rotate, or unlink) and the write is tried once more; a `429` is handed back
 * with its wait, so the publisher holds off. One call at a time, so a queue and its `now` land in order.
 */
@Singleton
class HandoffSync @Inject constructor(
    private val api: SyncApi,
    private val store: WebLinkStore,
    private val repo: WebLinkRepository,
) : HandoffChannel {
    private val lock = Mutex()

    override suspend fun linked(): Boolean = store.space() != null

    override suspend fun publish(now: StashNow, queue: StashQueue?): PublishOutcome = lock.withLock {
        val nowText = HandoffWire.nowJson(now)
        val queueText = queue?.let(HandoffWire::queueJson)
        var queueSent = queueText == null
        repeat(2) { attempt ->
            val id = store.identity() ?: return@withLock PublishOutcome.NotLinked
            val sp = store.space() ?: return@withLock PublishOutcome.NotLinked
            val auth = DeviceAuth(id.deviceId, id.token)
            val key = SyncCrypto.dataKey(sp.k, sp.spaceId)
            // The queue first, so a reader that sees the new `now` finds the queue it names.
            if (!queueSent) {
                val env = SyncCrypto.seal(key, sp.spaceId, sp.epoch, SyncCrypto.Place.queue(id.deviceId), queueText!!)
                when (val o = outcome(api.putSlot(auth, sp.spaceId, SLOT_QUEUE, env))) {
                    null -> queueSent = true
                    RETRY -> if (attempt == 0 && refreshed()) return@repeat else return@withLock PublishOutcome.Refused("refresh")
                    else -> return@withLock o
                }
            }
            val env = SyncCrypto.seal(key, sp.spaceId, sp.epoch, SyncCrypto.Place.now(id.deviceId), nowText)
            when (val r = api.putSlot(auth, sp.spaceId, SLOT_NOW, env)) {
                is SyncResult.Ok -> return@withLock PublishOutcome.Sent(r.value.serverAt)
                else -> when (val o = outcome(r)) {
                    RETRY -> if (attempt == 0 && refreshed()) return@repeat else return@withLock PublishOutcome.Refused("refresh")
                    null -> Unit
                    else -> return@withLock o
                }
            }
        }
        PublishOutcome.Refused("unfinished")
    }

    override suspend fun readNow(): NowRead? = lock.withLock {
        repeat(2) { attempt ->
            val id = store.identity() ?: return@withLock null
            val sp = store.space() ?: return@withLock null
            val read = when (val r = api.nowSlots(DeviceAuth(id.deviceId, id.token), sp.spaceId)) {
                is SyncResult.Ok -> r.value
                is SyncResult.Error -> {
                    if (attempt == 0 && needsRefresh(r) && refreshed()) return@repeat
                    return@withLock null
                }
                is SyncResult.Unreachable -> return@withLock null
            }
            // A slot sealed under a key this phone doesn't hold yet: someone rotated. Catch up once, then read again.
            if (attempt == 0 && read.slots.any { it.env.e > sp.epoch } && refreshed()) return@repeat
            val names = store.roster().associate { it.deviceId to it.displayName }
            var newer = false
            val states = read.slots.filter { it.device != id.deviceId }.mapNotNull { slot ->
                try {
                    PublishedState(slot.device, names[slot.device] ?: DEFAULT_NAME, slot.serverAt, HandoffWire.readNow(open(sp, SyncCrypto.Place.now(slot.device), slot)))
                } catch (e: HandoffWireException) {
                    if (e.newer) newer = true
                    Log.w(TAG, "now slot of ${slot.device.take(6)}… unreadable: ${e.message}")
                    null
                } catch (e: SyncCryptoException) {
                    Log.w(TAG, "now slot of ${slot.device.take(6)}… didn't open: ${e.message}")
                    null
                }
            }
            val own = read.slots.firstOrNull { it.device == id.deviceId }?.serverAt
            return@withLock NowRead(id.deviceId, read.serverTime, states, own, newer)
        }
        null
    }

    override suspend fun readQueue(device: String): StashQueue? = lock.withLock {
        val id = store.identity() ?: return@withLock null
        val sp = store.space() ?: return@withLock null
        val slot = when (val r = api.queueSlot(DeviceAuth(id.deviceId, id.token), sp.spaceId, device)) {
            is SyncResult.Ok -> r.value
            else -> return@withLock null
        }
        try {
            HandoffWire.readQueue(open(sp, SyncCrypto.Place.queue(device), slot))
        } catch (e: HandoffWireException) {
            Log.w(TAG, "queue of ${device.take(6)}… unreadable: ${e.message}")
            null
        } catch (e: SyncCryptoException) {
            Log.w(TAG, "queue of ${device.take(6)}… didn't open: ${e.message}")
            null
        }
    }

    private fun open(sp: LinkedSpace, place: String, slot: StoredSlot): String {
        val k = sp.keyFor(slot.env.e) ?: throw SyncCryptoException("no key for epoch ${slot.env.e}")
        return SyncCrypto.open(SyncCrypto.dataKey(k, sp.spaceId), sp.spaceId, place, slot.env)
    }

    private fun needsRefresh(r: SyncResult.Error) =
        r.revoked || r.code == SyncErrorCode.EPOCH || r.code == SyncErrorCode.ROTATION_DUE

    /** Catches up on the key, rotates when one is due, or unlinks (a revoked phone). True when still linked. */
    private suspend fun refreshed(): Boolean {
        repo.refresh()
        return store.space() != null
    }

    /** Null for success; [RETRY] for a refresh-and-retry; else the outcome to hand back. */
    private fun outcome(r: SyncResult<*>): PublishOutcome? = when (r) {
        is SyncResult.Ok -> null
        is SyncResult.Unreachable -> PublishOutcome.Offline
        is SyncResult.Error -> when {
            needsRefresh(r) -> RETRY
            r.status == 429 -> PublishOutcome.RateLimited(((r.retryAfterSeconds ?: 60L).coerceIn(1L, 3600L)) * 1000L)
            r.status >= 500 -> PublishOutcome.Offline
            else -> PublishOutcome.Refused(r.code)
        }
    }

    private companion object {
        const val TAG = "WebLinkHandoff"
        const val SLOT_NOW = "now"
        const val SLOT_QUEUE = "queue"
        const val DEFAULT_NAME = "Stash on the web"

        /** Marker outcome: refresh the link and try once more. */
        val RETRY = PublishOutcome.Refused("retry")
    }
}

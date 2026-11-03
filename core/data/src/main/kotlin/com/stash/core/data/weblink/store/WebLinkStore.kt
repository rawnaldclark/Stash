package com.stash.core.data.weblink.store

import java.nio.ByteBuffer
import java.security.PrivateKey
import kotlinx.coroutines.flow.StateFlow

/**
 * The link state couldn't be read just now (a Keystore or provider error, not a missing key). Callers keep the link and show
 * the problem; only a key that is definitely gone ([WebLinkStore.identity] returns null) unlinks.
 */
class LinkStoreUnavailable(cause: Throwable) : Exception("link state unreadable: ${cause.javaClass.simpleName}", cause)

/** This device as the sync service knows it: id, token and long-term P-256 key (sync-v1 §1). */
class LinkIdentity(
    val deviceId: String,
    val token: ByteArray,
    val devicePub: ByteArray,
    val devicePriv: PrivateKey,
    val name: String,
)

/**
 * The space this phone is in and its keys. [prevK] (of [prevEpoch]) and the [older] ones are kept after a rotation (sync-v1
 * §3.5), so labels, log batches and a snapshot still sealed under an old key stay readable. More than one: the server keeps
 * old-epoch batches for a device several rotations behind, and two rotations before a mirror run (Settings › remove two
 * devices) would otherwise turn a third device's unread batches into gaps no remaining device can close, freezing the log
 * behind `409 compact` for every device (audit A5). The newest [KEPT_EPOCHS] previous keys are kept.
 */
class LinkedSpace(
    val spaceId: String,
    val epoch: Int,
    val k: ByteArray,
    val prevEpoch: Int = 0,
    val prevK: ByteArray? = null,
    /** Keys of epochs before [prevEpoch], by epoch. */
    val older: Map<Int, ByteArray> = emptyMap(),
) {
    /** K of [e], when this phone holds it. */
    fun keyFor(e: Int): ByteArray? = when {
        e == epoch -> k
        e == prevEpoch && e > 0 -> prevK
        e > 0 -> older[e]
        else -> null
    }

    /** After a rotation to [next] at [nextEpoch]: the current key becomes the previous one, and the oldest beyond the bound go. */
    fun advanced(next: ByteArray, nextEpoch: Int): LinkedSpace {
        val kept = if (prevK != null && prevEpoch > 0) older + (prevEpoch to prevK) else older
        return LinkedSpace(spaceId, nextEpoch, next, epoch, k, kept.filterKeys { it < epoch && it >= nextEpoch - KEPT_EPOCHS })
    }

    /**
     * [prevK] and [older] as one blob, for a store that seals the previous key in one column: [prevK] alone while there are no
     * older ones (the format before them, so a store written then reads back the same), else [prevK] followed by a 4-byte
     * big-endian epoch and the key for each older one.
     */
    fun previousKeys(): ByteArray? {
        val p = prevK ?: return null
        if (older.isEmpty()) return p
        val buf = ByteBuffer.allocate(p.size + older.size * (4 + KEY_BYTES)).put(p)
        for ((e, key) in older.toSortedMap()) buf.putInt(e).put(key)
        return buf.array()
    }

    companion object {
        /** How many previous keys are kept: a device further behind walks the key envelopes from its own key anyway. */
        const val KEPT_EPOCHS = 8

        private const val KEY_BYTES = 32

        /** A [LinkedSpace] from its parts as a store keeps them; [previous] is what [previousKeys] wrote (or a lone old key). */
        fun of(spaceId: String, epoch: Int, k: ByteArray, prevEpoch: Int, previous: ByteArray?): LinkedSpace {
            if (previous == null || previous.size <= KEY_BYTES || (previous.size - KEY_BYTES) % (4 + KEY_BYTES) != 0) {
                return LinkedSpace(spaceId, epoch, k, prevEpoch, previous)
            }
            val buf = ByteBuffer.wrap(previous)
            val prev = ByteArray(KEY_BYTES).also { buf.get(it) }
            val older = HashMap<Int, ByteArray>()
            while (buf.hasRemaining()) {
                val e = buf.getInt()
                older[e] = ByteArray(KEY_BYTES).also { buf.get(it) }
            }
            return LinkedSpace(spaceId, epoch, k, prevEpoch, prev, older)
        }
    }
}

/** One verified member (sync-v1 §3.6): id, type, the name in its label, its pinned key, and this phone's nickname for it. */
data class RosterEntry(
    val deviceId: String,
    val type: String,
    val labelName: String,
    val pub: ByteArray,
    val nickname: String? = null,
) {
    val displayName: String get() = nickname?.takeIf { it.isNotBlank() } ?: labelName
}

/**
 * This phone's local link state (spec §4.4), decrypted on read and sealed on write. Never synced, never in a backup.
 * [AndroidWebLinkStore] keeps it in `stash_sync.db`; tests use an in-memory one.
 */
interface WebLinkStore {
    /** Null when there is none, or its device key is definitely gone. Throws [LinkStoreUnavailable] on a passing failure. */
    suspend fun identity(): LinkIdentity?

    /** This device's id and token without its key, for a last `DELETE …/devices/me` when the key is gone (null if unreadable). */
    suspend fun deviceToken(): Pair<String, ByteArray>? = null

    /** Makes this device's id, token and long-term key (the first link); replaces any earlier identity. */
    suspend fun createIdentity(name: String): LinkIdentity

    suspend fun setName(name: String)

    suspend fun space(): LinkedSpace?

    /** Joins (or updates) the space: id, epoch and keys. */
    suspend fun saveSpace(space: LinkedSpace)

    suspend fun roster(): List<RosterEntry>

    suspend fun saveRoster(entries: List<RosterEntry>)

    /**
     * Goes up each time the roster may have changed: saved ([saveRoster]), or gone with the rest ([wipe], [createIdentity]).
     * A reader that follows the roster (the remote, which needs a socket only while a browser is linked) re-reads [roster] on
     * each value. Every roster write goes through those three, so this is every change: a browser linked, renamed or removed,
     * a refresh or rotation that rewrote it, an unlink.
     */
    val rosterVersion: StateFlow<Long>

    /** Unlinked: forgets the space, the roster, the identity and the device key. The library is never touched. */
    suspend fun wipe()
}

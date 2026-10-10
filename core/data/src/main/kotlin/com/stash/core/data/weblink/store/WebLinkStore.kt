package com.stash.core.data.weblink.store

import java.security.PrivateKey

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
 * The space this phone is in and its keys. [prevK] (of [prevEpoch]) is kept after a rotation until a snapshot under the new
 * key lands (sync-v1 §3.5), so labels and data still sealed under the old key stay readable meanwhile.
 */
class LinkedSpace(
    val spaceId: String,
    val epoch: Int,
    val k: ByteArray,
    val prevEpoch: Int = 0,
    val prevK: ByteArray? = null,
) {
    /** K of [e], when this phone holds it. */
    fun keyFor(e: Int): ByteArray? = when {
        e == epoch -> k
        e == prevEpoch && e > 0 -> prevK
        else -> null
    }

    /** After a rotation to [next] at [nextEpoch]: the current key becomes the previous one. */
    fun advanced(next: ByteArray, nextEpoch: Int) = LinkedSpace(spaceId, nextEpoch, next, epoch, k)
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

    /** Unlinked: forgets the space, the roster, the identity and the device key. The library is never touched. */
    suspend fun wipe()
}

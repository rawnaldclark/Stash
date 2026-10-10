package com.stash.core.data.weblink

import android.util.Log
import com.stash.core.data.weblink.store.LinkIdentity
import com.stash.core.data.weblink.store.LinkedSpace
import com.stash.core.data.weblink.store.RosterEntry
import com.stash.core.data.weblink.store.WebLinkStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The phone's side of a link with Stash on the web, once paired (spec §2.6, §5.2, §12; sync-v1 §3.5, §3.6): the device list
 * (labels opened and keys pinned here), renaming, removing a device (then rotating the key), unlinking everything, walking
 * key envelopes after someone else rotated, and rotating when the server says one is due. Every run holds one lock, so two
 * actions never interleave (spec §7: runs are serialised per device). Pairing itself is [PairingSession].
 */
@Singleton
class WebLinkRepository @Inject constructor(
    private val api: SyncApi,
    private val store: WebLinkStore,
) {
    private val lock = Mutex()
    private val _status = MutableStateFlow<WebLinkStatus>(WebLinkStatus.Loading)
    val status: StateFlow<WebLinkStatus> = _status.asStateFlow()

    /** A one-time notice ("Unlinked from Chrome on Windows.") for the screen to show once; null when shown. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun noticeShown() {
        _notice.value = null
    }

    /** Reads the space: device list, labels, keys after a rotation elsewhere, and the rotation a removal left due. */
    suspend fun refresh(): WebLinkResult = lock.withLock { refreshLocked() }

    /**
     * Renames a device. This phone: its label is re-sealed and written (`PUT …/devices/me/label`), so every device sees the new
     * name. Another device: a nickname kept on this phone, since only a device can rewrite its own label.
     */
    suspend fun rename(deviceId: String, name: String): WebLinkResult = lock.withLock {
        val clean = cleanName(name) ?: return@withLock WebLinkResult.Failed("Type a name.")
        val id = store.identity() ?: return@withLock notLinked()
        if (deviceId != id.deviceId) {
            store.saveRoster(store.roster().map { if (it.deviceId == deviceId) it.copy(nickname = clean) else it })
            return@withLock refreshLocked()
        }
        repeat(3) {
            val sp = store.space() ?: return@withLock notLinked()
            val label = SyncKeys.Label(clean, TYPE_PHONE, id.deviceId, id.devicePub)
            val env = SyncCrypto.seal(SyncCrypto.dataKey(sp.k, sp.spaceId), sp.spaceId, sp.epoch, SyncCrypto.Place.label(id.deviceId), SyncKeys.labelJson(label))
            when (val r = api.putMyLabel(auth(id), sp.spaceId, env)) {
                is SyncResult.Ok -> {
                    store.setName(clean)
                    return@withLock refreshLocked()
                }
                is SyncResult.Error -> {
                    if (r.revoked) return@withLock unlinked()
                    // A rotation is due (a device was removed) or happened elsewhere: catch up, then write under the new key.
                    if (r.code != SyncErrorCode.ROTATION_DUE && r.code != SyncErrorCode.EPOCH) return@withLock WebLinkResult.Failed(r.userMessage())
                    val res = refreshLocked()
                    if (res !is WebLinkResult.Ok) return@withLock res
                }
                is SyncResult.Unreachable -> return@withLock WebLinkResult.Failed(WebLinkCopy.OFFLINE)
            }
        }
        WebLinkResult.Failed(WebLinkCopy.UNFINISHED)
    }

    /**
     * Removes a device: cut off at once on the server, then this phone rotates the key so the removed device can't read
     * what comes next (spec §2.6, §5.2). Removing this phone itself is [unlinkEverything]'s job, not this.
     */
    suspend fun remove(deviceId: String): WebLinkResult = lock.withLock {
        val id = store.identity() ?: return@withLock notLinked()
        val sp = store.space() ?: return@withLock notLinked()
        if (deviceId == id.deviceId) return@withLock WebLinkResult.Failed(WebLinkCopy.UNFINISHED)
        when (val r = api.removeDevice(auth(id), sp.spaceId, deviceId)) {
            is SyncResult.Ok -> Unit
            is SyncResult.Error -> when {
                r.revoked -> return@withLock unlinked()
                r.code == SyncErrorCode.NOT_FOUND -> Unit // already gone
                else -> return@withLock WebLinkResult.Failed(r.userMessage())
            }
            is SyncResult.Unreachable -> return@withLock WebLinkResult.Failed(WebLinkCopy.OFFLINE)
        }
        store.saveRoster(store.roster().filter { it.deviceId != deviceId })
        refreshLocked() // the removal left a rotation due: refresh rotates
    }

    /** Unlink everything (spec §2.6): deletes the space on the server; every device is unlinked; no library changes. */
    suspend fun unlinkEverything(): WebLinkResult = lock.withLock {
        val id = store.identity()
        val sp = store.space()
        if (id == null || sp == null) {
            store.wipe()
            _status.value = WebLinkStatus.NotLinked
            return@withLock WebLinkResult.Ok
        }
        when (val r = api.deleteSpace(auth(id), sp.spaceId)) {
            is SyncResult.Ok -> Unit
            is SyncResult.Error -> if (!r.revoked) return@withLock WebLinkResult.Failed(r.userMessage())
            is SyncResult.Unreachable -> return@withLock WebLinkResult.Failed(WebLinkCopy.OFFLINE)
        }
        store.wipe()
        _status.value = WebLinkStatus.NotLinked
        WebLinkResult.Ok
    }

    // -------------------------------------------------------------------------------------------- shared with pairing

    /** Runs [block] under this repository's lock (pairing's final step: join or create, then save). */
    internal suspend fun <T> locked(block: suspend () -> T): T = lock.withLock { block() }

    /** [refresh] for a caller already holding the lock. */
    internal suspend fun refreshLocked(): WebLinkResult {
        val id = store.identity()
        val sp = store.space()
        if (id == null || sp == null) {
            if (sp != null) store.wipe() // a space whose device key can't be read is a dead link
            _status.value = WebLinkStatus.NotLinked
            return WebLinkResult.Ok
        }
        var rotated = false
        while (true) {
            val info = when (val r = api.space(auth(id), sp.spaceId)) {
                is SyncResult.Ok -> r.value
                is SyncResult.Error -> return if (r.revoked) unlinked() else problem(r.userMessage())
                is SyncResult.Unreachable -> return problem(WebLinkCopy.OFFLINE)
            }
            val current = when (val c = catchUp(id, info.epoch)) {
                is CatchUp.Done -> c.space
                is CatchUp.Lost -> return c.result
            }
            val (devices, roster) = verify(id, current, info, store.roster().associateBy { it.deviceId })
            store.saveRoster(roster)
            if (info.rotationDue && !rotated) {
                rotated = true
                when (val r = rotateLocked(id)) {
                    WebLinkResult.Ok -> continue // read the space again under the new key
                    else -> {
                        publish(devices, info, (r as? WebLinkResult.Failed)?.message)
                        return r
                    }
                }
            }
            publish(devices, info, null)
            return WebLinkResult.Ok
        }
    }

    /**
     * Walks the key envelopes from this phone's epoch up to [serverEpoch], one epoch at a time, each proven under the key
     * before it (sync-v1 §3.5). A missing step or a forged one means this phone links again.
     */
    internal suspend fun catchUp(id: LinkIdentity, serverEpoch: Int): CatchUp {
        var sp = store.space() ?: return CatchUp.Lost(notLinked())
        while (sp.epoch < serverEpoch) {
            val next = sp.epoch + 1
            val env = when (val r = api.key(auth(id), sp.spaceId, next)) {
                is SyncResult.Ok -> r.value.ct
                is SyncResult.Error -> return CatchUp.Lost(
                    when {
                        r.revoked -> unlinked()
                        // No envelope for this phone: it was left out of a rotation. Treated as removed (spec §12).
                        r.code == SyncErrorCode.NO_KEY -> unlinked(selfRemove = true)
                        else -> problem(r.userMessage())
                    },
                )
                is SyncResult.Unreachable -> return CatchUp.Lost(problem(WebLinkCopy.OFFLINE))
            }
            val opened = try {
                SyncKeys.openKeyEnvelope(env, sp.spaceId, id.deviceId, id.devicePriv, id.devicePub, sp.k, sp.epoch)
            } catch (e: SyncCryptoException) {
                Log.w(TAG, "key envelope for epoch $next refused: ${e.message}")
                return CatchUp.Lost(problem(WebLinkCopy.CANT_READ))
            }
            sp = sp.advanced(opened.k, opened.epoch)
            store.saveSpace(sp)
        }
        return CatchUp.Done(sp)
    }

    internal sealed interface CatchUp {
        class Done(val space: LinkedSpace) : CatchUp
        class Lost(val result: WebLinkResult) : CatchUp
    }

    /**
     * Opens every member's label (under the data key of its own epoch), checks it names that device, and pins its key
     * (sync-v1 §3.6). Returns the list to show and the roster to keep: verified members with their pinned keys; a member
     * whose label carries another key keeps its old pin (and shows "Key changed").
     */
    internal fun verify(
        id: LinkIdentity,
        sp: LinkedSpace,
        info: SpaceInfo,
        pinned: Map<String, RosterEntry>,
    ): Pair<List<LinkedDevice>, List<RosterEntry>> {
        val devices = mutableListOf<LinkedDevice>()
        val roster = mutableListOf<RosterEntry>()
        for (d in info.devices) {
            if (d.id == id.deviceId) {
                devices += LinkedDevice(d.id, d.type, id.name, isMe = true, trust = DeviceTrust.VERIFIED, lastSeenAt = d.lastSeenAt)
                continue
            }
            val known = pinned[d.id]
            val label = openLabel(sp, d)
            val trust = when {
                label == null -> DeviceTrust.NOT_VERIFIED
                known != null && !SyncCrypto.sameBytes(known.pub, label.pub) -> DeviceTrust.KEY_CHANGED
                else -> DeviceTrust.VERIFIED
            }
            when {
                trust == DeviceTrust.VERIFIED && label != null ->
                    roster += RosterEntry(d.id, label.type, label.name, known?.pub ?: label.pub, known?.nickname)
                known != null -> roster += known
            }
            val name = known?.nickname?.takeIf { it.isNotBlank() }
                ?: label?.name?.takeIf { trust == DeviceTrust.VERIFIED }
                ?: known?.labelName
                ?: if (d.type == TYPE_PHONE) "Phone" else "Browser"
            devices += LinkedDevice(d.id, d.type, name, isMe = false, trust = trust, lastSeenAt = d.lastSeenAt)
        }
        return devices to roster
    }

    private fun openLabel(sp: LinkedSpace, d: SpaceDevice): SyncKeys.Label? {
        val env = d.labelCt ?: return null
        val k = sp.keyFor(env.e) ?: return null
        return try {
            val label = SyncKeys.readLabel(SyncCrypto.open(SyncCrypto.dataKey(k, sp.spaceId), sp.spaceId, SyncCrypto.Place.label(d.id), env), d.id)
            label.takeIf { it.type == d.type }
        } catch (e: SyncCryptoException) {
            null
        }
    }

    /**
     * Rotates the key (spec §5.2, sync-v1 §3.5): first removes every member it can't seal to (no label it opened, or a
     * changed key), then seals a fresh K for the next epoch to each remaining member's pinned key, proven under the current
     * K, re-seals their labels and the mirror config under it, and posts it all in one call. A changed device set is
     * re-read and retried; another device rotating first is caught up with.
     */
    internal suspend fun rotateLocked(id: LinkIdentity): WebLinkResult {
        repeat(MAX_ROTATE_TRIES) {
            val sp0 = store.space() ?: return notLinked()
            val info = when (val r = api.space(auth(id), sp0.spaceId)) {
                is SyncResult.Ok -> r.value
                is SyncResult.Error -> return if (r.revoked) unlinked() else WebLinkResult.Failed(r.userMessage())
                is SyncResult.Unreachable -> return WebLinkResult.Failed(WebLinkCopy.OFFLINE)
            }
            val sp = when (val c = catchUp(id, info.epoch)) {
                is CatchUp.Done -> c.space
                is CatchUp.Lost -> return c.result
            }
            if (!info.rotationDue) return WebLinkResult.Ok // someone else rotated meanwhile
            val (devices, roster) = verify(id, sp, info, store.roster().associateBy { it.deviceId })
            store.saveRoster(roster)
            val unsealable = devices.filter { !it.isMe && it.trust != DeviceTrust.VERIFIED }
            if (unsealable.isNotEmpty()) {
                for (d in unsealable) {
                    when (val r = api.removeDevice(auth(id), sp.spaceId, d.id)) {
                        is SyncResult.Error -> if (r.revoked) return unlinked() else if (r.code != SyncErrorCode.NOT_FOUND) return WebLinkResult.Failed(r.userMessage())
                        is SyncResult.Unreachable -> return WebLinkResult.Failed(WebLinkCopy.OFFLINE)
                        is SyncResult.Ok -> Unit
                    }
                }
                return@repeat // re-read the space without them
            }
            val byId = roster.associateBy { it.deviceId }
            val members = devices.map { it.id }.sorted()
            val next = sp.epoch + 1
            val newK = SyncCrypto.randomBytes(SyncCrypto.KEY_BYTES)
            val newData = SyncCrypto.dataKey(newK, sp.spaceId)
            val envelopes = members.associateWith { m ->
                val pub = if (m == id.deviceId) id.devicePub else byId.getValue(m).pub
                SyncKeys.sealKeyFor(newK, sp.spaceId, next, m, pub, members, sp.k)
            }
            val labels = members.associateWith { m ->
                val label = if (m == id.deviceId) {
                    SyncKeys.Label(id.name, TYPE_PHONE, m, id.devicePub)
                } else {
                    byId.getValue(m).let { SyncKeys.Label(it.labelName, it.type, m, it.pub) }
                }
                SyncCrypto.seal(newData, sp.spaceId, next, SyncCrypto.Place.label(m), SyncKeys.labelJson(label))
            }
            val config = when (val r = api.config(auth(id), sp.spaceId)) {
                is SyncResult.Ok -> r.value?.let { slot ->
                    // The config is re-sealed as it is: this phone doesn't read mirror settings yet (phase 6).
                    val k = sp.keyFor(slot.env.e) ?: return WebLinkResult.Failed(WebLinkCopy.CANT_READ)
                    val text = try {
                        SyncCrypto.open(SyncCrypto.dataKey(k, sp.spaceId), sp.spaceId, SyncCrypto.Place.CONFIG, slot.env)
                    } catch (e: SyncCryptoException) {
                        return WebLinkResult.Failed(WebLinkCopy.CANT_READ)
                    }
                    SyncCrypto.seal(newData, sp.spaceId, next, SyncCrypto.Place.CONFIG, text)
                }
                is SyncResult.Error -> return if (r.revoked) unlinked() else WebLinkResult.Failed(r.userMessage())
                is SyncResult.Unreachable -> return WebLinkResult.Failed(WebLinkCopy.OFFLINE)
            }
            when (val r = api.rotate(auth(id), sp.spaceId, RotateBody(next, envelopes, labels, config))) {
                is SyncResult.Ok -> {
                    store.saveSpace(sp.advanced(newK, next))
                    Log.i(TAG, "rotated to epoch $next for ${members.size} devices")
                    return WebLinkResult.Ok
                }
                is SyncResult.Error -> when {
                    r.revoked -> return unlinked()
                    // The device set changed, or another device rotated first: read the space again and go on from there.
                    r.code == SyncErrorCode.DEVICES_CHANGED || r.code == SyncErrorCode.EPOCH -> Unit
                    else -> return WebLinkResult.Failed(r.userMessage())
                }
                is SyncResult.Unreachable -> return WebLinkResult.Failed(WebLinkCopy.OFFLINE)
            }
        }
        return WebLinkResult.Failed(WebLinkCopy.UNFINISHED)
    }

    /** Deletes the local link state after a `401 revoked` (or a lost key): the library stays as it is. */
    internal suspend fun unlinked(selfRemove: Boolean = false): WebLinkResult {
        val first = store.roster().firstOrNull { it.type == TYPE_WEB }?.displayName ?: "Stash on the web"
        if (selfRemove) {
            val id = store.identity()
            val sp = store.space()
            if (id != null && sp != null) api.removeDevice(auth(id), sp.spaceId, "me") // best effort: free the slot
        }
        store.wipe()
        _status.value = WebLinkStatus.NotLinked
        val notice = WebLinkCopy.unlinkedFrom(first)
        _notice.value = notice
        return WebLinkResult.Unlinked(notice)
    }

    private fun notLinked(): WebLinkResult {
        _status.value = WebLinkStatus.NotLinked
        return WebLinkResult.Failed("This phone isn't linked.")
    }

    /** A failed refresh: the last list stays, with the problem; with none yet, the devices this phone knows. */
    private suspend fun problem(message: String): WebLinkResult {
        val s = _status.value
        _status.value = if (s is WebLinkStatus.Linked) s.copy(problem = message) else knownDevices(message)
        return WebLinkResult.Failed(message)
    }

    private suspend fun knownDevices(problem: String): WebLinkStatus {
        val id = store.identity() ?: return WebLinkStatus.NotLinked
        val sp = store.space() ?: return WebLinkStatus.NotLinked
        val me = LinkedDevice(id.deviceId, TYPE_PHONE, id.name, isMe = true, trust = DeviceTrust.VERIFIED, lastSeenAt = 0)
        val others = store.roster().map { LinkedDevice(it.deviceId, it.type, it.displayName, isMe = false, trust = DeviceTrust.VERIFIED, lastSeenAt = 0) }
        return WebLinkStatus.Linked(listOf(me) + others, sp.epoch, 0, problem)
    }

    private fun publish(devices: List<LinkedDevice>, info: SpaceInfo, problem: String?) {
        // This phone first, then the others in the order they joined (the server's order).
        _status.value = WebLinkStatus.Linked(devices.sortedByDescending { it.isMe }, info.epoch, info.serverTime, problem)
    }

    internal companion object {
        const val TAG = "WebLink"
        const val TYPE_PHONE = "phone"
        const val TYPE_WEB = "web"
        const val MAX_ROTATE_TRIES = 4

        fun auth(id: LinkIdentity) = DeviceAuth(id.deviceId, id.token)

        /** A label name: trimmed, 1–60 code points (sync-v1 §5.6). */
        fun cleanName(name: String): String? {
            val t = name.trim()
            if (t.isEmpty()) return null
            return t.substring(0, t.offsetByCodePoints(0, minOf(SyncKeys.MAX_LABEL, t.codePointCount(0, t.length))))
        }
    }
}

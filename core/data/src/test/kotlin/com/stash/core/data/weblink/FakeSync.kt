package com.stash.core.data.weblink

import com.stash.core.data.weblink.store.LinkIdentity
import com.stash.core.data.weblink.store.LinkedSpace
import com.stash.core.data.weblink.store.RosterEntry
import com.stash.core.data.weblink.store.WebLinkStore
import java.security.KeyPair
import java.security.interfaces.ECPublicKey
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A stash-sync Worker in memory, with the rules the phone depends on (MP3APK `infra/sync-worker`): one answer per code, the
 * phone's create/join refused with `409 no_reply` until the browser's reply, tokens checked, `rotationDue` after a removal,
 * `409 devices_changed` and `409 epoch` on rotate, key envelopes per device and epoch. [failNext] makes the next call of a
 * route answer an error instead.
 */
class FakeSyncServer : SyncApi {
    class Slot(val pairId: String, var browser: DeviceRecord) {
        var answer: PairAnswerBody? = null
        var reply: SyncEnvelope? = null
        var burned = false
    }

    class Dev(val id: String, val type: String, val tokenHash: String, val pub: String, var labelCt: SyncEnvelope?, var lastSeenAt: Long = 1_000)

    class Space(val id: String) {
        var epoch = 1
        var rotationDue = false
        val devices = LinkedHashMap<String, Dev>()
        val envelopes = HashMap<Pair<String, Int>, SyncEnvelope>()
        var config: ConfigSlot? = null
    }

    val slots = HashMap<String, Slot>()
    val spaces = HashMap<String, Space>()
    val calls = mutableListOf<String>()

    /** Called once the phone has answered a code (the browser's turn: it may reply, or sponsor and reply). */
    var onAnswered: (Slot) -> Unit = {}

    /** How many `204 pending` the reply long-poll gives before it looks at the slot. */
    var pendingPolls = 0

    private val forced = HashMap<String, ArrayDeque<SyncResult.Error>>()

    fun failNext(route: String, error: SyncResult.Error) {
        forced.getOrPut(route) { ArrayDeque() }.addLast(error)
    }

    private fun forced(route: String): SyncResult.Error? = forced[route]?.removeFirstOrNull()

    private fun err(status: Int, code: String, body: JsonObject? = null) = SyncResult.Error(status, code, code, body)

    private fun member(auth: DeviceAuth, spaceId: String): Pair<Space, Dev>? {
        val s = spaces[spaceId] ?: return null
        val d = s.devices[auth.deviceId] ?: return null
        return if (d.tokenHash == SyncCrypto.tokenHash(auth.token)) s to d else null
    }

    // ------------------------------------------------------------------------------------------ the browser's side

    fun openSlot(pairId: String, browser: DeviceRecord) {
        slots[pairId] = Slot(pairId, browser)
    }

    fun postReply(pairId: String, ct: SyncEnvelope) {
        slots.getValue(pairId).reply = ct
    }

    /** The browser as sponsor (it holds a phone-less space): adds the phone from its answer, before replying. */
    fun browserAddsPhone(spaceId: String, pairId: String, labelCt: SyncEnvelope) {
        val slot = slots.getValue(pairId)
        val phone = slot.answer!!.device
        spaces.getValue(spaceId).devices[phone.id] = Dev(phone.id, "phone", phone.tokenHash, phone.pub, labelCt)
        slot.burned = true
    }

    // ------------------------------------------------------------------------------------------ SyncApi

    override suspend fun pairLabel(pairId: String): SyncResult<PairLabelInfo> {
        calls += "label"
        forced("label")?.let { return it }
        val s = slots[pairId] ?: return err(410, SyncErrorCode.EXPIRED)
        if (s.answer != null) return err(409, SyncErrorCode.USED)
        return SyncResult.Ok(PairLabelInfo(s.browser.labelCt, 180_000, DeviceRef(s.browser.id, s.browser.pub)))
    }

    override suspend fun pairAnswer(pairId: String, body: PairAnswerBody): SyncResult<Unit> {
        calls += "answer"
        forced("answer")?.let { return it }
        val s = slots[pairId] ?: return err(410, SyncErrorCode.EXPIRED)
        if (s.answer != null) return err(409, SyncErrorCode.USED)
        s.answer = body
        onAnswered(s)
        return SyncResult.Ok(Unit)
    }

    override suspend fun pairReply(pairId: String, auth: DeviceAuth): SyncResult<PairReplyInfo?> {
        calls += "reply"
        forced("reply")?.let { return it }
        val s = slots[pairId] ?: return err(410, SyncErrorCode.EXPIRED)
        if (s.answer?.device?.tokenHash != SyncCrypto.tokenHash(auth.token)) return err(401, SyncErrorCode.UNAUTHORIZED)
        if (pendingPolls > 0) {
            pendingPolls--
            return SyncResult.Ok(null)
        }
        return SyncResult.Ok(s.reply?.let(::PairReplyInfo))
    }

    override suspend fun createSpace(auth: DeviceAuth, body: CreateSpaceBody): SyncResult<SpaceCreated> {
        calls += "create"
        forced("create")?.let { return it }
        val slot = slots[body.pairId] ?: return err(410, SyncErrorCode.EXPIRED)
        val phone = slot.answer?.device ?: return err(409, "pending")
        if (phone.id != auth.deviceId || phone.tokenHash != SyncCrypto.tokenHash(auth.token)) return err(403, SyncErrorCode.FORBIDDEN)
        if (slot.reply == null) return err(409, SyncErrorCode.NO_REPLY)
        if (slot.burned) return err(409, SyncErrorCode.USED)
        if (spaces.containsKey(body.spaceId)) return err(409, SyncErrorCode.EXISTS)
        val space = Space(body.spaceId)
        space.devices[phone.id] = Dev(phone.id, "phone", phone.tokenHash, phone.pub, body.labels[phone.id])
        val b = slot.browser
        space.devices[b.id] = Dev(b.id, "web", b.tokenHash, b.pub, body.labels[b.id])
        spaces[body.spaceId] = space
        slot.burned = true
        return SyncResult.Ok(SpaceCreated(body.spaceId, 1))
    }

    override suspend fun join(auth: DeviceAuth, spaceId: String, body: JoinBody): SyncResult<Joined> {
        calls += "join"
        forced("join")?.let { return it }
        val (space, me) = member(auth, spaceId) ?: return err(401, SyncErrorCode.REVOKED)
        val slot = slots[body.pairId] ?: return err(410, SyncErrorCode.EXPIRED)
        if (body.epoch != space.epoch && body.envelope?.e != space.epoch) {
            return err(409, SyncErrorCode.EPOCH, buildJsonObject { put("epoch", space.epoch) })
        }
        if (body.labelCt.e != space.epoch) return err(409, SyncErrorCode.EPOCH, buildJsonObject { put("epoch", space.epoch) })
        if (me.type == "phone" && slot.reply == null) return err(409, SyncErrorCode.NO_REPLY)
        if (slot.burned) return err(409, SyncErrorCode.USED)
        val add = slot.browser
        if (space.devices.containsKey(add.id)) return err(409, SyncErrorCode.MEMBER)
        if (space.devices.values.count { it.type == "web" } >= 4) return err(409, SyncErrorCode.FULL)
        space.devices[add.id] = Dev(add.id, "web", add.tokenHash, add.pub, body.labelCt)
        body.envelope?.let { space.envelopes[add.id to space.epoch] = it }
        slot.burned = true
        return SyncResult.Ok(Joined(add.id, "web", space.epoch))
    }

    override suspend fun space(auth: DeviceAuth, spaceId: String): SyncResult<SpaceInfo> {
        calls += "space"
        forced("space")?.let { return it }
        val (space, me) = member(auth, spaceId) ?: return err(401, SyncErrorCode.REVOKED)
        return SyncResult.Ok(
            SpaceInfo(
                spaceId = space.id,
                me = me.id,
                epoch = space.epoch,
                rotationDue = space.rotationDue,
                devices = space.devices.values.map { SpaceDevice(it.id, it.type, it.pub, it.labelCt, 1_000, it.lastSeenAt) },
                serverTime = 5_000,
            ),
        )
    }

    override suspend fun putMyLabel(auth: DeviceAuth, spaceId: String, labelCt: SyncEnvelope): SyncResult<Unit> {
        calls += "label-put"
        forced("label-put")?.let { return it }
        val (space, me) = member(auth, spaceId) ?: return err(401, SyncErrorCode.REVOKED)
        if (space.rotationDue) return err(409, SyncErrorCode.ROTATION_DUE)
        if (labelCt.e != space.epoch) return err(409, SyncErrorCode.EPOCH, buildJsonObject { put("epoch", space.epoch) })
        me.labelCt = labelCt
        return SyncResult.Ok(Unit)
    }

    override suspend fun removeDevice(auth: DeviceAuth, spaceId: String, deviceId: String): SyncResult<Unit> {
        calls += "remove:$deviceId"
        forced("remove")?.let { return it }
        val (space, me) = member(auth, spaceId) ?: return err(401, SyncErrorCode.REVOKED)
        val id = if (deviceId == "me") me.id else deviceId
        space.devices.remove(id) ?: return err(404, SyncErrorCode.NOT_FOUND)
        if (space.devices.isEmpty()) spaces.remove(spaceId) else space.rotationDue = true
        return SyncResult.Ok(Unit)
    }

    override suspend fun deleteSpace(auth: DeviceAuth, spaceId: String): SyncResult<Unit> {
        calls += "delete-space"
        member(auth, spaceId) ?: return err(401, SyncErrorCode.REVOKED)
        spaces.remove(spaceId)
        return SyncResult.Ok(Unit)
    }

    override suspend fun key(auth: DeviceAuth, spaceId: String, epoch: Int): SyncResult<KeyEnvelopeInfo> {
        calls += "key:$epoch"
        val (space, me) = member(auth, spaceId) ?: return err(401, SyncErrorCode.REVOKED)
        val env = space.envelopes[me.id to epoch] ?: return err(404, SyncErrorCode.NO_KEY)
        return SyncResult.Ok(KeyEnvelopeInfo(epoch, env))
    }

    var lastRotate: RotateBody? = null

    override suspend fun rotate(auth: DeviceAuth, spaceId: String, body: RotateBody): SyncResult<Rotated> {
        calls += "rotate"
        forced("rotate")?.let { return it }
        val (space, _) = member(auth, spaceId) ?: return err(401, SyncErrorCode.REVOKED)
        if (body.epoch != space.epoch + 1) return err(409, SyncErrorCode.EPOCH, buildJsonObject { put("epoch", space.epoch) })
        if (body.envelopes.keys != space.devices.keys) return err(409, SyncErrorCode.DEVICES_CHANGED, buildJsonObject { put("epoch", space.epoch) })
        if (space.config != null && body.config == null) return err(400, SyncErrorCode.BAD_REQUEST)
        lastRotate = body
        space.epoch = body.epoch
        space.rotationDue = false
        body.envelopes.forEach { (id, env) -> space.envelopes[id to body.epoch] = env }
        body.labels?.forEach { (id, env) -> space.devices[id]?.labelCt = env }
        body.config?.let { space.config = ConfigSlot("", 9_000, it) }
        return SyncResult.Ok(Rotated(body.epoch))
    }

    override suspend fun config(auth: DeviceAuth, spaceId: String): SyncResult<ConfigSlot?> {
        calls += "config"
        val (space, _) = member(auth, spaceId) ?: return err(401, SyncErrorCode.REVOKED)
        return SyncResult.Ok(space.config)
    }
}


/**
 * Stash on the web, scripted: its device id, token and long-term key, an ephemeral key and pair secret per code, its
 * pairing label, and the reply it posts once "its user" confirmed (sync-v1 §3.4), with the same primitives the phone uses.
 */
class FakeBrowser(
    private val server: FakeSyncServer,
    val name: String = "Chrome on Windows",
    val deviceId: String = WebLinkIds.newDeviceId(),
) {
    val token: ByteArray = SyncCrypto.randomBytes(32)
    val deviceKey: KeyPair = SyncCrypto.newKeyPair()
    val pub: ByteArray = SyncCrypto.rawPublicKey(deviceKey.public as ECPublicKey)
    val auth = DeviceAuth(deviceId, token)

    lateinit var pairId: String
    lateinit var pairSecret: ByteArray
    lateinit var eB: KeyPair
    lateinit var eBPub: ByteArray

    /** The code this browser computes from the phone's answer; null before one arrived. */
    var code: String? = null
    var kpair: ByteArray? = null
    var answer: SyncKeys.PairAnswer? = null

    fun label() = SyncKeys.Label(name, "web", deviceId, pub)

    /** Opens a code (`POST /v1/pair` through the player) and returns the QR link. */
    fun openCode(): String {
        pairId = Base64Url.encode(SyncCrypto.randomBytes(16))
        pairSecret = SyncCrypto.randomBytes(16)
        eB = SyncCrypto.newKeyPair()
        eBPub = SyncCrypto.rawPublicKey(eB.public as ECPublicKey)
        val labelCt = SyncCrypto.seal(SyncKeys.pairLabelKey(pairSecret), "pair", 0, SyncCrypto.Place.label(deviceId), SyncKeys.labelJson(label()))
        server.openSlot(pairId, DeviceRecord(deviceId, SyncCrypto.tokenHash(token), Base64Url.encode(pub), labelCt))
        return SyncKeys.qrLink(pairId, pairSecret, eBPub)
    }

    /** Reads the phone's answer as the browser does: Kpair from its own label (what it put in the slot), the code, the answer. */
    fun readAnswer(slot: FakeSyncServer.Slot) {
        val k = deriveKpair(slot)
        answer = SyncKeys.readPairAnswer(SyncCrypto.open(k, "pair", SyncCrypto.Place.pairAnswer(pairId), slot.answer!!.ct))
    }

    /** Kpair and the code from the phone's ephemeral key and this browser's own label (what it put in the slot). */
    fun deriveKpair(slot: FakeSyncServer.Slot): ByteArray {
        val ePPub = Base64Url.decode(slot.answer!!.phonePub)
        val k = SyncKeys.pairKey(eB.private, ePPub, pairSecret, pairId, eBPub, ePPub, deviceId, pub)
        kpair = k
        code = SyncKeys.pairCode(k)
        return k
    }

    /** The user confirmed the code: post the reply under Kpair, with [space] only when granting its own. */
    fun reply(space: SyncKeys.SpaceGrant? = null) {
        val doc = buildJsonObject {
            put("kind", "stash-pair-reply")
            put("v", 1)
            if (space != null) {
                put(
                    "space",
                    buildJsonObject {
                        put("id", space.id)
                        put("k", Base64Url.encode(space.k))
                        put("epoch", space.epoch)
                    },
                )
            }
        }
        server.postReply(pairId, SyncCrypto.seal(kpair!!, "pair", 0, SyncCrypto.Place.pairReply(pairId), doc.toString()))
    }

    /** Opens its own label in a space as the phone would (to check what a sponsor or rotator sealed). */
    fun openOwnLabel(spaceId: String, k: ByteArray): SyncKeys.Label {
        val env = server.spaces.getValue(spaceId).devices.getValue(deviceId).labelCt!!
        return SyncKeys.readLabel(SyncCrypto.open(SyncCrypto.dataKey(k, spaceId), spaceId, SyncCrypto.Place.label(deviceId), env), deviceId)
    }

    /** Reads its key envelope for [epoch] and checks the proof under [prev], as the browser does after a rotation. */
    fun openKey(spaceId: String, epoch: Int, prev: ByteArray): SyncKeys.NextKey {
        val env = server.spaces.getValue(spaceId).envelopes.getValue(deviceId to epoch)
        return SyncKeys.openKeyEnvelope(env, spaceId, deviceId, deviceKey.private, pub, prev, epoch - 1)
    }
}

/** [WebLinkStore] in memory, with a software device key. */
class InMemoryWebLinkStore : WebLinkStore {
    var id: LinkIdentity? = null
    var sp: LinkedSpace? = null
    var rosterList: List<RosterEntry> = emptyList()
    var wiped = 0

    /** Every read throws, as a Keystore hiccup does (LinkStoreUnavailable). */
    var failReads = false

    /** The device key is definitely gone: identity() is null while the token still reads. */
    var keyGone = false

    override suspend fun identity(): LinkIdentity? {
        if (failReads) throw com.stash.core.data.weblink.store.LinkStoreUnavailable(java.security.KeyStoreException("busy"))
        return if (keyGone) null else id
    }

    override suspend fun deviceToken(): Pair<String, ByteArray>? = id?.let { it.deviceId to it.token }

    override suspend fun createIdentity(name: String): LinkIdentity {
        val pair = SyncCrypto.newKeyPair()
        return LinkIdentity(WebLinkIds.newDeviceId(), SyncCrypto.randomBytes(32), SyncCrypto.rawPublicKey(pair.public as ECPublicKey), pair.private, name)
            .also { id = it; sp = null; rosterList = emptyList() }
    }

    override suspend fun setName(name: String) {
        id = id?.let { LinkIdentity(it.deviceId, it.token, it.devicePub, it.devicePriv, name) }
    }

    override suspend fun space(): LinkedSpace? {
        if (failReads) throw com.stash.core.data.weblink.store.LinkStoreUnavailable(java.security.KeyStoreException("busy"))
        return sp
    }

    override suspend fun saveSpace(space: LinkedSpace) {
        sp = space
    }

    override suspend fun roster() = rosterList

    override suspend fun saveRoster(entries: List<RosterEntry>) {
        rosterList = entries
    }

    override suspend fun wipe() {
        wiped++
        keyGone = false
        id = null
        sp = null
        rosterList = emptyList()
    }
}

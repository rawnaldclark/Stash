package com.stash.core.data.weblink

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * What a call to the stash-sync Worker came back with (MP3APK `infra/sync-worker/README.md`). Three shapes, so every caller
 * handles "the server said no" apart from "the server was never reached".
 */
sealed interface SyncResult<out T> {
    data class Ok<T>(val value: T) : SyncResult<T>

    /**
     * The Worker answered with an error: `{ "error": { "code", "message" }, … }`. [body] is the whole answer, for the codes
     * that carry more (`409 epoch` and `devices_changed` carry `epoch`, `devices_changed` also `devices`).
     */
    data class Error(
        val status: Int,
        val code: String,
        val message: String = "",
        val body: JsonObject? = null,
        val retryAfterSeconds: Long? = null,
    ) : SyncResult<Nothing> {
        /** This device is not (or no longer) in that space, whether the space exists or not: delete the local sync state. */
        val revoked: Boolean get() = status == 401 && code == SyncErrorCode.REVOKED

        /** `409 epoch`'s current epoch, when the answer carries one. */
        val epoch: Int? get() = (body?.get("epoch") as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
    }

    /** Never reached the Worker, or no answer in time: offline, DNS, TLS, a timeout. Worth retrying. */
    data class Unreachable(val cause: String?) : SyncResult<Nothing>
}

/** The Worker's error codes this app acts on (README "Errors"). */
object SyncErrorCode {
    const val BAD_REQUEST = "bad_request"
    const val UNAUTHORIZED = "unauthorized"
    const val REVOKED = "revoked"
    const val FORBIDDEN = "forbidden"
    const val NOT_FOUND = "not_found"
    const val NO_KEY = "no_key"
    const val EXPIRED = "expired"
    const val USED = "used"
    const val EXISTS = "exists"
    const val MEMBER = "member"
    const val FULL = "full"
    const val EPOCH = "epoch"
    const val NO_REPLY = "no_reply"
    const val DEVICES_CHANGED = "devices_changed"
    const val ROTATION_DUE = "rotation_due"
    const val COMPACT = "compact"
    const val STALE = "stale"
    const val SNAPSHOT = "snapshot"
    const val CHANGED = "changed"
    const val SPACE_FULL = "space_full"
    const val RATE_LIMITED = "rate_limited"
    const val DAILY_LIMIT = "daily_limit"
    const val UNAVAILABLE = "unavailable"

    /** The code of an answer that had no error body (a proxy's page, an empty 5xx). */
    const val HTTP = "http"
}

/** `Authorization: Stash-Device <deviceId>:<token>`: who is calling. */
class DeviceAuth(val deviceId: String, val token: ByteArray) {
    val header: String get() = SyncCrypto.authHeader(deviceId, token)
}

// ------------------------------------------------------------------------------------------------ wire shapes

/** `GET /v1/pair/{pairId}/label`: the browser's pairing label, and its id and key as the server holds them. */
@Serializable
data class PairLabelInfo(val labelCt: SyncEnvelope, val expiresAt: Long = 0, val browser: DeviceRef)

@Serializable
data class DeviceRef(val id: String, val pub: String)

/** A device as the server keeps it: its token hash (never the token), public key and pairing-time label. */
@Serializable
data class DeviceRecord(val id: String, val tokenHash: String, val pub: String, val labelCt: SyncEnvelope)

@Serializable
data class PairAnswerBody(val phonePub: String, val ct: SyncEnvelope, val device: DeviceRecord)

@Serializable
data class PairReplyInfo(val ct: SyncEnvelope)

@Serializable
data class CreateSpaceBody(val pairId: String, val spaceId: String, val labels: Map<String, SyncEnvelope>)

@Serializable
data class SpaceCreated(val spaceId: String, val epoch: Int)

@Serializable
data class JoinBody(val pairId: String, val epoch: Int, val labelCt: SyncEnvelope, val envelope: SyncEnvelope? = null)

@Serializable
data class Joined(val device: String, val type: String, val epoch: Int)

/** `GET /v1/spaces/{sid}`. */
@Serializable
data class SpaceInfo(
    val spaceId: String,
    val me: String,
    val epoch: Int,
    val rotationDue: Boolean = false,
    val compactDue: Boolean = false,
    val head: Long = 0,
    val devices: List<SpaceDevice>,
    val serverTime: Long = 0,
    /** The mirror's current snapshot, or null when the space has none (sync-v1 §5.4). */
    val snapshot: SnapshotMeta? = null,
)

/** Where the mirror's snapshot stands: after log entry [uptoSeq], in [parts] parts, sealed under [epoch]. */
@Serializable
data class SnapshotMeta(val uptoSeq: Long = 0, val parts: Int = 0, val epoch: Int = 0)

/** One mirror batch in the log (`GET …/log/after/{seq}`): the server stamps [device] from the writer's token. */
@Serializable
data class LogEntry(val seq: Long, val device: String, val serverAt: Long, val env: SyncEnvelope)

@Serializable
data class LogPage(val entries: List<LogEntry> = emptyList(), val head: Long = 0, val more: Boolean = false)

@Serializable
data class LogPosted(val seq: Long, val serverAt: Long = 0)

@Serializable
data class SnapshotPut(val complete: Boolean = false)

@Serializable
data class SnapshotPartInfo(val uptoSeq: Long, val part: Int, val count: Int, val epoch: Int, val env: SyncEnvelope)

@Serializable
data class SpaceDevice(
    val id: String,
    val type: String,
    val pub: String,
    val labelCt: SyncEnvelope? = null,
    val addedAt: Long = 0,
    val lastSeenAt: Long = 0,
)

@Serializable
data class KeyEnvelopeInfo(val epoch: Int, val ct: SyncEnvelope)

@Serializable
data class LabelBody(val labelCt: SyncEnvelope)

@Serializable
data class RotateBody(
    val epoch: Int,
    val envelopes: Map<String, SyncEnvelope>,
    val labels: Map<String, SyncEnvelope>? = null,
    val config: SyncEnvelope? = null,
)

@Serializable
data class Rotated(val epoch: Int)

/** The shared mirror config slot (`GET …/slots/config`); re-sealed under the new key in a rotation. */
@Serializable
data class ConfigSlot(val device: String = "", val serverAt: Long = 0, val env: SyncEnvelope)

@Serializable
data class SlotBody(val env: SyncEnvelope)

@Serializable
data class SlotWritten(val serverAt: Long)

/** One device's handoff state as the server keeps it: who wrote it, when (the server's clock), and the envelope. */
@Serializable
data class StoredSlot(val device: String = "", val serverAt: Long = 0, val env: SyncEnvelope)

/** `GET …/slots/now`: every device's `now` slot, newest first, and the server's clock. */
@Serializable
data class NowSlots(val slots: List<StoredSlot> = emptyList(), val serverTime: Long = 0)

/** `PUT …/inbox/{to}/{sendId}/{part}/{count}`: whether every part of that send is in. */
@Serializable
data class InboxPut(val complete: Boolean = false)

/** One complete send waiting for this device (`GET …/inbox`). */
@Serializable
data class InboxListing(val sendId: String, val from: String, val count: Int, val bytes: Long = 0, val serverAt: Long = 0)

@Serializable
data class InboxList(val sends: List<InboxListing> = emptyList())

/** One part of a send (`GET …/inbox/{sendId}/{part}`). */
@Serializable
data class InboxPart(val sendId: String, val part: Int, val count: Int, val from: String = "", val serverAt: Long = 0, val env: SyncEnvelope)

/**
 * The stash-sync routes the phone uses for pairing and device management (spec §6.2, sync-v1 §3.4–3.6). Every id is in the
 * path. A long-poll answers `Ok(null)` while nothing has arrived yet (204).
 */
interface SyncApi {
    suspend fun pairLabel(pairId: String): SyncResult<PairLabelInfo>
    suspend fun pairAnswer(pairId: String, body: PairAnswerBody): SyncResult<Unit>

    /** Long-poll (≤ 25 s) for the browser's reply, with the token the phone put in its answer. */
    suspend fun pairReply(pairId: String, auth: DeviceAuth): SyncResult<PairReplyInfo?>

    suspend fun createSpace(auth: DeviceAuth, body: CreateSpaceBody): SyncResult<SpaceCreated>
    suspend fun join(auth: DeviceAuth, spaceId: String, body: JoinBody): SyncResult<Joined>
    suspend fun space(auth: DeviceAuth, spaceId: String): SyncResult<SpaceInfo>
    suspend fun putMyLabel(auth: DeviceAuth, spaceId: String, labelCt: SyncEnvelope): SyncResult<Unit>
    suspend fun removeDevice(auth: DeviceAuth, spaceId: String, deviceId: String): SyncResult<Unit>
    suspend fun deleteSpace(auth: DeviceAuth, spaceId: String): SyncResult<Unit>
    suspend fun key(auth: DeviceAuth, spaceId: String, epoch: Int): SyncResult<KeyEnvelopeInfo>
    suspend fun rotate(auth: DeviceAuth, spaceId: String, body: RotateBody): SyncResult<Rotated>

    /** The mirror config slot, or `Ok(null)` when the space has none. */
    suspend fun config(auth: DeviceAuth, spaceId: String): SyncResult<ConfigSlot?>

    /**
     * Writes the mirror config with `If-Match: <serverAt read>` ([ifMatch] 0: there was none). `412 changed`: someone wrote
     * meanwhile (read again, apply, redo the change on top).
     */
    suspend fun putConfig(auth: DeviceAuth, spaceId: String, env: SyncEnvelope, ifMatch: Long): SyncResult<SlotWritten>

    /** Up to 200 mirror batches after [seq] (sync-v1 §7); `409 snapshot` when [seq] is older than the snapshot. */
    suspend fun logAfter(auth: DeviceAuth, spaceId: String, seq: Long): SyncResult<LogPage>

    /** Appends one mirror batch; `409 compact` past 2,000 batches or while a snapshot under a new key is due. */
    suspend fun postLog(auth: DeviceAuth, spaceId: String, env: SyncEnvelope): SyncResult<LogPosted>

    /** One part of a snapshot after log entry [uptoSeq] (sync-v1 §3.3, §5.4). */
    suspend fun putSnapshot(auth: DeviceAuth, spaceId: String, uptoSeq: Long, part: Int, count: Int, env: SyncEnvelope): SyncResult<SnapshotPut>

    suspend fun snapshotPart(auth: DeviceAuth, spaceId: String, part: Int): SyncResult<SnapshotPartInfo>

    /** Writes this device's own handoff slot ([slot] is `now` or `queue`; spec §4.2, §8.1). */
    suspend fun putSlot(auth: DeviceAuth, spaceId: String, slot: String, env: SyncEnvelope): SyncResult<SlotWritten>

    /** Every device's `now` slot (spec §8.2). */
    suspend fun nowSlots(auth: DeviceAuth, spaceId: String): SyncResult<NowSlots>

    /** One device's `queue` slot; `404 not_found` when it published none (or it expired). */
    suspend fun queueSlot(auth: DeviceAuth, spaceId: String, deviceId: String): SyncResult<StoredSlot>

    /** One part of a one-off send to [to] (spec §6.2, sync-v1 §3.3, §5.5). */
    suspend fun putInbox(auth: DeviceAuth, spaceId: String, to: String, sendId: String, part: Int, count: Int, env: SyncEnvelope): SyncResult<InboxPut>

    /** The complete sends addressed to this device, newest first. */
    suspend fun inbox(auth: DeviceAuth, spaceId: String): SyncResult<InboxList>

    suspend fun inboxPart(auth: DeviceAuth, spaceId: String, sendId: String, part: Int): SyncResult<InboxPart>

    /** Deletes a send (the receiver, done with it; or the sender, taking it back). */
    suspend fun deleteInbox(auth: DeviceAuth, spaceId: String, sendId: String): SyncResult<Unit>
}

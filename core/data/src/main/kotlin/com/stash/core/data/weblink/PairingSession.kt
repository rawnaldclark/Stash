package com.stash.core.data.weblink

import android.util.Log
import com.stash.core.data.weblink.WebLinkRepository.Companion.TYPE_PHONE
import com.stash.core.data.weblink.WebLinkRepository.Companion.TYPE_WEB
import com.stash.core.data.weblink.WebLinkRepository.Companion.auth
import com.stash.core.data.weblink.store.LinkIdentity
import com.stash.core.data.weblink.store.LinkedSpace
import com.stash.core.data.weblink.store.RosterEntry
import com.stash.core.data.weblink.store.WebLinkStore
import java.security.KeyPair
import java.security.interfaces.ECPublicKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Where a pairing is (spec §2.2; sync-v1 §3.4). The code is six digits, shown as "123 456". */
sealed interface PairingState {
    data object Idle : PairingState

    /** Reading the code's slot (the browser's label). */
    data object Checking : PairingState

    /** The confirm sheet: "Link Chrome on Windows?" with the code the computer will ask about. */
    data class Confirm(val browserName: String, val code: String) : PairingState

    /** Answered; "Waiting for your computer… · 123 456" until the browser's user confirms the same code. */
    data class Waiting(val browserName: String, val code: String) : PairingState

    /** "Linked. Nothing is mirrored yet." */
    data class Linked(val browserName: String) : PairingState

    /** Stopped, with nothing joined, created or pinned. [code] stays on screen when the phone had already shown it. */
    data class Failed(val message: String, val code: String? = null) : PairingState
}

/**
 * The phone's half of pairing (spec §5.1, sync-v1 §3.4), one code at a time:
 *
 * 1. [open]: parse the QR link, read the browser's pairing label from the slot (sealed under the pair secret; its key must
 *    equal the one the server lists), make the ephemeral key `eP`, derive `Kpair` and the six-digit code → [PairingState.Confirm].
 * 2. [confirm] (the user tapped Link): post the answer (this phone's label, and the space: the current one when linked,
 *    else a freshly minted one) → [PairingState.Waiting], keeping the code on screen.
 * 3. Long-poll the browser's reply and open it under **this phone's own** `Kpair` (R7). Only then: join the browser to this
 *    phone's space, or create the minted space, or (the browser granted its phone-less space) confirm membership in it;
 *    save the space and pin the browser's key → [PairingState.Linked]. A reply that doesn't open (the browser's label was
 *    swapped in the slot, so the codes differ) stops here with nothing joined, created or pinned.
 *
 * Not thread-safe: one coroutine drives a session (the screen's view model); cancelling it cancels the pairing.
 */
class PairingSession(
    private val api: SyncApi,
    private val store: WebLinkStore,
    private val repo: WebLinkRepository,
    /** This phone's name for its label, the first time it links ("Pixel 6", `Build.MODEL`). */
    private val phoneName: () -> String,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val _state = MutableStateFlow<PairingState>(PairingState.Idle)
    val state: StateFlow<PairingState> = _state.asStateFlow()

    /** What [open] learned, for [confirm]. */
    private class Pending(
        val link: SyncKeys.QrLink,
        val browser: SyncKeys.Label,
        val eP: KeyPair,
        val ePPub: ByteArray,
        val kpair: ByteArray,
        val code: String,
    )

    private var pending: Pending? = null

    fun reset() {
        pending = null
        _state.value = PairingState.Idle
    }

    /** Step 1: a scanned or opened link → the confirm sheet, or why not. */
    suspend fun open(linkText: String): PairingState {
        pending = null
        val link = SyncKeys.parseQrLink(linkText.trim()) ?: return fail(WebLinkCopy.NOT_A_CODE)
        _state.value = PairingState.Checking
        val info = when (val r = api.pairLabel(link.pairId)) {
            is SyncResult.Ok -> r.value
            is SyncResult.Error -> return fail(if (r.status == 404) WebLinkCopy.EXPIRED else r.userMessage())
            is SyncResult.Unreachable -> return fail(WebLinkCopy.OFFLINE)
        }
        val browser = try {
            val text = SyncCrypto.open(SyncKeys.pairLabelKey(link.pairSecret), PAIR, SyncCrypto.Place.label(info.browser.id), info.labelCt)
            SyncKeys.readLabel(text, info.browser.id)
        } catch (e: SyncCryptoException) {
            return fail(WebLinkCopy.NOT_A_CODE)
        }
        // The label must be a browser's, and its key the one the server holds for that device (sync-v1 §3.4).
        val serverPub = try {
            Base64Url.decode(info.browser.pub)
        } catch (e: IllegalArgumentException) {
            return fail(WebLinkCopy.NOT_A_CODE)
        }
        if (browser.type != TYPE_WEB || !SyncCrypto.sameBytes(serverPub, browser.pub)) return fail(WebLinkCopy.NOT_A_CODE)

        // Already linked: the browser joins this space, so check it isn't in it already and that there is room (spec §2.2).
        val linked = store.space()
        if (linked != null) {
            when (val r = repo.refresh()) {
                is WebLinkResult.Unlinked -> Unit // not linked any more: this pairing makes a new space
                is WebLinkResult.Failed -> return fail(r.message)
                WebLinkResult.Ok -> {
                    val status = repo.status.value as? WebLinkStatus.Linked
                    if (status != null) {
                        if (status.devices.any { it.id == browser.deviceId }) return fail(WebLinkCopy.ALREADY_LINKED)
                        if (status.devices.count { it.type == TYPE_WEB } >= MAX_BROWSERS) return fail(WebLinkCopy.FULL)
                    }
                }
            }
        }

        val eP = SyncCrypto.newKeyPair()
        val ePPub = SyncCrypto.rawPublicKey(eP.public as ECPublicKey)
        val kpair = SyncKeys.pairKey(eP.private, link.eBPub, link.pairSecret, link.pairId, link.eBPub, ePPub, browser.deviceId, browser.pub)
        val code = SyncKeys.pairCode(kpair)
        pending = Pending(link, browser, eP, ePPub, kpair, code)
        return set(PairingState.Confirm(browser.name, code))
    }

    /** Steps 2 and 3: the user tapped Link. Runs until linked or stopped (or the caller is cancelled). */
    suspend fun confirm(): PairingState {
        val p = pending ?: return fail(WebLinkCopy.UNFINISHED)
        pending = null
        val name = p.browser.name
        val id = store.identity() ?: store.createIdentity(WebLinkRepository.cleanName(phoneName()) ?: "Phone")
        val linked = store.space()
        val grant = linked?.let { SyncKeys.SpaceGrant(it.spaceId, it.k, it.epoch) }
            ?: SyncKeys.SpaceGrant(WebLinkIds.newSpaceId(), SyncCrypto.randomBytes(SyncCrypto.KEY_BYTES), 1)
        val myLabel = SyncKeys.Label(id.name, TYPE_PHONE, id.deviceId, id.devicePub)

        // The answer: this phone's label (with its key) and the space, sealed under Kpair; its device record in clear.
        val answer = buildJsonObject {
            put("kind", "stash-pair-answer")
            put("v", 1)
            put("deviceId", id.deviceId)
            put("label", labelObject(myLabel))
            put("space", grantObject(grant))
        }
        val body = PairAnswerBody(
            phonePub = Base64Url.encode(p.ePPub),
            ct = SyncCrypto.seal(p.kpair, PAIR, 0, SyncCrypto.Place.pairAnswer(p.link.pairId), answer.toString()),
            device = DeviceRecord(
                id = id.deviceId,
                tokenHash = SyncCrypto.tokenHash(id.token),
                pub = Base64Url.encode(id.devicePub),
                labelCt = SyncCrypto.seal(SyncKeys.pairLabelKey(p.link.pairSecret), PAIR, 0, SyncCrypto.Place.label(id.deviceId), SyncKeys.labelJson(myLabel)),
            ),
        )
        when (val r = api.pairAnswer(p.link.pairId, body)) {
            is SyncResult.Ok -> Unit
            is SyncResult.Error -> return fail(if (r.status == 404) WebLinkCopy.EXPIRED else r.userMessage(), p.code)
            is SyncResult.Unreachable -> return fail(WebLinkCopy.OFFLINE, p.code)
        }
        set(PairingState.Waiting(name, p.code))

        // The browser's reply, opened under this phone's own Kpair: nothing is joined, created or pinned before it (R7).
        val replyEnv = when (val r = awaitReply(p.link.pairId, auth(id))) {
            is SyncResult.Ok -> r.value
            is SyncResult.Error -> return fail(r.userMessage(), p.code)
            is SyncResult.Unreachable -> return fail(r.cause ?: WebLinkCopy.UNFINISHED, p.code)
        }
        val granted = try {
            SyncKeys.readPairReply(SyncCrypto.open(p.kpair, PAIR, SyncCrypto.Place.pairReply(p.link.pairId), replyEnv))
        } catch (e: SyncCryptoException) {
            Log.w(TAG, "the browser's reply didn't open under this phone's Kpair: nothing joined or pinned")
            return fail(WebLinkCopy.UNFINISHED, p.code)
        }

        val outcome = repo.locked {
            when {
                granted != null && linked != null -> WebLinkResult.Failed(WebLinkCopy.UNFINISHED) // a linked phone grants, never joins
                granted != null -> joinedBrowserSpace(id, granted)
                linked != null -> joinBrowser(id, linked, p)
                else -> createSpace(id, grant, myLabel, p)
            }.also { if (it == WebLinkResult.Ok) pin(p.browser) }
        }
        return when (outcome) {
            WebLinkResult.Ok -> {
                repo.refresh()
                set(PairingState.Linked(name))
            }
            is WebLinkResult.Failed -> fail(outcome.message, p.code)
            is WebLinkResult.Unlinked -> fail(WebLinkCopy.UNFINISHED, p.code)
        }
    }

    /**
     * Long-polls `GET …/reply` (the Worker holds each call up to 25 s) until the browser's reply arrives, the code dies, or
     * [REPLY_WAIT_MS] passes (the code lives 3 minutes). A dropped connection is retried.
     */
    private suspend fun awaitReply(pairId: String, auth: DeviceAuth): SyncResult<SyncEnvelope> {
        val deadline = now() + REPLY_WAIT_MS
        while (now() < deadline) {
            val asked = now()
            when (val r = api.pairReply(pairId, auth)) {
                is SyncResult.Ok -> {
                    r.value?.let { return SyncResult.Ok(it.ct) }
                    // The Worker holds the call up to 25 s; one that comes back at once (a proxy) must not spin.
                    if (now() - asked < MIN_POLL_MS) sleep(MIN_POLL_MS)
                }
                is SyncResult.Error -> when {
                    r.status == 429 -> sleep((r.retryAfterSeconds ?: 5).coerceIn(1, 30) * 1000)
                    r.status >= 500 -> sleep(RETRY_MS)
                    r.status == 404 || r.code == SyncErrorCode.EXPIRED -> return SyncResult.Error(410, SyncErrorCode.EXPIRED)
                    else -> return SyncResult.Unreachable(WebLinkCopy.UNFINISHED)
                }
                is SyncResult.Unreachable -> sleep(RETRY_MS)
            }
        }
        return SyncResult.Unreachable(WebLinkCopy.UNFINISHED)
    }

    /** No space on either side: create the one this phone minted, with both labels re-sealed under its data key. */
    private suspend fun createSpace(id: LinkIdentity, grant: SyncKeys.SpaceGrant, myLabel: SyncKeys.Label, p: Pending): WebLinkResult {
        val data = SyncCrypto.dataKey(grant.k, grant.id)
        val labels = mapOf(
            id.deviceId to SyncCrypto.seal(data, grant.id, grant.epoch, SyncCrypto.Place.label(id.deviceId), SyncKeys.labelJson(myLabel)),
            p.browser.deviceId to SyncCrypto.seal(data, grant.id, grant.epoch, SyncCrypto.Place.label(p.browser.deviceId), SyncKeys.labelJson(p.browser)),
        )
        val r = retryNoReply { api.createSpace(auth(id), CreateSpaceBody(p.link.pairId, grant.id, labels)) }
        return when (r) {
            is SyncResult.Ok -> {
                store.saveSpace(LinkedSpace(grant.id, grant.epoch, grant.k))
                WebLinkResult.Ok
            }
            is SyncResult.Error -> WebLinkResult.Failed(r.userMessage())
            is SyncResult.Unreachable -> WebLinkResult.Failed(WebLinkCopy.OFFLINE)
        }
    }

    /**
     * This phone has a space: add the browser (`POST …/devices`) with its label re-sealed under the data key. If the space
     * rotated since the answer was sealed (`409 epoch`), catch up and hand the browser a key envelope for the current epoch,
     * proven under the one before (sync-v1 §3.4 "Join and the key epoch").
     */
    private suspend fun joinBrowser(id: LinkIdentity, space: LinkedSpace, p: Pending): WebLinkResult {
        var sp = space
        var envelope: SyncEnvelope? = null
        repeat(3) {
            val labelCt = SyncCrypto.seal(SyncCrypto.dataKey(sp.k, sp.spaceId), sp.spaceId, sp.epoch, SyncCrypto.Place.label(p.browser.deviceId), SyncKeys.labelJson(p.browser))
            val r = retryNoReply { api.join(auth(id), sp.spaceId, JoinBody(p.link.pairId, space.epoch, labelCt, envelope)) }
            when (r) {
                is SyncResult.Ok -> return WebLinkResult.Ok
                is SyncResult.Error -> when {
                    r.code == SyncErrorCode.MEMBER -> return WebLinkResult.Ok
                    r.revoked -> return repo.unlinked()
                    r.code == SyncErrorCode.EPOCH && r.epoch != null -> {
                        sp = when (val c = repo.catchUp(id, r.epoch!!)) {
                            is WebLinkRepository.CatchUp.Done -> c.space
                            is WebLinkRepository.CatchUp.Lost -> return c.result
                        }
                        val prev = sp.keyFor(sp.epoch - 1) ?: return WebLinkResult.Failed(WebLinkCopy.UNFINISHED)
                        val members = currentMembers(id, sp) + p.browser.deviceId
                        envelope = SyncKeys.sealKeyFor(sp.k, sp.spaceId, sp.epoch, p.browser.deviceId, p.browser.pub, members.distinct(), prev)
                    }
                    else -> return WebLinkResult.Failed(r.userMessage())
                }
                is SyncResult.Unreachable -> return WebLinkResult.Failed(WebLinkCopy.OFFLINE)
            }
        }
        return WebLinkResult.Failed(WebLinkCopy.UNFINISHED)
    }

    private suspend fun currentMembers(id: LinkIdentity, sp: LinkedSpace): List<String> =
        (api.space(auth(id), sp.spaceId) as? SyncResult.Ok)?.value?.devices?.map { it.id } ?: listOf(id.deviceId)

    /** The browser granted its phone-less space and already added this phone (it does so before replying): confirm, then keep it. */
    private suspend fun joinedBrowserSpace(id: LinkIdentity, granted: SyncKeys.SpaceGrant): WebLinkResult {
        repeat(MEMBER_TRIES) { attempt ->
            when (val r = api.space(auth(id), granted.id)) {
                is SyncResult.Ok -> {
                    store.saveSpace(LinkedSpace(granted.id, granted.epoch, granted.k))
                    return WebLinkResult.Ok
                }
                is SyncResult.Error -> if (r.status != 401 || attempt == MEMBER_TRIES - 1) return WebLinkResult.Failed(WebLinkCopy.UNFINISHED)
                is SyncResult.Unreachable -> if (attempt == MEMBER_TRIES - 1) return WebLinkResult.Failed(WebLinkCopy.OFFLINE)
            }
            sleep(RETRY_MS)
        }
        return WebLinkResult.Failed(WebLinkCopy.UNFINISHED)
    }

    /** `409 no_reply`: the server hasn't seen the browser's reply land yet (it is on the slot by now); try again shortly. */
    private suspend fun <T> retryNoReply(call: suspend () -> SyncResult<T>): SyncResult<T> {
        var r = call()
        var n = 0
        while (r is SyncResult.Error && r.code == SyncErrorCode.NO_REPLY && n++ < NO_REPLY_TRIES) {
            sleep(RETRY_MS)
            r = call()
        }
        return r
    }

    /** Pins the browser's key from its code-checked pairing label (sync-v1 §3.6). */
    private suspend fun pin(browser: SyncKeys.Label) {
        val roster = store.roster().filter { it.deviceId != browser.deviceId }
        store.saveRoster(roster + RosterEntry(browser.deviceId, browser.type, browser.name, browser.pub))
    }

    private fun labelObject(l: SyncKeys.Label): JsonObject = SyncJson.parseToJsonElement(SyncKeys.labelJson(l)).jsonObject

    private fun grantObject(g: SyncKeys.SpaceGrant) = buildJsonObject {
        put("id", g.id)
        put("k", Base64Url.encode(g.k))
        put("epoch", g.epoch)
    }

    private fun set(s: PairingState): PairingState {
        _state.value = s
        return s
    }

    private fun fail(message: String, code: String? = null) = set(PairingState.Failed(message, code))

    companion object {
        private const val TAG = "WebLinkPairing"

        /** Pairing envelopes use spaceId `pair` and epoch 0 (sync-v1 §3.2). */
        const val PAIR = "pair"
        const val MAX_BROWSERS = 4

        /** A code lives 3 minutes; wait a little past that for the browser's user to confirm. */
        const val REPLY_WAIT_MS = 4 * 60_000L
        const val RETRY_MS = 2_000L
        const val MIN_POLL_MS = 1_000L
        const val NO_REPLY_TRIES = 5
        const val MEMBER_TRIES = 10

        /** "123456" → "123 456", as both screens show it. */
        fun spaced(code: String) = if (code.length == 6) code.substring(0, 3) + " " + code.substring(3) else code
    }
}

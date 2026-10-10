package com.stash.core.data.weblink

import com.stash.core.data.weblink.SyncCrypto.b64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.security.KeyPair
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey

/**
 * sync-v1 keys (stash-player `docs/sync-v1.md` §3.4–3.6; spec §5.1, §5.2): pairing (QR link, labels, Kpair, the six-digit code
 * both screens show), what a pairing grants (a space), and key rotation with its proof under the previous key. The web's
 * counterpart is `src/lib/sync/keys.ts`; both pass `crypto-vectors.json`.
 */
object SyncKeys {
    const val INFO_PAIR_LABEL = "stash-sync pair label"
    const val INFO_PAIR = "stash-sync pair v1"
    const val INFO_SAS = "stash-sync pair sas"
    const val INFO_KEY = "stash-sync key v1"
    const val INFO_ROTATE = "stash-sync rotate v1"

    /** The QR's link, before the `#` fragment. */
    const val LINK_BASE = "https://stashfm.app/link"

    /** A device's label is cut to this many characters (code points). */
    const val MAX_LABEL = 60

    private val DEVICE_ID = Regex("^[A-Za-z0-9_-]{8,64}$")
    private val PAIR_ID = Regex("^[A-Za-z0-9_-]{22}$")
    private val SPACE_ID = Regex("^s_[A-Za-z0-9_-]{22}$")

    private fun utf8(s: String) = s.toByteArray(Charsets.UTF_8)
    private fun isPubBytes(b: ByteArray) = b.size == 65 && b[0] == 4.toByte()
    private fun bad(what: String): Nothing = throw SyncCryptoException(what)

    // Strict JSON readers: a value of the wrong JSON type is refused, never coerced (as JavaScript's `typeof` checks).
    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.int(k: String): Long? = (this[k] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
    private fun parseObject(text: String, what: String): JsonObject =
        try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        } ?: bad(what)

    // -------------------------------------------------------------------------------------------- labels

    /** A device's name and long-term public key, as its owner wrote it (inside the pair secret or the space key, never in clear). */
    class Label(val name: String, val type: String, val deviceId: String, val pub: ByteArray)

    fun labelJson(l: Label): String = buildJsonObject {
        put("kind", "stash-label")
        put("v", 1)
        put("name", l.name)
        put("type", l.type)
        put("deviceId", l.deviceId)
        put("pub", Base64Url.encode(l.pub))
    }.toString()

    /**
     * A `stash-label` read back: the device it names must be [deviceId] (the place it was found at), and its key a P-256 point.
     * The only trusted source of another device's public key: a key the server lists is used only when it equals this one.
     */
    fun readLabel(doc: JsonElement?, deviceId: String): Label {
        val d = doc as? JsonObject ?: bad("bad label")
        if (d.str("kind") != "stash-label") bad("bad label")
        if (d.int("v") != 1L) bad("newer label")
        val name = d.str("name") ?: bad("bad label")
        val type = d.str("type")?.takeIf { it == "phone" || it == "web" } ?: bad("bad label")
        if (d.str("deviceId") != deviceId) bad("bad label")
        val pub = b64(d.str("pub") ?: bad("bad label"))
        if (!isPubBytes(pub)) bad("bad label")
        val trimmed = name.trim()
        val cut = trimmed.substring(0, trimmed.offsetByCodePoints(0, minOf(MAX_LABEL, trimmed.codePointCount(0, trimmed.length))))
        return Label(cut.ifEmpty { if (type == "phone") "Phone" else "Browser" }, type, deviceId, pub)
    }

    fun readLabel(text: String, deviceId: String): Label = readLabel(parseObject(text, "bad label"), deviceId)

    /**
     * Keys are pinned (trust on first use per device id): once a device's key is known (from a code-checked pairing, or the first
     * sealed label seen for that id), a label with another key for that id is refused, and the device is shown as "Key changed:
     * remove it and link it again". Returns the key to keep.
     */
    fun pinKey(known: ByteArray?, label: Label): ByteArray {
        if (known != null && !SyncCrypto.sameBytes(known, label.pub)) bad("key changed")
        return known ?: label.pub
    }

    // -------------------------------------------------------------------------------------------- pairing (spec §5.1)

    class QrLink(val pairId: String, val pairSecret: ByteArray, val eBPub: ByteArray)

    /** `https://stashfm.app/link#1.<pairId>.<pairSecret>.<eB.pub>`, every part base64url. */
    fun qrLink(pairId: String, pairSecret: ByteArray, eBPub: ByteArray) =
        "$LINK_BASE#1.$pairId.${Base64Url.encode(pairSecret)}.${Base64Url.encode(eBPub)}"

    /** The QR's parts, or null when it isn't a version-1 Stash link ("That's not a Stash code"). */
    fun parseQrLink(s: String): QrLink? {
        if (!s.startsWith("$LINK_BASE#")) return null
        val parts = s.substring(LINK_BASE.length + 1).split('.')
        if (parts.size != 4 || parts[0] != "1" || !PAIR_ID.matches(parts[1])) return null
        return try {
            val secret = Base64Url.decode(parts[2])
            val pub = Base64Url.decode(parts[3])
            if (secret.size != 16 || !isPubBytes(pub)) null else QrLink(parts[1], secret, pub)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** The key both devices' pairing labels are sealed with: HKDF(pairSecret, empty salt, "stash-sync pair label"), place `label:<deviceId>`. */
    fun pairLabelKey(pairSecret: ByteArray): ByteArray = SyncCrypto.hkdf(pairSecret, ByteArray(0), utf8(INFO_PAIR_LABEL))

    /**
     * HKDF info of Kpair: `stash-sync pair v1|<pairId>|<eB.pub>|<eP.pub>|<browser deviceId>|<browser pub>` (keys base64url). The
     * browser's device id and long-term key come from its pairing label: a label swapped in the slot gives the phone another Kpair,
     * so another code than the browser shows.
     */
    fun pairInfo(pairId: String, eBPub: ByteArray, ePPub: ByteArray, browserId: String, browserPub: ByteArray) =
        "$INFO_PAIR|$pairId|${Base64Url.encode(eBPub)}|${Base64Url.encode(ePPub)}|$browserId|${Base64Url.encode(browserPub)}"

    /** Kpair = HKDF(ECDH(mine, theirs), salt = pairSecret, info = [pairInfo]). Both sides call it with their own private key. */
    fun pairKey(
        mine: PrivateKey,
        theirPub: ByteArray,
        pairSecret: ByteArray,
        pairId: String,
        eBPub: ByteArray,
        ePPub: ByteArray,
        browserId: String,
        browserPub: ByteArray,
    ): ByteArray = SyncCrypto.hkdf(SyncCrypto.ecdh(mine, theirPub), pairSecret, utf8(pairInfo(pairId, eBPub, ePPub, browserId, browserPub)))

    /**
     * The six digits both screens show before anything is shared: HKDF(Kpair, empty salt, "stash-sync pair sas", 4 bytes) as a
     * big-endian number, mod 1,000,000, zero-padded. Someone who answered a photographed code has another Kpair, so another code.
     */
    fun pairCode(kpair: ByteArray): String {
        val b = SyncCrypto.hkdf(kpair, ByteArray(0), utf8(INFO_SAS), 4)
        val n = b.fold(0L) { acc, x -> (acc shl 8) or (x.toLong() and 0xff) }
        return (n % 1_000_000).toString().padStart(6, '0')
    }

    /** What a pairing grants: a space, its key and epoch. */
    class SpaceGrant(val id: String, val k: ByteArray, val epoch: Int)

    /** A space grant read back: `s_` + 16 bytes in base64url, a 32-byte key, an epoch ≥ 1. */
    fun readSpaceGrant(x: JsonElement?): SpaceGrant {
        val s = x as? JsonObject ?: bad("bad space")
        val id = s.str("id")?.takeIf { SPACE_ID.matches(it) } ?: bad("bad space")
        if (b64(id.substring(2)).size != 16) bad("bad space")
        val k = b64(s.str("k") ?: bad("bad space"))
        val epoch = s.int("epoch")
        if (k.size != SyncCrypto.KEY_BYTES || epoch == null || epoch < 1 || epoch > Int.MAX_VALUE) bad("bad space")
        return SpaceGrant(id, k, epoch.toInt())
    }

    class PairAnswer(val label: Label, val space: SpaceGrant?)

    /** A `stash-pair-answer` read back: the phone's label (with its key), and the space it grants, if any. */
    fun readPairAnswer(text: String): PairAnswer {
        val d = parseObject(text, "bad answer")
        if (d.str("kind") != "stash-pair-answer") bad("bad answer")
        if (d.int("v") != 1L) bad("newer answer")
        val id = d.str("deviceId")?.takeIf { DEVICE_ID.matches(it) } ?: bad("bad answer")
        val label = readLabel(d["label"], id)
        if (label.type != "phone") bad("bad answer")
        val space = d["space"].takeUnless { it == null || it is JsonNull }?.let(::readSpaceGrant)
        return PairAnswer(label, space)
    }

    /** A `stash-pair-reply` read back. */
    fun readPairReply(text: String): SpaceGrant {
        val d = parseObject(text, "bad reply")
        if (d.str("kind") != "stash-pair-reply") bad("bad reply")
        if (d.int("v") != 1L) bad("newer reply")
        return readSpaceGrant(d["space"])
    }

    // -------------------------------------------------------------------------------------------- key rotation (spec §5.2)

    /** HKDF info of a key envelope: `stash-sync key v1|<spaceId>|<epoch>|<deviceId>|<ephemeral pub>|<device pub>`. */
    fun keyInfo(spaceId: String, epoch: Int, deviceId: String, ephPub: ByteArray, devicePub: ByteArray) =
        "$INFO_KEY|$spaceId|$epoch|$deviceId|${Base64Url.encode(ephPub)}|${Base64Url.encode(devicePub)}"

    /** The proof key: HKDF(K of the previous epoch, salt = UTF-8 spaceId, info = "stash-sync rotate v1|<previous epoch>"). */
    fun rotateKey(prevKey: ByteArray, spaceId: String, prevEpoch: Int): ByteArray = SyncCrypto.hkdf(prevKey, utf8(spaceId), utf8("$INFO_ROTATE|$prevEpoch"))

    /** What the proof covers: `<spaceId>|<epoch>|<deviceId>|<k base64url>|<members joined by ",">`. */
    fun proofInput(spaceId: String, epoch: Int, deviceId: String, k: ByteArray, members: List<String>) =
        "$spaceId|$epoch|$deviceId|${Base64Url.encode(k)}|${members.joinToString(",")}"

    /**
     * ECIES to one device, proven under the previous key: an ephemeral P-256 key, ECDH with the device's public key (from its
     * authenticated label, never from the server alone), HKDF (salt = UTF-8 spaceId, info = [keyInfo]), AES-GCM over
     * `{ kind: "stash-key", v: 1, epoch, k, members, proof }` at place `key:<deviceId>`. [epoch] is the new one; [prevKey] is K
     * of `epoch − 1`; [members] are every device the new key goes to.
     */
    fun sealKeyFor(newKey: ByteArray, spaceId: String, epoch: Int, deviceId: String, devicePub: ByteArray, members: Collection<String>, prevKey: ByteArray): SyncEnvelope =
        sealKeyWith(newKey, spaceId, epoch, deviceId, devicePub, members, prevKey, SyncCrypto.newKeyPair(), SyncCrypto.randomBytes(SyncCrypto.NONCE_BYTES))

    /** [sealKeyFor] with a given ephemeral key and nonce: for the golden tests only. */
    internal fun sealKeyWith(
        newKey: ByteArray,
        spaceId: String,
        epoch: Int,
        deviceId: String,
        devicePub: ByteArray,
        members: Collection<String>,
        prevKey: ByteArray,
        eph: KeyPair,
        nonce: ByteArray,
    ): SyncEnvelope {
        val sorted = members.toSortedSet().toList()
        if (deviceId !in sorted) bad("device not a member")
        val proof = SyncCrypto.hmac(rotateKey(prevKey, spaceId, epoch - 1), utf8(proofInput(spaceId, epoch, deviceId, newKey, sorted)))
        val ephPub = SyncCrypto.rawPublicKey(eph.public as ECPublicKey)
        val k = SyncCrypto.hkdf(SyncCrypto.ecdh(eph.private, devicePub), utf8(spaceId), utf8(keyInfo(spaceId, epoch, deviceId, ephPub, devicePub)))
        val doc = buildJsonObject {
            put("kind", "stash-key")
            put("v", 1)
            put("epoch", epoch)
            put("k", Base64Url.encode(newKey))
            putJsonArray("members") { sorted.forEach { add(JsonPrimitive(it)) } }
            put("proof", Base64Url.encode(proof))
        }
        return SyncCrypto.sealWithNonce(k, spaceId, epoch, SyncCrypto.Place.key(deviceId), doc.toString(), nonce).copy(p = Base64Url.encode(ephPub))
    }

    class NextKey(val k: ByteArray, val epoch: Int, val members: List<String>)

    /**
     * The next space key from a key envelope. Accepted only for exactly the next epoch (`prevEpoch + 1`), only when this device is
     * among `members`, and only with a valid proof under [prevKey]: the server, which never had a space key, can't forge one. A device
     * more than one epoch behind walks `key/{e}` one epoch at a time; a missing step means it links again.
     */
    fun openKeyEnvelope(env: SyncEnvelope, spaceId: String, deviceId: String, devicePriv: PrivateKey, devicePub: ByteArray, prevKey: ByteArray, prevEpoch: Int): NextKey {
        if (env.e != prevEpoch + 1) bad("wrong epoch")
        val ephPub = b64(env.p ?: bad("bad envelope"))
        val k = SyncCrypto.hkdf(SyncCrypto.ecdh(devicePriv, ephPub), utf8(spaceId), utf8(keyInfo(spaceId, env.e, deviceId, ephPub, devicePub)))
        val d = parseObject(SyncCrypto.open(k, spaceId, SyncCrypto.Place.key(deviceId), env), "bad key document")
        if (d.str("kind") != "stash-key" || d.int("v") != 1L || d.int("epoch") != env.e.toLong()) bad("bad key document")
        val members = (d["members"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: bad("bad key document") }
            ?: bad("bad key document")
        val sorted = members.toSortedSet().toList()
        if (sorted != members || members.any { !DEVICE_ID.matches(it) } || deviceId !in members) bad("bad key document")
        val newKey = b64(d.str("k") ?: bad("bad key document"))
        if (newKey.size != SyncCrypto.KEY_BYTES) bad("bad key document")
        val want = SyncCrypto.hmac(rotateKey(prevKey, spaceId, prevEpoch), utf8(proofInput(spaceId, env.e, deviceId, newKey, sorted)))
        if (!SyncCrypto.sameBytes(want, b64(d.str("proof") ?: bad("bad key document")))) bad("bad proof")
        return NextKey(newKey, env.e, sorted)
    }
}

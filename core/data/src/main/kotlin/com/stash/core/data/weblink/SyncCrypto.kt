package com.stash.core.data.weblink

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** What travels: epoch, nonce, ciphertext+tag (base64url). A key envelope adds [p], the sender's ephemeral public key. */
@Serializable
data class SyncEnvelope(val e: Int, val n: String, val c: String, val p: String? = null)

class SyncCryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * sync-v1 crypto (stash-player `docs/sync-v1.md` "Envelopes", "Pairing", "Key rotation"; spec §5): P-256 ECDH, HKDF-SHA256 and
 * AES-256-GCM through the JCA only, the same algorithms Stash on the web uses through WebCrypto. Every plaintext is
 * gzip(UTF-8 JSON). Golden fixtures: `src/test/resources/sync/crypto-vectors.json`, a byte-for-byte copy of stash-player's.
 *
 * Why the JCA and not Tink (which `core:auth` uses for local secrets): Tink's hybrid encryption and keyset formats are its own
 * wire formats, not interoperable with WebCrypto, and it exposes no raw ECDH or HKDF. The JCA has every primitive on all supported
 * API levels (minSdk 26), and on API 31+ an Android Keystore EC key does ECDH through the same `KeyAgreement`, so the long-term
 * device key can stay in the Keystore without changing this code. Pure JVM: no Android classes, testable as a plain unit test.
 */
object SyncCrypto {
    const val AAD_PREFIX = "stash-sync/1"
    const val INFO_DATA = "stash-sync v1 data"
    const val INFO_PAIR_LABEL = "stash-sync pair label"
    const val INFO_PAIR = "stash-sync pair v1"
    const val INFO_KEY = "stash-sync key v1"
    const val NONCE_BYTES = 12
    const val KEY_BYTES = 32

    /** Most bytes a plaintext may inflate to; a reader refuses more (the library file's own cap). */
    const val MAX_PLAINTEXT = 64 * 1024 * 1024

    /** The QR's link, before the `#` fragment. */
    const val LINK_BASE = "https://stashfm.app/link"

    private val random = SecureRandom()
    private val json = Json { ignoreUnknownKeys = true }

    /** `stash-sync/1|<spaceId>|<epoch>|<place>`; pairing uses spaceId `pair` and epoch 0. */
    fun aad(spaceId: String, epoch: Int, place: String) = "$AAD_PREFIX|$spaceId|$epoch|$place"

    /** The places of sync-v1 "Places". */
    object Place {
        const val LOG = "log"
        const val CONFIG = "config"
        const val PAIR_LABEL = "label"
        fun snapshot(uptoSeq: Long, part: Int, count: Int) = "snapshot:$uptoSeq:$part/$count"
        fun now(device: String) = "now:$device"
        fun queue(device: String) = "queue:$device"
        fun label(device: String) = "label:$device"
        fun inbox(to: String, sendId: String, part: Int, count: Int) = "inbox:$to:$sendId:$part/$count"
        fun key(device: String) = "key:$device"
        fun pairAnswer(pairId: String) = "answer:$pairId"
        fun pairReply(pairId: String) = "reply:$pairId"
    }

    // -------------------------------------------------------------------------------------------- primitives

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also(random::nextBytes)

    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    /** HKDF-SHA256 (RFC 5869). An empty salt is HashLen zero bytes, as the RFC says (the JCA refuses an empty HMAC key). */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int = KEY_BYTES): ByteArray {
        require(length in 1..255 * 32) { "hkdf: bad length" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArrayOutputStream(length)
        var t = ByteArray(0)
        var i = 1
        while (out.size() < length) {
            mac.update(t)
            mac.update(info)
            mac.update(i.toByte())
            t = mac.doFinal()
            out.write(t, 0, minOf(t.size, length - out.size()))
            i++
        }
        return out.toByteArray()
    }

    /** AES-256-GCM with a 128-bit tag appended to the ciphertext (the JCA's and WebCrypto's layout). */
    fun aesSeal(key: ByteArray, nonce: ByteArray, aad: String, plain: ByteArray): ByteArray = gcm(Cipher.ENCRYPT_MODE, key, nonce, aad, plain)

    fun aesOpen(key: ByteArray, nonce: ByteArray, aad: String, sealed: ByteArray): ByteArray = try {
        gcm(Cipher.DECRYPT_MODE, key, nonce, aad, sealed)
    } catch (e: GeneralSecurityException) {
        throw SyncCryptoException("can't decrypt", e)
    }

    private fun gcm(mode: Int, key: ByteArray, nonce: ByteArray, aad: String, input: ByteArray): ByteArray {
        if (key.size != KEY_BYTES) throw SyncCryptoException("key: 32 bytes")
        if (nonce.size != NONCE_BYTES) throw SyncCryptoException("nonce: 12 bytes")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return c.doFinal(input)
    }

    fun gzip(b: ByteArray): ByteArray = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(b) } }.toByteArray()

    /** Inflates [b], refusing more than [max] bytes (a small envelope can't become a huge string). */
    fun gunzip(b: ByteArray, max: Int = MAX_PLAINTEXT): ByteArray = try {
        GZIPInputStream(ByteArrayInputStream(b)).use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (out.size() + n > max) throw SyncCryptoException("too big")
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    } catch (e: SyncCryptoException) {
        throw e
    } catch (e: Exception) {
        throw SyncCryptoException("not gzip", e)
    }

    // -------------------------------------------------------------------------------------------- envelopes

    /** The data key of a space: HKDF-SHA256(K, salt = UTF-8 spaceId, info = "stash-sync v1 data"). */
    fun dataKey(spaceKey: ByteArray, spaceId: String): ByteArray = hkdf(spaceKey, spaceId.toByteArray(Charsets.UTF_8), INFO_DATA.toByteArray(Charsets.UTF_8))

    /** gzip(UTF-8 [jsonText]) sealed under [key] for (spaceId, epoch, place). [nonce] is for fixtures only; never reuse one. */
    fun seal(key: ByteArray, spaceId: String, epoch: Int, place: String, jsonText: String, nonce: ByteArray = randomBytes(NONCE_BYTES)): SyncEnvelope {
        val c = aesSeal(key, nonce, aad(spaceId, epoch, place), gzip(jsonText.toByteArray(Charsets.UTF_8)))
        return SyncEnvelope(epoch, Base64Url.encode(nonce), Base64Url.encode(c))
    }

    /** The JSON text of an envelope sealed for (spaceId, place); the epoch in the AAD is the envelope's own [SyncEnvelope.e]. */
    fun open(key: ByteArray, spaceId: String, place: String, env: SyncEnvelope): String {
        if (env.e < 0) throw SyncCryptoException("bad envelope")
        val nonce = b64(env.n)
        val sealed = b64(env.c)
        return utf8(gunzip(aesOpen(key, nonce, aad(spaceId, env.e, place), sealed)))
    }

    /** Most gzip bytes one part carries: sealed and in base64url it still fits a 1 MiB request with room for the JSON around it. */
    const val PART_BYTES = 768_000

    /** Most parts of one snapshot or send. */
    const val MAX_PARTS = 16

    /**
     * A document too big for one envelope (a snapshot, a send): gzip(UTF-8 [jsonText]) cut into slices of at most [partBytes], each
     * sealed at `placeOf(part, count)`, so the server can't reorder, drop or mix parts. [nonces] are for fixtures only.
     */
    fun sealParts(
        key: ByteArray,
        spaceId: String,
        epoch: Int,
        placeOf: (part: Int, count: Int) -> String,
        jsonText: String,
        partBytes: Int = PART_BYTES,
        nonces: List<ByteArray>? = null,
    ): List<SyncEnvelope> {
        val gz = gzip(jsonText.toByteArray(Charsets.UTF_8))
        val count = maxOf(1, (gz.size + partBytes - 1) / partBytes)
        if (count > MAX_PARTS) throw SyncCryptoException("too big")
        return List(count) { i ->
            val nonce = nonces?.getOrNull(i) ?: randomBytes(NONCE_BYTES)
            val slice = gz.copyOfRange(minOf(i * partBytes, gz.size), minOf((i + 1) * partBytes, gz.size))
            SyncEnvelope(epoch, Base64Url.encode(nonce), Base64Url.encode(aesSeal(key, nonce, aad(spaceId, epoch, placeOf(i, count)), slice)))
        }
    }

    /** The JSON text of the parts of [sealParts], given in order (one epoch). */
    fun openParts(key: ByteArray, spaceId: String, placeOf: (part: Int, count: Int) -> String, envs: List<SyncEnvelope>): String {
        val count = envs.size
        if (count !in 1..MAX_PARTS || envs.any { it.e != envs[0].e || it.e < 0 }) throw SyncCryptoException("bad envelope")
        val gz = ByteArrayOutputStream()
        envs.forEachIndexed { i, env -> gz.write(aesOpen(key, b64(env.n), aad(spaceId, env.e, placeOf(i, count)), b64(env.c))) }
        return utf8(gunzip(gz.toByteArray()))
    }

    private fun utf8(b: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(b)).toString()
    } catch (e: java.nio.charset.CharacterCodingException) {
        throw SyncCryptoException("bad plaintext", e)
    }

    private fun b64(s: String): ByteArray = try {
        Base64Url.decode(s)
    } catch (e: IllegalArgumentException) {
        throw SyncCryptoException("bad envelope", e)
    }

    /** A device token's server-side form: base64url(SHA-256(token bytes)). */
    fun tokenHash(token: ByteArray): String = Base64Url.encode(sha256(token))

    /** `Stash-Device <deviceId>:<token>`. */
    fun authHeader(deviceId: String, token: ByteArray) = "Stash-Device $deviceId:${Base64Url.encode(token)}"

    // -------------------------------------------------------------------------------------------- P-256

    private val p256: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
    }

    private fun unsigned32(n: BigInteger): ByteArray {
        val b = n.toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            else -> ByteArray(32 - b.size) + b
        }
    }

    /** A public key as 65 bytes: 0x04 ‖ X ‖ Y. */
    fun rawPublicKey(pub: ECPublicKey): ByteArray = byteArrayOf(4) + unsigned32(pub.w.affineX) + unsigned32(pub.w.affineY)

    /** A P-256 public key from 65 uncompressed bytes; refuses a point that isn't on the curve. */
    fun publicKey(raw: ByteArray): ECPublicKey {
        if (raw.size != 65 || raw[0] != 4.toByte()) throw SyncCryptoException("public key: 65 bytes, uncompressed")
        val x = BigInteger(1, raw.copyOfRange(1, 33))
        val y = BigInteger(1, raw.copyOfRange(33, 65))
        val curve = p256.curve
        val p = (curve.field as ECFieldFp).p
        val onCurve = x < p && y < p && y.modPow(BigInteger.valueOf(2), p) == x.pow(3).add(curve.a.multiply(x)).add(curve.b).mod(p)
        if (!onCurve) throw SyncCryptoException("public key: not on P-256")
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), p256)) as ECPublicKey
    }

    /** A private key from its 32-byte scalar (fixtures and tests; real keys are generated, the device key in the Keystore). */
    fun privateKey(d: ByteArray): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, d), p256))

    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()

    /** ECDH shared secret: the X coordinate, 32 bytes. */
    fun ecdh(mine: PrivateKey, theirRaw: ByteArray): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(mine)
        ka.doPhase(publicKey(theirRaw), true)
        val z = ka.generateSecret()
        return if (z.size < 32) ByteArray(32 - z.size) + z else z
    }

    // -------------------------------------------------------------------------------------------- pairing (spec §5.1)

    class QrLink(val pairId: String, val pairSecret: ByteArray, val eBPub: ByteArray)

    private val PAIR_ID = Regex("^[A-Za-z0-9_-]{22}$")

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
            if (secret.size != 16 || pub.size != 65 || pub[0] != 4.toByte()) null else QrLink(parts[1], secret, pub)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** The key of the browser's label in the pair slot: HKDF(pairSecret, empty salt, "stash-sync pair label"). */
    fun pairLabelKey(pairSecret: ByteArray): ByteArray = hkdf(pairSecret, ByteArray(0), INFO_PAIR_LABEL.toByteArray(Charsets.UTF_8))

    /** HKDF info of Kpair: `stash-sync pair v1|<pairId>|<eB.pub>|<eP.pub>` (keys base64url). */
    fun pairInfo(pairId: String, eBPub: ByteArray, ePPub: ByteArray) = "$INFO_PAIR|$pairId|${Base64Url.encode(eBPub)}|${Base64Url.encode(ePPub)}"

    /** Kpair = HKDF(ECDH(mine, theirs), salt = pairSecret, info = [pairInfo]). Both sides call it with their own private key. */
    fun pairKey(mine: PrivateKey, theirPub: ByteArray, pairSecret: ByteArray, pairId: String, eBPub: ByteArray, ePPub: ByteArray): ByteArray =
        hkdf(ecdh(mine, theirPub), pairSecret, pairInfo(pairId, eBPub, ePPub).toByteArray(Charsets.UTF_8))

    // -------------------------------------------------------------------------------------------- key rotation (spec §5.2)

    /** HKDF info of a key envelope: `stash-sync key v1|<spaceId>|<epoch>|<deviceId>|<ephemeral pub>|<device pub>`. */
    fun keyInfo(spaceId: String, epoch: Int, deviceId: String, ephPub: ByteArray, devicePub: ByteArray) =
        "$INFO_KEY|$spaceId|$epoch|$deviceId|${Base64Url.encode(ephPub)}|${Base64Url.encode(devicePub)}"

    /**
     * ECIES to one device: an ephemeral P-256 key, ECDH with the device's public key, HKDF (salt = UTF-8 spaceId, info = [keyInfo]),
     * AES-GCM over `{ "kind": "stash-key", "v": 1, "epoch", "k" }` at place `key:<deviceId>`. [eph] and [nonce] are for fixtures only.
     */
    fun sealKeyFor(
        newKey: ByteArray,
        spaceId: String,
        epoch: Int,
        deviceId: String,
        devicePub: ByteArray,
        eph: KeyPair = newKeyPair(),
        nonce: ByteArray = randomBytes(NONCE_BYTES),
    ): SyncEnvelope {
        val ephPub = rawPublicKey(eph.public as ECPublicKey)
        val k = hkdf(ecdh(eph.private, devicePub), spaceId.toByteArray(Charsets.UTF_8), keyInfo(spaceId, epoch, deviceId, ephPub, devicePub).toByteArray(Charsets.UTF_8))
        val doc = buildJsonObject {
            put("kind", "stash-key")
            put("v", 1)
            put("epoch", epoch)
            put("k", Base64Url.encode(newKey))
        }
        return seal(k, spaceId, epoch, Place.key(deviceId), doc.toString(), nonce).copy(p = Base64Url.encode(ephPub))
    }

    /** The new space key from a key envelope, with this device's long-term key pair. */
    fun openKeyEnvelope(env: SyncEnvelope, spaceId: String, deviceId: String, devicePriv: PrivateKey, devicePub: ByteArray): ByteArray {
        val ephPub = b64(env.p ?: throw SyncCryptoException("bad envelope"))
        val k = hkdf(ecdh(devicePriv, ephPub), spaceId.toByteArray(Charsets.UTF_8), keyInfo(spaceId, env.e, deviceId, ephPub, devicePub).toByteArray(Charsets.UTF_8))
        val text = open(k, spaceId, Place.key(deviceId), env)
        val key = try {
            val doc = json.parseToJsonElement(text).jsonObject
            val ok = doc["kind"]?.jsonPrimitive?.content == "stash-key" && doc["epoch"]?.jsonPrimitive?.intOrNull == env.e
            doc["k"]?.jsonPrimitive?.content?.takeIf { ok }?.let(Base64Url::decode)
        } catch (e: IllegalArgumentException) {
            null
        }
        if (key == null || key.size != KEY_BYTES) throw SyncCryptoException("bad key document")
        return key
    }
}

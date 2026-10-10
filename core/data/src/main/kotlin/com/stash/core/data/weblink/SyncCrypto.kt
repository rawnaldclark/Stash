package com.stash.core.data.weblink

import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
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
import java.security.spec.ECPrivateKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater
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
 * sync-v1 crypto primitives and envelopes (stash-player `docs/sync-v1.md` §3.1–3.3; spec §5): P-256 ECDH, HKDF-SHA256,
 * HMAC-SHA256 and AES-256-GCM through the JCA only, the same algorithms Stash on the web uses through WebCrypto. Every plaintext is
 * gzip(UTF-8 JSON). Pairing and key rotation are in [SyncKeys]. Golden fixtures: `src/test/resources/sync/crypto-vectors.json`, a
 * byte-for-byte copy of stash-player's.
 *
 * Why the JCA and not Tink (which `core:auth` uses for local secrets): Tink's hybrid encryption and keyset formats are its own
 * wire formats, not interoperable with WebCrypto, and it exposes no raw ECDH or HKDF. The JCA has every primitive on all supported
 * API levels (minSdk 26), and on API 31+ an Android Keystore EC key does ECDH through the same `KeyAgreement`, so the long-term
 * device key can stay in the Keystore without changing this code. Pure JVM: no Android classes; the Android providers are
 * covered by `SyncCryptoAndroidTest` (androidTest).
 */
object SyncCrypto {
    const val AAD_PREFIX = "stash-sync/1"
    const val INFO_DATA = "stash-sync v1 data"
    const val NONCE_BYTES = 12
    const val KEY_BYTES = 32

    /** Most bytes a plaintext may inflate to; a reader refuses more (the library file's own cap). */
    const val MAX_PLAINTEXT = 64 * 1024 * 1024

    /** Most gzip bytes one part carries: sealed and in base64url it still fits a 1 MiB request with room for the JSON around it. */
    const val PART_BYTES = 768_000

    /** Most parts of one snapshot or send. */
    const val MAX_PARTS = 16

    private val random = SecureRandom()

    /** `stash-sync/1|<spaceId>|<epoch>|<place>`; pairing uses spaceId `pair` and epoch 0. */
    fun aad(spaceId: String, epoch: Int, place: String) = "$AAD_PREFIX|$spaceId|$epoch|$place"

    /** The places of sync-v1 §3.2. */
    object Place {
        const val LOG = "log"
        const val CONFIG = "config"
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

    fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    /** Constant-time comparison. */
    fun sameBytes(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

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

    /** Length of a gzip member's header (RFC 1952: FEXTRA, FNAME, FCOMMENT, FHCRC), or -1 when it isn't one. */
    private fun gzipHeaderLength(b: ByteArray): Int {
        fun u(i: Int) = b[i].toInt() and 0xff
        if (b.size < 18 || u(0) != 0x1f || u(1) != 0x8b || u(2) != 8 || u(3) and 0xe0 != 0) return -1
        val flg = u(3)
        var i = 10
        if (flg and 4 != 0) i += 2 + (u(i) or (u(i + 1) shl 8))
        if (flg and 8 != 0) while (i < b.size && b[i++] != 0.toByte()) Unit
        if (flg and 16 != 0) while (i < b.size && b[i++] != 0.toByte()) Unit
        if (flg and 2 != 0) i += 2
        return if (i < b.size) i else -1
    }

    /**
     * Inflates exactly one gzip member, refusing more than [max] bytes: the member's deflate stream must end exactly 8 bytes before
     * the input does, and those 8 bytes must be the output's CRC-32 and length. So trailing bytes and second members (an empty first
     * one too) are refused. The web checks the same.
     */
    fun gunzip(b: ByteArray, max: Int = MAX_PLAINTEXT): ByteArray {
        val h = gzipHeaderLength(b)
        if (h < 0) throw SyncCryptoException("not gzip")
        val inflater = Inflater(true)
        val out = ByteArrayOutputStream()
        try {
            inflater.setInput(b, h, b.size - h)
            val buf = ByteArray(64 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw SyncCryptoException("not gzip")
                if (out.size() + n > max) throw SyncCryptoException("too big")
                out.write(buf, 0, n)
            }
            if (inflater.remaining != 8) throw SyncCryptoException("not gzip")
        } catch (e: DataFormatException) {
            throw SyncCryptoException("not gzip", e)
        } finally {
            inflater.end()
        }
        val bytes = out.toByteArray()
        val t = ByteBuffer.wrap(b, b.size - 8, 8).order(ByteOrder.LITTLE_ENDIAN)
        val crc = CRC32().apply { update(bytes) }.value
        if (t.int.toLong() and 0xffffffffL != crc || t.int.toLong() and 0xffffffffL != bytes.size.toLong()) throw SyncCryptoException("not gzip")
        return bytes
    }

    internal fun utf8(b: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(b)).toString()
    } catch (e: CharacterCodingException) {
        throw SyncCryptoException("bad plaintext", e)
    }

    /** base64url from an envelope or a document; anything malformed is a bad envelope. */
    internal fun b64(s: String): ByteArray = try {
        Base64Url.decode(s)
    } catch (e: IllegalArgumentException) {
        throw SyncCryptoException("bad envelope", e)
    }

    // -------------------------------------------------------------------------------------------- envelopes

    /** The data key of a space: HKDF-SHA256(K, salt = UTF-8 spaceId, info = "stash-sync v1 data"). */
    fun dataKey(spaceKey: ByteArray, spaceId: String): ByteArray = hkdf(spaceKey, spaceId.toByteArray(Charsets.UTF_8), INFO_DATA.toByteArray(Charsets.UTF_8))

    /** gzip(UTF-8 [jsonText]) sealed under [key] for (spaceId, epoch, place), with a fresh random nonce. */
    fun seal(key: ByteArray, spaceId: String, epoch: Int, place: String, jsonText: String): SyncEnvelope =
        sealWithNonce(key, spaceId, epoch, place, jsonText, randomBytes(NONCE_BYTES))

    /** [seal] with a given nonce: for the golden tests only (a nonce must never repeat under one key). */
    internal fun sealWithNonce(key: ByteArray, spaceId: String, epoch: Int, place: String, jsonText: String, nonce: ByteArray): SyncEnvelope {
        val c = aesSeal(key, nonce, aad(spaceId, epoch, place), gzip(jsonText.toByteArray(Charsets.UTF_8)))
        return SyncEnvelope(epoch, Base64Url.encode(nonce), Base64Url.encode(c))
    }

    /** The JSON text of an envelope sealed for (spaceId, place); the epoch in the AAD is the envelope's own [SyncEnvelope.e]. */
    fun open(key: ByteArray, spaceId: String, place: String, env: SyncEnvelope, max: Int = MAX_PLAINTEXT): String {
        if (env.e < 0) throw SyncCryptoException("bad envelope")
        return utf8(gunzip(aesOpen(key, b64(env.n), aad(spaceId, env.e, place), b64(env.c)), max))
    }

    /**
     * A document too big for one envelope (a snapshot, a send): gzip(UTF-8 [jsonText]) cut into slices of at most [partBytes], each
     * sealed at `placeOf(part, count)`, so the server can't reorder, drop or mix parts.
     */
    fun sealParts(key: ByteArray, spaceId: String, epoch: Int, placeOf: (part: Int, count: Int) -> String, jsonText: String, partBytes: Int = PART_BYTES) =
        sealPartsWith(key, spaceId, epoch, placeOf, jsonText, partBytes) { randomBytes(NONCE_BYTES) }

    /** [sealParts] with given nonces: for the golden tests only. */
    internal fun sealPartsWith(
        key: ByteArray,
        spaceId: String,
        epoch: Int,
        placeOf: (part: Int, count: Int) -> String,
        jsonText: String,
        partBytes: Int,
        nonceOf: (Int) -> ByteArray,
    ): List<SyncEnvelope> {
        val gz = gzip(jsonText.toByteArray(Charsets.UTF_8))
        val count = maxOf(1, (gz.size + partBytes - 1) / partBytes)
        if (count > MAX_PARTS) throw SyncCryptoException("too big")
        return List(count) { i ->
            val nonce = nonceOf(i)
            val slice = gz.copyOfRange(minOf(i * partBytes, gz.size), minOf((i + 1) * partBytes, gz.size))
            SyncEnvelope(epoch, Base64Url.encode(nonce), Base64Url.encode(aesSeal(key, nonce, aad(spaceId, epoch, placeOf(i, count)), slice)))
        }
    }

    /** The JSON text of the parts of [sealParts], given in order (one epoch). */
    fun openParts(key: ByteArray, spaceId: String, placeOf: (part: Int, count: Int) -> String, envs: List<SyncEnvelope>, max: Int = MAX_PLAINTEXT): String {
        val count = envs.size
        if (count !in 1..MAX_PARTS || envs.any { it.e != envs[0].e || it.e < 0 }) throw SyncCryptoException("bad envelope")
        val gz = ByteArrayOutputStream()
        envs.forEachIndexed { i, env -> gz.write(aesOpen(key, b64(env.n), aad(spaceId, env.e, placeOf(i, count)), b64(env.c))) }
        return utf8(gunzip(gz.toByteArray(), max))
    }

    /** A device token's server-side form: base64url(SHA-256(token bytes)). */
    fun tokenHash(token: ByteArray): String = Base64Url.encode(sha256(token))

    /** `Stash-Device <deviceId>:<token>`. */
    fun authHeader(deviceId: String, token: ByteArray) = "Stash-Device $deviceId:${Base64Url.encode(token)}"

    // -------------------------------------------------------------------------------------------- P-256

    private val p256: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
    }

    /** SubjectPublicKeyInfo header of a named-curve P-256 key: a key built from it keeps the curve's name on every provider. */
    private val P256_SPKI_PREFIX = byteArrayOf(
        0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x02, 0x01, 0x06, 0x08, 0x2a,
        0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00,
    )

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

    /**
     * A P-256 public key from 65 uncompressed bytes; refuses a point that isn't on the curve. Built from a named-curve
     * SubjectPublicKeyInfo (not an explicit-parameters spec), so Conscrypt and the Android Keystore's ECDH (API 31+) accept it.
     */
    fun publicKey(raw: ByteArray): ECPublicKey {
        if (raw.size != 65 || raw[0] != 4.toByte()) throw SyncCryptoException("public key: 65 bytes, uncompressed")
        val x = BigInteger(1, raw.copyOfRange(1, 33))
        val y = BigInteger(1, raw.copyOfRange(33, 65))
        val curve = p256.curve
        val p = (curve.field as ECFieldFp).p
        val onCurve = x < p && y < p && y.modPow(BigInteger.valueOf(2), p) == x.pow(3).add(curve.a.multiply(x)).add(curve.b).mod(p)
        if (!onCurve) throw SyncCryptoException("public key: not on P-256")
        return try {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(P256_SPKI_PREFIX + raw)) as ECPublicKey
        } catch (e: GeneralSecurityException) {
            throw SyncCryptoException("public key: not on P-256", e)
        }
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
}

package com.stash.data.download.lossless.squid

import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Solves the proof-of-work challenge that gates `qobuz.squid.wtf`'s
 * download endpoints. The challenge is the
 * [ALTCHA library v2 PoW format](https://github.com/altcha-org/altcha-lib)
 * — given (`nonce`, `salt`, `keyPrefix`, `keyLength`, `cost`,
 * `algorithm = "SHA-256"`), find the smallest `counter` whose derived
 * key starts with `keyPrefix`. Derivation is plain repeated SHA-256
 * (NOT HKDF or PBKDF2 — the field names superficially resemble HKDF
 * but the algorithm spelt out in `altcha-lib/v2/algorithms/sha.ts` is
 * iterated raw SHA-256).
 *
 * Sketch:
 * ```
 * password = nonce || uint32_BE(counter)
 * digest   = SHA-256(salt || password)
 * repeat (cost - 1):  digest = SHA-256(digest)
 * derivedKey = digest[0 ..< keyLength]
 * if derivedKey.startsWith(keyPrefix): return counter
 * else: counter++
 * ```
 *
 * For the parameters squid.wtf currently emits (cost=1000,
 * keyLength=16, keyPrefix=`00` → first byte must be 0), expected
 * counter is small (~128 attempts, since one-byte prefix matches at
 * 1/256). Total work ≈ 128 × 1000 = 128k SHA-256 invocations — well
 * under a second on a modern Android device.
 *
 * Pure JVM — no Android imports — so it lives in main and runs in
 * unit tests against the published altcha-lib test vectors.
 */
internal object AltchaSolver {

    /**
     * Brute-force the counter for the given [parameters] and return a
     * [Solution] suitable for posting to `/api/altcha/verify`.
     *
     * Throws [IllegalStateException] when no solution is found within
     * [maxCounter] attempts — that should never happen for a
     * well-formed challenge with a one-byte prefix; the cap is a
     * safety net against pathological server-side inputs.
     */
    fun solve(parameters: ChallengeParameters, maxCounter: Int = 1_000_000): Solution {
        val nonce = hexToBytes(parameters.nonce)
        val salt = hexToBytes(parameters.salt)
        val prefix = hexToBytes(parameters.keyPrefix)
        val cost = parameters.cost.coerceAtLeast(1)
        val keyLength = parameters.keyLength

        // Allocate the password buffer once and overwrite the trailing
        // counter on each iteration — saves ~150k allocations/sec on
        // the inner loop without changing the algorithm.
        val pwdBuffer = ByteArray(nonce.size + COUNTER_BYTES)
        System.arraycopy(nonce, 0, pwdBuffer, 0, nonce.size)
        val pwdView = ByteBuffer.wrap(pwdBuffer)

        val start = System.currentTimeMillis()
        var counter = 0
        while (counter <= maxCounter) {
            // Big-endian uint32 at offset = nonce.size.
            pwdView.putInt(nonce.size, counter)

            val derived = deriveSha256(salt, pwdBuffer, cost, keyLength)
            if (startsWith(derived, prefix)) {
                return Solution(
                    counter = counter,
                    derivedKey = bytesToHex(derived),
                    timeMs = System.currentTimeMillis() - start,
                )
            }
            counter++
        }
        error("AltchaSolver: no solution under counter=$maxCounter for $parameters")
    }

    /**
     * Deterministic key derivation matching `altcha-lib/v2/algorithms/sha.ts`.
     * Internal so tests can hit it directly with the published vectors
     * without going through the brute-force loop.
     */
    internal fun deriveSha256(
        salt: ByteArray,
        password: ByteArray,
        cost: Int,
        keyLength: Int,
    ): ByteArray {
        // First round: hash(salt || password). Subsequent rounds:
        // hash(previous_digest). MessageDigest.digest() resets state,
        // so we can reuse a single instance across iterations.
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        md.update(password)
        var digest = md.digest()
        repeat(cost - 1) {
            digest = md.digest(digest)
        }
        return if (keyLength >= digest.size) digest else digest.copyOf(keyLength)
    }

    private fun startsWith(haystack: ByteArray, needle: ByteArray): Boolean {
        if (haystack.size < needle.size) return false
        for (i in needle.indices) {
            if (haystack[i] != needle[i]) return false
        }
        return true
    }

    private const val COUNTER_BYTES = 4
}

/**
 * Verbatim subset of the JSON the server emits at
 * `/api/altcha/challenge`. We do NOT reorder keys or re-encode — the
 * `signature` is an HMAC over a canonical-JSON serialisation we don't
 * own and any byte change breaks server-side verification. Caller is
 * responsible for round-tripping the parameters back unchanged.
 *
 * `expiresAt` is informational here; client doesn't enforce it but the
 * server will reject expired challenges at verify time.
 */
data class ChallengeParameters(
    val algorithm: String,
    val cost: Int,
    val expiresAt: Long,
    val keyLength: Int,
    val keyPrefix: String,
    val nonce: String,
    val salt: String,
)

/**
 * Outcome of [AltchaSolver.solve]. Wire format defined by
 * altcha-lib's `Payload.solution` shape:
 *  - `counter`: the integer that hashed to the prefix
 *  - `derivedKey`: lowercase-hex of the full keyLength-byte digest
 *  - `time`: ms taken — server logs it, may use it for scoring; not
 *    security-critical
 */
data class Solution(
    val counter: Int,
    val derivedKey: String,
    val timeMs: Long,
)

internal fun hexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "odd-length hex string: '$hex'" }
    return ByteArray(hex.length / 2) { i ->
        ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
    }
}

internal fun bytesToHex(bytes: ByteArray): String =
    bytes.joinToString(separator = "") { "%02x".format(it) }

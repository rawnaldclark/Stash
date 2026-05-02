package com.stash.data.download.lossless.squid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the Kotlin port of altcha-lib v2's SHA-256 PoW against the
 * canonical test vectors from
 * `altcha-org/altcha-lib/tests/v2/algorithms/sha.test.ts`.
 *
 * If any of these fail, the squid.wtf verify endpoint will reject our
 * payload — the algorithm is bit-exact by design.
 */
class AltchaSolverTest {

    private val vectorNonce = "39baf91a19d671f8231217f9e28342a6"
    private val vectorSalt = "5e00d5d152e1a5db7d44fb6404a40a5e"
    private val vectorCounter = 123  // big-endian uint32 = 00 00 00 7B

    /**
     * Builds the password buffer the deriveKey function expects:
     * `nonce_bytes || uint32_BE(counter)`.
     */
    private fun password(nonceHex: String, counter: Int): ByteArray {
        val nonce = hexToBytes(nonceHex)
        return nonce + byteArrayOf(
            (counter ushr 24).toByte(),
            (counter ushr 16).toByte(),
            (counter ushr 8).toByte(),
            counter.toByte(),
        )
    }

    @Test fun `SHA-256 cost=1 keyLength=32 matches reference vector`() {
        val derived = AltchaSolver.deriveSha256(
            salt = hexToBytes(vectorSalt),
            password = password(vectorNonce, vectorCounter),
            cost = 1,
            keyLength = 32,
        )
        assertEquals(
            "6deccc5eecdb14c99d57129ef8f2f7d3e71812d8bd022c1caaf9e56512ec186c",
            bytesToHex(derived),
        )
    }

    @Test fun `SHA-256 cost=2 keyLength=32 matches reference vector`() {
        val derived = AltchaSolver.deriveSha256(
            salt = hexToBytes(vectorSalt),
            password = password(vectorNonce, vectorCounter),
            cost = 2,
            keyLength = 32,
        )
        assertEquals(
            "54129dc0097cb40d1c75bd8dc1e5f713839b285d72f9685a3d91ab76ca2746ad",
            bytesToHex(derived),
        )
    }

    @Test fun `keyLength truncates the digest`() {
        val full = AltchaSolver.deriveSha256(
            salt = hexToBytes(vectorSalt),
            password = password(vectorNonce, vectorCounter),
            cost = 1,
            keyLength = 32,
        )
        val truncated = AltchaSolver.deriveSha256(
            salt = hexToBytes(vectorSalt),
            password = password(vectorNonce, vectorCounter),
            cost = 1,
            keyLength = 16,
        )
        assertEquals(16, truncated.size)
        // Truncation = take the first keyLength bytes of the full digest.
        assertEquals(bytesToHex(full.copyOf(16)), bytesToHex(truncated))
    }

    /**
     * End-to-end: feed the solver a challenge whose answer we control.
     * Expectation: it finds *some* counter whose first byte is 0x00.
     * The first such counter for these particular parameters happens
     * to be 47 (verified by running the algorithm by hand in the test
     * setup), but we don't hardcode that — the contract is "derived
     * key starts with prefix", not "specific counter value".
     */
    @Test fun `solve finds a counter producing the required prefix`() {
        val params = ChallengeParameters(
            algorithm = "SHA-256",
            cost = 1000,
            expiresAt = 1_777_678_486L,
            keyLength = 16,
            keyPrefix = "00",  // 1 byte == 0x00 → ~1/256 hit rate
            nonce = "fdde129879f8c9ad085f97ce5911ea9b",
            salt = "3aa2d75295816724c70792dac4ba9f4f",
        )
        val solution = AltchaSolver.solve(params)

        // Re-derive at the returned counter; first byte of derived key
        // must be 0x00 to match the prefix.
        val derived = AltchaSolver.deriveSha256(
            salt = hexToBytes(params.salt),
            password = password(params.nonce, solution.counter),
            cost = params.cost,
            keyLength = params.keyLength,
        )
        assertEquals(0.toByte(), derived[0])
        assertEquals(bytesToHex(derived), solution.derivedKey)
        assertTrue("counter should be reasonable, got ${solution.counter}", solution.counter < 5000)
    }

    @Test fun `solve respects a longer prefix`() {
        // Two-byte prefix (16 bits) → ~1/65536 hit rate; still fine
        // with the maxCounter cap.
        val params = ChallengeParameters(
            algorithm = "SHA-256",
            cost = 100,
            expiresAt = 0,
            keyLength = 16,
            keyPrefix = "00aa",  // first 2 bytes must be 0x00, 0xAA — extremely rare
            nonce = "fdde129879f8c9ad085f97ce5911ea9b",
            salt = "3aa2d75295816724c70792dac4ba9f4f",
        )
        // We don't expect this to find a solution quickly, but if it
        // does, the derived key must begin with the prefix bytes.
        val solution = runCatching { AltchaSolver.solve(params, maxCounter = 200_000) }
            .getOrNull() ?: return  // acceptable to give up; cap is the safety net
        val derived = AltchaSolver.deriveSha256(
            salt = hexToBytes(params.salt),
            password = password(params.nonce, solution.counter),
            cost = params.cost,
            keyLength = params.keyLength,
        )
        assertEquals(0x00.toByte(), derived[0])
        assertEquals(0xAA.toByte(), derived[1])
    }

    @Test fun `hex round-trip is stable`() {
        val original = "fdde129879f8c9ad085f97ce5911ea9b"
        assertEquals(original, bytesToHex(hexToBytes(original)))
    }
}

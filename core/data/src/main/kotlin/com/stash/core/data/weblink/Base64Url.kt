package com.stash.core.data.weblink

import java.util.Base64

/**
 * base64url as sync-v1 writes it (stash-player `docs/sync-v1.md` "Bytes as text"): RFC 4648 §5 alphabet, no padding. The reader
 * is strict: padding, whitespace, `+` / `/`, a length of 4n+1 or non-zero unused bits are refused, so a value has one spelling.
 */
object Base64Url {
    private val ALPHABET = Regex("^[A-Za-z0-9_-]*$")
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)

    /** The bytes, or [IllegalArgumentException] for anything that isn't canonical unpadded base64url. */
    fun decode(s: String): ByteArray {
        require(ALPHABET.matches(s) && s.length % 4 != 1) { "base64url: bad input" }
        val bytes = decoder.decode(s)
        require(encode(bytes) == s) { "base64url: non-canonical" }
        return bytes
    }
}

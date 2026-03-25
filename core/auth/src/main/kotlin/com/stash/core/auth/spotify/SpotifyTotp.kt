package com.stash.core.auth.spotify

import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

/**
 * Generates TOTP codes for Spotify's token endpoint.
 *
 * Spotify's `/api/token` endpoint requires a time-based one-time password to prevent
 * automated access. The algorithm is standard RFC 6238 HMAC-SHA1 TOTP, but with a
 * Spotify-specific secret derivation scheme.
 *
 * Derivation steps:
 * 1. XOR each cipher byte with a positional key: `(index % 33) + 9`
 * 2. Concatenate the string representations of the transformed integers
 * 3. Hex-encode the concatenated string's UTF-8 bytes
 * 4. Treat the hex string as a Base32-encoded value and decode it to get the HMAC secret
 * 5. Standard HMAC-SHA1 TOTP with 6 digits and a 30-second interval
 */
object SpotifyTotp {

    /**
     * Generate the TOTP code for Spotify's token endpoint.
     *
     * @param serverTimeSeconds The current server time in epoch seconds. Using Spotify's
     *        server time (from the HTTP Date header) avoids clock-skew issues.
     * @return A zero-padded 6-digit TOTP code string.
     */
    fun generate(serverTimeSeconds: Long): String {
        val secret = deriveSecret()
        return generateTotp(secret, serverTimeSeconds)
    }

    /**
     * Derive the HMAC-SHA1 secret from the static cipher bytes.
     *
     * @return The raw secret bytes suitable for use as an HMAC key.
     */
    private fun deriveSecret(): ByteArray {
        // Step 1: XOR transform each cipher byte with its positional key
        val transformed = SpotifyAuthConfig.SECRET_CIPHER.mapIndexed { i, byte ->
            byte xor ((i % 33) + 9)
        }

        // Step 2: Concatenate string representations of the transformed integers
        val joined = transformed.joinToString("") { it.toString() }

        // Step 3: Hex-encode the UTF-8 bytes of the concatenated string
        val hexStr = joined.toByteArray(Charsets.UTF_8).joinToString("") {
            String.format("%02x", it)
        }

        // Step 4: Base32-decode the hex string to produce the HMAC secret
        return base32Decode(hexStr.uppercase())
    }

    /**
     * Standard RFC 6238 TOTP using HMAC-SHA1.
     *
     * @param secret The HMAC key bytes.
     * @param timeSeconds The current time in epoch seconds.
     * @return A zero-padded TOTP code string.
     */
    private fun generateTotp(secret: ByteArray, timeSeconds: Long): String {
        val counter = timeSeconds / SpotifyAuthConfig.TOTP_INTERVAL
        val counterBytes = ByteBuffer.allocate(8).putLong(counter).array()

        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret, "HmacSHA1"))
        val hash = mac.doFinal(counterBytes)

        // Dynamic truncation per RFC 4226
        val offset = hash[hash.size - 1].toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)

        val otp = binary % 10.0.pow(SpotifyAuthConfig.TOTP_DIGITS).toInt()
        return otp.toString().padStart(SpotifyAuthConfig.TOTP_DIGITS, '0')
    }

    /**
     * Decode a Base32-encoded string (RFC 4648) into raw bytes.
     *
     * @param input The Base32 string (padding characters are stripped automatically).
     * @return The decoded byte array.
     */
    private fun base32Decode(input: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val cleanInput = input.replace("=", "").uppercase()

        val output = mutableListOf<Byte>()
        var buffer = 0
        var bitsLeft = 0

        for (char in cleanInput) {
            val value = alphabet.indexOf(char)
            if (value < 0) continue
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                output.add((buffer shr bitsLeft).toByte())
                buffer = buffer and ((1 shl bitsLeft) - 1)
            }
        }

        return output.toByteArray()
    }
}

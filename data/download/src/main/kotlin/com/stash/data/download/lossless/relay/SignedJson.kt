package com.stash.data.download.lossless.relay

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * The envelope `lossless.json` and the add-on index share (add-ons plan phase 4 §1.4, §1.7): a JSON
 * file at `<url>` and, at `<url>.sig`, the base64 of a DER ECDSA signature (SHA-256, P-256) over
 * its exact bytes. Both are verified against the one public key the app ships, so each reader also
 * checks that the file is the kind it expects (the index names its `type`).
 *
 * The fetches throw [IOException] with a message of our own only (`HTTP 404`, `empty body`,
 * `body over N bytes`): a library's message can carry the host.
 */
internal object SignedJson {

    /**
     * True when [sigB64] is a signature of [bytes] by the key [publicKeyB64] holds (base64 of the
     * SubjectPublicKeyInfo DER). Checked over the bytes exactly as fetched, never a
     * re-serialisation. Never throws: anything malformed is simply not a valid signature.
     */
    fun verify(bytes: ByteArray, sigB64: String, publicKeyB64: String): Boolean = runCatching {
        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyB64)))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(pub)
            update(bytes)
            verify(Base64.getDecoder().decode(sigB64))
        }
    }.getOrDefault(false)

    /** `GET [url]`: its body, at most [maxBytes] (a hostile or misconfigured host must not exhaust memory). */
    fun getBytes(client: OkHttpClient, url: String, maxBytes: Long): ByteArray =
        (get(client, url, maxBytes, etag = null) as Fetched.Body).bytes

    /** What a conditional [get] came to. */
    sealed interface Fetched {
        /** `304`: the copy the [etag][get] names is still the one served. */
        data object NotModified : Fetched

        class Body(val bytes: ByteArray, val etag: String?) : Fetched
    }

    /**
     * `GET [url]`, with `If-None-Match: [etag]` when one is given, so an unchanged file costs a 304
     * and no body. Any other status but 2xx throws.
     */
    fun get(client: OkHttpClient, url: String, maxBytes: Long, etag: String?): Fetched {
        val req = Request.Builder().url(url).header("Accept", "*/*").get()
            .apply { if (etag != null) header("If-None-Match", etag) }
            .build()
        client.newCall(req).execute().use { r ->
            if (etag != null && r.code == 304) return Fetched.NotModified
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val src = r.body?.source() ?: throw IOException("empty body")
            // request(n) reads until n bytes are buffered or EOF; true = at least n available.
            if (src.request(maxBytes + 1)) throw IOException("body over $maxBytes bytes")
            return Fetched.Body(src.readByteArray(), r.header("ETag")?.takeIf { it.isNotBlank() && it.length <= 256 && it.all { c -> c in ' '..'~' } })
        }
    }
}

package com.stash.data.cloud.net

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream

/** The link stopped working (expired or revoked: 401/403/404/410); resolve a new one and retry. */
class LinkExpiredException(val status: Int) : IOException("link answered $status")

/**
 * Reads `[offset, offset + length)` of a file through a provider's pre-authenticated link
 * (OneDrive `downloadUrl`, Dropbox temporary link). No Authorization header ever goes to these
 * hosts: the link itself is the credential.
 *
 * A server may ignore `Range` and answer 200 with the whole file. Then the bytes before
 * [offset] are read and dropped, but only up to [maxSkipBytes]: a tail read (an MP4 `moov` at
 * the end) on such a server would download the whole song, so it fails instead and the scanner
 * falls back to folder names.
 */
class RangeFetcher(
    private val client: OkHttpClient,
    private val maxSkipBytes: Long = 4L * 1024 * 1024,
) {
    suspend fun fetch(url: String, offset: Long, length: Int): ByteArray {
        require(offset >= 0 && length > 0)
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$offset-${offset + length - 1}")
            // Identity: a gzip-transcoded body would make byte offsets meaningless.
            .header("Accept-Encoding", "identity")
            .build()
        client.newCall(request).await().use { response ->
            when (response.code) {
                206 -> return readUpTo(response.body!!.byteStream(), 0, length)
                200 -> {
                    if (offset > maxSkipBytes) throw IOException("server ignored Range at $offset")
                    return readUpTo(response.body!!.byteStream(), offset, length)
                }
                416 -> return ByteArray(0)
                401, 403, 404, 410 -> throw LinkExpiredException(response.code)
                else -> throw IOException("range read answered ${response.code}")
            }
        }
    }

    companion object {
        /** Skips [skip] bytes of [input] then reads up to [length] bytes (fewer at end of stream). */
        internal fun readUpTo(input: InputStream, skip: Long, length: Int): ByteArray {
            var toSkip = skip
            val scratch = ByteArray(16 * 1024)
            while (toSkip > 0) {
                val n = input.read(scratch, 0, minOf(scratch.size.toLong(), toSkip).toInt())
                if (n < 0) return ByteArray(0)
                toSkip -= n
            }
            val out = ByteArray(length)
            var filled = 0
            while (filled < length) {
                val n = input.read(out, filled, length - filled)
                if (n < 0) break
                filled += n
            }
            return if (filled == length) out else out.copyOf(filled)
        }
    }
}

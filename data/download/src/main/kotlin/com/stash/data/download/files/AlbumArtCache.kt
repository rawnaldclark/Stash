package com.stash.data.download.files

import android.util.Log
import com.stash.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lazily fetches and caches album-art JPEGs on the local filesystem so
 * [MetadataEmbedder] can attach them to downloaded audio files.
 *
 * Storage: [FileOrganizer.getAlbumArtDir] (= `cacheDir/albumart/`).
 * Filenames are SHA-1 over the canonicalised `albumArtUrl` (first 16
 * hex chars + `.jpg`), so two tracks on the same album resolve to the
 * same cache file regardless of the URL's size suffix. Network /
 * decode / missing-URL failures all return `null` — embedding proceeds
 * without art. We never block a download because the art server is
 * slow.
 *
 * Lifecycle: the cache dir lives under `cacheDir/`, so Android may
 * evict files under storage pressure. If a cached file disappears
 * between calls we just re-download.
 */
@Singleton
class AlbumArtCache @Inject constructor(
    private val fileOrganizer: FileOrganizer,
    private val httpClient: OkHttpClient,
) {
    /**
     * Returns a local JPEG file for [track]'s album cover. Returns
     * null when the URL is missing, the response is non-2xx, the
     * response Content-Type isn't an image, the file body is empty,
     * or any IO/network error fires.
     */
    suspend fun resolveArt(track: Track): File? = withContext(Dispatchers.IO) {
        val url = track.albumArtUrl?.takeIf { it.isNotBlank() } ?: return@withContext null
        val canonical = canonicaliseUrl(url)
        val cacheFile = File(fileOrganizer.getAlbumArtDir(), hashFilename(canonical))

        if (cacheFile.exists() && cacheFile.length() > 0) return@withContext cacheFile

        runCatching {
            httpClient.newCall(Request.Builder().url(canonical).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body ?: return@use null
                // Prefer the body's parsed Content-Type (matches real OkHttp
                // behaviour); fall back to the header for completeness.
                val contentType = body.contentType()?.toString()
                    ?: resp.header("Content-Type").orEmpty()
                if (!contentType.startsWith("image/")) return@use null
                val bytes = body.bytes()
                if (bytes.isEmpty()) return@use null
                cacheFile.writeBytes(bytes)
                cacheFile
            }
        }.getOrElse { e ->
            Log.w(TAG, "art fetch failed for $canonical: ${e.message}")
            cacheFile.delete()
            null
        }
    }

    /**
     * Spotify (`i.scdn.co/image/<hash>`) URLs are already
     * size-agnostic. YT-Music URLs (`lh3.googleusercontent.com/...
     * =w<size>-h<size>`) normalise to one [EMBED_SIZE_SUFFIX] tier so
     * requests for 64px and 1024px share a cache entry. Real YT-Music
     * URLs often carry multi-token suffixes like `-l50-rj` or
     * `-p-l90-rj`; the regex strips all of them so every resolution
     * variant collapses to a single canonical form.
     *
     * The tier is the cover that ends up EMBEDDED in the downloaded
     * file. It used to be 640, which shrank the 1024px covers the
     * library stores (and the ~1400px originals behind them) for every
     * player that reads the file's own art. 1200 is the usual
     * full-size cover; Google caps the request at the original, so a
     * smaller original is served as-is, never upscaled.
     */
    private fun canonicaliseUrl(url: String): String =
        url.replace(YT_MUSIC_SIZE_SUFFIX, EMBED_SIZE_SUFFIX)

    private fun hashFilename(canonical: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(canonical.toByteArray())
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "${hex.take(16)}.jpg"
    }

    private companion object {
        private const val TAG = "AlbumArtCache"
        private val YT_MUSIC_SIZE_SUFFIX = Regex("=w\\d+-h\\d+(?:-[a-z0-9]+)*")

        /**
         * Size of the YouTube Music cover embedded in downloads: see [canonicaliseUrl]. `-l90-rj`
         * asks for a JPEG: without it Google serves a PNG original as PNG, 3 MB at 1200px where
         * the JPEG is 0.5 MB (the old 640 tier embedded 958 KB PNGs that way).
         */
        private const val EMBED_SIZE_SUFFIX = "=w1200-h1200-l90-rj"
    }
}

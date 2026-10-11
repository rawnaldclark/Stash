package com.stash.data.download.files

import android.util.Log
import com.stash.core.common.ArtUrlSizer
import com.stash.core.model.Track
import kotlinx.coroutines.CancellationException
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
 * Filenames are SHA-1 over the URL actually embedded (first 16 hex chars
 * + `.jpg`), so two tracks on the same album resolve to the same cache
 * file regardless of the URL's size suffix. Network / decode /
 * missing-URL failures all return `null` — embedding proceeds without
 * art. We never block a download because the art server is slow.
 *
 * **Size.** The embedded cover is the host's variant for [EMBED_PX]
 * ([ArtUrlSizer.forSize]): YouTube Music `=w1200`, Qobuz `_max` instead
 * of `_600`, Last.fm's original instead of `770x0`, Spotify's original,
 * iTunes 1200. Originals bigger than that are shrunk to [EMBED_PX] by
 * [EmbedArtScaler]. The stored URL is the floor: when the bigger variant
 * fails for any reason (including running out of memory to shrink it), or
 * comes back with fewer pixels than the stored one (a Last.fm original
 * under its CDN's 770 rendering), the stored URL is embedded instead, as
 * before. A failure caches the stored cover under its own name, so the
 * next download from that album tries the bigger variant again.
 *
 * Lifecycle: the cache dir lives under `cacheDir/`, so Android may
 * evict files under storage pressure. If a cached file disappears
 * between calls we just re-download.
 */
@Singleton
class AlbumArtCache @Inject constructor(
    private val fileOrganizer: FileOrganizer,
    private val httpClient: OkHttpClient,
    private val scaler: EmbedArtScaler,
) {
    /**
     * Returns a local JPEG file for [track]'s album cover. Returns
     * null when the URL is missing, the response is non-2xx, the
     * response Content-Type isn't an image, the file body is empty,
     * or any IO/network error fires (for the bigger variant AND the
     * stored URL).
     */
    suspend fun resolveArt(track: Track): File? = withContext(Dispatchers.IO) {
        val url = track.albumArtUrl?.takeIf { it.isNotBlank() } ?: return@withContext null
        val stored = canonicaliseUrl(url)
        val embed = ArtUrlSizer.forSize(stored, EMBED_PX) ?: stored
        val embedFile = cached(embed)
        if (embedFile.exists() && embedFile.length() > 0) return@withContext embedFile

        if (embed != stored) {
            val sized = load(embed)
            if (sized != null) {
                val floorPx = ArtUrlSizer.storedPx(stored)
                if (floorPx == null || sized.longSidePx == 0 || sized.longSidePx >= floorPx) {
                    return@withContext write(embedFile, sized)
                }
                // Smaller than the stored cover (a Last.fm original under its CDN's 770): that
                // won't change, so the stored cover IS this album's embed, cached as such.
                val floor = load(stored) ?: return@withContext null
                return@withContext write(embedFile, floor)
            }
            // The bigger variant failed (5xx, timeout, out of memory): maybe only this once. Keep
            // the stored cover under its OWN name, so the next download tries the big one again.
        }
        val storedFile = cached(stored)
        if (storedFile.exists() && storedFile.length() > 0) return@withContext storedFile
        val fitted = load(stored) ?: return@withContext null
        write(storedFile, fitted)
    }

    private fun cached(url: String) = File(fileOrganizer.getAlbumArtDir(), hashFilename(url))

    private fun write(file: File, fitted: EmbedArtScaler.Fitted): File? = runCatching {
        file.writeBytes(fitted.bytes)
        file
    }.getOrElse { e ->
        Log.w(TAG, "art write failed for ${file.name}: ${e.message}")
        file.delete()
        null
    }

    /**
     * The cover at [url], fitted within [EMBED_PX], or null when the fetch fails or the device
     * ran out of memory fitting it (the caller then falls back to the smaller stored cover).
     * Bytes the device can't decode come back as fetched (as before; size unknown, so 0).
     */
    private suspend fun load(url: String): EmbedArtScaler.Fitted? {
        val bytes = fetch(url) ?: return null
        return scaler.fit(bytes, EMBED_PX)
    }

    /** The image bytes at [url], or null when it is not a non-empty image or the fetch fails. */
    private fun fetch(url: String): ByteArray? = try {
        httpClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val body = resp.body ?: return@use null
            // Prefer the body's parsed Content-Type (matches real OkHttp
            // behaviour); fall back to the header for completeness.
            val contentType = body.contentType()?.toString()
                ?: resp.header("Content-Type").orEmpty()
            if (!contentType.startsWith("image/")) return@use null
            body.bytes().takeIf { it.isNotEmpty() }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "art fetch failed for $url: ${e.message}")
        null
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

    companion object {
        /** Long side of the embedded cover: the usual full-size cover (Apple's, MusicBrainz's). */
        const val EMBED_PX = 1200

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

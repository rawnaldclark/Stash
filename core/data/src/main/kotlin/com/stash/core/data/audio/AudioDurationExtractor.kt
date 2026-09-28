package com.stash.core.data.audio

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads duration metadata off a downloaded audio file.
 *
 * The file itself is the authoritative source of truth — ExoPlayer reads
 * duration the same way at playback time, so using `MediaMetadataRetriever`
 * here guarantees the playlist UI agrees with the player's progress bar.
 *
 * Why this matters: tracks coming through the Stash Discover pipeline are
 * created as stubs with `duration_ms = 0` and rely on `persistMatchMetadata`
 * to fill duration from the YouTube match. When the match result has no
 * duration (yt-dlp direct fallback, or UGC uploads that surface as plain
 * video renderers), `fillMissingMetadata` writes 0, and the playlist row
 * displays a blank duration even though the file on disk is a real N-minute
 * track. Pulling from the file sidesteps the entire metadata-provider chain.
 *
 * Handles both app-internal java.io.File paths and SAF-backed `content://`
 * URIs (SD card / USB-OTG libraries) — same dispatch pattern as the
 * SAF-aware delete helper in `MusicRepositoryImpl`.
 */
@Singleton
class AudioDurationExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Returns the track's duration in milliseconds, or null if the file
     * is missing / corrupt / the container doesn't expose duration. Never
     * throws — a bad file returns null so a batch caller can continue.
     */
    fun extractMs(filePath: String): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            if (filePath.startsWith("content://")) {
                retriever.setDataSource(context, Uri.parse(filePath))
            } else {
                retriever.setDataSource(filePath)
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
        } catch (e: Exception) {
            Log.w(TAG, "extractMs failed for $filePath: ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Reads duration + codec + bitrate from the file in a single retriever
     * pass. Used by the download path to populate `file_format` and
     * `quality_kbps` (which the sync writer historically left at defaults)
     * and to reconcile duration against sync metadata when yt-dlp matched a
     * different-length cut than Spotify's track length implied.
     *
     * Returns null only when the file can't be opened at all; partial
     * metadata returns a record with the extracted fields and zero/unknown
     * for the rest, so callers can still trust whichever field they need.
     */
    fun extract(filePath: String): AudioMetadata? {
        val retriever = MediaMetadataRetriever()
        return try {
            if (filePath.startsWith("content://")) {
                retriever.setDataSource(context, Uri.parse(filePath))
            } else {
                retriever.setDataSource(filePath)
            }
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val bitrateBps = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toIntOrNull() ?: 0
            val mime = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            val (trackMime, extractorSampleRate, extractorBitDepth) = probeExtractor(context, filePath)
            // The retriever's MIMETYPE is the CONTAINER ("audio/mp4" for AAC in MP4,
            // "audio/ogg" for Opus), so the codec comes from the audio track when the
            // extractor reads one. Container-only, JioSaavn's AAC read as "mp4" and
            // failed its `format == "aac"` check every time (Pixel 5, 2026-09-28).
            // Except "audio/raw": Android's FLAC (and WAV) extractor decodes to PCM
            // itself, so its track says nothing about the file. Taking it stored new
            // FLACs as "raw" on the Pixel 5, which the lossless upgrade would refetch.
            val format = normalizeFormat(trackMime?.takeUnless { it == MediaFormat.MIMETYPE_AUDIO_RAW } ?: mime)
            // FLAC fallback: MediaExtractor on some devices returns -1 for FLAC
            // bit-depth even on API 30+. Always try STREAMINFO when we have a
            // FLAC file but no bit-depth from the extractor.
            val resolvedBitDepth = extractorBitDepth
                ?: if (format == "flac") parseFlacStreamInfoBitDepth(filePath) else null
            AudioMetadata(
                durationMs = durationMs,
                bitrateKbps = if (bitrateBps > 0) bitrateBps / 1000 else 0,
                format = format,
                bitsPerSample = resolvedBitDepth?.takeIf { it in 8..32 },
                sampleRateHz = extractorSampleRate?.takeIf { it > 0 },
            )
        } catch (e: Exception) {
            Log.w(TAG, "extract failed for $filePath: ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * v0.9.26 — read the embedded `album` tag from a downloaded file.
     * Cheap one-call MMR pull; used by the album-metadata backfill in
     * [com.stash.app.StashApplication] to recover the album column for
     * tracks that landed before the v0.9.26 fix wrote `tracks.album`.
     *
     * Returns `null` (not empty) if the tag is absent or the file can't
     * be opened — the caller skips that row instead of clobbering an
     * already-empty value.
     */
    fun extractAlbum(filePath: String): String? {
        val retriever = MediaMetadataRetriever()
        return try {
            if (filePath.startsWith("content://")) {
                retriever.setDataSource(context, Uri.parse(filePath))
            } else {
                retriever.setDataSource(filePath)
            }
            retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "extractAlbum failed for $filePath: ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    companion object {
        private const val TAG = "AudioDurationExtractor"

        /**
         * Maps a MIME string (the audio track's codec MIME, else the
         * retriever's container MIME) to the short format tags we store in
         * `file_format`. Kept stable so the Library Health screen can group
         * by these values without surprises.
         */
        internal fun normalizeFormat(mime: String?): String {
            if (mime == null) return "unknown"
            val lower = mime.lowercase()
            return when {
                "mp4a" in lower || "aac" in lower -> "aac"
                "opus" in lower -> "opus"
                "vorbis" in lower -> "vorbis"
                "flac" in lower -> "flac"
                "mpeg" in lower || "mp3" in lower -> "mp3"
                else -> lower.substringAfter("audio/", "").ifBlank { "unknown" }
            }
        }

        /**
         * Reads `KEY_MIME`, `KEY_SAMPLE_RATE` (all API levels) and
         * `KEY_BITS_PER_SAMPLE` (API 30+) from the first audio track.
         * Best-effort — containers/codecs vary in what they expose, and
         * `MediaExtractor` will throw on a corrupt file. Returns all nulls
         * on any failure so callers can continue.
         *
         * Mirrors the `content://` branch from [extract] so SAF/SD-card
         * libraries get probed too — without it, MediaExtractor would
         * NPE on the URI string when the underlying file isn't a plain
         * filesystem path.
         *
         * Returns `(trackMime, sampleRate, bitsPerSample)`.
         */
        internal fun probeExtractor(context: Context, filePath: String): Triple<String?, Int?, Int?> {
            val extractor = MediaExtractor()
            return try {
                if (filePath.startsWith("content://")) {
                    extractor.setDataSource(context, Uri.parse(filePath), null)
                } else {
                    extractor.setDataSource(filePath)
                }
                val format = (0 until extractor.trackCount).asSequence().map(extractor::getTrackFormat)
                    .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                    ?: return Triple(null, null, null)
                val sampleRate = runCatching { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) }
                    .getOrNull()
                    ?.takeIf { it > 0 }
                // "bits-per-sample" is not a public MediaFormat constant — it is an
                // implementation-level key populated by some OEM FLAC extractors.
                // We access it via the raw string and wrap in runCatching so that
                // MediaFormat.getInteger() throws cleanly when the key is absent.
                val bitDepth = if (Build.VERSION.SDK_INT >= 30) {
                    runCatching {
                        format.getInteger("bits-per-sample")
                    }.getOrNull()?.takeIf { it > 0 }
                } else null
                Triple(format.getString(MediaFormat.KEY_MIME), sampleRate, bitDepth)
            } catch (e: Exception) {
                Log.w(TAG, "probeExtractor failed for $filePath: ${e.javaClass.simpleName}: ${e.message}")
                Triple(null, null, null)
            } finally {
                runCatching { extractor.release() }
            }
        }

        /**
         * Reads bit-depth from the FLAC STREAMINFO metadata block.
         *
         * FLAC layout (frozen in 2008, stable):
         *   - Bytes 0..3:   "fLaC" magic
         *   - Bytes 4..7:   metadata block header (block-type 0 = STREAMINFO)
         *   - Bytes 8..25:  STREAMINFO body (18 bytes)
         *
         * Within STREAMINFO, the bit-depth field (`bps - 1`, 5 bits total) is
         * split across the last bit of file byte 20 and the top 4 bits of
         * file byte 21:
         *
         *   byte 20: |ssss ccch|   ssss = sample-rate low 4 bits,
         *                          ccc  = channels - 1 (3 bits),
         *                          h    = high bit of (bps-1)
         *   byte 21: |hhhh tttt|   hhhh = low 4 bits of (bps-1),
         *                          tttt = total-samples high 4 bits
         *
         * Concretely: `bps = (((byte20 and 0x01) shl 4) or ((byte21 shr 4) and 0x0F)) + 1`
         *
         * Returns null on any read failure or implausible value (sanity
         * range 8..32 bits-per-sample).
         *
         * NOTE: Filesystem-only — `RandomAccessFile` doesn't accept
         * `content://` URIs. SAF-backed FLACs land here only when the
         * MediaExtractor probe already returned a value (so we never
         * call this) or fall through with NULL bit-depth (acceptable;
         * the badge falls back to plain "FLAC").
         */
        internal fun parseFlacStreamInfoBitDepth(filePath: String): Int? {
            return try {
                RandomAccessFile(filePath, "r").use { raf ->
                    val header = ByteArray(22)
                    if (raf.read(header) < 22) return null
                    if (header[0] != 'f'.code.toByte() ||
                        header[1] != 'L'.code.toByte() ||
                        header[2] != 'a'.code.toByte() ||
                        header[3] != 'C'.code.toByte()
                    ) return null
                    val byte20 = header[20].toInt() and 0xFF
                    val byte21 = header[21].toInt() and 0xFF
                    val bps = (((byte20 and 0x01) shl 4) or ((byte21 shr 4) and 0x0F)) + 1
                    bps.takeIf { it in 8..32 }
                }
            } catch (e: Exception) {
                Log.w(TAG, "parseFlacStreamInfoBitDepth failed for $filePath: ${e.message}")
                null
            }
        }
    }
}

/**
 * Bundle of audio-file metadata read from the container itself. Each field
 * is optional in practice — `MediaMetadataRetriever` is best-effort and
 * sometimes returns nulls for fields a given codec/container exposes
 * differently. Zero / "unknown" indicate "not available" rather than
 * "actually zero."
 */
data class AudioMetadata(
    val durationMs: Long,
    val bitrateKbps: Int,
    val format: String,
    /** Bit-depth read from the file (16/24/32). Null = unknown / unparseable. */
    val bitsPerSample: Int? = null,
    /** Sample rate in Hz (44100/48000/96000/192000). Null = unknown. */
    val sampleRateHz: Int? = null,
)

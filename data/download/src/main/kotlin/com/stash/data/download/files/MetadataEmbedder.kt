package com.stash.data.download.files

import android.content.Context
import android.util.Base64
import android.util.Log
import com.stash.core.common.AppVersionProvider
import com.stash.core.model.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Embeds metadata (TITLE, ARTIST, ALBUMARTIST, ALBUM, ISRC, ENCODER)
 * and optional cover art into audio files using ffmpeg, plus the album,
 * year, track number and genre a catalogue knows when the file lacks
 * them ([ExtraTagSource]). Container-
 * agnostic — `-c copy` muxes the new metadata + picture stream into
 * the original codec without re-encoding.
 *
 * The ffmpeg binary is bundled by youtubedl-android as a native .so.
 * If tagging fails for any reason, the original untagged file is
 * preserved and the failure is reported to the caller.
 *
 * Vorbis-comment casing: writes each key twice (canonical
 * `ALBUMARTIST` + legacy `album_artist`) so both strict Vorbis
 * readers (Symfonium, some car head units) and ID3-style readers
 * (Plex, Foobar) see the value. ffmpeg normalises both forms to a
 * single atom/frame for M4A and MP3 containers, so the duplicate
 * is a no-op there.
 */
@Singleton
class MetadataEmbedder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appVersion: AppVersionProvider,
    private val extraTags: ExtraTagSource = ExtraTagSource.None,
) {
    /** Test seam: what a file's own tags already say (see [extraTagsFor]). */
    internal var readExisting: (File) -> ExistingTags = ::readExistingTags

    internal var renameFile: (File, File) -> Boolean = { source, target ->
        source.renameTo(target)
    }

    /** Test seam: how long one ffmpeg tag pass may run before it is killed. */
    internal var ffmpegTimeoutSec: Long = FFMPEG_TIMEOUT_SEC

    suspend fun embedMetadata(
        audioFile: File,
        track: Track,
        albumArtFile: File? = null,
    ): File = withContext(Dispatchers.IO) {
        val outputFile = File(
            audioFile.parent,
            "${audioFile.nameWithoutExtension}_tagged.${audioFile.extension}",
        )
        var backupFile: File? = null

        // Issue #118: realistic album art (300–500KB JPEG → 400–700KB base64)
        // can push a single argv entry near or past Linux's MAX_ARG_STRLEN
        // (~128KB on most kernels), and when ffmpeg's exec() truncates that
        // entry the whole metadata pass silently fails — title/artist all
        // come back null. For Opus/Ogg we route the picture block through
        // a sidecar ffmetadata file whenever the base64 would be large, so
        // argv stays small regardless of cover size.
        val opusPictureMetadataFile = prepareOpusPictureMetadataFile(audioFile, outputFile, albumArtFile)

        val args = buildFfmpegArgs(
            audioFile, outputFile, track, albumArtFile, appVersion,
            opusPictureMetadataFile = opusPictureMetadataFile,
            extraTags = extraTagsFor(audioFile, track),
        )

        try {
            val ffmpegPath = resolveFfmpegBinary()
                ?: throw IOException("ffmpeg binary not found")
            val pb = ProcessBuilder(listOf(ffmpegPath.absolutePath) + args)
            // Keep stderr separate from stdout so we can log it verbatim on
            // failure — merging via redirectErrorStream(true) and then never
            // reading the stream is what hid the Opus `attached_pic` exit-234
            // failure during integration test development. Mirrors the pattern
            // in FFmpegBridgeImpl.measureLoudness.
            // Android's dynamic linker does NOT auto-search the executable's
            // directory for sibling .so files. The bundled ffmpeg needs
            // libc++_shared.so and the libav*.so siblings that youtubedl-android
            // extracts into noBackupFilesDir/youtubedl-android/packages/.
            // Without LD_LIBRARY_PATH the process fails to launch with
            // "CANNOT LINK EXECUTABLE ... library libc++_shared.so not found".
            // Mirrors FFmpegBridgeImpl.ldLibraryPath().
            pb.environment()["LD_LIBRARY_PATH"] = ldLibraryPath()
            val process = pb.start()

            // Drain both streams AT ONCE, each on its own thread. Reading stdout
            // to EOF before touching stderr deadlocked: ffmpeg writes nothing to
            // stdout (the output is a file), so that read only returns at exit,
            // while ffmpeg itself blocks once stderr's ~64 KB pipe fills, which
            // per-packet warnings on a slightly broken file easily do. The stuck
            // thread held one of DownloadManager's download slots for good, and
            // hung MetadataBackfillWorker on the first such file.
            val stdoutDrain = StreamDrain(process.inputStream)
            val stderrDrain = StreamDrain(process.errorStream)
            val exit = try {
                // Interruptible, so a cancelled download or a stopped backfill
                // stops waiting; the finally below then kills ffmpeg.
                val finished = runInterruptible {
                    process.waitFor(ffmpegTimeoutSec, TimeUnit.SECONDS)
                }
                if (!finished) {
                    throw IOException("ffmpeg timed out after ${ffmpegTimeoutSec}s for ${audioFile.absolutePath}")
                }
                process.exitValue()
            } finally {
                // A no-op once ffmpeg has exited; otherwise (timeout, cancellation)
                // it frees the process and lets the drain threads hit EOF.
                process.destroyForcibly()
            }
            val stdout = stdoutDrain.text()
            val stderr = stderrDrain.text()

            if (exit != 0) {
                Log.w(TAG, "ffmpeg exited $exit for ${audioFile.absolutePath} (args: ${args.take(8)}...)")
                if (stderr.isNotBlank()) {
                    Log.w(TAG, "ffmpeg stderr: ${stderr.take(500)}")
                }
                if (stdout.isNotBlank()) {
                    Log.d(TAG, "ffmpeg stdout: ${stdout.take(200)}")
                }
                throw IOException("ffmpeg exited $exit for ${audioFile.absolutePath}")
            }

            if (!outputFile.exists() || outputFile.length() <= 0) {
                throw IOException("ffmpeg produced no output for ${audioFile.absolutePath}")
            }

            val backup = File.createTempFile(
                "stash_${audioFile.nameWithoutExtension}_untagged_",
                ".${audioFile.extension}",
                audioFile.parentFile,
            ).apply { delete() }
            backupFile = backup
            if (!renameFile(audioFile, backup)) {
                throw IOException("could not preserve original file ${audioFile.absolutePath}")
            }
            if (!renameFile(outputFile, audioFile)) {
                throw IOException("could not install tagged file ${audioFile.absolutePath}")
            }
            if (!backup.delete()) {
                Log.w(TAG, "could not delete backup file ${backup.absolutePath}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "ffmpeg embed failed for ${audioFile.absolutePath}: ${e.message}", e)
            outputFile.delete()
            backupFile?.let { backup ->
                if (backup.exists() && !audioFile.exists()) {
                    if (!renameFile(backup, audioFile)) {
                        backup.copyTo(audioFile, overwrite = true)
                        backup.delete()
                    }
                }
            }
            throw e
        } finally {
            opusPictureMetadataFile?.delete()
        }

        audioFile
    }

    /**
     * For Opus/Ogg outputs with cover art, decide whether the
     * `METADATA_BLOCK_PICTURE` Vorbis comment should travel through a
     * sidecar ffmetadata file rather than an inline `-metadata` argv
     * entry. Returns the file when the base64 picture block exceeds
     * [MAX_INLINE_PICTURE_BASE64_BYTES], `null` otherwise (M4A/MP3/FLAC
     * always return null — they use `attached_pic` instead).
     */
    private fun prepareOpusPictureMetadataFile(
        audioFile: File,
        outputFile: File,
        albumArtFile: File?,
    ): File? {
        val outputExt = outputFile.extension.lowercase()
        if (outputExt !in OPUS_OGG_EXTENSIONS) return null
        if (albumArtFile == null || !albumArtFile.exists() || albumArtFile.length() <= 0) return null

        return try {
            val pictureBlock = FlacPictureBlock.build(albumArtFile.readBytes())
            val base64Block = Base64.encodeToString(pictureBlock, Base64.NO_WRAP)
            if (base64Block.length <= MAX_INLINE_PICTURE_BASE64_BYTES) return null

            val metaFile = File(audioFile.parent, "${audioFile.nameWithoutExtension}_picmeta.ffmetadata")
            // The leading `;FFMETADATA1` magic line is required by ffmpeg's
            // ffmetadata demuxer. Everything else is a plain key=value pair.
            // METADATA_BLOCK_PICTURE base64 has no newlines (NO_WRAP) and no
            // `;`/`#`/`=` ambiguity past the first `=`, so no escaping needed.
            metaFile.writeText(";FFMETADATA1\nMETADATA_BLOCK_PICTURE=$base64Block\n")
            metaFile
        } catch (e: Exception) {
            Log.w(TAG, "ffmetadata picture-file build failed for ${audioFile.absolutePath}: ${e.message}")
            null
        }
    }

    /**
     * The album, year, track number and genre a catalogue knows and the
     * file does not say: a source that hands over a bare stream leaves a
     * file with only its title and artist, which sorts poorly in any
     * music library. A file that already carries them (a source that
     * tags its own files) is not looked up at all. No answer, or any
     * failure, is `null`: the file is tagged with what Stash knows.
     */
    private suspend fun extraTagsFor(audioFile: File, track: Track): ExtraTags? {
        if (extraTags === ExtraTagSource.None) return null
        return try {
            val existing = readExisting(audioFile)
            if (existing.isComplete(track)) return null
            extraTags.lookUp(track)?.onlyBlanks(existing, track)?.takeUnless { it.isEmpty }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "extra tags skipped for ${audioFile.name}: ${e.message}")
            null
        }
    }

    private fun readExistingTags(file: File): ExistingTags {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            fun has(key: Int) = !retriever.extractMetadata(key).isNullOrBlank()
            ExistingTags(
                album = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty().trim(),
                hasGenre = has(android.media.MediaMetadataRetriever.METADATA_KEY_GENRE),
                hasYear = has(android.media.MediaMetadataRetriever.METADATA_KEY_YEAR) ||
                    has(android.media.MediaMetadataRetriever.METADATA_KEY_DATE),
                hasTrackNumber = has(android.media.MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER),
            )
        } catch (e: Exception) {
            // Unreadable here is not "has no tags": claim nothing, add nothing a source may have written.
            ExistingTags(hasGenre = true, hasYear = true, hasTrackNumber = true, album = "?")
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun resolveFfmpegBinary(): File? {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val candidates = listOf("libffmpeg.so", "libffmpeg.zip.so")
        return candidates.map { File(nativeDir, it) }.firstOrNull { it.exists() }
    }

    /**
     * Reconstructs the `LD_LIBRARY_PATH` that youtubedl-android sets
     * internally so the bundled ffmpeg can dlopen its sibling .so files
     * (libc++_shared.so, libav*.so). Order mirrors FFmpegBridgeImpl —
     * see core/data/.../FFmpegBridge.kt#ldLibraryPath for the precedent.
     */
    private fun ldLibraryPath(): String {
        val base = File(context.noBackupFilesDir, "youtubedl-android/packages")
        return buildString {
            append(File(base, "python/usr/lib").absolutePath); append(':')
            append(File(base, "ffmpeg/usr/lib").absolutePath); append(':')
            append(File(base, "aria2c/usr/lib").absolutePath)
        }
    }

    /**
     * Reads [stream] to EOF on a daemon thread, keeping the first [MAX_CAPTURE_CHARS]
     * characters for the failure log and discarding the rest, so a chatty ffmpeg
     * can neither block on a full pipe nor grow this buffer without bound.
     */
    private class StreamDrain(stream: InputStream) {
        private val captured = StringBuilder()
        private val thread = Thread {
            try {
                stream.bufferedReader().use { reader ->
                    val buf = CharArray(4096)
                    while (true) {
                        val n = reader.read(buf)
                        if (n < 0) break
                        synchronized(captured) {
                            val room = MAX_CAPTURE_CHARS - captured.length
                            if (room > 0) captured.append(buf, 0, minOf(n, room))
                        }
                    }
                }
            } catch (_: IOException) {
                // Stream closed under us (process destroyed): what we have is enough.
            }
        }.apply { isDaemon = true; start() }

        /** What was captured; waits briefly for the drain to reach EOF. */
        fun text(): String {
            thread.join(DRAIN_JOIN_MS)
            return synchronized(captured) { captured.toString() }
        }
    }

    companion object {
        private const val TAG = "MetadataEmbedder"

        /**
         * Generous: `-c copy` remuxes even a 140 MB hi-res FLAC in seconds. Past
         * this, ffmpeg is hung, and it would otherwise hold a download slot forever.
         */
        private const val FFMPEG_TIMEOUT_SEC = 120L

        /** First characters of each ffmpeg stream kept for logging. */
        private const val MAX_CAPTURE_CHARS = 8 * 1024

        /** How long to wait for a drain thread after ffmpeg exits. */
        private const val DRAIN_JOIN_MS = 2_000L

        // Containers that don't support ffmpeg's `-disposition:v:0 attached_pic`
        // mapping (mux fails with exit 234). Tag writing still works for these.
        private val OPUS_OGG_EXTENSIONS = setOf("opus", "ogg")

        // Issue #118 threshold: max base64 length we'll inline into argv before
        // switching to the ffmetadata-file fallback. Linux MAX_ARG_STRLEN is
        // ~128KB on most kernels — 64KB leaves plenty of headroom and keeps
        // realistic Spotify/YT cover art (~400–700KB base64) on the safe path.
        internal const val MAX_INLINE_PICTURE_BASE64_BYTES = 64 * 1024

        private fun sanitize(value: String): String =
            value.replace(Regex("[\\x00-\\x1f]"), "")

        /**
         * Pure helper — builds the ffmpeg argv list for the embed
         * pass. Extracted so unit tests can exercise the tag-writing
         * logic without spawning a process. Internal `companion`
         * visibility intentional: only the embedder + its tests
         * need this.
         *
         * [opusPictureMetadataFile] short-circuits the inline
         * `METADATA_BLOCK_PICTURE` arg in favour of `-i <file>
         * -map_metadata 1`, which side-steps argv length limits when
         * the picture block is large (issue #118).
         */
        internal fun buildFfmpegArgs(
            audioFile: File,
            outputFile: File,
            track: Track,
            albumArtFile: File?,
            appVersion: AppVersionProvider,
            opusPictureMetadataFile: File? = null,
            extraTags: ExtraTags? = null,
        ): List<String> = buildList {
            add("-i"); add(audioFile.absolutePath)

            // Opus / Ogg containers don't accept attached_pic in current ffmpeg
            // (mux fails with exit 234). For those containers the standard
            // Vorbis-comment way to carry cover art is METADATA_BLOCK_PICTURE:
            // a base64-encoded FLAC Picture block, passed via -metadata. All
            // other containers (M4A, MP3, FLAC) keep using the attached_pic
            // stream mapping. Fixes rawnaldclark/Stash#95.
            val outputExt = outputFile.extension.lowercase()
            val supportsAttachedPic = outputExt !in OPUS_OGG_EXTENSIONS
            val hasArt = albumArtFile != null && albumArtFile.exists() && albumArtFile.length() > 0
            val useMetadataFileFallback = !supportsAttachedPic && opusPictureMetadataFile != null

            if (supportsAttachedPic && hasArt) {
                add("-i"); add(albumArtFile.absolutePath)
                add("-map"); add("0:a")
                add("-map"); add("1:0")
                add("-disposition:v:0"); add("attached_pic")
            } else if (useMetadataFileFallback) {
                // ffmetadata sidecar carries METADATA_BLOCK_PICTURE without
                // bloating argv. `-map_metadata 1` lifts the global tags from
                // the sidecar; the inline `-metadata title=…` flags below
                // still override / extend per-key, which is what we want.
                add("-i"); add(opusPictureMetadataFile.absolutePath)
                add("-map_metadata"); add("1")
            }

            add("-metadata"); add("title=${sanitize(track.title)}")
            add("-metadata"); add("artist=${sanitize(track.artist)}")

            if (track.album.isNotEmpty()) {
                add("-metadata"); add("album=${sanitize(track.album)}")
            } else if (!extraTags?.album.isNullOrBlank()) {
                add("-metadata"); add("album=${sanitize(extraTags!!.album)}")
            }

            // What a catalogue added for the file's blanks. ffmpeg writes
            // these under each container's own names (GENRE / DATE /
            // TRACKNUMBER / DISCNUMBER in FLAC and Opus).
            extraTags?.let { extra ->
                if (extra.genre.isNotBlank()) { add("-metadata"); add("genre=${sanitize(extra.genre)}") }
                extra.year?.let { add("-metadata"); add("date=$it") }
                extra.trackNumber?.let { add("-metadata"); add("track=$it") }
                extra.discNumber?.let { add("-metadata"); add("disc=$it") }
            }

            val effectiveAlbumArtist = track.albumArtist.ifBlank { track.artist }
            if (effectiveAlbumArtist.isNotEmpty()) {
                add("-metadata"); add("ALBUMARTIST=${sanitize(effectiveAlbumArtist)}")
                add("-metadata"); add("album_artist=${sanitize(effectiveAlbumArtist)}")
            }

            track.isrc?.takeIf { it.isNotBlank() }?.let {
                add("-metadata"); add("ISRC=${sanitize(it)}")
            }

            add("-metadata"); add("ENCODER=Stash ${appVersion.versionName}")

            // Opus / Ogg cover art: build a FLAC Picture block from the JPEG
            // bytes, base64-encode it (NO_WRAP — ffmpeg's metadata value parser
            // doesn't tolerate newlines), and ship it as the standard
            // METADATA_BLOCK_PICTURE Vorbis comment. Plex / Foobar2000 /
            // Symfonium / car stereos all read this per the Vorbis-comment
            // spec. Avoids the attached_pic mux path that fails with exit 234
            // on Ogg containers.
            //
            // Skipped when the caller routed the picture through a sidecar
            // ffmetadata file (issue #118 — large base64 blocks overflow argv).
            if (!supportsAttachedPic && hasArt && !useMetadataFileFallback) {
                try {
                    val pictureBlock = FlacPictureBlock.build(albumArtFile.readBytes())
                    val base64Block = Base64.encodeToString(pictureBlock, Base64.NO_WRAP)
                    add("-metadata"); add("METADATA_BLOCK_PICTURE=$base64Block")
                } catch (e: Exception) {
                    Log.w(TAG, "FlacPictureBlock build failed for ${audioFile.absolutePath}: ${e.message}")
                }
            }

            add("-c"); add("copy")
            add("-y")
            add(outputFile.absolutePath)
        }
    }
}

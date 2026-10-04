package com.stash.data.download.files

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.stash.core.data.repository.MusicRepository
import com.stash.core.model.MusicSource
import com.stash.core.model.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * State of the "Import from device" flow. Observable via
 * [LocalImportCoordinator.state]. Stays on [Done] or [Error] until
 * [LocalImportCoordinator.dismiss] is called.
 */
sealed interface LocalImportState {
    data object Idle : LocalImportState
    data class Running(val current: Int, val total: Int) : LocalImportState
    data class Done(val imported: Int, val failed: Int) : LocalImportState
    data class Error(val message: String) : LocalImportState
}

/**
 * Imports user-picked audio files into the Stash library.
 *
 * For each URI from the SAF picker (or share intent):
 *   1. Copies the bytes to a temp cache file whose name Stash picks.
 *   2. Extracts metadata via `MediaMetadataRetriever` (title/artist/album/
 *      duration/embedded album art). Falls back to filename parsing
 *      (`Artist - Title.ext`) when tags are missing.
 *   3. Picks the extension: the provider's MIME type or display name when
 *      either maps to an audio type Stash saves ([ImportedAudioType]), else
 *      the content's own container type ([ImportedAudioType.fromContent]).
 *      A file none of them identifies is refused.
 *   4. Hands the temp file to [FileOrganizer.commitDownload] so the final
 *      destination obeys the user's storage preference (internal vs. SAF),
 *      as a new file: it never replaces a library file or shares a path
 *      with another track.
 *   5. Persists the embedded album art (if any) into the app's cache.
 *   6. Inserts a [Track] with `source = MusicSource.LOCAL`, `isDownloaded =
 *      true`, and `spotifyUri` / `youtubeId` null.
 *
 * Runs on an app-scoped [CoroutineScope] so the import survives VM
 * death — the user can leave Settings/Library and come back to see
 * progress. The UI observes [state] to render a progress strip.
 *
 * Safety invariants:
 *   - Only content:// URIs whose provider belongs to another app are read.
 *   - No sender-supplied text becomes the temp name or a file extension;
 *     folder and file names derived from tags or the display name go
 *     through slugify. The extension is always one of
 *     [ImportedAudioType.EXTENSIONS].
 *   - Per-file failures don't abort the batch — they increment `failed`
 *     and log with the offending URI.
 *   - Temp files are always deleted (success: commitDownload deletes
 *     them; a failure or a cancel: [importOne] deletes them).
 *   - A cancel stops the batch and leaves it [LocalImportState.Idle], never
 *     [LocalImportState.Done]. A file whose save has begun finishes first,
 *     so the library never keeps a file no song records.
 *   - Concurrent [start] calls while [Running] are no-ops.
 */
@Singleton
class LocalImportCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileOrganizer: FileOrganizer,
    private val musicRepository: MusicRepository,
) {
    private val _state = MutableStateFlow<LocalImportState>(LocalImportState.Idle)
    val state: StateFlow<LocalImportState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null

    /**
     * Start importing [uris]. No-op if already [LocalImportState.Running].
     * An empty list flips to `Done(0, 0)` immediately so the UI can show
     * a "nothing to import" banner without a spinner.
     */
    fun start(uris: List<Uri>) {
        if (_state.value is LocalImportState.Running) return
        if (uris.isEmpty()) {
            _state.value = LocalImportState.Done(imported = 0, failed = 0)
            return
        }
        activeJob = scope.launch { runImport(uris) }
    }

    /** Cancel a running import. Files imported so far remain in the library. */
    fun cancel() {
        activeJob?.cancel()
        activeJob = null
        _state.value = LocalImportState.Idle
    }

    /** Dismiss a terminal state ([Done] / [Error]), returning to [Idle]. */
    fun dismiss() {
        if (_state.value is LocalImportState.Done || _state.value is LocalImportState.Error) {
            _state.value = LocalImportState.Idle
        }
    }

    private suspend fun runImport(uris: List<Uri>) {
        try {
            val total = uris.size
            var imported = 0
            var failed = 0
            _state.value = LocalImportState.Running(current = 0, total = total)

            for ((index, uri) in uris.withIndex()) {
                _state.value = LocalImportState.Running(current = index, total = total)
                val ok = runCatching { importOne(uri) }
                    // A cancel is not a failed file: it stops the batch (the catch below).
                    .onFailure { e -> if (e is CancellationException) throw e }
                if (ok.isSuccess) {
                    imported++
                } else {
                    failed++
                    Log.w(TAG, "Import failed for $uri", ok.exceptionOrNull())
                }
                // A cancel that came while a file was being saved stops the
                // batch here, before the next file, and never ends on Done.
                currentCoroutineContext().ensureActive()
            }

            _state.value = LocalImportState.Done(imported = imported, failed = failed)
        } catch (t: CancellationException) {
            _state.value = LocalImportState.Idle
            throw t
        } catch (t: Throwable) {
            Log.e(TAG, "Import run failed", t)
            _state.value = LocalImportState.Error(t.message ?: "Unknown error")
        }
    }

    private suspend fun importOne(uri: Uri) {
        // Only content:// URIs whose provider belongs to another app are read.
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "Only content:// URIs are imported" }
        require(!isOwnAuthority(uri.authority)) { "URIs of Stash's own providers aren't imported" }

        val displayName = runCatching {
            DocumentFile.fromSingleUri(context, uri)?.name
        }.getOrNull()
        val mime = context.contentResolver.getType(uri)
        // The provider picks both of these, so they only choose the extension
        // when they map to an audio type Stash saves (see ImportedAudioType).
        val claimed = ImportedAudioType.fromClaims(mime, displayName)

        // Copy URI bytes into a temp file in the download cache dir so
        // FileOrganizer.commitDownload can route the write to either
        // internal or SAF based on the user's storage preference. The name is
        // ours alone; a claim-less file waits under `.tmp` for its content to
        // be identified.
        val tempDir = fileOrganizer.getTempDir()
        val tempFile = File(tempDir, "import_${UUID.randomUUID()}.${claimed ?: "tmp"}")
        check(tempFile.canonicalFile.parentFile == tempDir.canonicalFile) { "Import temp file outside the download cache" }
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Could not open input stream for $uri")

            val metadata = extractMetadata(tempFile, displayName)

            // No usable claim: the content decides, by the container type the
            // retriever reads. Content it can't identify as audio Stash saves is
            // refused, never given a default extension.
            val ext = claimed
                ?: ImportedAudioType.fromContent(metadata.containerMime, metadata.hasVideo)
                ?: throw IllegalArgumentException("Not an audio file Stash can import")

            // A cancel during the copy stops here, before anything reaches the library.
            currentCoroutineContext().ensureActive()

            // Saving the file and its row runs to the end once begun: a cancel
            // between the two would leave a library file no song records.
            withContext(NonCancellable) { save(tempFile, ext, metadata) }
        } finally {
            // commitDownload deletes the temp copy once it has the bytes; a
            // failure or a cancel before that leaves it here.
            if (tempFile.exists()) runCatching { tempFile.delete() }
        }
    }

    /** Files [tempFile] in the library and records it as a new LOCAL track. */
    private suspend fun save(tempFile: File, ext: String, metadata: LocalMetadata) {
        // A new track: never written over a file already at its name (a
        // downloaded copy of the same song, say) or onto a path another
        // track records; the name gets -2, -3, … instead.
        val committed = fileOrganizer.commitDownload(
            tempFile = tempFile,
            artist = metadata.artist,
            album = metadata.album,
            title = metadata.title,
            format = ext,
            asNewFile = true,
        )

        val albumArtPath = metadata.embeddedArt?.let { bytes ->
            runCatching {
                val artDir = fileOrganizer.getAlbumArtDir()
                val artFile = File(artDir, "local_${UUID.randomUUID()}.jpg")
                artFile.writeBytes(bytes)
                artFile.absolutePath
            }.getOrNull()
        }

        musicRepository.insertTrack(
            Track(
                title = metadata.title,
                artist = metadata.artist,
                album = metadata.album ?: "",
                durationMs = metadata.durationMs,
                filePath = committed.filePath,
                fileFormat = ext,
                fileSizeBytes = committed.sizeBytes,
                source = MusicSource.LOCAL,
                spotifyUri = null,
                youtubeId = null,
                albumArtPath = albumArtPath,
                isDownloaded = true,
                metadataEmbeddedAt = System.currentTimeMillis(),
            ),
        )
    }

    private data class LocalMetadata(
        val title: String,
        val artist: String,
        val album: String?,
        val durationMs: Long,
        val embeddedArt: ByteArray?,
        /** The container MIME type the retriever read from the content, if any. */
        val containerMime: String?,
        /** The retriever's "has video" answer for the content: "yes", or null. */
        val hasVideo: String?,
    )

    /**
     * Reads ID3 / MP4 metadata from [file] via `MediaMetadataRetriever`.
     * Falls back to filename parsing when tags are missing — so a file
     * named `Radiohead - Idioteque.mp3` becomes title=Idioteque,
     * artist=Radiohead even if the ID3 tags are stripped.
     */
    private fun extractMetadata(file: File, displayName: String?): LocalMetadata {
        val retriever = MediaMetadataRetriever()
        try {
            runCatching { retriever.setDataSource(file.absolutePath) }
            val taggedTitle = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.trim()?.takeIf { it.isNotBlank() }
            val taggedArtist = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.trim()?.takeIf { it.isNotBlank() }
            val taggedAlbum = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?.trim()?.takeIf { it.isNotBlank() }
            val duration = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val embedded = retriever.embeddedPicture
            val containerMime = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            val hasVideo = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)

            // Filename fallback: "Artist - Title.ext" → split on " - ".
            val baseName = (displayName ?: file.nameWithoutExtension).substringBeforeLast('.')
            val dashIndex = baseName.indexOf(" - ")
            val filenameArtist = if (dashIndex > 0) baseName.substring(0, dashIndex).trim() else null
            val filenameTitle = if (dashIndex > 0) {
                baseName.substring(dashIndex + 3).trim()
            } else {
                baseName.trim()
            }

            val title = taggedTitle
                ?: filenameTitle.takeIf { it.isNotBlank() }
                ?: "Unknown Track"
            val artist = taggedArtist ?: filenameArtist ?: "Unknown Artist"

            return LocalMetadata(
                title = title,
                artist = artist,
                album = taggedAlbum,
                durationMs = duration,
                embeddedArt = embedded,
                containerMime = containerMime,
                hasVideo = hasVideo,
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * True when [authority] names one of this app's providers: the package
     * name itself, or the package name followed by a dot, which is how every
     * provider the app declares is named (`<package>.fileprovider`,
     * `<package>.androidx-startup`). A `<user>@` prefix is ignored, as the
     * system ignores it when it looks the provider up.
     */
    private fun isOwnAuthority(authority: String?): Boolean {
        val name = authority?.substringAfterLast('@') ?: return false
        val own = context.packageName
        return name == own || name.startsWith("$own.")
    }

    companion object {
        private const val TAG = "LocalImportCoord"
    }
}

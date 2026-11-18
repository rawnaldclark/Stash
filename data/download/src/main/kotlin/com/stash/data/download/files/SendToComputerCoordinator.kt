package com.stash.data.download.files

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/** State of "Send to computer" (Settings › Library & storage). */
sealed interface SendToComputerState {
    data object Idle : SendToComputerState
    data class Running(val current: Int, val total: Int) : SendToComputerState
    data class Done(val sent: Int, val failed: Int, val bytes: Long) : SendToComputerState
    data class Error(val message: String) : SendToComputerState
}

/**
 * "Send to computer": copies every downloaded song into `Download/Stash transfer/`, where a computer sees it over USB
 * ("File transfer"), with `stash-transfer.json` saying which song each file is. Stash for desktop, with the phone
 * plugged in, takes the files into its library as downloaded songs (matched by ISRC, YouTube id, then title and
 * artist) and deletes the folder after.
 *
 * Downloads live in the app's private storage (or a picked folder), which a computer cannot reach; this copy is the
 * one way across that needs no developer options. It goes through MediaStore's Downloads collection, so it needs no
 * storage permission (Android 10 and later). An earlier send's files, if the computer never took them, are deleted
 * first, so the folder only ever holds one send. The list is written last: a computer that finds no list finds no
 * finished send.
 *
 * Runs on an app-scoped [scope] (it survives leaving Settings), not a process death; it is a manual, restartable action.
 * Nothing in the library changes: the phone keeps its downloads.
 */
@Singleton
class SendToComputerCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val trackDao: TrackDao,
) {
    private val _state = MutableStateFlow<SendToComputerState>(SendToComputerState.Idle)
    val state: StateFlow<SendToComputerState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null

    /** How many downloaded songs a send would copy, and their size in bytes. */
    suspend fun count(): Pair<Int, Long> {
        val songs = trackDao.getAllDownloaded().filter { !it.filePath.isNullOrBlank() }
        return songs.size to songs.sumOf { it.fileSizeBytes.coerceAtLeast(0) }
    }

    fun start() {
        if (_state.value is SendToComputerState.Running) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            _state.value = SendToComputerState.Error("Send to computer needs Android 10 or newer.")
            return
        }
        activeJob = scope.launch {
            _state.value = try {
                send()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                SendToComputerState.Error(e.message ?: "The songs could not be copied.")
            }
        }
    }

    fun cancel() {
        activeJob?.cancel()
        activeJob = null
        _state.value = SendToComputerState.Idle
    }

    fun dismiss() {
        if (_state.value is SendToComputerState.Done || _state.value is SendToComputerState.Error) {
            _state.value = SendToComputerState.Idle
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun send(): SendToComputerState {
        val songs = trackDao.getAllDownloaded().filter { !it.filePath.isNullOrBlank() }
        if (songs.isEmpty()) return SendToComputerState.Error("There are no downloaded songs to send.")
        clearEarlierSend()
        val entries = mutableListOf<JsonObject>()
        var failed = 0
        var bytes = 0L
        songs.forEachIndexed { i, track ->
            currentCoroutineContext().ensureActive()
            _state.value = SendToComputerState.Running(i, songs.size)
            val name = fileName(track)
            val written = runCatching { copy(track, name) }.getOrNull()
            if (written == null || written <= 0L) {
                failed++
            } else {
                bytes += written
                entries += entry(track, name, written)
            }
        }
        currentCoroutineContext().ensureActive()
        if (entries.isEmpty()) return SendToComputerState.Error("None of the songs could be copied.")
        writeList(entries)
        return SendToComputerState.Done(sent = entries.size, failed = failed, bytes = bytes)
    }

    /** The file's name in the folder: the song's row id (unique) and its own extension. */
    private fun fileName(track: TrackEntity): String {
        val path = track.filePath.orEmpty()
        val ext = path.substringAfterLast('/').substringAfterLast("%2F").substringAfterLast('.', "")
            .lowercase().takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) }
            ?: track.fileFormat.lowercase().ifBlank { "m4a" }
        return "${track.id}.$ext"
    }

    private fun open(track: TrackEntity): InputStream? {
        val path = track.filePath ?: return null
        return if (path.startsWith("content://")) {
            context.contentResolver.openInputStream(Uri.parse(path))
        } else {
            File(path).takeIf { it.isFile }?.inputStream()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun copy(track: TrackEntity, name: String): Long? {
        val input = open(track) ?: return null
        input.use { source ->
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_PATH)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            return try {
                val n = resolver.openOutputStream(uri)?.use { out -> source.copyTo(out, BUFFER) } ?: 0L
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                n
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        }
    }

    /** What the computer needs to know about the song in `name`. */
    private fun entry(t: TrackEntity, name: String, bytes: Long): JsonObject = buildJsonObject {
        put("file", name)
        put("bytes", bytes)
        put("title", t.title)
        put("artist", t.artist)
        if (t.album.isNotBlank()) put("album", t.album)
        if (t.albumArtist.isNotBlank()) put("album_artist", t.albumArtist)
        if (t.durationMs > 0) put("duration_ms", t.durationMs)
        t.isrc?.takeIf { it.isNotBlank() }?.let { put("isrc", it) }
        t.youtubeId?.takeIf { it.isNotBlank() }?.let { put("youtube_id", it) }
        t.spotifyUri?.takeIf { it.isNotBlank() }?.let { put("spotify_uri", it) }
        put("format", t.fileFormat)
        t.bitsPerSample?.let { put("bits", it) }
        t.sampleRateHz?.let { put("sample_rate", it) }
        t.albumArtUrl?.takeIf { it.startsWith("https://") }?.let { put("art_url", it) }
        if (t.stashLikedAt != null) put("liked", true)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeList(entries: List<JsonObject>) {
        val list = buildJsonObject {
            put("stash_transfer", 1)
            put("sent_at_ms", System.currentTimeMillis())
            put("songs", buildJsonArray { entries.forEach { add(it) } })
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, LIST_NAME)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("The song list could not be written.")
        resolver.openOutputStream(uri)?.use { it.write(list.toString().toByteArray()) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
    }

    /** Deletes this app's files from an earlier send (the computer never took them), so one send is all the folder holds. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun clearEarlierSend() {
        context.contentResolver.delete(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            "${MediaStore.Downloads.RELATIVE_PATH} = ?",
            arrayOf(RELATIVE_PATH),
        )
    }

    companion object {
        /** The folder a computer finds: Internal storage › Download › Stash transfer. */
        const val RELATIVE_PATH = "Download/Stash transfer/"
        const val LIST_NAME = "stash-transfer.json"
        private const val BUFFER = 256 * 1024
    }
}

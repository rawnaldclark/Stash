package com.stash.data.download.files

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.annotation.RequiresApi
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * State of "Send to computer" (Settings › Library & storage), one step at a time: getting started ([Preparing]), the
 * songs being copied out ([Copying]), waiting for Stash on the computer to start ([Waiting]), the computer taking them
 * ([Taking]: it deletes each file once it has it, so what is left in the folder is what is still to go), the end
 * ([Finished]), and a cancel being carried out ([Cancelling]).
 */
sealed interface SendToComputerState {
    data object Idle : SendToComputerState
    /** Asked for; the copy has not said how many songs yet. */
    data object Preparing : SendToComputerState
    data class Copying(val current: Int, val total: Int) : SendToComputerState
    /** [total] songs ready in the folder ([notCopied] could not be copied); the computer has taken none yet. */
    data class Waiting(val total: Int, val notCopied: Int) : SendToComputerState
    data class Taking(val taken: Int, val total: Int) : SendToComputerState
    /**
     * The computer took [taken] of [total]: all of them (the folder is gone), the ones chosen on it ([chosen]: it removed
     * the list, and the copies it did not want were cleared), or it stopped with some left.
     */
    data class Finished(val taken: Int, val total: Int, val chosen: Boolean = false) : SendToComputerState
    /** The copy is being stopped and its files removed. */
    data object Cancelling : SendToComputerState
    data class Error(val message: String) : SendToComputerState
}

/**
 * "Send to computer": copies every downloaded song into `Download/Stash transfer/`, where a computer sees it over USB
 * ("File transfer"), with `stash-transfer.json` saying which song each file is (and its format, quality and playlists,
 * to choose by). Stash for desktop, with the phone plugged in, takes the songs chosen there into its library as
 * downloaded songs (matched by ISRC, then title and artist), deleting each from the phone once it has it; then the
 * folder, or, with songs not chosen left in it, just the list, and the phone clears those copies.
 *
 * Downloads live in the app's private storage (or a picked folder), which a computer cannot reach; this copy is the
 * one way across that needs no developer options. It goes through MediaStore's Downloads collection
 * ([MediaStoreSendFolder]), so it needs no storage permission (Android 10 and later).
 *
 * The copy is tens of gigabytes for a big library, so it is built to be left alone and to survive being stopped:
 * - it runs as one unique piece of WorkManager work in the foreground ([SendToComputerWorker]), with a progress
 *   notification, so Android does not freeze it when the app leaves the screen or the screen turns off;
 * - there is only ever one: [start] marks the send as begun before it returns, and the work is unique;
 * - a send that was stopped (the app closed, the phone restarted) is carried on, not begun again ([SongSender]);
 * - [cancel] waits for the copy to really stop, then removes everything it wrote.
 *
 * After the copy it watches the folder and counts what the computer has taken, so the phone says how far along it is.
 * Nothing in the library changes: the phone keeps its downloads.
 */
@Singleton
class SendToComputerCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val trackDao: TrackDao,
    private val playlistDao: PlaylistDao,
) {
    private val _state = MutableStateFlow<SendToComputerState>(SendToComputerState.Idle)
    val state: StateFlow<SendToComputerState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watchJob: Job? = null
    private val lock = Any()

    private val workManager get() = WorkManager.getInstance(context)

    /** How many downloaded songs a send would copy, and their size in bytes. */
    suspend fun count(): Pair<Int, Long> {
        val songs = trackDao.getAllDownloaded().filter { !it.filePath.isNullOrBlank() }
        return songs.size to songs.sumOf { it.fileSizeBytes.coerceAtLeast(0) }
    }

    /** Begins a send, unless one is under way. The state says so before this returns: a second tap finds it busy. */
    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            _state.value = SendToComputerState.Error("Send to computer needs Android 10 or newer.")
            return
        }
        synchronized(lock) {
            if (busy(_state.value)) return
            watchJob?.cancel()
            _state.value = SendToComputerState.Preparing
        }
        // Unique and KEEP: were the state ever wrong, there would still be one copy, not two.
        workManager.enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SendToComputerWorker>().setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build(),
        )
    }

    /**
     * What is there from before, shown where it is: the copy still running (the app's screen was closed, or Android
     * restarted the work), or a finished send the computer has not taken yet. Nothing when idle with no send there.
     */
    fun resume() {
        if (_state.value !is SendToComputerState.Idle || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        scope.launch {
            if (copyUnderWay()) {
                synchronized(lock) { if (_state.value is SendToComputerState.Idle) _state.value = SendToComputerState.Preparing }
                return@launch
            }
            val total = runCatching { sentBefore() }.getOrNull() ?: return@launch
            synchronized(lock) {
                if (_state.value !is SendToComputerState.Idle) return@launch
                _state.value = SendToComputerState.Waiting(total, 0)
                watchJob = scope.launch { runCatching { watch(total) } }
            }
        }
    }

    /** Stops: the copy, or the wait for the computer. Returns at once; the files are gone when the state is Idle again. */
    fun cancel() {
        synchronized(lock) {
            if (_state.value is SendToComputerState.Cancelling || _state.value is SendToComputerState.Idle) return
            _state.value = SendToComputerState.Cancelling
            watchJob?.cancel()
            watchJob = null
        }
        scope.launch {
            runCatching {
                workManager.cancelUniqueWork(WORK_NAME)
                // The copy stops between blocks; nothing is removed until it has, or it would write on behind us.
                withTimeoutOrNull(STOP_WAIT_MS) { workManager.getWorkInfosForUniqueWorkFlow(WORK_NAME).first { infos -> infos.all { it.state.isFinished } } }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) folder().clear()
            }
            _state.value = SendToComputerState.Idle
        }
    }

    /** Puts away the result. A send the computer stopped partway stays in the folder, to be taken when it carries on. */
    fun dismiss() {
        synchronized(lock) {
            if (_state.value is SendToComputerState.Finished || _state.value is SendToComputerState.Error) {
                watchJob?.cancel()
                watchJob = null
                _state.value = SendToComputerState.Idle
            }
        }
    }

    /**
     * The copy itself, run by [SendToComputerWorker]. [onProgress] is told as it goes (for the notification). Throws
     * CancellationException when cancelled; anything else is shown as the error.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    internal suspend fun copy(onProgress: suspend (done: Int, total: Int) -> Unit) {
        // Cancelled as the work was starting: leave the state to the cancel.
        if (_state.value is SendToComputerState.Cancelling) return
        try {
            val songs = trackDao.getAllDownloaded().filter { !it.filePath.isNullOrBlank() }
            if (songs.isEmpty()) {
                folder().clear()
                _state.value = SendToComputerState.Error("There are no downloaded songs to send.")
                return
            }
            // Each song's playlists, for choosing on the computer; a send goes without them rather than not at all.
            val playlists = runCatching { playlistDao.playlistNamesOfSongs() }.getOrDefault(emptyList()).groupBy({ it.trackId }, { it.name })
            var told = 0L
            val result = SongSender(folder(), ::freeBytes).send(songs.map { sendSong(it, playlists[it.id].orEmpty()) }) { done, total ->
                if (_state.value !is SendToComputerState.Cancelling) _state.value = SendToComputerState.Copying(done.coerceAtMost(total - 1), total)
                val now = System.currentTimeMillis()
                if (now - told >= NOTIFY_EVERY_MS || done == total) {
                    told = now
                    runCatching { onProgress(done, total) }
                }
            }
            if (result.entries.isEmpty()) {
                _state.value = SendToComputerState.Error("None of the songs could be copied.")
                return
            }
            synchronized(lock) {
                if (_state.value is SendToComputerState.Cancelling) return
                _state.value = SendToComputerState.Waiting(result.entries.size, result.failed)
                watchJob?.cancel()
                watchJob = scope.launch { runCatching { watch(result.entries.size) } }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            stoppedFromOutside()
            throw e
        } catch (e: NoRoomException) {
            if (_state.value !is SendToComputerState.Cancelling) _state.value = SendToComputerState.Error(noRoom(e.needed))
        } catch (e: Exception) {
            if (_state.value !is SendToComputerState.Cancelling) _state.value = SendToComputerState.Error(e.message ?: "The songs could not be copied.")
        }
    }

    /**
     * The copy was stopped by something other than [cancel]. The notification's own Cancel cancels the work directly:
     * that is finished the same way (the files removed, the state Idle). Android stopping the work to run it again
     * later is left alone: the send carries on when it does.
     */
    private fun stoppedFromOutside() {
        if (_state.value is SendToComputerState.Cancelling) return
        scope.launch {
            delay(500)
            val cancelled = runCatching {
                workManager.getWorkInfosForUniqueWorkFlow(WORK_NAME).first().all { it.state == WorkInfo.State.CANCELLED }
            }.getOrDefault(false)
            if (!cancelled) return@launch
            synchronized(lock) {
                if (_state.value !is SendToComputerState.Preparing && _state.value !is SendToComputerState.Copying) return@launch
                _state.value = SendToComputerState.Cancelling
            }
            runCatching { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) folder().clear() }
            _state.value = SendToComputerState.Idle
        }
    }

    private fun busy(state: SendToComputerState) = state is SendToComputerState.Preparing || state is SendToComputerState.Copying ||
        state is SendToComputerState.Waiting || state is SendToComputerState.Taking || state is SendToComputerState.Cancelling

    private suspend fun copyUnderWay(): Boolean = runCatching {
        workManager.getWorkInfosForUniqueWorkFlow(WORK_NAME).first().any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
    }.getOrDefault(false)

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun folder() = MediaStoreSendFolder(context.contentResolver)

    /** How many songs the list in the folder names, when this app's list is there. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun sentBefore(): Int? {
        val text = folder().readList() ?: return null
        val songs = (kotlinx.serialization.json.Json.parseToJsonElement(text) as? JsonObject)?.get("songs") as? JsonArray
        return songs?.size?.takeIf { it > 0 }
    }

    /**
     * Counts what the computer has taken, every second, until it has all of it (the folder is empty), or all it chose
     * (it removed the list: the copies it did not want are cleared, the songs stay in the library). Once it has
     * started, a minute with nothing more taken shows it stopped ([Finished] with some left), and the count goes on: a
     * computer that carries on (the phone plugged back in) is shown taking again. Ends with all taken, [cancel] or
     * [dismiss].
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun watch(total: Int) {
        var taken = 0
        var changedAt = System.currentTimeMillis()
        while (true) {
            delay(WATCH_EVERY_MS)
            val files = folder().files().filter { !it.pending }
            val listLeft = files.any { it.name == SongSender.LIST_NAME }
            val songsLeft = files.size - if (listLeft) 1 else 0
            if (songsLeft == 0 && !listLeft) {
                _state.value = SendToComputerState.Finished(total, total)
                return
            }
            if (!listLeft) {
                val took = (total - songsLeft).coerceIn(0, total)
                runCatching { folder().clear() }
                _state.value = SendToComputerState.Finished(took, total, chosen = true)
                return
            }
            val nowTaken = (total - songsLeft).coerceIn(0, total)
            if (nowTaken != taken) {
                taken = nowTaken
                changedAt = System.currentTimeMillis()
            }
            if (taken == 0) continue
            _state.value = if (System.currentTimeMillis() - changedAt > STOPPED_AFTER_MS) {
                SendToComputerState.Finished(taken, total)
            } else {
                SendToComputerState.Taking(taken, total)
            }
        }
    }

    private fun sendSong(track: TrackEntity, playlists: List<String>): SendSong = SendSong(
        name = fileName(track),
        bytes = sizeOf(track),
        open = { open(track) },
        entry = { name, bytes -> entry(track, name, bytes, playlists) },
    )

    private fun open(track: TrackEntity): InputStream? {
        val path = track.filePath ?: return null
        return if (path.startsWith("content://")) {
            context.contentResolver.openInputStream(Uri.parse(path))
        } else {
            File(path).takeIf { it.isFile }?.inputStream()
        }
    }

    /** The room left where the copies go (shared storage), or null when it can't be asked. */
    private fun freeBytes(): Long? =
        runCatching { android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path).availableBytes }.getOrNull()

    /** The file's real size now (not the library's note of it), or null when it can't be asked. */
    private fun sizeOf(track: TrackEntity): Long? {
        val path = track.filePath ?: return null
        return runCatching {
            if (path.startsWith("content://")) {
                context.contentResolver.query(Uri.parse(path), arrayOf(OpenableColumns.SIZE), null, null, null)
                    ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
            } else {
                File(path).takeIf { it.isFile }?.length()
            }
        }.getOrNull()?.takeIf { it > 0 }
    }

    companion object {
        const val WORK_NAME = "send_to_computer"
        private const val WATCH_EVERY_MS = 1_000L
        /** Once the computer has started, this long with nothing more taken means it has stopped. */
        private const val STOPPED_AFTER_MS = 60_000L
        /** How long a cancel waits for the copy to stop before removing the files anyway. */
        private const val STOP_WAIT_MS = 30_000L
        private const val NOTIFY_EVERY_MS = 1_000L

        /** What a send that has no room says: how much more it needs, and where room often hides. */
        internal fun noRoom(needed: Long): String {
            val size = if (needed >= 1_000_000_000L) "%.1f GB".format(java.util.Locale.ROOT, needed / 1e9) else "${(needed / 1_000_000L).coerceAtLeast(1)} MB"
            return "Not enough room on this phone: the copies need $size more. Free some space, then tap Send to computer again: " +
                "it carries on where it stopped. Files you deleted may still be taking room in the Files app's Trash."
        }

        /** The file's name in the folder: the song's row id (unique) and its own extension. */
        internal fun fileName(track: TrackEntity): String {
            val path = track.filePath.orEmpty()
            val ext = path.substringAfterLast('/').substringAfterLast("%2F").substringAfterLast('.', "")
                .lowercase().takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) }
                ?: track.fileFormat.lowercase().ifBlank { "m4a" }
            return "${track.id}.$ext"
        }

        /** What the computer needs to know about the song in `name`, and what it is chosen by there (its [playlists]). */
        internal fun entry(t: TrackEntity, name: String, bytes: Long, playlists: List<String> = emptyList()): JsonObject = buildJsonObject {
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
            val named = playlists.map(String::trim).filter(String::isNotEmpty).distinct()
            if (named.isNotEmpty()) put("playlists", JsonArray(named.map(::JsonPrimitive)))
        }
    }
}

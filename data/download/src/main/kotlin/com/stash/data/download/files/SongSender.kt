package com.stash.data.download.files

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import java.io.InputStream

/** A file in the send folder, as the folder itself reports it. */
data class SentFile(val name: String, val bytes: Long, val pending: Boolean = false)

/**
 * The folder a computer finds the songs in (`Download/Stash transfer/`), reduced to what a send needs. The real one is
 * MediaStore's Downloads collection ([MediaStoreSendFolder]); tests use one in memory.
 */
interface SendFolder {
    /** Every file of this app's in the folder, the unfinished ones too. */
    fun files(): List<SentFile>

    /**
     * Writes [source] as [name]: the name the folder really gave it (it may differ when a file of that name is in the
     * way) and its size, or null when it could not be started. [keepGoing] is asked between blocks: false stops the
     * copy, and the part written is removed.
     */
    fun write(name: String, source: InputStream, keepGoing: () -> Boolean): SentFile?

    fun delete(name: String)

    /** Writes the list last, replacing an earlier one. */
    fun writeList(text: String)
}

/** A song to send: the file's name in the folder, how to read it, its size when known, and what the list says of it. */
class SendSong(
    val name: String,
    /** The file's size on the phone, or null when it can't be told without reading it (never reused then). */
    val bytes: Long?,
    val open: () -> InputStream?,
    /** The list's entry for the song, given the name and size it has in the folder. */
    val entry: (name: String, bytes: Long) -> JsonObject,
)

/** What a send did: the list's entries (the songs in the folder), how many were already there, and how many failed. */
data class SendResult(val entries: List<JsonObject>, val reused: Int, val failed: Int)

/**
 * Copies songs into the send folder so that an interrupted send costs little: a song already there whole (its own
 * name, the size of the phone's file, finished) is left as it is, anything else in the folder is removed first, and the
 * list is written only when every song has been tried. So a send that was stopped (the app closed, the phone
 * restarted, a cancel) is carried on by starting it again, and never leaves two copies of a song.
 *
 * The list names each file as the folder really named it: asking for `76.flac` can give `76 (1).flac`.
 */
class SongSender(private val folder: SendFolder) {

    /** [onProgress] is told before each song: how many are done, of how many. Cancelling the coroutine stops between blocks. */
    suspend fun send(songs: List<SendSong>, onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }): SendResult {
        val context = currentCoroutineContext()
        val wanted = songs.associateBy { it.name }
        // What a stopped send left: whole files of this send are kept, everything else goes (halves, renamed doubles,
        // another send's songs, the old list).
        val kept = HashMap<String, SentFile>()
        for (file in folder.files()) {
            context.ensureActive()
            val song = wanted[file.name]
            if (song != null && !file.pending && song.bytes != null && song.bytes > 0 && file.bytes == song.bytes) {
                kept[file.name] = file
            } else {
                folder.delete(file.name)
            }
        }

        val entries = ArrayList<JsonObject>(songs.size)
        var failed = 0
        var reused = 0
        for ((i, song) in songs.withIndex()) {
            context.ensureActive()
            onProgress(i, songs.size)
            val there = kept[song.name]
            if (there != null) {
                entries += song.entry(there.name, there.bytes)
                reused++
                continue
            }
            val written = runCatching {
                song.open()?.use { source -> folder.write(song.name, source) { context[kotlinx.coroutines.Job]?.isActive != false } }
            }.getOrNull()
            // Stopped mid-song: the folder took the half back; say so rather than count the song as failed.
            context.ensureActive()
            if (written == null || written.bytes <= 0L) failed++ else entries += song.entry(written.name, written.bytes)
        }
        context.ensureActive()
        onProgress(songs.size, songs.size)
        if (entries.isNotEmpty()) folder.writeList(listText(entries))
        return SendResult(entries, reused, failed)
    }

    companion object {
        const val LIST_NAME = "stash-transfer.json"

        fun listText(entries: List<JsonObject>, sentAtMs: Long = System.currentTimeMillis()): String =
            kotlinx.serialization.json.buildJsonObject {
                put("stash_transfer", kotlinx.serialization.json.JsonPrimitive(1))
                put("sent_at_ms", kotlinx.serialization.json.JsonPrimitive(sentAtMs))
                put("songs", kotlinx.serialization.json.JsonArray(entries))
            }.toString()
    }
}

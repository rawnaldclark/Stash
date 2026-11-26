package com.stash.data.download.files

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import java.io.InputStream

/** A file in the send folder, as the folder itself reports it ([id]: its own id there, when it has one). */
data class SentFile(val name: String, val bytes: Long, val pending: Boolean = false, val id: Long? = null)

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

    /**
     * Writes the list last, replacing an earlier one. Throws [ListInTheWayException] when it can't have its own name
     * (nothing of it is left then).
     */
    fun writeList(text: String)

    /** The ids of this app's files in the folder that are in the Trash (put there in the Files app). */
    fun trashed(): Set<Long> = emptySet()

    /** Removes every file of this app's in the folder (a cancelled send leaves nothing): how many went. */
    fun clear(): Int

    /** The list's text, when this app's list is in the folder. */
    fun readList(): String?
}

/** A song to send: the file's name in the folder, how to read it, its size when known, and what the list says of it. */
class SendSong(
    val name: String,
    /** The file's size on the phone, or null when it can't be told without reading it (never reused then). */
    val bytes: Long?,
    val open: () -> InputStream?,
    /** The list's entry for the song, given the name and size it has in the folder. */
    val entry: (name: String, bytes: Long) -> JsonObject,
    /** The song's row id, to remember it by once the computer has it. */
    val id: Long = 0L,
)

/**
 * What a send did: the list's entries (the songs in the folder), how many were already there, and how many [failed]
 * (a file missing or unreadable, a write that didn't take). [full]: the phone filled up partway, so these were not
 * copied (the song that found it full, and the ones after it, not tried). They need [noRoom] bytes more than were free
 * then, even once the computer has taken the songs listed (which frees the room their copies take): 0 when that is room
 * enough, null when the phone didn't fill up. The list names the songs copied before it, for the computer to take.
 */
data class SendResult(val entries: List<JsonObject>, val reused: Int, val failed: Int, val noRoom: Long? = null, val full: Int = 0)

/**
 * The phone has no room for the copies: they need [needed] bytes more than are free. Nothing more is copied (a full
 * phone fails everything on it), and what was copied stays, for the send to carry on once there is room: [copies]
 * whole copies of this send are in the folder, with no list.
 */
class NoRoomException(val needed: Long, val copies: Int = 0) : Exception("There is not enough room on this phone for the copies.")

/**
 * The list could not have its own name ([SongSender.LIST_NAME]): the folder holds a list this app can't see, another
 * copy of Stash's (the release and debug apps on one phone) or one left by an uninstall. A computer reads only that
 * name, so it would take the other send and never this one.
 */
class ListInTheWayException : Exception("Another copy of Stash has a stash-transfer.json in Download/Stash transfer.")

/**
 * The folder was asked what it holds and gave no answer (MediaStore returned no cursor): not an empty folder, which
 * would read as every song of a send taken.
 */
class NoAnswerException : IllegalStateException("MediaStore did not answer.")

/**
 * Copies songs into the send folder so that an interrupted send costs little: a song already there whole (its own
 * name, the size of the phone's file, finished) is left as it is, anything else in the folder is removed first, and the
 * list is written only when every song has been tried. So a send that was stopped (the app closed, the phone
 * restarted, a cancel) is carried on by starting it again, and never leaves two copies of a song.
 *
 * The list names each file as the folder really named it: asking for `76.flac` can give `76 (1).flac`.
 *
 * The copies must fit: what is still to copy, and [SPARE] besides, is weighed against [freeBytes] (null: not known)
 * before anything is copied ([NoRoomException]). A copy that finds the phone full stops the copying: the songs copied
 * until then are listed, so the computer can take them (which frees their room), and the rest are not tried
 * ([SendResult.noRoom]); with none copied, or no room left for the list, the send stops ([NoRoomException]).
 */
class SongSender(private val folder: SendFolder, private val freeBytes: () -> Long? = { null }) {

    /**
     * [onProgress] is told before each song: how many are done, of how many. [beforeList] is told each song in the list
     * and its file, and what the send did (what [send] returns), just before the list is written (the computer takes
     * nothing before there is a list). Cancelling the coroutine stops between blocks.
     */
    suspend fun send(
        songs: List<SendSong>,
        beforeList: (listed: List<Pair<SendSong, SentFile>>, result: SendResult) -> Unit = { _, _ -> },
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): SendResult {
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

        // What is still to copy (from the [from]th song on) has to fit, with room to spare.
        fun stillToCopy(from: Int) = songs.drop(from).filter { kept[it.name] == null }.sumOf { it.bytes ?: 0L }
        freeBytes()?.let { free ->
            val needed = stillToCopy(0) + SPARE - free
            if (needed > 0) throw NoRoomException(needed, copies = kept.size)
        }

        val entries = ArrayList<JsonObject>(songs.size)
        val listed = ArrayList<Pair<SendSong, SentFile>>(songs.size)
        var failed = 0
        // Not copied because the phone is full, told apart from the songs that failed for another reason: those a send
        // again won't carry.
        var outOfRoom = 0
        var reused = 0
        // Once the phone is full: what the songs still to copy needed then, with the room to spare, more than was free.
        // No more are tried.
        var needed: Long? = null
        for ((i, song) in songs.withIndex()) {
            context.ensureActive()
            onProgress(i, songs.size)
            val there = kept[song.name]
            if (there != null) {
                entries += song.entry(there.name, there.bytes)
                listed += song to there
                reused++
                continue
            }
            if (needed != null) {
                outOfRoom++
                continue
            }
            val written = try {
                song.open()?.use { source -> folder.write(song.name, source) { context[kotlinx.coroutines.Job]?.isActive != false } }
            } catch (e: Exception) {
                context.ensureActive()
                // The phone is full: every song after this one would fail too, so none is tried. Those copied stay.
                if (full(e)) needed = stillToCopy(i) + SPARE - (freeBytes() ?: 0L)
                null
            }
            // Stopped mid-song: the folder took the half back; say so rather than count the song as failed.
            context.ensureActive()
            if (written == null || written.bytes <= 0L) {
                // The song that found the phone full is the first of those not copied for room.
                if (needed != null) outOfRoom++ else failed++
            } else {
                entries += song.entry(written.name, written.bytes)
                listed += song to written
            }
        }
        context.ensureActive()
        onProgress(songs.size, songs.size)
        // A cancel told during that last call: no list for a send that was stopped.
        context.ensureActive()
        // The room the listed copies take is freed once the computer has them: what the rest need even then.
        val noRoom = needed?.let { (it - listed.sumOf { (_, file) -> file.bytes }).coerceAtLeast(0L) }
        noRoom?.let { if (entries.isEmpty()) throw NoRoomException(it) }
        val result = SendResult(entries, reused, failed, noRoom, outOfRoom)
        if (entries.isNotEmpty()) {
            beforeList(listed, result)
            try {
                folder.writeList(listText(entries))
            } catch (e: Exception) {
                context.ensureActive()
                // Not even the list fits: the copies stay, with nothing to take them by (so their room is not freed).
                if (full(e)) throw NoRoomException((needed ?: (SPARE - (freeBytes() ?: 0L))).coerceAtLeast(0L), copies = entries.size)
                throw e
            }
        }
        return result
    }

    companion object {
        const val LIST_NAME = "stash-transfer.json"

        /** Left free beyond the copies: a phone with no room at all stops working well. */
        const val SPARE = 1_000_000_000L

        /** Whether [e] says the storage is full (ENOSPC), here or in what caused it. */
        internal fun full(e: Throwable): Boolean =
            generateSequence(e) { it.cause }.take(5).any { it.message.orEmpty().let { m -> "ENOSPC" in m || "No space left" in m } }

        fun listText(entries: List<JsonObject>, sentAtMs: Long = System.currentTimeMillis()): String =
            kotlinx.serialization.json.buildJsonObject {
                put("stash_transfer", kotlinx.serialization.json.JsonPrimitive(1))
                put("sent_at_ms", kotlinx.serialization.json.JsonPrimitive(sentAtMs))
                put("songs", kotlinx.serialization.json.JsonArray(entries))
            }.toString()
    }
}

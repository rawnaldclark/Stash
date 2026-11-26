package com.stash.data.download.files

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * What "Send to computer" remembers from one send to the next, kept in [file]: the songs the computer has ([onComputer],
 * by row id), so a send copies only the others; and, while a send waits in the folder, which song each of its files is
 * ([sending]), so each one the computer takes is known as it goes, the app running or not.
 *
 * A song is on the computer once its copy goes from the folder while the send is there: the computer deletes each file
 * once it has it, and the copies of songs it had already. A copy put in the Files app's Trash was not taken: it is told
 * by the file's own id, as a trashed file keeps its name (and the Trash can hold an older send's file of that name).
 *
 * Row ids name songs only in the library they were given in: a restore that swaps in a backup's library gives them
 * out again, to other songs. So the memory is kept under [library] ([com.stash.core.data.db.LibrarySwaps], how many
 * times the library was swapped), and one kept under another number is not trusted: a send then copies every song.
 */
class SendMemory(private val file: File, private val library: Long = 0L) {

    /** A file of the send in the folder: the song it is ([track], its row id) and the file's own id there, when known. */
    data class Sent(val track: Long, val media: Long?)

    private var known: Pair<Set<Long>, Map<String, Sent>>? = null

    /** The row ids of the songs the computer has. */
    @Synchronized fun onComputer(): Set<Long> = read().first

    /** The send now in the folder, by file name; empty when there is none. */
    @Synchronized fun sending(): Map<String, Sent> = read().second

    /** The send now in the folder (written before its list, so nothing is taken before it is known). */
    @Synchronized fun startSending(files: Map<String, Sent>) = write(read().first, files)

    /**
     * Every file of the send that is gone from the folder, but not to the Trash, is a song the computer has. [present] are
     * the files' names in the folder now, [trashed] the ids of this app's files there in the Trash. How many more it has.
     *
     * A file found in the Trash is decided then, once: it is let go, so the Trash emptied later doesn't make it taken.
     */
    @Synchronized fun taken(present: Set<String>, trashed: Set<Long>): Int {
        val (has, sending) = read()
        val gone = sending.filterKeys { it !in present }
        val binned = gone.filterValues { it.media != null && it.media in trashed }.keys
        val took = (gone - binned).values.map { it.track }.filter { it !in has }.toSet()
        if (took.isNotEmpty() || binned.isNotEmpty()) write(has + took, sending - binned)
        return took.size
    }

    /** The send in the folder is over (taken, cleared, cancelled): what the computer took stays known, the files don't. */
    @Synchronized fun doneSending() {
        val (has, sending) = read()
        if (sending.isNotEmpty()) write(has, emptyMap())
    }

    /** Sends every song again from now on (the computer lost some, or it is another computer). */
    @Synchronized fun forget() {
        val (has, sending) = read()
        if (has.isNotEmpty()) write(emptySet(), sending)
    }

    private fun read(): Pair<Set<Long>, Map<String, Sent>> = known ?: load().also { known = it }

    /**
     * What the file says; nothing when it is missing, can't be read, or was kept for another library (a send then
     * copies every song, as before).
     */
    private fun load(): Pair<Set<Long>, Map<String, Sent>> = runCatching {
        if (!file.isFile) return@runCatching null
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        if ((root["library"]?.jsonPrimitive?.longOrNull ?: 0L) != library) return@runCatching null
        val has = root["on_computer"]?.jsonArray?.mapNotNull { it.jsonPrimitive.longOrNull }?.toSet().orEmpty()
        val sending = (root["sending"] as? JsonObject)?.mapNotNull { (name, value) ->
            val sent = value as? JsonObject ?: return@mapNotNull null
            val track = sent["track"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            name to Sent(track, sent["media"]?.jsonPrimitive?.longOrNull)
        }?.toMap().orEmpty()
        has to sending
    }.getOrNull() ?: (emptySet<Long>() to emptyMap())

    /** Written whole beside it, then put in its place: a phone that dies mid-write keeps the last good one. */
    private fun write(has: Set<Long>, sending: Map<String, Sent>) {
        val text = buildJsonObject {
            put("library", JsonPrimitive(library))
            put("on_computer", JsonArray(has.sorted().map(::JsonPrimitive)))
            put(
                "sending",
                JsonObject(
                    sending.mapValues { (_, sent) ->
                        JsonObject(listOfNotNull("track" to JsonPrimitive(sent.track), sent.media?.let { "media" to JsonPrimitive(it) }).toMap())
                    },
                ),
            )
        }.toString()
        file.parentFile?.mkdirs()
        val next = File(file.path + ".new")
        next.writeText(text)
        if (!next.renameTo(file)) {
            file.delete()
            check(next.renameTo(file)) { "What was sent could not be kept." }
        }
        known = has to sending
    }
}

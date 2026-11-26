package com.stash.data.download.files

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test
import java.io.InputStream

/** [SongSender]: a send that can be stopped anywhere and carried on, and never leaves two copies of a song. */
@OptIn(ExperimentalCoroutinesApi::class)
class SongSenderTest {

    /** The send folder, in memory. [taken] names are "in the way": a write asking for one gets `name (1).ext`. */
    private class FakeFolder : SendFolder {
        val files = LinkedHashMap<String, SentFile>()
        val contents = HashMap<String, ByteArray>()
        var list: String? = null
        val log = mutableListOf<String>()
        val inTheWay = HashSet<String>()
        var failOn: String? = null
        /** The phone is full after this many writes: the next one fails as a full phone's does. */
        var fullAfter: Int? = null
        /** What writing the list throws, when set. */
        var listFails: Exception? = null
        private var writes = 0

        /** Called after each block of a write: a test cancels from here. */
        var onBlock: (name: String) -> Unit = {}

        override fun files(): List<SentFile> = files.values.toList() + listOfNotNull(list?.let { SentFile(SongSender.LIST_NAME, it.length.toLong()) })

        override fun write(name: String, source: InputStream, keepGoing: () -> Boolean): SentFile? {
            if (name == failOn) return null
            if (fullAfter?.let { writes >= it } == true) throw java.io.IOException("write failed: ENOSPC (No space left on device)")
            writes++
            val real = if (name in inTheWay) name.substringBeforeLast('.') + " (1)." + name.substringAfterLast('.') else name
            log += "write $real"
            files[real] = SentFile(real, 0, pending = true)
            val out = java.io.ByteArrayOutputStream()
            val block = ByteArray(4)
            while (true) {
                if (!keepGoing()) { files.remove(real); log += "dropped $real"; return null }
                val n = source.read(block)
                if (n < 0) break
                out.write(block, 0, n)
                onBlock(real)
            }
            contents[real] = out.toByteArray()
            return SentFile(real, out.size().toLong()).also { files[real] = it }
        }

        override fun delete(name: String) {
            log += "delete $name"
            if (name == SongSender.LIST_NAME) list = null else { files.remove(name); contents.remove(name) }
        }

        override fun writeList(text: String) {
            listFails?.let { throw it }
            log += "list"
            list = text
        }

        override fun clear(): Int = files().size.also { files.clear(); contents.clear(); list = null }

        override fun readList(): String? = list
    }

    private val folder = FakeFolder()
    private val sender = SongSender(folder)

    private fun song(id: Int, bytes: ByteArray = ByteArray(10) { id.toByte() }, knownSize: Boolean = true, ext: String = "flac") = SendSong(
        name = "$id.$ext",
        bytes = if (knownSize) bytes.size.toLong() else null,
        open = { bytes.inputStream() },
        entry = { name, n -> buildJsonObject { put("file", name); put("bytes", n); put("title", "Song $id") } },
    )

    private fun listed(): List<JsonObject> = Json.parseToJsonElement(folder.list!!).jsonObject["songs"]!!.jsonArray.map { it.jsonObject }
    private fun listedFiles() = listed().map { it["file"]!!.jsonPrimitive.content }

    @Test fun `every song is copied, then the list is written last`() = runTest {
        val seen = mutableListOf<Pair<Int, Int>>()
        val result = sender.send(listOf(song(1), song(2), song(3))) { done, total -> seen += done to total }
        assertThat(result.entries).hasSize(3)
        assertThat(result.reused).isEqualTo(0)
        assertThat(result.failed).isEqualTo(0)
        assertThat(folder.log).containsExactly("write 1.flac", "write 2.flac", "write 3.flac", "list").inOrder()
        assertThat(listedFiles()).containsExactly("1.flac", "2.flac", "3.flac").inOrder()
        assertThat(seen).containsExactly(0 to 3, 1 to 3, 2 to 3, 3 to 3).inOrder()
        assertThat(folder.files.values.none { it.pending }).isTrue()
    }

    @Test fun `a send carried on leaves the whole songs alone and copies only the rest`() = runTest {
        sender.send(listOf(song(1), song(2)))
        folder.log.clear()
        val result = sender.send(listOf(song(1), song(2), song(3)))
        assertThat(result.reused).isEqualTo(2)
        // The old list goes first: a computer that finds no list finds no finished send.
        assertThat(folder.log).containsExactly("delete ${SongSender.LIST_NAME}", "write 3.flac", "list").inOrder()
        assertThat(listedFiles()).containsExactly("1.flac", "2.flac", "3.flac").inOrder()
    }

    @Test fun `what a stopped send left is cleared - halves, doubles, the wrong size, another send's songs`() = runTest {
        folder.files["1.flac"] = SentFile("1.flac", 10) // whole: kept
        folder.files["2.flac"] = SentFile("2.flac", 4) // cut short
        folder.files["3.flac"] = SentFile("3.flac", 10, pending = true) // never finished
        folder.files["1 (1).flac"] = SentFile("1 (1).flac", 10) // a double from two sends at once
        folder.files["99.flac"] = SentFile("99.flac", 10) // no longer a download
        val result = sender.send(listOf(song(1), song(2), song(3)))
        assertThat(result.reused).isEqualTo(1)
        assertThat(folder.files.keys).containsExactly("1.flac", "2.flac", "3.flac")
        assertThat(folder.files.values.map { it.bytes }).containsExactly(10L, 10L, 10L)
        assertThat(listedFiles()).containsExactly("1.flac", "2.flac", "3.flac").inOrder()
    }

    @Test fun `a song whose size can't be told is always copied again`() = runTest {
        sender.send(listOf(song(1, knownSize = false)))
        folder.log.clear()
        sender.send(listOf(song(1, knownSize = false)))
        assertThat(folder.log).contains("delete 1.flac")
        assertThat(folder.log).contains("write 1.flac")
    }

    @Test fun `the list names each file as the folder really named it`() = runTest {
        folder.inTheWay += "2.flac"
        sender.send(listOf(song(1), song(2)))
        assertThat(listedFiles()).containsExactly("1.flac", "2 (1).flac").inOrder()
    }

    @Test fun `a song that can't be read or written is counted, and the rest still go`() = runTest {
        folder.failOn = "2.flac"
        val unreadable = SendSong("3.flac", 10, open = { null }, entry = { n, b -> buildJsonObject { put("file", n); put("bytes", b) } })
        val result = sender.send(listOf(song(1), song(2), unreadable, song(4)))
        assertThat(result.failed).isEqualTo(2)
        assertThat(listedFiles()).containsExactly("1.flac", "4.flac").inOrder()
    }

    @Test fun `when nothing could be copied no list is written`() = runTest {
        folder.failOn = "1.flac"
        val result = sender.send(listOf(song(1)))
        assertThat(result.entries).isEmpty()
        assertThat(folder.list).isNull()
    }

    @Test fun `a cancel in the middle of a song stops there, takes the half back and writes no list`() = runTest {
        val big = ByteArray(40) { 7 }
        lateinit var job: Job
        folder.onBlock = { name -> if (name == "2.flac") job.cancel() }
        var ended: Throwable? = null
        job = launch {
            try { sender.send(listOf(song(1), song(2, big), song(3))) } catch (e: CancellationException) { ended = e; throw e }
        }
        job.join()
        assertThat(ended).isInstanceOf(CancellationException::class.java)
        assertThat(folder.files.keys).containsExactly("1.flac")
        assertThat(folder.log).contains("dropped 2.flac")
        assertThat(folder.log).doesNotContain("write 3.flac")
        assertThat(folder.list).isNull()

        // Started again, it carries on from there.
        folder.onBlock = {}
        folder.log.clear()
        val result = sender.send(listOf(song(1), song(2, big), song(3)))
        assertThat(result.reused).isEqualTo(1)
        assertThat(folder.log).containsExactly("write 2.flac", "write 3.flac", "list").inOrder()
    }

    @Test fun `a send that won't fit copies nothing and says how much more room it needs`() = runTest {
        // Three songs of 10 bytes to copy, and the room to spare besides.
        val tight = SongSender(folder) { 25L }
        val e = runCatching { tight.send(listOf(song(1), song(2), song(3))) }.exceptionOrNull()
        assertThat(e).isInstanceOf(NoRoomException::class.java)
        assertThat((e as NoRoomException).needed).isEqualTo(30L + SongSender.SPARE - 25L)
        assertThat(e.copies).isEqualTo(0)
        assertThat(folder.files).isEmpty()
        assertThat(folder.list).isNull()
    }

    @Test fun `a send that won't fit says what an earlier one left in the folder`() = runTest {
        sender.send(listOf(song(1), song(2)))
        val e = runCatching { SongSender(folder) { 5L }.send(listOf(song(1), song(2), song(3))) }.exceptionOrNull()
        assertThat((e as NoRoomException).copies).isEqualTo(2)
        assertThat(folder.files.keys).containsExactly("1.flac", "2.flac")
        assertThat(folder.list).isNull()
    }

    @Test fun `songs already there whole need no room again`() = runTest {
        sender.send(listOf(song(1), song(2)))
        val result = SongSender(folder) { SongSender.SPARE + 10L }.send(listOf(song(1), song(2), song(3)))
        assertThat(result.entries).hasSize(3)
        assertThat(result.reused).isEqualTo(2)
    }

    @Test fun `a phone that fills up stops copying, and lists what was copied for the computer to take`() = runTest {
        folder.fullAfter = 1
        // Room enough when asked first; then something else on the phone takes it.
        var asked = 0
        var told: SendResult? = null
        val result = SongSender(folder) { if (asked++ == 0) SongSender.SPARE + 30L else 4L }
            .send(listOf(song(1), song(2), song(3)), beforeList = { _, sent -> told = sent })
        assertThat(result.entries).hasSize(1)
        assertThat(result.failed).isEqualTo(2)
        // What the two songs not copied need, with the room to spare, more than is free, even once the computer has
        // taken the one copied (which frees its 10 bytes).
        assertThat(result.noRoom).isEqualTo(20L + SongSender.SPARE - 4L - 10L)
        // Told before the list, so it is kept with the send.
        assertThat(told).isEqualTo(result)
        assertThat(folder.files.keys).containsExactly("1.flac")
        assertThat(folder.log).doesNotContain("write 3.flac")
        assertThat(listedFiles()).containsExactly("1.flac")

        // With room again, it carries on from there.
        folder.fullAfter = null
        val again = sender.send(listOf(song(1), song(2), song(3)))
        assertThat(again.reused).isEqualTo(1)
        assertThat(again.noRoom).isNull()
        assertThat(listedFiles()).containsExactly("1.flac", "2.flac", "3.flac").inOrder()
    }

    @Test fun `a phone full from the first copy still lists the songs already there whole`() = runTest {
        folder.files["3.flac"] = SentFile("3.flac", 10)
        folder.fullAfter = 0
        val result = sender.send(listOf(song(1), song(2), song(3)))
        assertThat(result.failed).isEqualTo(2)
        // The copy already there is freed too once the computer has it.
        assertThat(result.noRoom).isEqualTo(20L + SongSender.SPARE - 10L)
        assertThat(listedFiles()).containsExactly("3.flac")
        // Song 2 was not tried once the phone was full.
        assertThat(folder.log).doesNotContain("write 2.flac")
    }

    @Test fun `a phone that fills up needs no more room for the rest when the copies free enough`() = runTest {
        folder.fullAfter = 2
        var asked = 0
        // Full with 5 bytes short; the two copies listed free 20 once the computer has them.
        val result = SongSender(folder) { if (asked++ == 0) SongSender.SPARE + 30L else SongSender.SPARE + 5L }
            .send(listOf(song(1), song(2), song(3)))
        assertThat(result.entries).hasSize(2)
        assertThat(result.noRoom).isEqualTo(0L)
    }

    @Test fun `a phone full with nothing copied writes no list and says how much more room it needs`() = runTest {
        folder.fullAfter = 0
        val e = runCatching { sender.send(listOf(song(1), song(2))) }.exceptionOrNull()
        assertThat(e).isInstanceOf(NoRoomException::class.java)
        assertThat((e as NoRoomException).needed).isEqualTo(20L + SongSender.SPARE)
        assertThat(e.copies).isEqualTo(0)
        assertThat(folder.files).isEmpty()
        assertThat(folder.list).isNull()
    }

    @Test fun `no room for the list stops the send, with the copies left in the folder`() = runTest {
        folder.listFails = java.io.IOException("write failed: ENOSPC (No space left on device)")
        val e = runCatching { sender.send(listOf(song(1), song(2))) }.exceptionOrNull()
        assertThat(e).isInstanceOf(NoRoomException::class.java)
        assertThat((e as NoRoomException).copies).isEqualTo(2)
        assertThat(folder.files.keys).containsExactly("1.flac", "2.flac")

        // Any other failure of the list is its own.
        folder.listFails = IllegalStateException("The song list could not be written.")
        assertThat(runCatching { sender.send(listOf(song(1))) }.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
    }

    @Test fun `a cancel told during the last progress call writes no list`() = runTest {
        lateinit var job: Job
        var ended: Throwable? = null
        job = launch {
            try {
                sender.send(listOf(song(1), song(2))) { done, total -> if (done == total) job.cancel() }
            } catch (e: CancellationException) {
                ended = e
                throw e
            }
        }
        job.join()
        assertThat(ended).isInstanceOf(CancellationException::class.java)
        assertThat(folder.list).isNull()
        assertThat(folder.log).doesNotContain("list")
    }

    @Test fun `each song in the list is told with its file just before the list is written`() = runTest {
        folder.inTheWay += "2.flac"
        folder.failOn = "3.flac"
        var told: List<Pair<String, String>> = emptyList()
        sender.send(listOf(song(1), song(2), song(3)), beforeList = { listed, _ ->
            folder.log += "told"
            told = listed.map { (song, file) -> song.name to file.name }
        })
        assertThat(told).containsExactly("1.flac" to "1.flac", "2.flac" to "2 (1).flac").inOrder()
        assertThat(folder.log.takeLast(2)).containsExactly("told", "list").inOrder()

        // Carried on: the songs already there are told too.
        folder.failOn = null
        sender.send(listOf(song(1), song(2), song(3)), beforeList = { listed, _ -> told = listed.map { (song, file) -> song.name to file.name } })
        assertThat(told.map { it.first }).containsExactly("1.flac", "2.flac", "3.flac").inOrder()

        // Nothing copied: nothing told, as there is no list.
        folder.failOn = "9.flac"
        var toldAgain = false
        sender.send(listOf(song(9)), beforeList = { _, _ -> toldAgain = true })
        assertThat(toldAgain).isFalse()
    }

    @Test fun `a full phone is told from the error or what caused it`() {
        assertThat(SongSender.full(java.io.IOException("write failed: ENOSPC (No space left on device)"))).isTrue()
        assertThat(SongSender.full(RuntimeException("wrapped", java.io.IOException("No space left on device")))).isTrue()
        assertThat(SongSender.full(java.io.IOException("Broken pipe"))).isFalse()
    }
}

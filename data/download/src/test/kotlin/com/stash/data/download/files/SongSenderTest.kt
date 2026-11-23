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

        /** Called after each block of a write: a test cancels from here. */
        var onBlock: (name: String) -> Unit = {}

        override fun files(): List<SentFile> = files.values.toList() + listOfNotNull(list?.let { SentFile(SongSender.LIST_NAME, it.length.toLong()) })

        override fun write(name: String, source: InputStream, keepGoing: () -> Boolean): SentFile? {
            if (name == failOn) return null
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

        override fun writeList(text: String) { log += "list"; list = text }
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
}

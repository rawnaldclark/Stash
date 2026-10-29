package com.stash.feature.record.bridge

import com.google.common.truth.Truth.assertThat
import com.stash.feature.record.archive.ArchivePlay
import com.stash.feature.record.archive.RecordArchiveSource
import com.stash.feature.record.art.ArtSong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/** `stash:archive-ack` for chunk [seq]. */
private fun ack(seq: Int) = """{"type":"stash:archive-ack","seq":$seq}"""

/** The bridge's app side, without a WebView: what the record says in, what goes back out. */
class RecordHostTest {

    /** A page's reply proxy: what it was sent, parsed. With [acks], it acks each archive chunk, as the fork's app.ts does. */
    private class Page(val acks: Boolean = true) : RecordPort {
        var host: RecordHost? = null
        val sent = mutableListOf<String>()
        override fun post(message: String) {
            sent += message
            if (acks && message.startsWith("{\"type\":\"stash:archive\"")) {
                host?.onMessage(ack(Json.parseToJsonElement(message).jsonObject["seq"]!!.jsonPrimitive.int), this)
            }
        }

        val messages get() = sent.map { Json.parseToJsonElement(it).jsonObject }
        fun types() = messages.map { it["type"]!!.jsonPrimitive.content }
        fun archives() = messages.filter { it["type"]!!.jsonPrimitive.content == "stash:archive" }
        fun seqs() = archives().map { it["seq"]!!.jsonPrimitive.int }
    }

    private class Saves : RecordSaveSink {
        val taken = mutableListOf<RecordAsk.Save>()
        val aborted = mutableListOf<String>()
        var drops = 0
        var note: RecordNote? = null

        /** When set, a chunk waits on it (a slow copy to Downloads). */
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun take(chunk: RecordAsk.Save, appended: suspend () -> Unit): RecordNote? {
            gate?.await()
            taken += chunk
            appended()
            return if (chunk.done) note else null
        }

        override suspend fun abort(id: String) {
            aborted += id
        }

        override suspend fun drop() {
            drops++
        }
    }

    private fun chunk(seq: Int, done: Boolean, id: String = "u-1") =
        """{"type":"stash:save","id":"$id","name":"poster.png","mime":"image/png","action":"save","seq":$seq,"done":$done,"data":"AAAA"}"""

    private fun row(i: Int) = ArchivePlay(id = "t$i|a|$i", t = "T$i", a = "A", s = i.toLong(), ms = 1000, f = 256)

    /** [n] rows, in chunks as [OwnArchive][com.stash.feature.record.archive.OwnArchive] hands them (the last marked). */
    private fun rows(n: Int) = RecordArchiveSource { size, emit ->
        val all = (0 until n).map(::row).chunked(size)
        if (all.isEmpty()) emit(emptyList(), true) else all.forEachIndexed { i, c -> emit(c, i == all.lastIndex) }
    }

    private class Harness(
        val test: TestScope,
        archive: RecordArchiveSource,
        val covers: RecordCovers = RecordCovers { songs, _, _ -> songs.map { null } },
        chunk: Int = 2,
        scope: CoroutineScope = test.backgroundScope,
        library: RecordLibraryArt = RecordLibraryArt.None,
    ) {
        val events = mutableListOf<RecordEvent>()
        val played = mutableListOf<Triple<List<RecordSong>, Int, String>>()
        val saves = Saves()
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        val host = RecordHost(
            scope = scope,
            archive = archive,
            covers = covers,
            player = { songs, index, from -> played += Triple(songs, index, from) },
            saves = saves,
            events = { events += it },
            main = dispatcher,
            background = dispatcher,
            chunkSize = chunk,
            now = { test.testScheduler.currentTime },
            library = library,
        )

        /** Says [raw] and lets the host handle it (messages are queued). */
        fun say(raw: String, page: RecordPort) {
            (page as? Page)?.host = host
            host.onMessage(raw, page)
            test.runCurrent()
        }
    }

    private val hello = """{"type":"stash:hello","v":1}"""

    @Test fun `at most two archive chunks are out unacknowledged - each ack lets one more go, a stray or repeated one moves nothing`() = runTest {
        val h = Harness(this, rows(10)) // five chunks of two
        val page = Page(acks = false)
        h.say(hello, page)
        runCurrent()
        assertThat(page.seqs()).containsExactly(0, 1).inOrder()

        h.say(ack(7), page) // no such chunk out
        h.say("""{"type":"stash:archive-ack","seq":-1}""", page)
        h.say("""{"type":"stash:archive-ack","seq":0.5}""", page)
        runCurrent()
        assertThat(page.seqs()).hasSize(2)

        h.say(ack(0), page)
        runCurrent()
        assertThat(page.seqs()).containsExactly(0, 1, 2).inOrder()
        h.say(ack(0), page) // the same one again
        runCurrent()
        assertThat(page.seqs()).hasSize(3)

        h.say(ack(1), page)
        h.say(ack(2), page)
        runCurrent()
        assertThat(page.seqs()).containsExactly(0, 1, 2, 3, 4).inOrder()
        assertThat(page.archives().last()["done"]!!.jsonPrimitive.content).isEqualTo("true")
    }

    @Test fun `a new hello starts its window afresh - an old stream's ack moves the new one by one at most`() = runTest {
        val h = Harness(this, rows(10))
        val page = Page(acks = false)
        h.say(hello, page)
        runCurrent()
        h.say(hello, page)
        runCurrent()
        // Two of the first stream's, then two of the new one's (it waits on nothing the old one left out)
        assertThat(page.seqs()).containsExactly(0, 1, 0, 1).inOrder()
        // The first stream's chunk 1, acked late: it counts against the new stream's chunk 1, still out, and one more goes
        h.say(ack(1), page)
        runCurrent()
        assertThat(page.seqs()).containsExactly(0, 1, 0, 1, 2).inOrder()
        // No more till chunk 0 or 2 is acked
        h.say(ack(1), page)
        runCurrent()
        assertThat(page.seqs()).hasSize(5)
    }

    @Test fun `a page that has acked is waited for however long - one slow ack never turns the window off`() = runTest {
        val h = Harness(this, rows(10))
        val page = Page(acks = false)
        h.say(hello, page)
        runCurrent()
        h.say(ack(0), page)
        runCurrent()
        assertThat(page.seqs()).containsExactly(0, 1, 2).inOrder()

        // The renderer in the background, slow to answer: no burst, however long it takes
        advanceTimeBy(RecordHost.ARCHIVE_ACK_WAIT_MS * 4)
        runCurrent()
        assertThat(page.seqs()).hasSize(3)

        h.say(ack(1), page)
        runCurrent()
        assertThat(page.seqs()).containsExactly(0, 1, 2, 3).inOrder()
    }

    @Test fun `a page that never acks (one that doesn't speak acks) gets the rest once the wait is over`() = runTest {
        val h = Harness(this, rows(10))
        val page = Page(acks = false)
        h.say(hello, page)
        runCurrent()
        assertThat(page.seqs()).hasSize(2)
        advanceTimeBy(RecordHost.ARCHIVE_ACK_WAIT_MS - 1)
        runCurrent()
        assertThat(page.seqs()).hasSize(2)
        advanceTimeBy(2)
        runCurrent()
        assertThat(page.seqs()).containsExactly(0, 1, 2, 3, 4).inOrder()
    }

    @Test fun `hello - the theme, then the archive in chunks, seq from 0, the last done`() = runTest {
        val h = Harness(this, rows(5))
        val page = Page()
        h.host.setTheme(light = true)
        h.say(hello, page)
        runCurrent()
        assertThat(page.types().first()).isEqualTo("stash:theme")
        assertThat(page.messages.first()["theme"]!!.jsonPrimitive.content).isEqualTo("light")
        val chunks = page.archives()
        assertThat(chunks.map { it["seq"]!!.jsonPrimitive.int }).containsExactly(0, 1, 2).inOrder()
        assertThat(chunks.map { it["done"]!!.jsonPrimitive.content }).containsExactly("false", "false", "true").inOrder()
        assertThat(chunks.flatMap { c -> c["rows"]!!.jsonArray.map { it.jsonObject["t"]!!.jsonPrimitive.content } })
            .containsExactly("T0", "T1", "T2", "T3", "T4").inOrder()
    }

    @Test fun `an empty archive is one empty chunk, done`() = runTest {
        val h = Harness(this, rows(0))
        val page = Page()
        h.say(hello, page)
        runCurrent()
        val c = page.archives().single()
        assertThat(c["seq"]!!.jsonPrimitive.int).isEqualTo(0)
        assertThat(c["done"]!!.jsonPrimitive.content).isEqualTo("true")
        assertThat(c["rows"]!!.jsonArray).isEmpty()
    }

    @Test fun `a new hello stops the old stream - no chunk of the old one after it, the new one from seq 0`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var streams = 0
        val archive = RecordArchiveSource { _, emit ->
            val mine = ++streams
            emit(listOf(row(mine * 10)), false)
            if (mine == 1) gate.await() // the first stream waits mid-way
            emit(listOf(row(mine * 10 + 1)), true)
        }
        val h = Harness(this, archive)
        val first = Page()
        h.say(hello, first)
        assertThat(first.archives()).hasSize(1)
        // the page reloads (stash:reload) and says hello again
        h.say(hello, first)
        gate.complete(Unit)
        runCurrent()
        val chunks = first.archives()
        assertThat(chunks.map { it["seq"]!!.jsonPrimitive.int }).containsExactly(0, 0, 1).inOrder()
        val ts = chunks.map { c -> c["rows"]!!.jsonArray.single().jsonObject["t"]!!.jsonPrimitive.content }
        assertThat(ts).containsExactly("T10", "T20", "T21").inOrder()
    }

    @Test fun `a chunk built for an old hello is never posted, even one a slow read finishes after its stream was stopped`() = runTest {
        // A source that, once it has started a chunk, finishes it whatever happens (a Room read can't be stopped half-way):
        // only the host's own check can keep that chunk from the page that has said hello since.
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val archive = RecordArchiveSource { _, emit ->
            if (++calls == 1) {
                withContext(NonCancellable) {
                    release.await()
                    emit(listOf(row(1)), true)
                }
            } else {
                emit(listOf(row(2)), true)
            }
        }
        val h = Harness(this, archive)
        val page = Page()
        h.say(hello, page)
        h.say(hello, page)
        release.complete(Unit)
        runCurrent()
        val ts = page.archives().map { c -> c["rows"]!!.jsonArray.single().jsonObject["t"]!!.jsonPrimitive.content }
        assertThat(ts).containsExactly("T2")
        // and after the page went, nothing at all
        val again = CompletableDeferred<Unit>()
        val h2 = Harness(
            this,
            RecordArchiveSource { _, emit -> withContext(NonCancellable) { again.await(); emit(listOf(row(3)), true) } },
        )
        val other = Page()
        h2.say(hello, other)
        h2.host.detach()
        again.complete(Unit)
        runCurrent()
        assertThat(other.archives()).isEmpty()
    }

    @Test fun `ready - the theme again, to the page that asked`() = runTest {
        val h = Harness(this, rows(0))
        val page = Page()
        h.say("""{"type":"stash:ready","plays":3000}""", page)
        assertThat(page.types()).containsExactly("stash:theme")
        assertThat(page.messages.single()["theme"]!!.jsonPrimitive.content).isEqualTo("dark")
    }

    @Test fun `a theme change goes to the page that said hello, and only once there is one`() = runTest {
        val h = Harness(this, rows(0))
        val page = Page()
        h.host.setTheme(light = true)
        assertThat(page.sent).isEmpty()
        h.say(hello, page)
        runCurrent()
        page.sent.clear()
        h.host.setTheme(light = true) // no change: nothing
        h.host.setTheme(light = false)
        assertThat(page.messages.map { it["theme"]!!.jsonPrimitive.content }).containsExactly("dark")
    }

    @Test fun `back asks the record, without a hello, or with the renderer stuck, it closes at once`() = runTest {
        val h = Harness(this, rows(0))
        h.host.back()
        assertThat(h.events).containsExactly(RecordEvent.Close)
        val page = Page()
        h.say(hello, page)
        runCurrent()
        page.sent.clear()
        h.host.back()
        assertThat(page.sent).containsExactly("""{"type":"stash:back"}""")
        assertThat(h.events).hasSize(1)
        h.say("""{"type":"stash:backed"}""", page)
        h.host.unresponsive = true
        h.host.back()
        assertThat(h.events).containsExactly(RecordEvent.Close, RecordEvent.Close)
        assertThat(page.sent).hasSize(1)
    }

    @Test fun `back stays when the page says backed, closes when it says close, and closes by itself after silence`() = runTest {
        val h = Harness(this, rows(0))
        val page = Page()
        h.say(hello, page)
        runCurrent()
        // the page closed a sheet: the record stays, then and later
        h.host.back()
        h.say("""{"type":"stash:backed"}""", page)
        advanceTimeBy(RecordHost.BACK_WAIT_MS * 2)
        assertThat(h.events).isEmpty()
        // any other word from the page is not an answer to back
        h.host.back()
        h.say("""{"type":"stash:counts","counts":{"spotify":0,"stash":1,"lastfm":0}}""", page)
        advanceTimeBy(RecordHost.BACK_WAIT_MS - 100)
        assertThat(h.events).isEmpty()
        advanceTimeBy(200)
        assertThat(h.events).containsExactly(RecordEvent.Close)
        // nothing was open: the page says close, the record closes at once and only once
        h.host.back()
        h.say("""{"type":"stash:close"}""", page)
        advanceTimeBy(RecordHost.BACK_WAIT_MS * 2)
        assertThat(h.events).containsExactly(RecordEvent.Close, RecordEvent.Close)
    }

    @Test fun `a second back closes only while the first is still unanswered`() = runTest {
        val h = Harness(this, rows(0))
        val page = Page()
        h.say(hello, page)
        runCurrent()
        // two backs over a sheet, the page answering the first: only the sheet goes (the page hears both)
        h.host.back()
        h.say("""{"type":"stash:backed"}""", page)
        advanceTimeBy(300)
        h.host.back()
        assertThat(h.events).isEmpty()
        assertThat(page.types().count { it == "stash:back" }).isEqualTo(2)
        h.say("""{"type":"stash:backed"}""", page)
        // a page that hasn't answered: the second back within a second closes it
        advanceTimeBy(5_000)
        h.host.back()
        advanceTimeBy(RecordHost.SECOND_BACK_MS - 200)
        h.host.back()
        assertThat(h.events).containsExactly(RecordEvent.Close)
        assertThat(page.types().count { it == "stash:back" }).isEqualTo(3)
    }

    @Test fun `close closes, import opens the import, counts and junk do nothing`() = runTest {
        val h = Harness(this, rows(0))
        val page = Page()
        h.say("""{"type":"stash:close"}""", page)
        h.say("""{"type":"stash:import"}""", page)
        h.say("""{"type":"stash:counts","counts":{"spotify":0,"stash":333,"lastfm":0}}""", page)
        h.say("""{"type":"stash:live","plays":[]}""", page)
        h.say("not json", page)
        assertThat(h.events).containsExactly(RecordEvent.Close, RecordEvent.Import).inOrder()
        assertThat(page.sent).isEmpty()
    }

    @Test fun `an archive that changed is a reload to the page that said hello, and its hello is answered with the new rows`() = runTest {
        val h = Harness(this, rows(2))
        h.host.reload() // no page yet: nothing to tell
        val page = Page()
        h.say("""{"type":"stash:hello","v":1}""", page)
        advanceUntilIdle()
        page.sent.clear()

        h.host.reload()
        assertThat(page.types()).containsExactly("stash:reload")
        h.say("""{"type":"stash:hello","v":1}""", page)
        advanceUntilIdle()
        assertThat(page.types()).containsExactly("stash:reload", "stash:theme", "stash:archive").inOrder()
    }

    @Test fun `play says what starts at once and hands the songs, the start and the label on`() = runTest {
        val h = Harness(this, rows(0))
        val page = Page()
        h.say(
            """{"type":"stash:play","songs":[{"title":"Ivy","artist":"Frank Ocean","album":"Blonde"},{"title":"Nights","artist":"Frank Ocean"}],"index":1,"from":"The Record · 3 March 2024"}""",
            page,
        )
        assertThat(h.played.single()).isEqualTo(
            Triple(listOf(RecordSong("Ivy", "Frank Ocean", "Blonde"), RecordSong("Nights", "Frank Ocean")), 1, "The Record · 3 March 2024"),
        )
        assertThat(h.events).containsExactly(RecordEvent.Say(RecordNote.PlayingSongs(2)))
        h.say("""{"type":"stash:play","songs":[{"title":"Nude","artist":"Radiohead"}],"index":0}""", page)
        assertThat(h.events.last()).isEqualTo(RecordEvent.Say(RecordNote.Playing("Nude")))
        assertThat(h.played.last().third).isEqualTo(RecordMessages.FROM)
    }

    @Test fun `art - covers by the record's names as each batch lands, then the whole list with null for none`() = runTest {
        val asked = mutableListOf<List<ArtSong>>()
        val covers = RecordCovers { songs, _, onBatch ->
            asked += songs
            onBatch(listOf("https://x/blonde.jpg", null, null))
            listOf("https://x/blonde.jpg", "https://x/frank.jpg", null)
        }
        val h = Harness(this, rows(0), covers = covers)
        val page = Page()
        h.say(
            """{"type":"stash:art","asks":[{"id":"Ivy|Frank Ocean","artist":"Frank Ocean","title":"Ivy","album":"Blonde"},""" +
                """{"id":"Frank Ocean","artist":"Frank Ocean"},{"id":"Nights|Frank Ocean","artist":"Frank Ocean","title":"Nights"}]}""",
            page,
        )
        runCurrent()
        assertThat(asked.single()).containsExactly(
            ArtSong("Ivy", "Frank Ocean", "Blonde"),
            ArtSong("Frank Ocean", "Frank Ocean", isArtist = true),
            ArtSong("Nights", "Frank Ocean"),
        ).inOrder()
        val lists = page.messages.map { m -> m["covers"]!!.jsonArray.map { c -> (c as JsonObject).let { it["id"]!!.jsonPrimitive.content to it["url"]!!.toString() } } }
        assertThat(lists).hasSize(2)
        assertThat(lists[0]).containsExactly("Ivy|Frank Ocean" to "\"https://x/blonde.jpg\"")
        assertThat(lists[1]).containsExactly(
            "Ivy|Frank Ocean" to "\"https://x/blonde.jpg\"",
            "Frank Ocean" to "\"https://x/frank.jpg\"",
            "Nights|Frank Ocean" to "null",
        ).inOrder()
    }

    /**
     * The library: own files for the songs in [local] (a null value: a file that can't be made), remote addresses for those in
     * [remote], by title. [encoding], when set, holds the making of the files.
     */
    private class Library(
        val local: Map<String, String?>,
        val remote: Map<String, String>,
        val encoding: CompletableDeferred<Unit>? = null,
    ) : RecordLibraryArt {
        val asked = mutableListOf<String>()
        override suspend fun owned(songs: List<ArtSong>): List<Boolean> = songs.map { it.title in local }
        override suspend fun local(songs: List<ArtSong>): List<String?> {
            songs.forEach { asked += "local ${it.title}" }
            encoding?.await()
            return songs.map { local[it.title] }
        }
        override suspend fun remote(songs: List<ArtSong>): List<String?> = songs.map { asked += "remote ${it.title}"; remote[it.title] }
    }

    private fun lists(page: Page) = page.messages.filter { it["type"]!!.jsonPrimitive.content == "stash:covers" }.map { m ->
        m["covers"]!!.jsonArray.map { c -> (c as JsonObject).let { it["id"]!!.jsonPrimitive.content to it["url"]!!.toString() } }
    }

    @Test fun `art - own files first as their own batch, the lookups only for the rest, the library's addresses only for what they missed`() = runTest {
        val looked = mutableListOf<List<ArtSong>>()
        val order = mutableListOf<String>()
        val library = Library(
            local = mapOf("Blonde" to "https://appassets.androidplatform.net/art/v1/${"a".repeat(32)}.jpg"),
            remote = mapOf(
                "Blonde" to "https://never/blonde.jpg",
                "Nights" to "https://appassets.androidplatform.net/art/v1/${"b".repeat(32)}.jpg",
                "Solo" to "https://never/solo.jpg",
            ),
        )
        val covers = RecordCovers { songs, _, onBatch ->
            order += "lookups"
            looked += songs
            onBatch(listOf("https://x/frank.jpg", null, null, null))
            listOf("https://x/frank.jpg", null, null, "https://x/solo.jpg")
        }
        val h = Harness(this, rows(0), covers = covers, library = library)
        val page = Page()
        h.say(
            """{"type":"stash:art","asks":[{"id":"Blonde|Frank Ocean","artist":"Frank Ocean","title":"Blonde","album":"Blonde"},""" +
                """{"id":"Frank Ocean","artist":"Frank Ocean"},{"id":"Nights|Frank Ocean","artist":"Frank Ocean","title":"Nights"},""" +
                """{"id":"Ivy|Frank Ocean","artist":"Frank Ocean","title":"Ivy"},{"id":"Solo|Frank Ocean","artist":"Frank Ocean","title":"Solo"}]}""",
            page,
        )
        runCurrent()
        // Own files asked first; the lookups never hear of a song the library answered
        assertThat(library.asked.first()).isEqualTo("local Blonde")
        assertThat(looked.single().map { it.title }).containsExactly("Frank Ocean", "Nights", "Ivy", "Solo").inOrder()
        // The library's addresses only for the songs the lookups found nothing for (never an artist)
        assertThat(library.asked.filter { it.startsWith("remote") }).containsExactly("remote Nights", "remote Ivy").inOrder()
        val l = lists(page)
        assertThat(l).hasSize(3)
        assertThat(l[0]).containsExactly("Blonde|Frank Ocean" to "\"https://appassets.androidplatform.net/art/v1/${"a".repeat(32)}.jpg\"")
        assertThat(l[1]).containsExactly("Frank Ocean" to "\"https://x/frank.jpg\"")
        assertThat(l[2]).containsExactly(
            "Blonde|Frank Ocean" to "\"https://appassets.androidplatform.net/art/v1/${"a".repeat(32)}.jpg\"",
            "Frank Ocean" to "\"https://x/frank.jpg\"",
            "Nights|Frank Ocean" to "\"https://appassets.androidplatform.net/art/v1/${"b".repeat(32)}.jpg\"",
            "Ivy|Frank Ocean" to "null",
            "Solo|Frank Ocean" to "\"https://x/solo.jpg\"",
        ).inOrder()
    }

    @Test fun `art - when every song has its own file the lookups are never asked, and a library that fails leaves them to the lookups`() = runTest {
        var lookups = 0
        val covers = RecordCovers { songs, _, _ -> lookups++; songs.map { "https://x/${it.title}.jpg" } }
        val own = Library(local = mapOf("Ivy" to "https://appassets.androidplatform.net/art/v1/${"c".repeat(32)}.jpg"), remote = emptyMap())
        val h = Harness(this, rows(0), covers = covers, library = own)
        val page = Page()
        h.say("""{"type":"stash:art","asks":[{"id":"Ivy|Frank Ocean","artist":"Frank Ocean","title":"Ivy"}]}""", page)
        runCurrent()
        assertThat(lookups).isEqualTo(0)
        assertThat(lists(page).last()).containsExactly("Ivy|Frank Ocean" to "\"https://appassets.androidplatform.net/art/v1/${"c".repeat(32)}.jpg\"")

        val broken = object : RecordLibraryArt {
            override suspend fun owned(songs: List<ArtSong>): List<Boolean> = error("no database")
            override suspend fun local(songs: List<ArtSong>): List<String?> = error("no database")
            override suspend fun remote(songs: List<ArtSong>): List<String?> = error("no database")
        }
        val h2 = Harness(this, rows(0), covers = covers, library = broken)
        val page2 = Page()
        h2.say("""{"type":"stash:art","asks":[{"id":"Ivy|Frank Ocean","artist":"Frank Ocean","title":"Ivy"}]}""", page2)
        runCurrent()
        assertThat(lookups).isEqualTo(1)
        assertThat(lists(page2).last()).containsExactly("Ivy|Frank Ocean" to "\"https://x/Ivy.jpg\"")
    }

    @Test fun `art - the lookups never wait for a file being made, and a file that can't be made goes to the lookups`() = runTest {
        val looked = mutableListOf<List<String>>()
        val covers = RecordCovers { songs, _, onBatch ->
            looked += songs.map { it.title }
            songs.map { "https://x/${it.title}.jpg" }.also(onBatch)
        }
        val gate = CompletableDeferred<Unit>()
        val own = "https://appassets.androidplatform.net/art/v1/${"d".repeat(32)}.jpg"
        val library = Library(local = mapOf("Ivy" to own, "Broken" to null), remote = emptyMap(), encoding = gate)
        val h = Harness(this, rows(0), covers = covers, library = library)
        val page = Page()
        h.say(
            """{"type":"stash:art","asks":[{"id":"Ivy|F","artist":"F","title":"Ivy"},{"id":"Broken|F","artist":"F","title":"Broken"},""" +
                """{"id":"Nights|F","artist":"F","title":"Nights"}]}""",
            page,
        )
        runCurrent()
        // The files are still being made: the song without one is looked up already, and answered
        assertThat(looked).containsExactly(listOf("Nights"))
        assertThat(lists(page)).containsExactly(listOf("Nights|F" to "\"https://x/Nights.jpg\""))
        gate.complete(Unit)
        runCurrent()
        // Ivy's own file, never looked up; Broken's couldn't be made, so it is looked up after all
        assertThat(looked).containsExactly(listOf("Nights"), listOf("Broken")).inOrder()
        val l = lists(page)
        assertThat(l[1]).containsExactly("Ivy|F" to "\"$own\"")
        assertThat(l[2]).containsExactly("Broken|F" to "\"https://x/Broken.jpg\"")
        assertThat(l.last()).containsExactly(
            "Ivy|F" to "\"$own\"",
            "Broken|F" to "\"https://x/Broken.jpg\"",
            "Nights|F" to "\"https://x/Nights.jpg\"",
        ).inOrder()
    }

    @Test fun `what a page started stops when it goes (its renderer died, the screen stays) - its covers, its wait on back, its file`() = runTest {
        var coversStopped = false
        val covers = RecordCovers { _, _, _ ->
            try {
                awaitCancellation()
            } finally {
                coversStopped = true
            }
        }
        val h = Harness(this, rows(0), covers = covers)
        val page = Page()
        h.say(hello, page)
        h.say("""{"type":"stash:art","asks":[{"id":"A","artist":"A"}]}""", page)
        h.host.back() // waiting on the page's answer
        h.host.detach()
        runCurrent()
        assertThat(coversStopped).isTrue()
        advanceTimeBy(RecordHost.BACK_WAIT_MS * 2)
        assertThat(h.events).isEmpty()
        assertThat(h.saves.drops).isEqualTo(1)
        assertThat(page.sent.none { it.contains("stash:covers") }).isTrue()
    }

    @Test fun `when the screen goes its scope is gone before detach, so the host can't clear the file - the screen does`() = runTest {
        // ViewModel.clear() cancels viewModelScope before onCleared: nothing detach queues can run then. RecordViewModel's
        // onCleared calls RecordSaves.clearNow() itself (RecordSavesTest: clearNow).
        val screen = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val h = Harness(this, rows(0), scope = screen)
        val page = Page()
        h.say(chunk(0, false), page)
        assertThat(h.saves.taken).hasSize(1)
        screen.cancel()
        h.host.detach()
        runCurrent()
        assertThat(h.saves.drops).isEqualTo(0)
    }

    @Test fun `a file's chunks go to the saves in order, each acknowledged once kept, and what came of it is said`() = runTest {
        val h = Harness(this, rows(0))
        h.saves.note = RecordNote.Saved
        val page = Page()
        h.host.onMessage(chunk(0, false), page)
        h.host.onMessage(chunk(1, false), page)
        h.host.onMessage(chunk(2, true), page)
        runCurrent()
        assertThat(h.saves.taken.map { it.seq }).containsExactly(0, 1, 2).inOrder()
        assertThat(page.sent).containsExactly(
            """{"type":"stash:save-ack","id":"u-1","seq":0}""",
            """{"type":"stash:save-ack","id":"u-1","seq":1}""",
            """{"type":"stash:save-ack","id":"u-1","seq":2}""",
        ).inOrder()
        assertThat(h.events).containsExactly(RecordEvent.Say(RecordNote.Saved))
    }

    @Test fun `a tap, a cover's ask or the x never waits behind a file being kept`() = runTest {
        val h = Harness(this, rows(0))
        val page = Page()
        val slow = CompletableDeferred<Unit>()
        h.saves.gate = slow
        h.say(chunk(0, false), page)
        h.say("""{"type":"stash:play","songs":[{"title":"Nude","artist":"Radiohead"}],"index":0}""", page)
        h.say("""{"type":"stash:close"}""", page)
        assertThat(h.played).hasSize(1)
        assertThat(h.events).containsExactly(RecordEvent.Say(RecordNote.Playing("Nude")), RecordEvent.Close).inOrder()
        assertThat(page.sent).isEmpty()
        slow.complete(Unit)
        runCurrent()
        assertThat(page.sent).containsExactly("""{"type":"stash:save-ack","id":"u-1","seq":0}""")
    }

    @Test fun `save-abort - the page gave up on a file, what was gathered goes`() = runTest {
        val h = Harness(this, rows(0))
        val page = Page()
        h.say(chunk(0, false), page)
        h.say("""{"type":"stash:save-abort","id":"u-1"}""", page)
        h.say("""{"type":"stash:save-abort","id":""}""", page) // no id: junk
        assertThat(h.saves.aborted).containsExactly("u-1")
    }

    @Test fun `what a page that went still had queued is dropped - its tap, its hello, its chunk, an ack for it`() = runTest {
        val h = Harness(this, rows(3))
        val page = Page()
        h.say(hello, page)
        runCurrent()
        // a file mid-way when the page goes: its chunk is kept, but no ack goes to a page that is gone
        val slow = CompletableDeferred<Unit>()
        h.saves.gate = slow
        h.say(chunk(0, false), page)
        page.sent.clear()
        // the renderer dies with these still queued
        h.host.onMessage("""{"type":"stash:play","songs":[{"title":"Nude","artist":"Radiohead"}],"index":0}""", page)
        h.host.onMessage(hello, page)
        h.host.onMessage(chunk(1, false), page)
        h.host.detach()
        slow.complete(Unit)
        runCurrent()
        assertThat(h.played).isEmpty()
        assertThat(page.sent).isEmpty()
        assertThat(h.saves.taken.map { it.seq }).containsExactly(0)
        assertThat(h.saves.drops).isEqualTo(1)
        // the "stopped" notice: no page said hello since, so back closes at once
        h.host.back()
        assertThat(h.events).containsExactly(RecordEvent.Close)
        // the reloaded page is heard as usual
        val again = Page()
        h.say(hello, again)
        runCurrent()
        assertThat(again.archives()).isNotEmpty()
    }

    @Test fun `an archive that fails mid-way ends with a done chunk, and the listener is told`() = runTest {
        val h = Harness(
            this,
            RecordArchiveSource { _, emit ->
                emit(listOf(row(1)), false)
                error("database gone")
            },
        )
        val page = Page()
        h.say(hello, page)
        runCurrent()
        val chunks = page.archives()
        assertThat(chunks.map { it["seq"]!!.jsonPrimitive.int to it["done"]!!.jsonPrimitive.content }).containsExactly(0 to "false", 1 to "true").inOrder()
        assertThat(h.events).containsExactly(RecordEvent.Say(RecordNote.ArchiveFailed))
    }
}

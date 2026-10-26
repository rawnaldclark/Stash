package com.stash.feature.record.bridge

import com.google.common.truth.Truth.assertThat
import com.stash.feature.record.archive.ArchivePlay
import com.stash.feature.record.archive.RecordArchiveSource
import com.stash.feature.record.art.ArtSong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/** The bridge's app side, without a WebView: what the record says in, what goes back out. */
class RecordHostTest {

    /** A page's reply proxy: what it was sent, parsed. */
    private class Page : RecordPort {
        val sent = mutableListOf<String>()
        override fun post(message: String) {
            sent += message
        }

        val messages get() = sent.map { Json.parseToJsonElement(it).jsonObject }
        fun types() = messages.map { it["type"]!!.jsonPrimitive.content }
        fun archives() = messages.filter { it["type"]!!.jsonPrimitive.content == "stash:archive" }
    }

    private fun row(i: Int) = ArchivePlay(id = "t$i|a|$i", t = "T$i", a = "A", s = i.toLong(), ms = 1000, f = 256)

    /** [n] rows, in chunks as [OwnArchive][com.stash.feature.record.archive.OwnArchive] hands them (the last marked). */
    private fun rows(n: Int) = RecordArchiveSource { size, emit ->
        val all = (0 until n).map(::row).chunked(size)
        if (all.isEmpty()) emit(emptyList(), true) else all.forEachIndexed { i, c -> emit(c, i == all.lastIndex) }
    }

    private class Harness(
        val scope: TestScope,
        archive: RecordArchiveSource,
        val covers: RecordCovers = RecordCovers { songs, _, _ -> songs.map { null } },
        val player: RecordPlayer = RecordPlayer { _, _, _ -> null },
        chunk: Int = 2,
    ) {
        val events = mutableListOf<RecordEvent>()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val host = RecordHost(scope, archive, covers, player, { events += it }, main = dispatcher, background = dispatcher, chunkSize = chunk)
    }

    private val hello = """{"type":"stash:hello","v":1}"""

    @Test fun `hello, the theme, then the archive in chunks, seq from 0, the last done`() = runTest {
        val h = Harness(this, rows(5))
        val page = Page()
        h.host.setTheme(light = true)
        h.host.onMessage(hello, page)
        advanceUntilIdle()
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
        h.host.onMessage(hello, page)
        advanceUntilIdle()
        val c = page.archives().single()
        assertThat(c["seq"]!!.jsonPrimitive.int).isEqualTo(0)
        assertThat(c["done"]!!.jsonPrimitive.content).isEqualTo("true")
        assertThat(c["rows"]!!.jsonArray).isEmpty()
    }

    @Test fun `a new hello stops the old stream, no chunk of the old one after it, the new one from seq 0`() = runTest {
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
        h.host.onMessage(hello, first)
        runCurrent()
        assertThat(first.archives()).hasSize(1)
        // the page reloads (stash:reload) and says hello again
        h.host.onMessage(hello, first)
        gate.complete(Unit)
        advanceUntilIdle()
        val chunks = first.archives()
        assertThat(chunks.map { it["seq"]!!.jsonPrimitive.int }).containsExactly(0, 0, 1).inOrder()
        val ts = chunks.map { c -> c["rows"]!!.jsonArray.single().jsonObject["t"]!!.jsonPrimitive.content }
        assertThat(ts).containsExactly("T10", "T20", "T21").inOrder()
    }

    @Test fun `a chunk built for an old hello is never posted`() = runTest {
        val built = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val archive = RecordArchiveSource { _, emit ->
            if (++calls == 1) {
                built.complete(Unit)
                release.await()
            }
            emit(listOf(row(calls)), true)
        }
        val h = Harness(this, archive)
        val page = Page()
        h.host.onMessage(hello, page)
        runCurrent()
        built.await()
        h.host.detach()
        release.complete(Unit)
        advanceUntilIdle()
        assertThat(page.archives()).isEmpty()
    }

    @Test fun `ready, the theme again, to the page that asked`() = runTest {
        val h = Harness(this, rows(0))
        val page = Page()
        h.host.onMessage("""{"type":"stash:ready","plays":3000}""", page)
        assertThat(page.types()).containsExactly("stash:theme")
        assertThat(page.messages.single()["theme"]!!.jsonPrimitive.content).isEqualTo("dark")
    }

    @Test fun `a theme change goes to the page that said hello, and only once there is one`() = runTest {
        val h = Harness(this, rows(0))
        val page = Page()
        h.host.setTheme(light = true)
        assertThat(page.sent).isEmpty()
        h.host.onMessage(hello, page)
        advanceUntilIdle()
        page.sent.clear()
        h.host.setTheme(light = true) // no change: nothing
        h.host.setTheme(light = false)
        assertThat(page.messages.map { it["theme"]!!.jsonPrimitive.content }).containsExactly("dark")
    }

    @Test fun `back asks the record, without a hello, or with the page stuck, it closes`() = runTest {
        val h = Harness(this, rows(0))
        h.host.back()
        assertThat(h.events).containsExactly(RecordEvent.Close)
        val page = Page()
        h.host.onMessage(hello, page)
        advanceUntilIdle()
        page.sent.clear()
        h.host.back()
        assertThat(page.sent).containsExactly("""{"type":"stash:back"}""")
        assertThat(h.events).hasSize(1)
        h.host.unresponsive = true
        h.host.back()
        assertThat(h.events).containsExactly(RecordEvent.Close, RecordEvent.Close)
        assertThat(page.sent).hasSize(1)
        // and once the page is gone
        h.host.unresponsive = false
        h.host.detach()
        h.host.back()
        assertThat(h.events).hasSize(3)
    }

    @Test fun `close closes, import says the Spotify import is coming, counts and junk do nothing`() = runTest {
        val h = Harness(this, rows(0))
        val page = Page()
        h.host.onMessage("""{"type":"stash:close"}""", page)
        h.host.onMessage("""{"type":"stash:import"}""", page)
        h.host.onMessage("""{"type":"stash:counts","counts":{"spotify":0,"stash":333,"lastfm":0}}""", page)
        h.host.onMessage("""{"type":"stash:live","plays":[]}""", page)
        h.host.onMessage("not json", page)
        assertThat(h.events).containsExactly(RecordEvent.Close, RecordEvent.Say(RecordHost.IMPORT_COMING)).inOrder()
        assertThat(page.sent).isEmpty()
    }

    @Test fun `play hands the songs, the start and the label to the player, what it says is said`() = runTest {
        val asked = mutableListOf<Triple<List<RecordSong>, Int, String>>()
        var note: String? = null
        val h = Harness(this, rows(0), player = RecordPlayer { songs, index, from -> asked += Triple(songs, index, from); note })
        val page = Page()
        val play = """{"type":"stash:play","songs":[{"title":"Ivy","artist":"Frank Ocean","album":"Blonde"},{"title":"Nights","artist":"Frank Ocean"}],"index":1,"from":"The Record · 3 March 2024"}"""
        h.host.onMessage(play, page)
        advanceUntilIdle()
        assertThat(asked.single()).isEqualTo(
            Triple(listOf(RecordSong("Ivy", "Frank Ocean", "Blonde"), RecordSong("Nights", "Frank Ocean")), 1, "The Record · 3 March 2024"),
        )
        assertThat(h.events).isEmpty()
        note = NONE_FOUND
        h.host.onMessage(play, page)
        advanceUntilIdle()
        assertThat(h.events).containsExactly(RecordEvent.Say(NONE_FOUND))
    }

    @Test fun `a newer tap replaces one still matching, and a failure says so without crashing`() = runTest {
        val slow = CompletableDeferred<Unit>()
        val played = mutableListOf<String>()
        val h = Harness(
            this,
            rows(0),
            player = RecordPlayer { songs, _, _ ->
                if (songs[0].title == "First") slow.await()
                if (songs[0].title == "Boom") error("boom")
                played += songs[0].title
                null
            },
        )
        val page = Page()
        fun tap(title: String) = h.host.onMessage("""{"type":"stash:play","songs":[{"title":"$title","artist":"A"}],"index":0}""", page)
        tap("First")
        runCurrent()
        tap("Second")
        slow.complete(Unit)
        advanceUntilIdle()
        assertThat(played).containsExactly("Second")
        tap("Boom")
        advanceUntilIdle()
        assertThat(h.events).containsExactly(RecordEvent.Say(RecordHost.PLAY_FAILED))
    }

    @Test fun `art, covers by the record's names as each batch lands, then the whole list with null for none`() = runTest {
        val asked = mutableListOf<List<ArtSong>>()
        val covers = RecordCovers { songs, _, onBatch ->
            asked += songs
            onBatch(listOf("https://x/blonde.jpg", null, null))
            listOf("https://x/blonde.jpg", "https://x/frank.jpg", null)
        }
        val h = Harness(this, rows(0), covers = covers)
        val page = Page()
        h.host.onMessage(
            """{"type":"stash:art","asks":[{"id":"Ivy|Frank Ocean","artist":"Frank Ocean","title":"Ivy","album":"Blonde"},""" +
                """{"id":"Frank Ocean","artist":"Frank Ocean"},{"id":"Nights|Frank Ocean","artist":"Frank Ocean","title":"Nights"}]}""",
            page,
        )
        advanceUntilIdle()
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

    @Test fun `an archive that fails mid-way ends with a done chunk, and the listener is told`() = runTest {
        val h = Harness(
            this,
            RecordArchiveSource { _, emit ->
                emit(listOf(row(1)), false)
                error("database gone")
            },
        )
        val page = Page()
        h.host.onMessage(hello, page)
        advanceUntilIdle()
        val chunks = page.archives()
        assertThat(chunks.map { it["seq"]!!.jsonPrimitive.int to it["done"]!!.jsonPrimitive.content }).containsExactly(0 to "false", 1 to "true").inOrder()
        assertThat(h.events).containsExactly(RecordEvent.Say(RecordHost.ARCHIVE_FAILED))
    }
}

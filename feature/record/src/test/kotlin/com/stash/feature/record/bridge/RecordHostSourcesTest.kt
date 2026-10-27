package com.stash.feature.record.bridge

import com.google.common.truth.Truth.assertThat
import com.stash.feature.record.archive.ArchivePlay
import com.stash.feature.record.archive.RecordArchiveSource
import com.stash.feature.record.archive.RecordFlags
import com.stash.feature.record.archive.SourceCounts
import com.stash.feature.record.archive.SourcesOn
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * The record's switches at the bridge (stash-player `src/routes/spotify/+page.svelte`'s sources, sent as `stash:sources`): the
 * archive's sources counted as it goes out, the page told which to show before the last chunk (so it presses once), and again
 * only when that changes.
 */
class RecordHostSourcesTest {
    private class Page : RecordPort {
        var host: RecordHost? = null
        val sent = mutableListOf<String>()
        override fun post(message: String) {
            sent += message
            if (message.startsWith("{\"type\":\"stash:archive\"")) {
                val seq = Json.parseToJsonElement(message).jsonObject["seq"]!!.jsonPrimitive.int
                host?.onMessage("""{"type":"stash:archive-ack","seq":$seq}""", this)
            }
        }

        fun types() = sent.map { Json.parseToJsonElement(it).jsonObject["type"]!!.jsonPrimitive.content }
        fun sources() = sent.filter { it.startsWith("{\"type\":\"stash:sources\"") }
    }

    private class Switch(var choice: SourcesOn) : RecordSourceSwitch {
        val counted = mutableListOf<SourceCounts>()
        override fun chosen() = choice
        override fun counted(counts: SourceCounts) {
            counted += counts
        }
    }

    private fun row(i: Int, f: Int, ms: Long = 200_000) = ArchivePlay(id = "t$i|a|$i", t = "T$i", a = "A", s = 1_000L + i, ms = ms, f = f)

    /** Two Spotify plays (one a skip under 30 s), three of this phone's, one scrobble: in chunks of two. */
    private val archive = listOf(
        row(1, 0), row(2, RecordFlags.REASONS, ms = 29_999), row(3, RecordFlags.STASH), row(4, RecordFlags.STASH), row(5, RecordFlags.STASH), row(6, RecordFlags.LASTFM),
    )

    private inner class Harness(
        val test: TestScope,
        choice: SourcesOn,
        rows: List<ArchivePlay> = archive,
        /** The archive as it is at each hello (it can change between two). */
        rowsOf: () -> List<ArchivePlay> = { rows },
    ) {
        val switch = Switch(choice)
        private val dispatcher = StandardTestDispatcher(test.testScheduler)
        val host = RecordHost(
            scope = test.backgroundScope,
            archive = RecordArchiveSource { size, emit ->
                val chunks = rowsOf().chunked(size)
                chunks.forEachIndexed { i, c -> emit(c, i == chunks.lastIndex) }
            },
            covers = { songs, _, _ -> songs.map { null } },
            player = { _, _, _ -> },
            saves = object : RecordSaveSink {
                override suspend fun take(chunk: RecordAsk.Save, appended: suspend () -> Unit): RecordNote? = null
                override suspend fun abort(id: String) = Unit
                override suspend fun drop() = Unit
            },
            events = {},
            sources = switch,
            main = dispatcher,
            background = dispatcher,
            chunkSize = 2,
            now = { test.testScheduler.currentTime },
        )

        fun sayTo(raw: String, port: RecordPort) {
            host.onMessage(raw, port)
            test.runCurrent()
            test.advanceUntilIdle()
        }

        fun say(raw: String, page: Page) {
            page.host = host
            host.onMessage(raw, page)
            test.runCurrent()
            test.advanceUntilIdle()
        }
    }

    private val hello = """{"type":"stash:hello","v":1}"""
    private fun sources(spotify: Boolean, stash: Boolean, lastfm: Boolean) =
        """{"type":"stash:sources","on":{"spotify":$spotify,"stash":$stash,"lastfm":$lastfm}}"""

    @Test fun `the page is told its sources just before the last chunk, and each source's listens are counted`() = runTest {
        val h = Harness(this, SourcesOn(lastfm = false))
        val page = Page()
        h.say(hello, page)
        assertThat(page.types()).containsExactly("stash:theme", "stash:archive", "stash:archive", "stash:sources", "stash:archive").inOrder()
        assertThat(page.sources()).containsExactly(sources(spotify = true, stash = true, lastfm = false))
        assertThat(h.switch.counted).containsExactly(SourceCounts(spotify = 1, stash = 3, lastfm = 1))
    }

    @Test fun `every source on - nothing to tell a page that shows them all`() = runTest {
        val h = Harness(this, SourcesOn.ALL_ON)
        val page = Page()
        h.say(hello, page)
        assertThat(page.sources()).isEmpty()
    }

    @Test fun `a choice that leaves no source with plays on shows them all`() = runTest {
        // Spotify alone was kept on, and there are no Spotify plays now: every source
        val h = Harness(this, SourcesOn(spotify = true, stash = false, lastfm = false), rows = archive.filter { it.f and (RecordFlags.STASH or RecordFlags.LASTFM) != 0 })
        val page = Page()
        h.say(hello, page)
        assertThat(page.sources()).isEmpty()
    }

    @Test fun `a switch flipped is sent once, the same again sends nothing`() = runTest {
        val h = Harness(this, SourcesOn.ALL_ON)
        val page = Page()
        h.say(hello, page)
        h.switch.choice = SourcesOn(stash = false)
        h.host.sourcesChanged()
        h.host.sourcesChanged()
        assertThat(page.sources()).containsExactly(sources(spotify = true, stash = false, lastfm = true))
    }

    @Test fun `the page's own count is taken, and a reload keeps what the page was told`() = runTest {
        val h = Harness(this, SourcesOn(lastfm = false))
        val page = Page()
        h.say(hello, page)
        h.say("""{"type":"stash:counts","counts":{"spotify":1,"stash":3,"lastfm":0}}""", page)
        assertThat(h.switch.counted.last()).isEqualTo(SourceCounts(1, 3, 0))
        // the page says hello again (an import changed the archive): it still shows what it was told
        h.say(hello, page)
        assertThat(page.sources()).hasSize(1)
    }

    /** One frame's reply proxy: what it was sent. */
    private class Proxy {
        var host: RecordHost? = null
        val sent = mutableListOf<String>()
        fun sources() = sent.filter { it.startsWith("{\"type\":\"stash:sources\"") }
    }

    /** A port as the WebView hands one over: a new wrapper with every message, over the frame's one proxy (equal by it). */
    private data class Wrapped(val proxy: Proxy) : RecordPort {
        override fun post(message: String) {
            proxy.sent += message
            if (message.startsWith("{\"type\":\"stash:archive\"")) {
                val seq = Json.parseToJsonElement(message).jsonObject["seq"]!!.jsonPrimitive.int
                proxy.host?.onMessage("""{"type":"stash:archive-ack","seq":$seq}""", Wrapped(proxy))
            }
        }
    }

    @Test fun `a reload from the same page, each message in a new wrapper, keeps what it was told and tells it what changed`() = runTest {
        // Last.fm alone is on; then the scrobbles are removed while the record is open
        var rows = archive
        val h = Harness(this, SourcesOn(spotify = false, stash = false, lastfm = true), rowsOf = { rows })
        val proxy = Proxy().also { it.host = h.host }
        h.sayTo(hello, Wrapped(proxy))
        assertThat(proxy.sources()).containsExactly(sources(spotify = false, stash = false, lastfm = true))
        rows = archive.filter { it.f and RecordFlags.LASTFM == 0 }
        h.host.reload()
        h.sayTo(hello, Wrapped(proxy))
        // No source with plays left on: the page, still showing Last.fm alone, is told to show them all
        assertThat(proxy.sources()).containsExactly(
            sources(spotify = false, stash = false, lastfm = true),
            sources(spotify = true, stash = true, lastfm = true),
        ).inOrder()
    }

    @Test fun `the page's own count, in a new wrapper over the same page, re-tells it`() = runTest {
        val h = Harness(this, SourcesOn(stash = false))
        val proxy = Proxy().also { it.host = h.host }
        h.sayTo(hello, Wrapped(proxy))
        assertThat(proxy.sources()).containsExactly(sources(spotify = true, stash = false, lastfm = true))
        // The page counts no Spotify or Last.fm plays any more (an import removed meanwhile): with Stash off, nothing is on
        h.sayTo("""{"type":"stash:counts","counts":{"spotify":0,"stash":3,"lastfm":0}}""", Wrapped(proxy))
        assertThat(proxy.sources().last()).isEqualTo(sources(spotify = true, stash = true, lastfm = true))
    }

    @Test fun `a hello over another proxy is another page, which starts with every source`() = runTest {
        val h = Harness(this, SourcesOn(lastfm = false))
        val first = Proxy().also { it.host = h.host }
        h.sayTo(hello, Wrapped(first))
        val second = Proxy().also { it.host = h.host }
        h.sayTo(hello, Wrapped(second))
        assertThat(second.sources()).containsExactly(sources(spotify = true, stash = true, lastfm = false))
    }

    @Test fun `a new page starts with every source, so it is told again`() = runTest {
        val h = Harness(this, SourcesOn(lastfm = false))
        val first = Page()
        h.say(hello, first)
        h.host.detach()
        val second = Page()
        h.say(hello, second)
        assertThat(second.sources()).containsExactly(sources(spotify = true, stash = true, lastfm = false))
    }
}

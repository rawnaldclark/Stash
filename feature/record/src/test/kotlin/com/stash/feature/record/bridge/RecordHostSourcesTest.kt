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

    private inner class Harness(val test: TestScope, choice: SourcesOn, rows: List<ArchivePlay> = archive) {
        val switch = Switch(choice)
        private val dispatcher = StandardTestDispatcher(test.testScheduler)
        val host = RecordHost(
            scope = test.backgroundScope,
            archive = RecordArchiveSource { size, emit ->
                val chunks = rows.chunked(size)
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

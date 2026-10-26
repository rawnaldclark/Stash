package com.stash.feature.record.art

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** A store in memory, counting its flushes. */
class MemoryArtStore : ArtStore {
    val entries = LinkedHashMap<String, ArtHit>()
    var flushes = 0

    override suspend fun get(key: String): ArtHit? = entries[key]

    override suspend fun set(key: String, hit: ArtHit) {
        entries[key] = hit
    }

    override suspend fun flush() {
        flushes++
    }
}

/** The client's pacing (src/lib/spotify/art.ts `createArt`), with the lookup faked: its tests, ported. */
class RecordArtTest {
    private val dz = "https://cdn-images.dzcdn.net/images/cover"

    /** A lookup from [table] by album, else title; every song looked up is kept, in order. */
    private class Lookups(
        val table: Map<String, String?>,
        val answer: (ArtSong, Int) -> Found? = { _, _ -> null },
    ) {
        val asked = mutableListOf<String>()
        suspend fun lookUp(s: ArtSong): Found {
            asked += s.album ?: s.title
            answer(s, asked.size)?.let { return it }
            return Found.Cover(table[s.album ?: s.title])
        }
    }

    private fun TestScope.art(l: Lookups, store: ArtStore = MemoryArtStore(), now: () -> Long = { testScheduler.currentTime }) =
        RecordArt(lookUp = l::lookUp, store = store, now = now, work = StandardTestDispatcher(testScheduler))

    private val songs = listOf(
        ArtSong("Nights", "Frank Ocean", "Blonde"),
        ArtSong("Ivy", "Frank Ocean", "Blonde"),
        ArtSong("X", "Band", "Other"),
        ArtSong("Y", "Nobody", "Unknown"),
    )

    @Test fun `asks once for every album not known, keeps hits and misses, and asks for a stale miss again`() = runTest {
        val l = Lookups(mapOf("Blonde" to "$dz/blonde.jpg", "Other" to "$dz/other.jpg"))
        val store = MemoryArtStore()
        var t = 1_000_000L
        val art = RecordArt(lookUp = l::lookUp, store = store, now = { t }, work = StandardTestDispatcher(testScheduler))
        assertThat(art.covers(songs)).containsExactly("$dz/blonde.jpg", "$dz/blonde.jpg", "$dz/other.jpg", null).inOrder()
        assertThat(l.asked).containsExactly("Blonde", "Other", "Unknown").inOrder()
        art.covers(songs)
        assertThat(l.asked).hasSize(3) // all known now, the miss too
        t += RecordArt.MISS_TTL_MS + 1
        // a new process (its memory empty) reads the kept miss, now stale, and asks for it again; the kept hits it doesn't
        val fresh = RecordArt(lookUp = l::lookUp, store = store, now = { t }, work = StandardTestDispatcher(testScheduler))
        fresh.covers(songs)
        assertThat(l.asked.drop(3)).containsExactly("Unknown")
        // a kept cover lasts its 180 days
        t += RecordArt.HIT_TTL_MS
        RecordArt(lookUp = l::lookUp, store = store, now = { t }, work = StandardTestDispatcher(testScheduler)).covers(songs)
        assertThat(l.asked.drop(4)).containsExactly("Blonde", "Other", "Unknown").inOrder()
    }

    @Test fun `keeps nothing for a song Deezer couldn't look up yet, and asks once more a while after`() = runTest {
        val l = Lookups(mapOf("Blonde" to "$dz/blonde.jpg", "Other" to "$dz/other.jpg")) { s, n -> if (s.album == "Blonde" && n == 1) Found.Later else null }
        val store = MemoryArtStore()
        val art = art(l, store)
        val seen = mutableListOf<List<String?>>()
        val two = listOf(ArtSong("Ivy", "Frank Ocean", "Blonde"), ArtSong("X", "B", "Other"))
        assertThat(art.covers(two, retry = backgroundScope) { seen += it }).containsExactly(null, "$dz/other.jpg").inOrder()
        assertThat(store.entries[ArtText.artKey(two[0])]).isNull()
        advanceTimeBy(RecordArt.LATER_MS + 1)
        runCurrent()
        assertThat(l.asked).containsExactly("Blonde", "Other", "Blonde").inOrder()
        assertThat(seen.last()).containsExactly("$dz/blonde.jpg", "$dz/other.jpg").inOrder()
        assertThat(store.entries[ArtText.artKey(two[0])]?.url).isEqualTo("$dz/blonde.jpg")
    }

    @Test fun `keeps nothing and waits a minute after a refusal`() = runTest {
        val l = Lookups(emptyMap()) { _, _ -> Found.Refused }
        val art = art(l)
        assertThat(art.covers(listOf(ArtSong("S", "A", "B")))).containsExactly(null)
        assertThat(art.covers(listOf(ArtSong("S2", "A2", "B2")))).containsExactly(null)
        assertThat(l.asked).hasSize(1) // paused after the first refusal
        advanceTimeBy(RecordArt.PAUSE_MS + 1)
        art.covers(listOf(ArtSong("S2", "A2", "B2")))
        assertThat(l.asked).hasSize(2)
    }

    @Test fun `a big list a batch at a time, telling as each batch lands`() = runTest {
        val table = (0 until 120).associate { "Album $it" to "$dz/$it.jpg" }
        val l = Lookups(table)
        val seen = mutableListOf<Int>()
        val many = (0 until 120).map { ArtSong("T$it", "A", "Album $it") }
        val store = MemoryArtStore()
        val out = art(l, store).covers(many) { sofar -> seen += sofar.count { it != null } }
        assertThat(RecordArt.BATCH).isEqualTo(25)
        assertThat(seen).containsExactly(0, 25, 50, 75, 100, 120).inOrder()
        assertThat(out.all { it != null }).isTrue()
        assertThat(store.flushes).isEqualTo(5) // once a batch
    }

    @Test fun `the Worker's reading, words it won't take are no cover, an artist is asked as one`() = runTest {
        val l = Lookups(mapOf("Sepultura" to "$dz/s.jpg"))
        val out = art(l).covers(listOf(ArtSong("T", "  "), ArtSong("Sepultura", "  Sepultura ", isArtist = true), ArtSong("x‮", "A")))
        assertThat(out).containsExactly(null, "$dz/s.jpg", null).inOrder()
        assertThat(l.asked).containsExactly("Sepultura")
    }

    @Test fun `an album two asks want at once is looked up once`() = runTest {
        var lookups = 0
        val art = RecordArt(
            lookUp = { s ->
                lookups++
                delay(500) // still in flight when the second ask comes
                Found.Cover("$dz/${s.album}.jpg")
            },
            store = MemoryArtStore(),
            now = { testScheduler.currentTime },
            work = StandardTestDispatcher(testScheduler),
        )
        val both = listOf(
            async { art.covers(listOf(ArtSong("Ivy", "Frank Ocean", "Blonde"))) },
            async { art.covers(listOf(ArtSong("Nights", "Frank Ocean", "Blonde"), ArtSong("X", "B", "Other"))) },
        ).awaitAll()
        assertThat(lookups).isEqualTo(2) // Blonde once, Other once
        assertThat(both[0]).containsExactly("$dz/Blonde.jpg")
        assertThat(both[1]).containsExactly("$dz/Blonde.jpg", "$dz/Other.jpg").inOrder()
    }

    @Test fun `each batch is told on the caller's thread, the work done off it`() = runTest {
        val seen = mutableListOf<String>()
        val art = RecordArt(
            lookUp = { Found.Cover("$dz/x.jpg") },
            store = MemoryArtStore(),
            now = { testScheduler.currentTime },
            work = kotlinx.coroutines.Dispatchers.Default,
        )
        val caller = Thread.currentThread().name
        art.covers(listOf(ArtSong("T", "A", "B"))) { seen += Thread.currentThread().name }
        assertThat(seen).isNotEmpty()
        assertThat(seen.toSet()).containsExactly(caller)
    }

    @Test fun `a lookup that throws is a refusal, never a crash`() = runTest {
        val art = RecordArt(lookUp = { error("boom") }, store = MemoryArtStore(), now = { testScheduler.currentTime }, work = StandardTestDispatcher(testScheduler))
        assertThat(art.covers(listOf(ArtSong("S", "A", "B")))).containsExactly(null)
    }
}

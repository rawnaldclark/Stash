package com.stash.feature.record.taste

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.ListeningEventEntity
import com.stash.core.data.db.entity.RecordHiddenEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.weblink.SongKey
import com.stash.feature.record.archive.ArchivePlay
import com.stash.feature.record.archive.RecordFlags
import com.stash.feature.record.archive.playId
import com.stash.feature.record.archive.toEntity
import com.stash.feature.record.importer.ImportRecord
import com.stash.feature.record.imports.MemoryPrefs
import com.stash.feature.record.imports.RecordImportStore
import com.stash.feature.record.imports.RecordWrites
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset

/**
 * [RecordTaste] on a real (in-memory) database: the taste worked out from the record's plays, this phone's own plays (the
 * scrobbles they cover left out) and the library (its liked songs kept), stored whole, only while there is an import; "Kept out
 * of your mixes" with why; Put back, only with an import, each song once, never kept out by the next taste; the removals; the
 * once-a-day refresh; the start-up's taste for an import with none; and the first taste's call for Daily Discover.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RecordTasteTest {
    private lateinit var db: StashDatabase
    private val prefs = MemoryPrefs()
    private val store = RecordImportStore(prefs)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var now = NOW
    private var firstTastes = 0
    private lateinit var taste: RecordTaste

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), StashDatabase::class.java).allowMainThreadQueries().build()
        taste = RecordTaste(
            store = store,
            plays = db.recordPlayDao(),
            library = db.webLibraryExportDao(),
            hidden = db.recordHiddenDao(),
            tastes = db.recordTasteDao(),
            writes = RecordWrites(),
            scope = scope,
            now = { now },
            zone = { ZoneOffset.UTC },
            io = Dispatchers.IO,
            firstTaste = { firstTastes++ },
        )
    }

    @After fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun imported() = store.setRecord(ImportRecord(firstAt = 1, lastAt = 1, imports = 1, plays = 1, first = 1, last = 1))

    private fun row(t: String, a: String, s: Long, ms: Long = 200_000, f: Int = RecordFlags.REASONS or RecordFlags.DONE) =
        ArchivePlay(id = playId(t, a, s), t = t, a = a, s = s, ms = ms, f = f)

    private val skipped = RecordFlags.REASONS or RecordFlags.SKIPPED
    private val scrobble = RecordFlags.LASTFM or RecordFlags.ESTIMATED

    /** The record: songs always skipped (one liked here), one hidden on Spotify, a lost love, and scrobbles of a song played here. */
    private val rows = buildList {
        for (i in 0 until 8) add(row("Never Again", "Artist Y", T0 + i * DAY, 2_000, skipped))
        for (i in 0 until 8) add(row("Liked Song", "Band", T0 + i * DAY, 2_000, skipped))
        for (i in 0 until 3) add(row("Hidden Song", "Band", T0 + i * DAY))
        for (i in 0 until 10) add(row("Old Love", "Band", OLD + i * DAY))
        // a scrobble the play here covers (it starts a minute in), and one it doesn't
        add(row("Played Here", "Band", OWN + 60_000, 210_000, scrobble))
        add(row("Played Here", "Band", OWN - 10 * DAY, 210_000, scrobble))
    }

    private suspend fun seed() {
        db.recordPlayDao().put(rows.map { it.toEntity() })
        db.recordHiddenDao().add(listOf(RecordHiddenEntity(SongKey.songKey("Hidden Song", "Band"), "Hidden Song", "Band")))
        val liked = db.trackDao().insert(TrackEntity(title = "Liked Song", artist = "Band", canonicalTitle = "liked song", canonicalArtist = "band", stashLikedAt = 5))
        val here = db.trackDao().insert(TrackEntity(title = "Played Here", artist = "Band", album = "Here", canonicalTitle = "played here", canonicalArtist = "band", durationMs = 240_000))
        db.listeningEventDao().insert(ListeningEventEntity(trackId = here, startedAt = OWN, listenedMs = 100_000))
        check(liked > 0)
    }

    /** The same taste worked out apart from the database: what [RecordTaste] must have stored. */
    private fun expected(putBack: Set<String> = emptySet()): Taste {
        val input = TasteInput(listOf(OwnPlay("Played Here", "Band", "Here", OWN, 240_000)))
        input.add(rows)
        return Taste.compute(
            input.plays(),
            TasteOptions(now = now, hidden = setOf(SongKey.songKey("Hidden Song", "Band")), kept = setOf(SongKey.songKey("Liked Song", "Band")), putBack = putBack),
        )
    }

    @Test fun `no import, no taste`() = runBlocking {
        seed()
        taste.refresh()
        assertThat(db.recordTasteDao().computedAt()).isNull()
        assertThat(taste.daily()).isNull()
        taste.ensure()
        assertThat(db.recordTasteDao().computedAt()).isNull()
    }

    @Test fun `the taste of the record, this phone's plays and the library, stored whole`() = runBlocking {
        seed()
        imported()
        taste.refresh()

        val want = expected()
        assertThat(db.recordTasteDao().computedAt()).isEqualTo(now)
        val stored = db.recordTasteDao().avoided().map { it.key }
        assertThat(stored).isEqualTo(want.songs.filter { it.avoid }.map { it.key })
        // the scrobble the play here covers is left out: two listens, the play here and the other scrobble
        val here = want.songs.single { it.key == "played here|band" }
        assertThat(here.plays).isEqualTo(2)
        assertThat(here.album).isEqualTo("Here")
        // liked here: never kept out, however often skipped
        assertThat(want.songs.single { it.key == "liked song|band" }.avoid).isFalse()
        assertThat(want.songs.filter { it.avoid }.map { it.key }).containsExactly("never again|artist y", "hidden song|band")
        // every song, in the taste's order, with its numbers
        assertThat(db.recordTasteDao().songCount()).isEqualTo(want.songs.size)
        val body = TasteRows.readBody(db.recordTasteDao().taste()!!.body)
        assertThat(body.seedArtists).isEqualTo(want.seedArtists)
        assertThat(body.lost).isEqualTo(want.lost)
        assertThat(body.lost).contains("old love|band")
        assertThat(firstTastes).isEqualTo(1)
    }

    @Test fun `kept out with why, put back once and only with an import, and never kept out by the next taste`() = runBlocking {
        seed()
        assertThat(taste.putBack("never again|artist y")).isFalse()
        imported()
        taste.refresh()

        val list = taste.keptOut()
        // in the taste's order: the hidden song was listened to (a weight), the skipped one never was
        assertThat(list.map { it.key to it.why }).containsExactly(
            "hidden song|band" to KeptOutWhy.Hidden,
            "never again|artist y" to KeptOutWhy.Skips(8, 8),
        ).inOrder()

        assertThat(taste.putBack("never again|artist y")).isTrue()
        assertThat(taste.putBack("never again|artist y")).isTrue()
        assertThat(db.recordTasteDao().keepKeys()).containsExactly("never again|artist y")
        // off the list at once, before any taste is worked out again
        assertThat(taste.keptOut().map { it.key }).containsExactly("hidden song|band")

        // The next taste reads it as never kept out (put back beats hidden and the skips)
        now += DAY
        taste.refresh()
        assertThat(db.recordTasteDao().avoided().map { it.key }).containsExactly("hidden song|band")
        assertThat(db.recordTasteDao().avoided().map { it.key }).isEqualTo(expected(putBack = setOf("never again|artist y")).songs.filter { it.avoid }.map { it.key })
        assertThat(firstTastes).isEqualTo(1)
    }

    @Test fun `a song liked since leaves the list at once, before the taste is worked out again`() = runBlocking<Unit> {
        seed()
        imported()
        taste.refresh()
        assertThat(taste.keptOut().map { it.key }).contains("never again|artist y")
        db.trackDao().insert(TrackEntity(title = "Never Again (Remastered)", artist = "Artist Y", canonicalTitle = "never again", canonicalArtist = "artist y", stashLikedAt = 9))
        assertThat(taste.keptOut().map { it.key }).containsExactly("hidden song|band")
        // the stored taste still has it kept out until the next one, which counts it kept
        assertThat(db.recordTasteDao().avoided().map { it.key }).contains("never again|artist y")
        now += DAY
        taste.refresh()
        assertThat(db.recordTasteDao().avoided().map { it.key }).containsExactly("hidden song|band")
    }

    @Test fun `Settings shown again reads the list afresh - a song liked elsewhere is gone from it`() = runBlocking<Unit> {
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.Dispatchers.Unconfined)
        try {
            seed()
            imported()
            taste.refresh()
            val vm = RecordInMixesViewModel(taste, RecordInMixes(com.stash.core.data.prefs.RecordInMixesPreference.Fixed(), { true }, { true }, scope), store)
            suspend fun until(what: String, ok: (List<String>?) -> Boolean): List<String>? {
                repeat(100) {
                    val keys = vm.list.value?.map { k -> k.key }
                    if (ok(keys)) return keys
                    kotlinx.coroutines.delay(50)
                }
                error("the list never showed $what: ${vm.list.value?.map { it.key }}")
            }
            until("the songs kept out") { it?.contains("never again|artist y") == true }
            // liked elsewhere (Now Playing): nothing the list watches changes...
            db.trackDao().insert(TrackEntity(title = "Never Again", artist = "Artist Y", canonicalTitle = "never again", canonicalArtist = "artist y", stashLikedAt = 9))
            // ...until Settings is shown again
            vm.reload()
            assertThat(until("the liked song gone") { it != null && "never again|artist y" !in it }).containsExactly("hidden song|band")
        } finally {
            kotlinx.coroutines.Dispatchers.resetMain()
        }
    }

    @Test fun `Daily Discover is asked for once, when the first taste with seed artists lands`() = runBlocking {
        // Only skips at first: a taste with no listens has no seed artists
        db.recordPlayDao().put((0 until 8).map { row("Never Again", "Artist Y", T0 + it * DAY, 2_000, skipped).toEntity() })
        imported()
        taste.refresh()
        assertThat(TasteRows.seedArtistsOf(db.recordTasteDao().taste()!!.body)).isEmpty()
        assertThat(firstTastes).isEqualTo(0)
        db.recordPlayDao().put((0 until 5).map { row("Loved", "Band", T0 + it * DAY).toEntity() })
        now += DAY
        taste.refresh()
        assertThat(firstTastes).isEqualTo(1)
        now += DAY
        taste.refresh()
        assertThat(firstTastes).isEqualTo(1)
    }

    @Test fun `the removals - the taste goes, and the songs put back with it unless kept`() = runBlocking {
        seed()
        imported()
        taste.refresh()
        taste.putBack("never again|artist y")

        taste.clearLocked(keep = false)
        assertThat(db.recordTasteDao().computedAt()).isNull()
        assertThat(db.recordTasteDao().keepKeys()).containsExactly("never again|artist y")
        assertThat(taste.keptOut()).isEmpty()

        taste.clearLocked(keep = true)
        assertThat(db.recordTasteDao().keepKeys()).isEmpty()
    }

    @Test fun `once a local day, and at start only for an import with no taste`() = runBlocking {
        seed()
        imported()
        taste.ensure()
        val first = db.recordTasteDao().computedAt()
        assertThat(first).isEqualTo(now)
        // a taste there: start-up leaves it
        now += 1_000
        taste.ensure()
        assertThat(db.recordTasteDao().computedAt()).isEqualTo(first)

        taste.daily()!!.join()
        assertThat(db.recordTasteDao().computedAt()).isEqualTo(now)
        now += 1_000
        assertThat(taste.daily()).isNull()
        // the next day (UTC here)
        now += DAY
        taste.daily()!!.join()
        assertThat(db.recordTasteDao().computedAt()).isEqualTo(now)
    }

    @Test fun `the put-back rules walk as the web's archive walks them (kept-out json rules)`() = runBlocking {
        val rules = kotlinx.serialization.json.Json.parseToJsonElement(requireNotNull(javaClass.getResource("/taste/kept-out.json")).readText())
            .let { (it as kotlinx.serialization.json.JsonObject)["rules"] as kotlinx.serialization.json.JsonArray }
            .map { it as kotlinx.serialization.json.JsonObject }
        fun keepOf(i: Int): List<String> = (rules[i]["keep"] as? kotlinx.serialization.json.JsonArray)?.map { (it as kotlinx.serialization.json.JsonPrimitive).content }.orEmpty()
        fun resultOf(i: Int): Boolean = (rules[i]["result"] as kotlinx.serialization.json.JsonPrimitive).content.toBoolean()
        // Kept sorted by UTF-16 units in Kotlin, each once (the table's key), as archive.ts keeps its list
        suspend fun keep() = db.recordTasteDao().keepKeys().sorted()

        assertThat(taste.putBack("zed|band")).isEqualTo(resultOf(0))
        assertThat(keep()).isEqualTo(keepOf(0))
        imported()
        assertThat(taste.putBack("zed|band")).isEqualTo(resultOf(1))
        assertThat(keep()).isEqualTo(keepOf(1))
        assertThat(taste.putBack("alpha|band")).isEqualTo(resultOf(2))
        assertThat(keep()).isEqualTo(keepOf(2))
        assertThat(taste.putBack("zed|band")).isEqualTo(resultOf(3))
        assertThat(keep()).isEqualTo(keepOf(3))
        // Remove import (RecordImports.removeImport clears the taste and the songs put back)
        taste.clearLocked(keep = true)
        assertThat(keep()).isEqualTo(keepOf(4))
        // Removing Last.fm: when it was all there was, they go; beside Spotify plays, they stay (RecordImports'
        // recordAfterScrobblesRemoved calls clearLocked(keep = true), or refreshLocked, which keeps them: RecordImportsTest)
        db.recordTasteDao().insertKeep(com.stash.core.data.db.entity.RecordTasteKeepEntity("back|band", 1))
        taste.refreshLocked()
        assertThat(keep()).isEqualTo(keepOf(6))
        taste.clearLocked(keep = true)
        assertThat(keep()).isEqualTo(keepOf(5))
    }

    private companion object {
        const val DAY = 86_400_000L

        /** 2026-10-27T12:00:00Z. */
        const val NOW = 1_793_102_400_000L
        val T0 = NOW - 200 * DAY
        val OLD = NOW - 1_000 * DAY
        val OWN = NOW - 5 * DAY
    }
}

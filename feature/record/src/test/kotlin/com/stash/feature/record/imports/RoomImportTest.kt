package com.stash.feature.record.imports

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.RecordPlayEntity
import com.stash.feature.record.archive.ArchivePlay
import com.stash.feature.record.importer.MemoryArchive
import com.stash.feature.record.importer.PickedFile
import com.stash.feature.record.importer.SpotifyImporter
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.time.ZoneOffset

/**
 * The golden fixture (SpotifyGoldenTest's) written through a real Room database (in memory) by [DaoArchiveStore], as the app
 * writes it: the same plays and ids as the web's, row for row, read back a page at a time; the same zips again add nothing; the
 * Account zip then the Extended one gives the same archive.
 *
 * One way the database differs from memory, on purpose not put right: SQLite stores text as UTF-8, and a lone surrogate (a
 * title cut at 300 UTF-16 units through an emoji, as the web's `str` cuts it) can't be UTF-8, so it comes back as U+FFFD. Such
 * a play's id then differs from the web's in that one character. Nothing is counted twice for it (the id written again is the
 * same U+FFFD one, a replace), but a later import's preview counts it as new. The fixture has none. Left to itself SQLite
 * would do worse mid-text (it pairs the lone surrogate with the next unit: an id's bar), so the store writes U+FFFD itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RoomImportTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: StashDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun tearDown() = db.close()

    private fun resource(name: String): File = File(requireNotNull(javaClass.getResource("/spotify-export/$name")) { name }.toURI())

    private fun fixture() = listOf("my_spotify_data.zip", "my_spotify_data (1).zip").map { PickedFile(it, resource(it)) }

    private val expected = Json.parseToJsonElement(resource("expected.json").readText()).jsonObject["archive"]!!.jsonObject

    /** Every row in `record_plays`, a page at a time by id, as the import reads them. */
    private suspend fun stored() = buildList {
        var after = ""
        while (true) {
            val page = db.recordPlayDao().pageAfter(after, 1_000)
            addAll(page)
            if (page.size < 1_000) break
            after = page.last().id
        }
    }

    @Test fun `the fixture through Room - the web's plays, row for row, lone surrogates read back as U+FFFD`() = runTest {
        val memory = MemoryArchive()
        SpotifyImporter(memory, tmp.newFolder(), ZoneOffset.UTC).let { it.commit(it.prepare(fixture(), NOW)) }
        val importer = SpotifyImporter(DaoArchiveStore(db.recordPlayDao()), tmp.newFolder(), ZoneOffset.UTC)
        importer.commit(importer.prepare(fixture(), NOW))

        val rows = stored()
        // The memory archive is the web's (SpotifyGoldenTest): the same plays, their ids
        val webIds = memory.sortedIds()
        assertThat(webIds.size).isEqualTo(expected["plays"]!!.jsonPrimitive.int)
        assertThat(sha256(webIds.joinToString("\n"))).isEqualTo(expected["sha256"]!!.jsonPrimitive.content)
        assertThat(rows).hasSize(webIds.size)
        // Row for row, but for lone surrogates
        val byId = rows.associateBy { it.id }
        for (p in memory.plays.values) {
            val r = requireNotNull(byId[utf8(p.id)]) { p.id }
            assertThat(listOf(r.t, r.a, r.al, r.sid, r.s, r.ms, r.f)).isEqualTo(listOf(utf8(p.t), utf8(p.a), p.al?.let(::utf8), p.sid?.let(::utf8), p.s, p.ms, p.f))
        }
        // The fixture has no lone surrogate, so the ids through Room are the web's exactly (expected.json's SHA-256 and all)
        assertThat(webIds.count { it != utf8(it) }).isEqualTo(0)
        assertThat(rows.map { it.id }.sorted()).isEqualTo(webIds)
        assertThat(webIds.take(3)).isEqualTo(expected["first"]!!.jsonArray.map { it.jsonPrimitive.content })

        // The same zips again, merged against what Room kept: nothing new, nothing written
        val again = importer.prepare(fixture(), NOW)
        assertThat(again.preview.plays).isEqualTo(0)
        assertThat(importer.commit(again).written).isEqualTo(0)
        assertThat(db.recordPlayDao().count()).isEqualTo(webIds.size)
    }


    @Test fun `a lone surrogate is stored as U+FFFD - SQLite left alone would pair it with what follows, the id's bar`() = runTest {
        // A title cut at 300 UTF-16 units through an emoji, as the web's str cuts it, ends in a lone high surrogate
        val title = "a".repeat(299) + "\uD83C"
        val id = "$title|artist|1"
        // SQLite itself: at the end of a text it reads U+FFFD, but mid-text it pairs the surrogate with the next unit (the bar)
        db.recordPlayDao().apply(listOf(RecordPlayEntity(id = id, t = title, a = "artist", al = null, s = 1, ms = 1, f = 0)), emptyList())
        val raw = stored().single()
        assertThat(raw.t).isEqualTo("a".repeat(299) + "\uFFFD")
        assertThat(raw.id).doesNotContain("\uFFFD|artist|1")
        db.recordPlayDao().clear()

        // Through the import's store: made well-formed first, so the id keeps its bar and differs from the web's in that one unit
        val store = DaoArchiveStore(db.recordPlayDao())
        store.apply(listOf(ArchivePlay(id = id, t = title, a = "artist", s = 1, ms = 1, f = 0)), emptyList())
        val back = stored().single()
        assertThat(back.id).isEqualTo("a".repeat(299) + "\uFFFD|artist|1")
        assertThat(back.t).isEqualTo("a".repeat(299) + "\uFFFD")
        // The same play written again (a later import): the same row, replaced, never a second one
        store.apply(listOf(ArchivePlay(id = id, t = title, a = "artist", s = 1, ms = 1, f = 0)), emptyList())
        assertThat(db.recordPlayDao().count()).isEqualTo(1)
    }

    @Test fun `the account zip, then the extended one, through Room - the same archive`() = runTest {
        val importer = SpotifyImporter(DaoArchiveStore(db.recordPlayDao()), tmp.newFolder(), ZoneOffset.UTC)
        val (extended, account) = fixture()
        importer.commit(importer.prepare(listOf(account), NOW))
        importer.commit(importer.prepare(listOf(extended), NOW))

        val memory = MemoryArchive()
        SpotifyImporter(memory, tmp.newFolder(), ZoneOffset.UTC).let { it.commit(it.prepare(fixture(), NOW)) }
        assertThat(stored().map { it.id }.toSet()).isEqualTo(memory.sortedIds().map(::utf8).toSet())
    }

    /** [s] as UTF-8 can hold it: a lone surrogate becomes U+FFFD (what SQLite gives back; Java's own encoder would give '?'). */
    private fun utf8(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate() -> {
                    out.append(c).append(s[i + 1])
                    i += 2
                    continue
                }
                c.isSurrogate() -> out.append('�')
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private companion object {
        /** 2026-10-23T12:00:00Z. */
        const val NOW = 1_792_756_800_000L
    }
}

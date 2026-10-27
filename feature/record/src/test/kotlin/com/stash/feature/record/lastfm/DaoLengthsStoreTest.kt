package com.stash.feature.record.lastfm

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.RecordLengthEntity
import com.stash.feature.record.archive.ArchivePlay
import com.stash.feature.record.archive.RecordFlags
import com.stash.feature.record.archive.toEntity
import com.stash.feature.record.imports.RecordImportStore
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The lengths pass in the database: a slice against Room as the web's against its archive (every save the same mark, written
 * as only what moved), the rows timed by their id range, and the pass's memory gone with the scrobbles.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DaoLengthsStoreTest {
    private lateinit var db: StashDatabase
    private lateinit var store: RecordImportStore
    private val nowS = 1_800_000_000L
    private val t0 = 1_714_564_800L

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StashDatabase::class.java).allowMainThreadQueries().build()
        store = RecordImportStore(context.getSharedPreferences("lengths-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() })
    }

    @After fun tearDown() = db.close()

    private fun scrobble(t: String, i: Int, a: String = "Band"): ArchivePlay {
        val sc = Scrobble(t, a, uts = t0 + i * 60L)
        return ArchivePlay(id = Scrobbles.id(sc), t = t, a = a, s = sc.uts * 1000, ms = 210_000, f = RecordFlags.LASTFM or RecordFlags.ESTIMATED)
    }

    private suspend fun write(rows: List<ArchivePlay>) = db.recordPlayDao().apply(rows.map { it.toEntity() }, emptyList())

    private fun lengths() = DaoLengthsStore(db.recordPlayDao(), db.recordLengthDao(), store)

    @Test fun `a slice against the database gives what the memory archive gives`() = runTest {
        val rows = listOf(
            scrobble("Hit", 0), scrobble("Hit", 1), scrobble("hit", 2), scrobble("Hit - 2011 Remaster", 3),
            scrobble("Miss", 4), scrobble("Miss", 5), scrobble("Gone Wrong", 6), scrobble("Known", 7),
        ) + ArchivePlay(id = "known|band|1", t = "Known", a = "Band", s = 1, ms = 222_000, f = RecordFlags.DONE)
        write(rows)
        val memory = MemoryLengthsStore(rows)
        val answers = mapOf("Hit" to 187_000L, "Miss" to null)
        val look = LengthReader { _, t -> if (t == "Gone Wrong") throw LastfmError(LastfmFailure.BUSY) else LengthAnswer(answers[t]) }
        val fromDb = Lengths.fill(lengths(), look, nowS, gapMs = 0, busyMs = 0)
        val fromMemory = Lengths.fill(memory, look, nowS, gapMs = 0, busyMs = 0)
        assertThat(fromDb).isEqualTo(fromMemory)
        val dbRows = db.recordPlayDao().pageAfter("", 100).associate { it.id to (it.ms to it.f) }
        assertThat(dbRows).isEqualTo(memory.rows.values.associate { it.id to (it.ms to it.f) })
        // The mark read back as it was saved
        assertThat(lengths().mark()).isEqualTo(memory.mark)
        assertThat(memory.mark!!.none.keys).containsExactly(Scrobbles.songKey("Miss", "Band"))
        assertThat(memory.mark!!.failed.keys).containsExactly(Scrobbles.songKey("Gone Wrong", "Band"))
    }

    @Test fun `each save writes only what moved, and the plan's pruning reaches the table`() = runTest {
        write(listOf(scrobble("A", 0), scrobble("B", 1)))
        db.recordLengthDao().replace(listOf(RecordLengthEntity("gone|band", RecordLengthEntity.NONE, nowS - 10), RecordLengthEntity(Scrobbles.songKey("A", "Band"), RecordLengthEntity.FAILED, nowS - 5)))
        store.setLengths(timed = 7, at = nowS - 100)
        val s = lengths()
        assertThat(s.mark()).isEqualTo(
            LengthsMark(
                none = mapOf("gone|band" to nowS - 10), failed = mapOf(Scrobbles.songKey("A", "Band") to nowS - 5),
                failures = mapOf(Scrobbles.songKey("A", "Band") to 1), timed = 7, at = nowS - 100,
            ),
        )
        s.setMark(LengthsMark(none = mapOf(Scrobbles.songKey("B", "Band") to nowS), failed = mapOf(Scrobbles.songKey("A", "Band") to nowS - 5), timed = 8, at = nowS))
        assertThat(db.recordLengthDao().all().associate { it.key to (it.kind to it.at) }).isEqualTo(
            mapOf(Scrobbles.songKey("A", "Band") to (RecordLengthEntity.FAILED to nowS - 5), Scrobbles.songKey("B", "Band") to (RecordLengthEntity.NONE to nowS)),
        )
        // A third failure in a row is kept as its count (the table's kind), and read back so
        s.setMark(LengthsMark(none = emptyMap(), failed = mapOf(Scrobbles.songKey("A", "Band") to nowS), failures = mapOf(Scrobbles.songKey("A", "Band") to 3), timed = 8, at = nowS))
        assertThat(db.recordLengthDao().all().map { it.key to it.kind }).containsExactly(Scrobbles.songKey("A", "Band") to 3)
        assertThat(lengths().mark()!!.failures).containsExactly(Scrobbles.songKey("A", "Band"), 3)
        assertThat(store.info.value.lengthsTimed).isEqualTo(8)
        assertThat(store.lengthsAt).isEqualTo(nowS)
    }

    @Test fun `nothing kept yet is no mark`() = runTest {
        assertThat(lengths().mark()).isNull()
    }

    @Test fun `removing the scrobbles takes the pass's memory with them, and Remove import too`() = runTest {
        write(listOf(scrobble("A", 0)) + ArchivePlay(id = "x|band|1", t = "X", a = "Band", s = 1, ms = 200_000, f = RecordFlags.DONE))
        db.recordLengthDao().put(listOf(RecordLengthEntity("a|band", RecordLengthEntity.NONE, nowS)))
        store.setLengths(timed = 3, at = nowS)
        val scrobbles = DaoScrobbleStore(db.recordPlayDao(), store, db.recordLengthDao())
        assertThat(ImportScrobbles.remove(scrobbles)).isEqualTo(1)
        assertThat(db.recordLengthDao().all()).isEmpty()
        assertThat(store.info.value.lengthsTimed).isEqualTo(0)
        assertThat(store.lengthsAt).isNull()
        assertThat(db.recordPlayDao().count()).isEqualTo(1)

        db.recordLengthDao().put(listOf(RecordLengthEntity("b|band", RecordLengthEntity.NONE, nowS)))
        store.setLengths(timed = 1, at = nowS)
        com.stash.feature.record.imports.DaoArchiveStore(db.recordPlayDao(), db.recordLengthDao()).clear()
        store.clearRecord()
        assertThat(db.recordLengthDao().all()).isEmpty()
        assertThat(db.recordPlayDao().count()).isEqualTo(0)
        assertThat(store.lengthsAt).isNull()
    }
}

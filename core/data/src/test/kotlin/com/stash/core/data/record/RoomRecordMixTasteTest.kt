package com.stash.core.data.record

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.RecordTasteEntity
import com.stash.core.data.db.entity.RecordTasteKeepEntity
import com.stash.core.data.db.entity.RecordTasteSongEntity
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import com.stash.core.data.prefs.RecordInMixesPreference
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The seam the mixes read ([RoomRecordMixTaste]) over a real database: nothing with no taste or with "Use your record in mixes"
 * off; kept out = the taste's avoided songs minus the songs put back since, at once; weights over the top weight of every song,
 * a put-back song's given back; a new taste read afresh.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RoomRecordMixTasteTest {
    private lateinit var db: StashDatabase
    private val switch = RecordInMixesPreference.Fixed(on = true)
    private lateinit var seam: RoomRecordMixTaste

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), StashDatabase::class.java).allowMainThreadQueries().build()
        seam = RoomRecordMixTaste(db.recordTasteDao(), switch, db.webLibraryExportDao())
    }

    @After fun tearDown() = db.close()

    private fun song(key: String, rank: Int, weight: Double, avoid: Boolean = false) = RecordTasteSongEntity(
        key = key, rank = rank, title = key, artist = "Band", artistKey = "band", starts = 1, plays = 1, full = 1, chosen = 0, skips = 0,
        years = 1, listenedMs = 1, first = 1, last = 1, peak = 1, peakAt = 1, weight = weight, depth = weight, avoid = avoid,
    )

    private suspend fun store(at: Long, seeds: String, vararg songs: RecordTasteSongEntity) =
        db.recordTasteDao().replace(RecordTasteEntity(v = 1, computedAt = at, body = """{"seedArtists":$seeds,"eras":[]}"""), songs.toList())

    @Test fun `no taste, no mixes taste`() = runTest {
        assertNull(seam.current())
    }

    @Test fun `seeds, kept out and weights over the top weight of every song, the avoided ones too`() = runTest {
        store(1, """["Loved Band","Second"]""", song("a|band", 0, 4.0, avoid = true), song("b|band", 1, 2.0), song("c|band", 2, 0.0), song("d|band", 3, 1.0, avoid = true))
        val t = seam.current()!!
        assertEquals(listOf("Loved Band", "Second"), t.artists)
        assertEquals(setOf("a|band", "d|band"), t.avoid)
        assertEquals(mapOf("b|band" to 0.5), t.weights)
    }

    @Test fun `a song put back is never kept out again, at once, and gets its weight back`() = runTest {
        store(1, "[]", song("a|band", 0, 4.0, avoid = true), song("b|band", 1, 2.0))
        db.recordTasteDao().insertKeep(RecordTasteKeepEntity("a|band", 5))
        val t = seam.current()!!
        assertEquals(emptySet<String>(), t.avoid)
        assertEquals(mapOf("a|band" to 1.0, "b|band" to 0.5), t.weights)
    }

    @Test fun `a song liked since, or in a playlist, is never kept out, at once, and gets its weight back`() = runTest {
        store(1, "[]", song("never again|artist y", 0, 4.0, avoid = true), song("b|band", 1, 2.0), song("c|band", 2, 1.0, avoid = true))
        assertEquals(setOf("never again|artist y", "c|band"), seam.current()!!.avoid)
        // liked here, a remastered spelling of the same song
        db.trackDao().insert(TrackEntity(title = "Never Again (Remastered)", artist = "Artist Y", canonicalTitle = "never again", canonicalArtist = "artist y", stashLikedAt = 9))
        // and one in a playlist of the listener's own
        val c = db.trackDao().insert(TrackEntity(title = "c", artist = "Band", canonicalTitle = "c", canonicalArtist = "band"))
        val p = db.playlistDao().insert(PlaylistEntity(name = "Mine", source = MusicSource.LOCAL, sourceId = "custom_mine", type = PlaylistType.CUSTOM, isActive = true, syncEnabled = true))
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = p, trackId = c, position = 0))
        val t = seam.current()!!
        assertEquals(emptySet<String>(), t.avoid)
        assertEquals(mapOf("never again|artist y" to 1.0, "b|band" to 0.5, "c|band" to 0.25), t.weights)
    }

    @Test fun `the switch off is no taste, on again the taste`() = runTest {
        store(1, "[]", song("a|band", 0, 1.0))
        switch.setEnabled(false)
        assertNull(seam.current())
        switch.setEnabled(true)
        assertEquals(setOf("a|band"), seam.current()!!.weights.keys)
    }

    @Test fun `a new taste is read afresh, a cleared one is gone`() = runTest {
        store(1, """["Old"]""", song("a|band", 0, 1.0))
        assertEquals(listOf("Old"), seam.current()!!.artists)
        store(2, """["New"]""", song("b|band", 0, 1.0))
        val t = seam.current()!!
        assertEquals(listOf("New"), t.artists)
        assertEquals(setOf("b|band"), t.weights.keys)
        db.recordTasteDao().clear()
        assertNull(seam.current())
    }
}

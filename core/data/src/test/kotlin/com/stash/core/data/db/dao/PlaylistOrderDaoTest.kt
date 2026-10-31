package com.stash.core.data.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Edit order against a real database: a move rewrites one song's place; songs that arrive later sit at the bottom;
 * a sync's clear and re-add keeps every place; reset is the service's order again; a move between songs Download
 * mode hides keeps theirs; a playlist out of room between two songs is spaced out again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PlaylistOrderDaoTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: StashDatabase
    private lateinit var order: PlaylistOrderDao
    private val ids = mutableMapOf<String, Long>()
    private var playlist = 0L

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, StashDatabase::class.java).allowMainThreadQueries().build()
        order = db.playlistOrderDao()
    }

    @After fun tearDown() = db.close()

    private suspend fun seed(vararg titles: String, type: PlaylistType = PlaylistType.CUSTOM, downloaded: Set<String> = titles.toSet()) {
        playlist = db.playlistDao().insert(PlaylistEntity(name = "Night drive", source = MusicSource.SPOTIFY, type = type))
        titles.forEachIndexed { i, t -> add(t, i, downloaded = t in downloaded) }
    }

    private suspend fun add(title: String, position: Int, downloaded: Boolean = true, addedAt: Long = position.toLong()) {
        val id = ids.getOrPut(title) {
            db.trackDao().insert(
                TrackEntity(
                    title = title, artist = "Artist", canonicalTitle = title.lowercase(), canonicalArtist = "artist",
                    isDownloaded = downloaded, filePath = if (downloaded) "/music/$title.opus" else null, isStreamable = true,
                ),
            )
        }
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = playlist, trackId = id, position = position, addedAt = java.time.Instant.ofEpochSecond(addedAt)))
    }

    private suspend fun shown(includeStreamable: Boolean = true): List<String> {
        val byId = ids.entries.associate { (t, id) -> id to t }
        return db.trackDao().getByPlaylist(playlist, includeStreamable).first().map { byId.getValue(it.id) }
    }

    private suspend fun move(title: String, before: String?, after: String?) =
        order.move(playlist, ids.getValue(title), before?.let(ids::getValue), after?.let(ids::getValue))

    @Test fun `an untouched playlist keeps the service's order`() = runTest {
        seed("A", "B", "C")
        assertEquals(listOf("A", "B", "C"), shown())
        assertFalse(order.observeIsCustom(playlist).first())
    }

    @Test fun `a move puts the song where it was dropped, to the top, the middle and the end`() = runTest {
        seed("A", "B", "C", "D")
        assertTrue(move("C", before = "A", after = null))
        assertEquals(listOf("C", "A", "B", "D"), shown())
        assertTrue(order.observeIsCustom(playlist).first())

        move("D", before = "B", after = "A")
        assertEquals(listOf("C", "A", "D", "B"), shown())

        move("C", before = null, after = "B")
        assertEquals(listOf("A", "D", "B", "C"), shown())
    }

    @Test fun `a later move rewrites only the moved song's place`() = runTest {
        seed("A", "B", "C", "D")
        move("D", before = "A", after = null)
        val before = order.entries(playlist).associate { it.trackId to it.rank }
        move("B", before = null, after = "C")
        val after = order.entries(playlist).associate { it.trackId to it.rank }
        assertEquals(listOf(ids.getValue("B")), after.keys.filter { after[it] != before[it] })
    }

    @Test fun `a sync's new songs land at the bottom, in the service's order`() = runTest {
        seed("A", "B", "C")
        move("C", before = "A", after = null)
        // The service lists E then D, both ahead of the songs already here.
        add("E", -2, addedAt = 100)
        add("D", -1, addedAt = 100)
        assertEquals(listOf("C", "A", "B", "E", "D"), shown())
    }

    @Test fun `a refresh that clears and re-adds the playlist's songs keeps every place`() = runTest {
        seed("A", "B", "C")
        move("A", before = null, after = "C")
        db.openHelper.writableDatabase.execSQL("DELETE FROM playlist_tracks WHERE playlist_id = $playlist")
        // The service now has them as C, A, B; and dropped nothing.
        add("C", 0); add("A", 1); add("B", 2)
        assertEquals(listOf("B", "C", "A"), shown())
    }

    @Test fun `reset is the service's order again`() = runTest {
        seed("A", "B", "C")
        move("A", before = null, after = "C")
        order.reset(playlist)
        assertEquals(listOf("A", "B", "C"), shown())
        assertFalse(order.observeIsCustom(playlist).first())
        assertTrue(order.entries(playlist).isEmpty())
    }

    @Test fun `a move in Download mode keeps the hidden songs' places`() = runTest {
        seed("A", "x", "B", "y", "C", downloaded = setOf("A", "B", "C"))
        assertEquals(listOf("A", "B", "C"), shown(includeStreamable = false))
        // Drag C between A and B as shown: it lands just before B, after the hidden x.
        move("C", before = "B", after = "A")
        assertEquals(listOf("A", "C", "B"), shown(includeStreamable = false))
        assertEquals(listOf("A", "x", "C", "B", "y"), shown())
    }

    @Test fun `a daily mix's first move starts from the order it was shown in, newest first`() = runTest {
        playlist = db.playlistDao().insert(PlaylistEntity(name = "Daily Mix 1", source = MusicSource.SPOTIFY, type = PlaylistType.DAILY_MIX))
        add("Old", 0, addedAt = 1); add("New", 1, addedAt = 5)
        add("Mid", 2, addedAt = 3)
        assertEquals(listOf("New", "Mid", "Old"), shown())
        move("Old", before = "Mid", after = "New")
        assertEquals(listOf("New", "Old", "Mid"), shown())
    }

    @Test fun `out of room between two songs, the playlist is spaced out again in its new order`() = runTest {
        seed("A", "B", "C")
        repeat(40) { i -> if (i % 2 == 0) move("C", before = "B", after = "A") else move("B", before = "C", after = "A") }
        assertEquals(listOf("A", "B", "C"), shown())
        move("C", before = "B", after = "A")
        assertEquals(listOf("A", "C", "B"), shown())
        assertEquals(3, order.entries(playlist).map { it.rank }.distinct().size)
    }

    @Test fun `a gone song or gone neighbours change nothing`() = runTest {
        seed("A", "B", "C")
        assertFalse(order.move(playlist, trackId = 999, beforeTrackId = ids.getValue("A"), afterTrackId = null))
        assertFalse(order.move(playlist, ids.getValue("C"), beforeTrackId = 998, afterTrackId = 999))
        assertEquals(listOf("A", "B", "C"), shown())
    }

    @Test fun `a removed song added back later goes to the bottom`() = runTest {
        seed("A", "B", "C")
        move("C", before = "A", after = null)
        db.openHelper.writableDatabase.execSQL(
            "UPDATE playlist_tracks SET removed_at = 1 WHERE playlist_id = $playlist AND track_id = ${ids.getValue("A")}",
        )
        move("B", before = "C", after = null) // any move drops the places of songs that left
        db.openHelper.writableDatabase.execSQL(
            "UPDATE playlist_tracks SET removed_at = NULL WHERE playlist_id = $playlist AND track_id = ${ids.getValue("A")}",
        )
        assertEquals(listOf("B", "C", "A"), shown())
    }
}

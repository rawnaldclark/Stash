package com.stash.core.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import com.stash.core.model.SyncMode
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The joint hide (plan 2026-11-04 §2.4), the highest-risk code of several Spotify accounts: a slip
 * hides another account's whole library. On a real in-memory database, with the listings the
 * accounts' passes would register. "A" is the main account (rows tagged NULL), "B" an extra.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SpotifyJointHideTest {

    private lateinit var db: StashDatabase
    private val syncLog = SyncLog()
    private val syncId = 7L

    private val b = "userb"
    private val c = "userc"

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun tearDown() = db.close()

    // ── Fixtures ─────────────────────────────────────────────────────────

    private suspend fun row(
        sourceId: String,
        owner: String? = null,
        type: PlaylistType = PlaylistType.CUSTOM,
        source: MusicSource = MusicSource.SPOTIFY,
    ): Long = db.playlistDao().insert(
        PlaylistEntity(name = sourceId, source = source, sourceId = sourceId, type = type, spotifyAccount = owner),
    )

    private fun album(id: String) = "album:spotify:$id"

    /** A listing; [complete] false alone means its walk missed a page ([listedAll] false too). */
    private fun listing(owner: String?, vararg ids: String, complete: Boolean = true, listedAll: Boolean = complete) =
        SpotifyAccountListing(owner).apply {
            this.ids += ids
            this.complete = complete
            this.listedAll = listedAll
        }

    private fun listings(
        playlists: List<SpotifyAccountListing> = emptyList(),
        albums: List<SpotifyAccountListing> = emptyList(),
    ) = SpotifySyncListings().apply {
        this.playlists += playlists
        this.albums += albums
    }

    private suspend fun hide(listings: SpotifySyncListings, mode: SyncMode? = SyncMode.REFRESH) =
        hideWhatNoSpotifyAccountListed(db.playlistDao(), db.syncUndoDao(), syncLog, syncId, mode, listings)

    private suspend fun active(id: Long) = db.playlistDao().getById(id)!!.isActive

    // ── Playlists ────────────────────────────────────────────────────────

    @Test fun `B's complete listing never hides A's rows, and hides B's own unlisted ones`() = runTest {
        val a1 = row("a1")
        val a2 = row("a2")
        val b1 = row("b1", owner = b)
        val b2 = row("b2", owner = b)

        val outcome = hide(listings(playlists = listOf(listing(null, "a1", "a2"), listing(b, "b1"))))

        assertTrue(active(a1))
        assertTrue(active(a2))
        assertTrue(active(b1))
        assertFalse("B no longer has it", active(b2))
        assertEquals(mapOf<String?, Int>(b to 1), outcome.hiddenPlaylists)
    }

    @Test fun `with only B listing, none of A's rows can go, missing from B's listing as they all are`() = runTest {
        val a1 = row("a1")
        val b2 = row("b2", owner = b)

        hide(listings(playlists = listOf(listing(b))))

        assertTrue(active(a1))
        assertFalse(active(b2))
    }

    @Test fun `one incomplete listing hides nothing for anyone`() = runTest {
        val a2 = row("a2")
        val b2 = row("b2", owner = b)

        val outcome = hide(listings(playlists = listOf(listing(null, "a1"), listing(b, "b1", complete = false))))

        assertTrue("A listed completely, but B didn't", active(a2))
        assertTrue(active(b2))
        assertTrue(outcome.hiddenPlaylists.isEmpty())
        assertNull("nothing hidden: no restore point taken", db.syncUndoDao().latestPointNow())
    }

    @Test fun `an extra with a playlist it couldn't read keeps its own rows and blocks nobody else's`() = runTest {
        val a2 = row("a2")
        val b2 = row("b2", owner = b)
        val sharedOwnedByA = row("shared")

        // B listed every id (its unread playlist "b1" included) but couldn't read b1's songs.
        val outcome = hide(
            listings(playlists = listOf(listing(null, "a1"), listing(b, "b1", "shared", complete = false, listedAll = true))),
        )

        assertFalse("the main's own listing is complete: its gone row goes", active(a2))
        assertTrue("B's own rows wait for a complete read of B", active(b2))
        assertTrue("B's listed ids still count for everyone", active(sharedOwnedByA))
        assertEquals(mapOf<String?, Int>(null to 1), outcome.hiddenPlaylists)
    }

    @Test fun `the main with a playlist it couldn't read keeps its own rows, and B's gone rows still go`() = runTest {
        val a2 = row("a2")
        val b2 = row("b2", owner = b)

        hide(listings(playlists = listOf(listing(null, "a1", complete = false, listedAll = true), listing(b, "b1"))))

        assertTrue(active(a2))
        assertFalse(active(b2))
    }

    @Test fun `one account with a playlist it couldn't read hides nothing, as a single account always did`() = runTest {
        val a2 = row("a2")

        val outcome = hide(listings(playlists = listOf(listing(null, "a1", complete = false, listedAll = true))))

        assertTrue(active(a2))
        assertTrue(outcome.hiddenPlaylists.isEmpty())
        assertNull(db.syncUndoDao().latestPointNow())
    }

    @Test fun `a signed-out account's rows are untouched`() = runTest {
        // C is signed out (or switched off, or over the limit): its pass never ran, so it listed nothing.
        val c1 = row("c1", owner = c)
        val a2 = row("a2")

        hide(listings(playlists = listOf(listing(null, "a1"), listing(b, "b1"))))

        assertTrue(active(c1))
        assertFalse(active(a2))
    }

    @Test fun `a row tagged with an account that has no account row at all is left alone`() = runTest {
        // A merge restore or a likes-only import copies the tag but never the spotify_accounts row.
        val orphan = row("o1", owner = "ghost")
        val orphanAlbum = row(album("o2"), owner = "ghost")

        hide(listings(playlists = listOf(listing(null), listing(b)), albums = listOf(listing(null), listing(b))))

        assertTrue(active(orphan))
        assertTrue(active(orphanAlbum))
    }

    @Test fun `a playlist both follow survives A unfollowing it, whoever owns the row`() = runTest {
        val sharedOwnedByA = row("shared")
        val sharedOwnedByB = row("shared2", owner = b)

        // A unfollowed both; B still follows both.
        hide(listings(playlists = listOf(listing(null), listing(b, "shared", "shared2"))))

        assertTrue(active(sharedOwnedByA))
        assertTrue(active(sharedOwnedByB))
    }

    @Test fun `a whole listing with nothing in it hides every playlist of its account, and nothing else`() = runTest {
        val first = row("first")
        val second = row("second")
        val alreadyHidden = db.playlistDao().insert(
            PlaylistEntity(name = "x", source = MusicSource.SPOTIFY, sourceId = "x", type = PlaylistType.CUSTOM, isActive = false),
        )
        val local = row("local", source = MusicSource.BOTH)

        val outcome = hide(listings(playlists = listOf(listing(null))))

        assertFalse(active(first))
        assertFalse(active(second))
        assertFalse(active(alreadyHidden))
        assertTrue("the listener's own", active(local))
        assertEquals(mapOf<String?, Int>(null to 2), outcome.hiddenPlaylists)
    }

    /**
     * Past 999 ids a single NOT IN UPDATE threw "too many SQL variables" on API 26-30 and nothing
     * was hidden; a naive per-chunk NOT IN would hide what the other chunks list. The joint hide
     * reads the rows once and writes by id, in chunks.
     */
    @Test fun `a library-sized listing hides only what is missing`() = runTest {
        val present = (1..1200).map { "sp$it" }
        present.forEach { row(it) }
        val gone = row("gone")

        hide(listings(playlists = listOf(listing(null, *present.toTypedArray()))))

        assertFalse(active(gone))
        assertEquals(1200, db.playlistDao().getActivePlaylistsBySource(MusicSource.SPOTIFY).size)
    }

    @Test fun `Accumulate never hides, and an unread mode hides nothing`() = runTest {
        val a2 = row("a2")
        val b2 = row("b2", owner = b)
        val all = listings(playlists = listOf(listing(null), listing(b)))

        hide(all, mode = SyncMode.ACCUMULATE)
        hide(all, mode = null)

        assertTrue(active(a2))
        assertTrue(active(b2))
    }

    @Test fun `Liked Songs, mixes, saved albums and other services' rows are never hidden as playlists`() = runTest {
        val liked = row("spotify_liked_songs", type = PlaylistType.LIKED_SONGS)
        val extraLiked = row(SpotifyPlaylistIds.extraLikedSongs(b), owner = b, type = PlaylistType.LIKED_SONGS)
        val mix = row("mix", type = PlaylistType.DAILY_MIX)
        val savedAlbum = row(album("x"))
        val youtube = row("PLyt", source = MusicSource.YOUTUBE)

        hide(listings(playlists = listOf(listing(null), listing(b))))

        for (id in listOf(liked, extraLiked, mix, savedAlbum, youtube)) assertTrue("row $id", active(id))
    }

    // ── Saved albums: the same rule on their own listings ────────────────

    @Test fun `albums - B's complete listing never hides A's albums`() = runTest {
        val a1 = row(album("a1"))
        val b2 = row(album("b2"), owner = b)

        val outcome = hide(listings(albums = listOf(listing(null, album("a1")), listing(b))))

        assertTrue(active(a1))
        assertFalse(active(b2))
        assertEquals(mapOf<String?, Int>(b to 1), outcome.hiddenAlbums)
    }

    @Test fun `albums - one incomplete listing hides nothing for anyone`() = runTest {
        val a2 = row(album("a2"))
        val b2 = row(album("b2"), owner = b)

        hide(listings(albums = listOf(listing(null), listing(b, complete = false))))

        assertTrue(active(a2))
        assertTrue(active(b2))
    }

    @Test fun `albums - a signed-out account's albums are untouched`() = runTest {
        val c1 = row(album("c1"), owner = c)

        hide(listings(albums = listOf(listing(null), listing(b))))

        assertTrue(active(c1))
    }

    @Test fun `albums - an album both saved survives A un-saving it`() = runTest {
        val shared = row(album("shared"))

        hide(listings(albums = listOf(listing(null), listing(b, album("shared")))))

        assertTrue(active(shared))
    }

    @Test fun `albums - a pasted album (source BOTH) is never hidden`() = runTest {
        val pasted = row(album("p"), source = MusicSource.BOTH)

        hide(listings(albums = listOf(listing(null))))

        assertTrue(active(pasted))
    }

    @Test fun `each kind is measured by its own listings only`() = runTest {
        val a2 = row("a2")
        val albumA2 = row(album("a2"))

        // The album listing failed part way; the playlist listing is whole.
        hide(listings(playlists = listOf(listing(null, "a1")), albums = listOf(listing(null, complete = false))))

        assertFalse("the playlists' own listing was whole", active(a2))
        assertTrue("an incomplete album listing hides no album", active(albumA2))
        // A playlist id never stands for an album, nor the other way round.
        val a3 = row("a3")
        hide(listings(playlists = listOf(listing(null, album("a3"))), albums = listOf(listing(null, "a3"))))
        assertFalse(active(a3))
    }

    @Test fun `no account listed a kind - nothing of it is measured`() = runTest {
        val a2 = row("a2")
        val albumA2 = row(album("a2"))

        hide(listings())
        hide(listings(albums = listOf(listing(null, album("a2")))))

        assertTrue("no playlist listing at all", active(a2))
        assertTrue(active(albumA2))
    }

    // ── Undo ─────────────────────────────────────────────────────────────

    @Test fun `Undo after a sync that hid rows of both accounts restores both`() = runTest {
        val a2 = row("a2")
        val b2 = row("b2", owner = b)
        val albumB2 = row(album("b2"), owner = b)

        hide(listings(playlists = listOf(listing(null), listing(b)), albums = listOf(listing(null), listing(b))))
        assertFalse(active(a2))
        assertFalse(active(b2))
        assertFalse(active(albumB2))
        val point = db.syncUndoDao().latestPointNow()!!
        assertEquals("one restore point, this sync's", syncId, point.syncId)

        db.syncUndoDao().restore(syncId)

        assertTrue(active(a2))
        assertTrue(active(b2))
        assertTrue(active(albumB2))
        assertEquals("the rows keep their account", b, db.playlistDao().getById(b2)!!.spotifyAccount)
    }

    @Test fun `the restore point is taken before the first hide, and only once`() = runTest {
        val a2 = row("a2")
        db.syncUndoDao().captureOnce(syncId, nowEpochMs = 1L)

        hide(listings(playlists = listOf(listing(null))))

        assertFalse(active(a2))
        assertEquals("the point taken earlier this sync stands", 1L, db.syncUndoDao().latestPointNow()!!.createdAtEpochMs)
    }

    @Test fun `the live log says what went, once`() = runTest {
        row("a2")
        row(album("b2"), owner = b)

        hide(listings(playlists = listOf(listing(null)), albums = listOf(listing(b))))

        val lines = syncLog.lines.value.map { it.text }
        assertTrue(lines.contains("Hid 1 Spotify playlist(s) no longer in your account"))
        assertTrue(lines.contains("Took 1 album you un-saved on Spotify out of Albums"))
    }
}

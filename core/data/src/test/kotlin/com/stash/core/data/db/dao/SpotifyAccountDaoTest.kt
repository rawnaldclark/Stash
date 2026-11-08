package com.stash.core.data.db.dao

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.SpotifyAccountEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [SpotifyAccountDao]: the extra Spotify accounts' roster (plan 2026-11-04 §2.1). */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SpotifyAccountDaoTest {

    private lateinit var db: StashDatabase
    private lateinit var dao: SpotifyAccountDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.spotifyAccountDao()
    }

    @After fun tearDown() = db.close()

    private fun account(userId: String, label: String, addedAt: Long) =
        SpotifyAccountEntity(userId = userId, label = label, addedAt = addedAt)

    @Test fun `an added account reads back by id and by user id, with its default switches`() = runTest {
        val id = dao.insert(account("lm7d11nsf65n11sojl4twwr6t", "Sam", addedAt = 5))

        val sam = dao.getById(id)!!
        assertEquals(sam, dao.getByUserId("lm7d11nsf65n11sojl4twwr6t"))
        assertEquals("Sam", sam.label)
        assertEquals(listOf(true, true, true, false), listOf(sam.syncPlaylists, sam.syncLiked, sam.syncAlbums, sam.syncMixes))
        assertNull(sam.signedOutAt)
        assertNull(dao.getByUserId("someone-else"))
        assertNull(dao.getById(id + 1))
    }

    @Test fun `the same Spotify account can't be added twice`() = runTest {
        dao.insert(account("kimk", "Kim", addedAt = 1))

        val second = runCatching { dao.insert(account("kimk", "Kim again", addedAt = 2)) }.exceptionOrNull()

        assertTrue("$second", second is SQLiteConstraintException)
        assertEquals(1, dao.count())
    }

    @Test fun `the roster lists in the order accounts were added, and follows changes`() = runTest {
        val kim = dao.insert(account("kimk", "Kim", addedAt = 20))
        val sam = dao.insert(account("sam", "Sam", addedAt = 10))

        assertEquals(listOf(sam, kim), dao.getAll().map { it.id })
        assertEquals(listOf(sam, kim), dao.observeAll().first().map { it.id })
        assertEquals(2, dao.count())

        dao.delete(sam)
        assertEquals(listOf(kim), dao.observeAll().first().map { it.id })
    }

    @Test fun `label, each switch and the signed-out time change on their own`() = runTest {
        val id = dao.insert(account("sam", "Sam", addedAt = 1))

        assertEquals(1, dao.updateLabel(id, "Sam (work)"))
        dao.setSyncPlaylists(id, false)
        dao.setSyncMixes(id, true)
        dao.updateSignedOutAt(id, 99)
        var sam = dao.getById(id)!!
        assertEquals("Sam (work)", sam.label)
        assertEquals(listOf(false, true, true, true), listOf(sam.syncPlaylists, sam.syncLiked, sam.syncAlbums, sam.syncMixes))
        assertEquals(99L, sam.signedOutAt)

        dao.setSyncLiked(id, false)
        dao.setSyncAlbums(id, false)
        dao.updateSignedOutAt(id, null)
        sam = dao.getById(id)!!
        assertEquals(listOf(false, false, false, true), listOf(sam.syncPlaylists, sam.syncLiked, sam.syncAlbums, sam.syncMixes))
        assertNull("signed in again", sam.signedOutAt)
        assertEquals("Sam (work)", sam.label)
    }

    // -- One account, one key, whatever shape its id comes in -----------------

    @Test fun `adding stores the normalised id, and adding the same account again finds its row`() = runTest {
        val added = dao.addOrFind("spotify:user:LM7D11NSF65N11SOJL4TWWR6T", "Sam", addedAt = 5)

        assertEquals("lm7d11nsf65n11sojl4twwr6t", added.userId)
        assertEquals("Sam", added.label)

        val again = dao.addOrFind("  Lm7d11nsf65n11sojl4twwr6t ", "Sam again", addedAt = 9)
        assertEquals("the same row, unchanged", added, again)
        assertEquals(1, dao.count())
    }

    @Test fun `add says whether it added the row or found it there`() = runTest {
        val first = dao.add("spotify:user:Sam", "Sam", addedAt = 5)
        assertTrue("added", first.added)
        assertEquals("sam", first.row.userId)

        val second = dao.add("SAM", "Sam again", addedAt = 9)
        assertEquals("found, not added", false, second.added)
        assertEquals("the same row, unchanged", first.row, second.row)
        assertEquals(1, dao.count())
    }

    @Test fun `an account is found by its id in any shape`() = runTest {
        val sam = dao.addOrFind("lm7d11nsf65n11sojl4twwr6t", "Sam", addedAt = 5)

        listOf("lm7d11nsf65n11sojl4twwr6t", "LM7D11NSF65N11SOJL4TWWR6T", "spotify:user:lm7d11nsf65n11sojl4twwr6t", " lm7d11nsf65n11sojl4twwr6t ")
            .forEach { assertEquals(it, sam, dao.findByAccount(it)) }
        assertNull(dao.findByAccount("kimk"))
        assertNull(dao.findByAccount("  "))
        // The raw query compares exactly, which is why callers go through findByAccount.
        assertNull(dao.getByUserId("LM7D11NSF65N11SOJL4TWWR6T"))
    }

    @Test fun `a row with an id that isn't normalised can't be built, so never reaches the table`() = runTest {
        listOf("Sam", " sam", "spotify:user:sam", "").forEach { id ->
            val refused = runCatching { account(id, "Sam", addedAt = 1) }.exceptionOrNull()
            assertTrue("'$id' built: $refused", refused is IllegalArgumentException)
        }
        assertTrue(runCatching { dao.addOrFind(" ", "Nobody", addedAt = 1) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, dao.count())
    }

    @Test fun `changing or deleting an account that is gone changes nothing`() = runTest {
        val id = dao.insert(account("sam", "Sam", addedAt = 1))

        assertEquals(1, dao.delete(id))
        assertEquals(0, dao.delete(id))
        assertEquals(0, dao.updateLabel(id, "x"))
        assertEquals(0, dao.setSyncMixes(id, true))
        assertEquals(0, dao.updateSignedOutAt(id, 5))
        assertEquals(0, dao.count())
    }
}

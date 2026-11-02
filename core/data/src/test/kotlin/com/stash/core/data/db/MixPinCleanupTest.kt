package com.stash.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
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

/**
 * The one-time cleanup of mix hearts nobody gave (2026-11-02): a mix pinned before v0.9.119,
 * the release that brought Favorite, loses its pin; later pins and every other playlist type
 * keep theirs; it runs once. Real in-memory Room and the real SharedPreferences file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MixPinCleanupTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences("stash_migrations_test", Context.MODE_PRIVATE)
    private lateinit var db: StashDatabase
    private lateinit var dao: PlaylistDao

    @Before fun setUp() {
        prefs.edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.playlistDao()
    }

    @After fun tearDown() = db.close()

    private fun cleanup() = MixPinCleanup(dao, prefs)

    private suspend fun insert(name: String, type: PlaylistType, pinnedAt: Long?): Long = dao.insert(
        PlaylistEntity(
            name = name,
            source = MusicSource.SPOTIFY,
            sourceId = name,
            type = type,
            isActive = true,
            pinnedToHomeAt = pinnedAt,
        ),
    )

    private suspend fun pinOf(id: Long): Long? = dao.getById(id)!!.pinnedToHomeAt

    @Test fun `clears mix pins from before Favorite and nothing else`() = runTest {
        val before = CUTOFF - 1
        // The release app's 27 hearts were stamped on 09-29 by the Sync tab switch.
        val switchedOnMix = insert("Daily Mix 1", PlaylistType.DAILY_MIX, pinnedAt = 1_790_000_000_000L)
        val justBeforeMix = insert("Discover Weekly", PlaylistType.DAILY_MIX, pinnedAt = before)
        val oldStashMix = insert("Daily Discover", PlaylistType.STASH_MIX, pinnedAt = before)
        val favoriteAtCutoff = insert("Release Radar", PlaylistType.DAILY_MIX, pinnedAt = CUTOFF)
        val favoriteLater = insert("My Mix 3", PlaylistType.DAILY_MIX, pinnedAt = CUTOFF + 86_400_000L)
        val unpinnedMix = insert("Daily Mix 2", PlaylistType.DAILY_MIX, pinnedAt = null)
        val ownPlaylist = insert("Road trip", PlaylistType.CUSTOM, pinnedAt = before)
        val liked = insert("Liked Songs", PlaylistType.LIKED_SONGS, pinnedAt = before)
        val stashLiked = insert("Stash Liked", PlaylistType.STASH_LIKED, pinnedAt = before)
        val downloads = insert("Downloads", PlaylistType.DOWNLOADS_MIX, pinnedAt = before)

        val result = cleanup().runOnce()

        assertEquals(MixPinCleanup.Result(cleared = 3, kept = 2), result)
        assertNull(pinOf(switchedOnMix))
        assertNull(pinOf(justBeforeMix))
        assertNull(pinOf(oldStashMix))
        assertEquals(CUTOFF, pinOf(favoriteAtCutoff))
        assertEquals(CUTOFF + 86_400_000L, pinOf(favoriteLater))
        assertNull(pinOf(unpinnedMix))
        assertEquals(before, pinOf(ownPlaylist))
        assertEquals(before, pinOf(liked))
        assertEquals(before, pinOf(stashLiked))
        assertEquals(before, pinOf(downloads))
    }

    @Test fun `runs once`() = runTest {
        insert("Daily Mix 1", PlaylistType.DAILY_MIX, pinnedAt = CUTOFF - 1)
        assertEquals(MixPinCleanup.Result(cleared = 1, kept = 0), cleanup().runOnce())
        assertTrue(prefs.getBoolean(MixPinCleanup.DONE_KEY, false))

        // A pre-cutoff pin that turns up later (a restored backup) is left alone: the flag is set.
        val restored = insert("Daily Mix 4", PlaylistType.DAILY_MIX, pinnedAt = CUTOFF - 1)
        assertNull(cleanup().runOnce())
        assertEquals(CUTOFF - 1, pinOf(restored))
    }

    @Test fun `the update itself is idempotent`() = runTest {
        val old = insert("Daily Mix 1", PlaylistType.DAILY_MIX, pinnedAt = CUTOFF - 1)
        val kept = insert("Daily Mix 2", PlaylistType.DAILY_MIX, pinnedAt = CUTOFF + 1)

        assertEquals(1, dao.clearMixPinsBefore(CUTOFF))
        assertEquals(0, dao.clearMixPinsBefore(CUTOFF))
        assertNull(pinOf(old))
        assertEquals(CUTOFF + 1, pinOf(kept))
    }

    @Test fun `the cutoff is v0_9_119's publish time`() {
        // GitHub release v0.9.119: published_at 2026-10-23T11:51:53Z.
        assertEquals(java.time.Instant.parse("2026-10-23T11:51:53Z").toEpochMilli(), CUTOFF)
    }

    private companion object {
        const val CUTOFF = MixPinCleanup.FAVORITE_RELEASED_AT_MS
    }
}

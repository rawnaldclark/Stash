package com.stash.core.data.mix

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.lastfm.LastFmSession
import com.stash.core.data.lastfm.LastFmSessionPreference
import com.stash.core.data.lastfm.LastFmSourcePreference
import com.stash.core.data.prefs.StashMixPreference
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [LastFmRecommendationSource] — the issue #255 lifecycle owner
 * that turns a Last.fm scrobble connection into a Sync **source** backed by
 * the app-managed "Recommended by Last.fm" mix recipe.
 *
 * Uses a real in-memory Room DB (the DAO interactions are the contract) and
 * mock-backed DataStores (Robolectric + process-singleton DataStore delegates
 * don't reset cleanly between tests — see StashMixRecipeDaoRetuneTest for the
 * Room-only variant of this pattern).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class LastFmRecommendationSourceTest {

    private lateinit var db: StashDatabase
    private lateinit var source: LastFmRecommendationSource

    private val sessionFlow = MutableStateFlow<LastFmSession?>(null)
    private val enabledFlow = MutableStateFlow(true)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StashDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()

        val sessionPreference = mockk<LastFmSessionPreference> {
            every { session } returns sessionFlow
        }
        val sourcePreference = mockk<LastFmSourcePreference> {
            every { recommendationsEnabled } returns enabledFlow
            coEvery { current() } answers { enabledFlow.value }
            // Simulate the real DataStore: persisting flips the observable flow.
            coEvery { setRecommendationsEnabled(any()) } answers {
                enabledFlow.value = firstArg()
            }
        }
        // Master switch ON would make setRecommendationsEnabled(true) try to
        // kick a one-shot refresh through WorkManager, which isn't initialized
        // under Robolectric. OFF keeps that glue out of these lifecycle tests;
        // the enqueue call itself is fail-safe runCatching glue.
        val stashMixPreference = mockk<StashMixPreference> {
            coEvery { current() } returns false
        }

        source = LastFmRecommendationSource(
            recipeDao = db.stashMixRecipeDao(),
            playlistDao = db.playlistDao(),
            sessionPreference = sessionPreference,
            sourcePreference = sourcePreference,
            stashMixPreference = stashMixPreference,
            context = ApplicationProvider.getApplicationContext(),
        )
    }

    @After fun tearDown() {
        db.close()
    }

    private fun dao() = db.stashMixRecipeDao()
    private fun playlistDao() = db.playlistDao()

    private suspend fun recipe() = dao().getByName(LastFmRecommendationSource.RECIPE_NAME)

    /** Insert a materialized playlist row and point the managed recipe at it,
     *  mirroring what StashMixRefreshWorker.materializeMix does on first fill. */
    private suspend fun materializePlaylist(): Long {
        val recipeId = source.ensureRecipe()
        val playlistId = playlistDao().insert(
            PlaylistEntity(
                name = LastFmRecommendationSource.RECIPE_NAME,
                source = MusicSource.BOTH,
                sourceId = "stash_mix_test_$recipeId",
                type = PlaylistType.STASH_MIX,
                trackCount = 12,
            ),
        )
        dao().setPlaylistId(recipeId, playlistId)
        return playlistId
    }

    @Test fun `reconcile creates the managed recipe when missing`() = runTest {
        source.reconcile()
        val r = recipe()
        assertNotNull("reconcile must find-or-create the recipe", r)
        assertEquals(LastFmRecommendationSource.TARGET_LENGTH, r!!.targetLength)
        assertEquals(LastFmRecommendationSource.DISCOVERY_RATIO, r.discoveryRatio)
        assertEquals(LastFmRecommendationSource.SEED_STRATEGY, r.seedStrategy)
        assertFalse("recipe must not ship builtin-flagged", r.isBuiltin)
    }

    @Test fun `ensureRecipe is idempotent`() = runTest {
        val first = source.ensureRecipe()
        val second = source.ensureRecipe()
        assertEquals(first, second)
        // getActive() filters on is_active (the managed recipe seeds inactive),
        // so count via the unfiltered list.
        val rows = dao().observeAll().first()
        assertEquals(1, rows.count { it.name == LastFmRecommendationSource.RECIPE_NAME })
    }

    @Test fun `reconcile activates while connected and enabled`() = runTest {
        sessionFlow.value = LastFmSession("scrobbler", "key")
        source.reconcile()
        assertTrue(recipe()!!.isActive)
    }

    @Test fun `reconcile keeps the recipe inactive without a session`() = runTest {
        source.reconcile()
        assertNotNull(recipe())
        assertFalse(recipe()!!.isActive)
    }

    @Test fun `reconcile honors the user opt-out even when connected`() = runTest {
        sessionFlow.value = LastFmSession("scrobbler", "key")
        enabledFlow.value = false
        source.reconcile()
        assertFalse(recipe()!!.isActive)
    }

    @Test fun `disconnecting deactivates without deleting anything`() = runTest {
        sessionFlow.value = LastFmSession("scrobbler", "key")
        val playlistId = materializePlaylist()
        source.reconcile()
        assertTrue(recipe()!!.isActive)

        sessionFlow.value = null
        source.reconcile()
        assertFalse("no session → no active recipe", recipe()!!.isActive)
        assertNotNull("playlist must survive disconnect", playlistDao().getById(playlistId))
    }

    @Test fun `toggling off hides the playlist and toggling on restores it`() = runTest {
        sessionFlow.value = LastFmSession("scrobbler", "key")
        val playlistId = materializePlaylist()
        source.reconcile()
        assertTrue(playlistDao().getById(playlistId)!!.isActive)

        source.setRecommendationsEnabled(false)
        assertFalse(enabledFlow.value)
        assertFalse(recipe()!!.isActive)
        assertFalse("disabled source must hide its playlist", playlistDao().getById(playlistId)!!.isActive)

        source.setRecommendationsEnabled(true)
        assertTrue(recipe()!!.isActive)
        assertTrue("re-enabling restores the playlist in place", playlistDao().getById(playlistId)!!.isActive)
    }

    @Test fun `observeState reports connection, preference, and track count`() = runTest {
        var state = source.observeState().first()
        assertFalse(state.connected)

        sessionFlow.value = LastFmSession("scrobbler", "key")
        val playlistId = materializePlaylist()
        source.reconcile()

        state = source.observeState().first()
        assertTrue(state.connected)
        assertTrue(state.enabled)
        assertEquals(12, state.trackCount)

        playlistDao().setActiveById(playlistId, false)
        state = source.observeState().first()
        assertEquals("a hidden playlist counts as no tracks", null, state.trackCount)
    }

    @Test fun `observeByName emits the managed recipe reactively`() = runTest {
        // Sanity: the reactive lookup used by observeState resolves after create.
        source.reconcile()
        val observed = dao().observeByName(LastFmRecommendationSource.RECIPE_NAME).first()
        assertNotNull(observed)
        assertEquals(false, observed!!.isActive)
    }
}

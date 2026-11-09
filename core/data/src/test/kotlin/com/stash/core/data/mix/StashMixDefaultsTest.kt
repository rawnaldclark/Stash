package com.stash.core.data.mix

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.dao.StashMixRecipeDao
import com.stash.core.data.db.entity.StashMixRecipeEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The shipped built-in recipes ([StashMixDefaults.ALL]) and the retune that moves an existing install
 * onto them ([StashMixDefaults.retuneBuiltins], run once per tuning version by StashApplication).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class StashMixDefaultsTest {

    private lateinit var db: StashDatabase
    private lateinit var dao: StashMixRecipeDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.stashMixRecipeDao()
    }

    @After fun tearDown() { db.close() }

    @Test fun `Daily Discover is all recommendations, 40 of them`() {
        val daily = StashMixDefaults.ALL.single { it.name == "Daily Discover" }

        assertEquals(1.0f, daily.discoveryRatio)
        assertEquals(40, daily.targetLength)
        assertTrue(daily.isBuiltin)
        assertEquals(MixSlots(discovery = 40, library = 0), MixSlots.of(daily))
    }

    @Test fun `the retune turns an existing install's 85 percent Daily Discover into all recommendations`() = runTest {
        dao.insert(
            StashMixRecipeEntity(
                name = "Daily Discover",
                description = "Mostly fresh finds via similar-artists. A pinch of your library.",
                freshnessWindowDays = 7,
                discoveryRatio = 0.85f,
                targetLength = 40,
                seedStrategy = "ARTIST_SIMILAR",
                isBuiltin = true,
            ),
        )
        // A mix the user built at the slider's 85% default: not a built-in, so never retuned.
        dao.insert(
            StashMixRecipeEntity(
                name = "Indie Mix",
                includeTagsCsv = "indie",
                discoveryRatio = 0.85f,
                targetLength = 40,
                seedStrategy = "TAG_GRAPH",
            ),
        )

        val updated = StashMixDefaults.retuneBuiltins(dao)

        assertEquals(1, updated)
        val rows = dao.getActive().associateBy { it.name }
        val daily = rows.getValue("Daily Discover")
        assertEquals(1.0f, daily.discoveryRatio)
        assertEquals(40, daily.targetLength)
        assertEquals(7, daily.freshnessWindowDays)
        assertEquals("ARTIST_SIMILAR", daily.seedStrategy)
        assertEquals(0.85f, rows.getValue("Indie Mix").discoveryRatio)
    }

    @Test fun `on a fresh install the retune finds the seeded values already in place`() = runTest {
        StashMixDefaults.seedIfNeeded(dao)

        StashMixDefaults.retuneBuiltins(dao)

        assertEquals(1.0f, dao.getActive().single { it.name == "Daily Discover" }.discoveryRatio)
    }
}

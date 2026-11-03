package com.stash.core.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Settings > Appearance > Moods & genres in Search ([HomeSectionsPreference.searchBrowseOn]), through the real store. */
@RunWith(RobolectricTestRunner::class)
class HomeSectionsSearchBrowseTest {
    private lateinit var context: Context

    // The DataStore instance outlives a test, so deleting its file doesn't reset it: each test starts from an empty store.
    @Before fun setUp() = runTest {
        context = ApplicationProvider.getApplicationContext()
        context.homeSectionsDataStore.edit { it.clear() }
    }

    @Test fun `on until turned off, so every install keeps Search as it is`() = runTest {
        assertTrue(HomeSectionsPreference(context).searchBrowseOn.first())
    }

    @Test fun `turned off it stays off, for a new reader too, and on again it's on`() = runTest {
        val pref = HomeSectionsPreference(context)
        pref.setSearchBrowseOn(false)
        assertFalse(pref.searchBrowseOn.first())
        // As after a restart: another instance reads the same store
        assertFalse(HomeSectionsPreference(context).searchBrowseOn.first())

        pref.setSearchBrowseOn(true)
        assertTrue(pref.searchBrowseOn.first())
    }

    @Test fun `the switch leaves Home's layout alone`() = runTest {
        val pref = HomeSectionsPreference(context)
        val order = pref.order.first()
        val shown = pref.visibleSections.first()
        pref.setSearchBrowseOn(false)
        assertEquals(order, pref.order.first())
        assertEquals(shown, pref.visibleSections.first())
        assertTrue(pref.hidden.first().isEmpty())
    }

    @Test fun `stored under its own key`() = runTest {
        HomeSectionsPreference(context).setSearchBrowseOn(false)
        assertEquals(false, context.homeSectionsDataStore.data.first()[booleanPreferencesKey("search_moods_genres")])
    }
}

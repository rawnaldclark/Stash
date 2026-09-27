package com.stash.core.data.prefs

import android.content.Context
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

@RunWith(RobolectricTestRunner::class)
class HomeSectionsCommunityTest {
    private lateinit var context: Context

    // The DataStore instance outlives a test, so deleting its file doesn't reset it: each test starts from an empty store.
    @Before fun setUp() = runTest {
        context = ApplicationProvider.getApplicationContext()
        context.homeSectionsDataStore.edit { it.clear() }
    }

    @Test fun `off until turned on, the first time it goes to the top, after that it stays where it was put`() = runTest {
        val pref = HomeSectionsPreference(context)
        assertFalse(pref.communityOn.first())
        assertFalse(HomeSection.COMMUNITY in pref.visibleSections.first())

        pref.setCommunityOn(true)
        assertTrue(pref.communityOn.first())
        assertEquals(HomeSection.COMMUNITY, pref.visibleSections.first().first())

        pref.move(HomeSection.COMMUNITY, up = false)
        pref.setCommunityOn(false)
        assertFalse(HomeSection.COMMUNITY in pref.visibleSections.first())
        pref.setCommunityOn(true)
        assertEquals(HomeSection.COMMUNITY, pref.visibleSections.first()[1])
    }

    @Test fun `placed by hand before it was ever on, it keeps that spot when turned on`() = runTest {
        val pref = HomeSectionsPreference(context)
        pref.move(HomeSection.COMMUNITY, up = true)
        val placed = pref.order.first()

        pref.setCommunityOn(true)
        assertEquals(placed, pref.visibleSections.first())
    }
}

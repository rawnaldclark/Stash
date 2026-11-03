package com.stash.core.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A drag's drop in Settings › Home layout ([HomeSectionsPreference.move] to a row), through the real DataStore: saved in
 * the same form the arrows always saved, so an order saved either way reads the same.
 */
@RunWith(RobolectricTestRunner::class)
class HomeSectionsDropTest {
    private lateinit var context: Context
    private val orderKey = stringPreferencesKey("home_sections_order")
    private val promotedKey = booleanPreferencesKey("your_listening_promoted_v1")
    private val communityOnKey = booleanPreferencesKey("community_on")

    // The DataStore instance outlives a test, so each test starts from an empty store.
    @Before fun setUp() = runTest {
        context = ApplicationProvider.getApplicationContext()
        context.homeSectionsDataStore.edit { it.clear() }
    }

    @Test fun `a drop saves the whole order as the arrows do, and ends the your listening promotion`() = runTest {
        val pref = HomeSectionsPreference(context)
        val before = pref.order.first().filter { it != HomeSection.RECORD_MIXES }
        pref.move(HomeSection.RADIOS, to = 0)

        val order = pref.order.first()
        assertEquals(HomeSection.RADIOS, order.first())
        val prefs = context.homeSectionsDataStore.data.first()
        // Every section's key, comma-separated, in the order Home now shows
        assertEquals(order.joinToString(",") { it.key }, prefs[orderKey])
        assertEquals(true, prefs[promotedKey])
        // The rest kept their order
        assertEquals(before - HomeSection.RADIOS, order.filter { it != HomeSection.RECORD_MIXES }.drop(1))

        // The same drop, one row at a time with the arrows, saves the same thing
        context.homeSectionsDataStore.edit { it.clear() }
        repeat(before.indexOf(HomeSection.RADIOS)) { pref.move(HomeSection.RADIOS, up = true) }
        assertEquals(order.joinToString(",") { it.key }, context.homeSectionsDataStore.data.first()[orderKey])
    }

    @Test fun `your record dropped lower takes its mixes, and a drop that moves nothing writes nothing`() = runTest {
        val pref = HomeSectionsPreference(context)
        pref.move(HomeSection.YOUR_RECORD, to = 4)
        val order = pref.order.first()
        val card = order.indexOf(HomeSection.YOUR_RECORD)
        assertEquals(HomeSection.RECORD_MIXES, order[card + 1])
        assertEquals(4, order.filter { it != HomeSection.RECORD_MIXES }.indexOf(HomeSection.YOUR_RECORD))

        context.homeSectionsDataStore.edit { it.clear() }
        pref.move(HomeSection.RECORD_MIXES, to = 0)
        pref.move(HomeSection.DISCOVER, to = 0)
        pref.move(HomeSection.DISCOVER, to = 99)
        assertNull(context.homeSectionsDataStore.data.first()[orderKey])
    }

    @Test fun `community dropped before it was ever on keeps that spot when turned on`() = runTest {
        val pref = HomeSectionsPreference(context)
        pref.move(HomeSection.COMMUNITY, to = 2)
        assertFalse(pref.communityOn.first())
        assertEquals(false, context.homeSectionsDataStore.data.first()[communityOnKey])

        val placed = pref.order.first()
        pref.setCommunityOn(true)
        assertEquals(placed, pref.visibleSections.first())
        assertTrue(HomeSection.COMMUNITY in pref.visibleSections.first())
    }
}

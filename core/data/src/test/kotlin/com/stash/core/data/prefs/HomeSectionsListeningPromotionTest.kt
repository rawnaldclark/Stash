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

/** The one-time Your listening promotion, through the real DataStore (see [effectiveHomeSectionOrder]). */
@RunWith(RobolectricTestRunner::class)
class HomeSectionsListeningPromotionTest {
    private lateinit var context: Context

    // The DataStore instance outlives a test, so each test starts from an empty store.
    @Before fun setUp() = runTest {
        context = ApplicationProvider.getApplicationContext()
        context.homeSectionsDataStore.edit { it.clear() }
    }

    @Test fun `a saved arrangement from before the update starts with your listening`() = runTest {
        // Written by the previous release: no promotion key.
        context.homeSectionsDataStore.edit {
            it[androidx.datastore.preferences.core.stringPreferencesKey("home_sections_order")] =
                "made_for_you,radios,your_playlists,new_releases,qobuz_playlists,top_albums,mood_decades,community"
        }
        val pref = HomeSectionsPreference(context)
        val order = pref.order.first()
        // Discover stays on top; Your record and Your listening go directly under it, From your record, New from your
        // artists and Moods & moments above the catalogs.
        assertEquals(listOf(HomeSection.DISCOVER, HomeSection.YOUR_RECORD, HomeSection.YOUR_LISTENING), order.take(3))
        assertEquals(
            listOf(
                HomeSection.MADE_FOR_YOU, HomeSection.RADIOS, HomeSection.YOUR_PLAYLISTS, HomeSection.RECORD_MIXES,
                HomeSection.NEW_FROM_ARTISTS, HomeSection.MOODS, HomeSection.NEW_RELEASES,
                HomeSection.QOBUZ_PLAYLISTS, HomeSection.TOP_ALBUMS, HomeSection.MOOD_DECADES, HomeSection.COMMUNITY,
            ),
            order.drop(3),
        )
        assertEquals(listOf(HomeSection.DISCOVER, HomeSection.YOUR_RECORD, HomeSection.YOUR_LISTENING), pref.visibleSections.first().take(3))
    }

    @Test fun `moving it down saves the order, and it stays under discover on the next read`() = runTest {
        val pref = HomeSectionsPreference(context)
        pref.move(HomeSection.YOUR_LISTENING, up = false)
        assertEquals(HomeSection.YOUR_LISTENING, pref.order.first()[3])
        // A fresh instance reads the saved order and keeps the promotion ended.
        val again = HomeSectionsPreference(context).order.first()
        assertEquals(HomeSection.DISCOVER, again[0])
        assertEquals(HomeSection.YOUR_RECORD, again[1])
        assertEquals(HomeSection.YOUR_PLAYLISTS, again[2])
        assertEquals(HomeSection.YOUR_LISTENING, again[3])
    }

    @Test fun `hidden stays hidden through the promotion`() = runTest {
        val pref = HomeSectionsPreference(context)
        pref.setHidden(HomeSection.YOUR_LISTENING, hide = true)
        assertFalse(HomeSection.YOUR_LISTENING in pref.visibleSections.first())
        assertTrue(HomeSection.YOUR_LISTENING in pref.order.first())
        assertEquals(HomeSection.DISCOVER, pref.order.first().first())
    }

    @Test fun `a fresh install shows discover, your record, then your listening`() = runTest {
        assertEquals(
            listOf(HomeSection.DISCOVER, HomeSection.YOUR_RECORD, HomeSection.YOUR_LISTENING),
            HomeSectionsPreference(context).visibleSections.first().take(3),
        )
    }
}

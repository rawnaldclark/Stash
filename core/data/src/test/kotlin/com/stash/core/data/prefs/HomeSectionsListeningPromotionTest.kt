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
 * The one-time Your listening promotion, and Your record's mixes following the card, through the real DataStore (see
 * [effectiveHomeSectionOrder], [movedHomeSectionOrder]).
 */
@RunWith(RobolectricTestRunner::class)
class HomeSectionsListeningPromotionTest {
    private lateinit var context: Context
    private val orderKey = stringPreferencesKey("home_sections_order")
    private val hiddenKey = stringPreferencesKey("home_sections_hidden")
    private val promotedKey = booleanPreferencesKey("your_listening_promoted_v1")

    // The DataStore instance outlives a test, so each test starts from an empty store.
    @Before fun setUp() = runTest {
        context = ApplicationProvider.getApplicationContext()
        context.homeSectionsDataStore.edit { it.clear() }
    }

    @Test fun `a saved arrangement from before the update starts with your listening`() = runTest {
        // Written by the previous release: no promotion key.
        context.homeSectionsDataStore.edit {
            it[orderKey] = "made_for_you,radios,your_playlists,new_releases,qobuz_playlists,top_albums,mood_decades,community"
        }
        val pref = HomeSectionsPreference(context)
        val order = pref.order.first()
        // Discover stays on top; Your record, its mixes and Your listening go directly under it.
        assertEquals(
            listOf(HomeSection.DISCOVER, HomeSection.YOUR_RECORD, HomeSection.RECORD_MIXES, HomeSection.YOUR_LISTENING),
            order.take(4),
        )
        assertEquals(
            listOf(
                HomeSection.MADE_FOR_YOU, HomeSection.RADIOS, HomeSection.YOUR_PLAYLISTS, HomeSection.NEW_RELEASES,
                HomeSection.QOBUZ_PLAYLISTS, HomeSection.TOP_ALBUMS, HomeSection.MOOD_DECADES, HomeSection.COMMUNITY,
            ),
            order.drop(4),
        )
        assertEquals(
            listOf(HomeSection.DISCOVER, HomeSection.YOUR_RECORD, HomeSection.RECORD_MIXES, HomeSection.YOUR_LISTENING),
            pref.visibleSections.first().take(4),
        )
    }

    @Test fun `moving it down saves the order, and it stays under discover on the next read`() = runTest {
        val pref = HomeSectionsPreference(context)
        pref.move(HomeSection.YOUR_LISTENING, up = false)
        assertEquals(HomeSection.YOUR_LISTENING, pref.order.first()[4])
        // A fresh instance reads the saved order and keeps the promotion ended.
        val again = HomeSectionsPreference(context).order.first()
        assertEquals(HomeSection.DISCOVER, again[0])
        assertEquals(HomeSection.YOUR_RECORD, again[1])
        assertEquals(HomeSection.RECORD_MIXES, again[2])
        assertEquals(HomeSection.YOUR_PLAYLISTS, again[3])
        assertEquals(HomeSection.YOUR_LISTENING, again[4])
    }

    @Test fun `hidden stays hidden through the promotion`() = runTest {
        val pref = HomeSectionsPreference(context)
        pref.setHidden(HomeSection.YOUR_LISTENING, hide = true)
        assertFalse(HomeSection.YOUR_LISTENING in pref.visibleSections.first())
        assertTrue(HomeSection.YOUR_LISTENING in pref.order.first())
        assertEquals(HomeSection.DISCOVER, pref.order.first().first())
    }

    @Test fun `a fresh install shows discover, your record and its mixes, then your listening`() = runTest {
        assertEquals(
            listOf(HomeSection.DISCOVER, HomeSection.YOUR_RECORD, HomeSection.RECORD_MIXES, HomeSection.YOUR_LISTENING),
            HomeSectionsPreference(context).visibleSections.first().take(4),
        )
    }

    @Test fun `moving the card moves its mixes, and the saved order keeps them together`() = runTest {
        val pref = HomeSectionsPreference(context)
        // Discover, Your record, From your record, Your listening, …: one tap down takes the pair past Your listening
        pref.move(HomeSection.YOUR_RECORD, up = false)
        val expected = listOf(HomeSection.DISCOVER, HomeSection.YOUR_LISTENING, HomeSection.YOUR_RECORD, HomeSection.RECORD_MIXES)
        assertEquals(expected, pref.order.first().take(4))
        assertEquals("discover,your_listening,your_record,record_mixes", context.homeSectionsDataStore.data.first()[orderKey]!!.split(',').take(4).joinToString(","))
        assertEquals(expected, HomeSectionsPreference(context).visibleSections.first().take(4))
    }

    @Test fun `moving the mixes on their own writes nothing`() = runTest {
        val pref = HomeSectionsPreference(context)
        val before = pref.order.first()
        pref.move(HomeSection.RECORD_MIXES, up = true)
        pref.move(HomeSection.RECORD_MIXES, up = false)
        assertEquals(before, pref.order.first())
        // Nothing saved, so the Your listening promotion isn't ended by a tap that moved nothing
        val prefs = context.homeSectionsDataStore.data.first()
        assertNull(prefs[orderKey])
        assertNull(prefs[promotedKey])
    }

    @Test fun `hiding the card keeps the mixes on Home, where the card was`() = runTest {
        val pref = HomeSectionsPreference(context)
        pref.setHidden(HomeSection.YOUR_RECORD, hide = true)
        assertEquals(
            listOf(HomeSection.DISCOVER, HomeSection.RECORD_MIXES, HomeSection.YOUR_LISTENING),
            pref.visibleSections.first().take(3),
        )
        // The card keeps its place: shown again, it's back above its mixes
        assertTrue(HomeSection.YOUR_RECORD in pref.order.first())
        pref.setHidden(HomeSection.YOUR_RECORD, hide = false)
        assertEquals(
            listOf(HomeSection.DISCOVER, HomeSection.YOUR_RECORD, HomeSection.RECORD_MIXES),
            pref.visibleSections.first().take(3),
        )
    }

    @Test fun `an arrangement saved by the release before keeps every choice, the mixes under the card`() = runTest {
        // 0.9.122 put the mixes above the catalogs; this user had reordered Home and hidden the card.
        context.homeSectionsDataStore.edit {
            it[orderKey] = "discover,your_record,community,your_listening,your_playlists,record_mixes,new_releases," +
                "qobuz_playlists,top_albums,made_for_you,radios,mood_decades"
            it[promotedKey] = true
            it[hiddenKey] = "your_record"
        }
        val pref = HomeSectionsPreference(context)
        // The mixes show where the card was (Community stays off: its own switch)
        assertEquals(
            listOf(HomeSection.DISCOVER, HomeSection.RECORD_MIXES, HomeSection.YOUR_LISTENING, HomeSection.YOUR_PLAYLISTS, HomeSection.NEW_RELEASES),
            pref.visibleSections.first().take(5),
        )
        // Hidden stays hidden: nothing is rewritten on read
        assertEquals(setOf(HomeSection.YOUR_RECORD), pref.hidden.first())
        assertEquals(
            "discover,your_record,community,your_listening,your_playlists,record_mixes,new_releases," +
                "qobuz_playlists,top_albums,made_for_you,radios,mood_decades",
            context.homeSectionsDataStore.data.first()[orderKey],
        )
        // Mixes hidden instead: the card alone, the mixes nowhere
        context.homeSectionsDataStore.edit { it[hiddenKey] = "record_mixes" }
        val visible = pref.visibleSections.first()
        assertEquals(listOf(HomeSection.DISCOVER, HomeSection.YOUR_RECORD, HomeSection.YOUR_LISTENING), visible.take(3))
        assertFalse(HomeSection.RECORD_MIXES in visible)
    }
}

package com.stash.core.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Settings > Appearance > Open Stash on (#428). */
@RunWith(RobolectricTestRunner::class)
class HomeSectionsStartTabTest {
    private lateinit var context: Context

    // The DataStore instance outlives a test, so deleting its file doesn't reset it: each test starts from an empty store.
    @Before fun setUp() = runTest {
        context = ApplicationProvider.getApplicationContext()
        context.homeSectionsDataStore.edit { it.clear() }
    }

    @Test fun `Home until set, then what was set`() = runTest {
        val pref = HomeSectionsPreference(context)
        assertEquals(StartTab.HOME, pref.startTab.first())

        pref.setStartTab(StartTab.LIBRARY)
        assertEquals(StartTab.LIBRARY, pref.startTab.first())

        pref.setStartTab(StartTab.SEARCH)
        assertEquals(StartTab.SEARCH, HomeSectionsPreference(context).startTab.first())
    }

    @Test fun `an unknown stored value reads as Home`() = runTest {
        context.homeSectionsDataStore.edit { it[stringPreferencesKey("start_tab")] = "SYNC" }
        assertEquals(StartTab.HOME, HomeSectionsPreference(context).startTab.first())
    }
}

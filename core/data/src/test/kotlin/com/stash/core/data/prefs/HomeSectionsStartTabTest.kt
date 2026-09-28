package com.stash.core.data.prefs

import android.content.Context
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

    @Before fun setUp() = runTest {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(HomeSectionsPreference.START_TAB_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun `Home until set, then what was set`() = runTest {
        val pref = HomeSectionsPreference(context)
        assertEquals(StartTab.HOME, pref.startTab.first())

        pref.setStartTab(StartTab.LIBRARY)
        assertEquals(StartTab.LIBRARY, pref.startTab.first())

        pref.setStartTab(StartTab.SEARCH)
        // A fresh instance (the next launch) reads it synchronously.
        assertEquals(StartTab.SEARCH, HomeSectionsPreference(context).startTabNow)
    }

    @Test fun `an unknown stored value reads as Home`() = runTest {
        context.getSharedPreferences(HomeSectionsPreference.START_TAB_PREFS, Context.MODE_PRIVATE).edit()
            .putString(HomeSectionsPreference.START_TAB_KEY, "SYNC").commit()
        assertEquals(StartTab.HOME, HomeSectionsPreference(context).startTab.first())
        assertEquals(StartTab.HOME, HomeSectionsPreference(context).startTabNow)
    }
}

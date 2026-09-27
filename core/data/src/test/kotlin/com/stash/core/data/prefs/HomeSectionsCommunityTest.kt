package com.stash.core.data.prefs

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
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
    private lateinit var file: File

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        file = context.preferencesDataStoreFile("home_sections_preference")
        if (file.exists()) file.delete()
    }

    @After fun tearDown() {
        if (file.exists()) file.delete()
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
}

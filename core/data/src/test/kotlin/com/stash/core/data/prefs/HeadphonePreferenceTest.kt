package com.stash.core.data.prefs

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class HeadphonePreferenceTest {

    private lateinit var context: Context
    private lateinit var prefs: HeadphonePreferencesManager
    private lateinit var file: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        file = context.preferencesDataStoreFile("headphone_preferences")
        if (file.exists()) file.delete()
        prefs = HeadphonePreferencesManager(context)
    }

    @After
    fun tearDown() {
        if (file.exists()) file.delete()
    }

    @Test
    fun defaultsToFalse() = runTest {
        assertFalse(prefs.resumeOnReconnect.first())
    }

    @Test
    fun resumeOnReconnect_roundTrips() = runTest {
        prefs.setResumeOnReconnect(true)
        assertTrue(prefs.resumeOnReconnect.first())

        prefs.setResumeOnReconnect(false)
        assertFalse(prefs.resumeOnReconnect.first())
    }
}

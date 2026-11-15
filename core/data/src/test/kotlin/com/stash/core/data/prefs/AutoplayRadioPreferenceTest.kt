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
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The DataStore delegate caches its store for the whole test JVM, so deleting the
 * file between tests does not clear it. Tests run by name, and the one that needs
 * an empty store ("a...") runs first.
 */
@RunWith(RobolectricTestRunner::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AutoplayRadioPreferenceTest {

    private lateinit var context: Context
    private lateinit var prefs: AutoplayRadioPreferencesManager
    private lateinit var file: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        file = context.preferencesDataStoreFile("autoplay_radio_preferences")
        if (file.exists()) file.delete()
        prefs = AutoplayRadioPreferencesManager(context)
    }

    @After
    fun tearDown() {
        if (file.exists()) file.delete()
    }

    @Test
    fun a_onWhenNothingStored() = runTest {
        assertTrue(prefs.enabled.first())
    }

    @Test
    fun b_aStoredOffStaysOff() = runTest {
        prefs.setEnabled(false)
        assertFalse(prefs.enabled.first())
    }

    @Test
    fun c_roundTrips() = runTest {
        prefs.setEnabled(false)
        assertFalse(prefs.enabled.first())
        prefs.setEnabled(true)
        assertTrue(prefs.enabled.first())
    }
}

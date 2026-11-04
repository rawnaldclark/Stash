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

/**
 * "Use your record in mixes" is off by default (owner 2026-11-04): nothing stored reads off, a stored on stays on (someone
 * who turned it on), a stored off stays off.
 */
@RunWith(RobolectricTestRunner::class)
class RecordInMixesPreferenceTest {
    private lateinit var context: Context
    private lateinit var preference: RecordInMixesPreferencesManager
    private lateinit var file: File

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        file = context.preferencesDataStoreFile("record_in_mixes_preference")
        if (file.exists()) file.delete()
        preference = RecordInMixesPreferencesManager(context)
    }

    @After fun tearDown() {
        if (file.exists()) file.delete()
    }

    @Test fun `never touched reads off`() = runTest {
        assertFalse(preference.current())
        assertFalse(preference.enabled.first())
    }

    @Test fun `turned on stays on, turned off stays off`() = runTest {
        preference.setEnabled(true)
        assertTrue(preference.current())

        preference.setEnabled(false)
        assertFalse(preference.current())
    }

    @Test fun `the test double starts off too`() = runTest {
        assertFalse(RecordInMixesPreference.Fixed().current())
    }
}

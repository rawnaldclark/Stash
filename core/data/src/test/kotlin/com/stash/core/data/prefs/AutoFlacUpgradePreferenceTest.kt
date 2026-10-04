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
class AutoFlacUpgradePreferenceTest {
    private lateinit var context: Context
    private lateinit var preference: AutoFlacUpgradePreference
    private lateinit var file: File

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // A DataStore file, so the settings backup (datastore/) carries the opt-in.
        file = context.preferencesDataStoreFile("auto_flac_upgrade_preference")
        if (file.exists()) file.delete()
        preference = AutoFlacUpgradePreference(context)
    }

    @After fun tearDown() {
        if (file.exists()) file.delete()
    }

    @Test fun `off until the user turns it on, then stays on`() = runTest {
        assertFalse(preference.current())

        preference.setEnabled(true)
        assertTrue(preference.current())
        assertTrue(preference.enabled.first())

        preference.setEnabled(false)
        assertFalse(preference.current())
    }
}

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

/**
 * The Tuning store against a real DataStore. Its DataStore is a process-wide singleton, so every
 * test puts standard back before it ends (as [StreamingPreferenceTest] does).
 */
@RunWith(RobolectricTestRunner::class)
class TuningPreferencesManagerTest {

    private lateinit var prefs: TuningPreferencesManager

    @Before fun setUp() {
        prefs = TuningPreferencesManager(ApplicationProvider.getApplicationContext<Context>())
    }

    @Test fun `defaults to standard 440 Hz`() = runTest {
        assertEquals(440f, prefs.referenceHz.first())
    }

    @Test fun `a reference round trips`() = runTest {
        prefs.setReferenceHz(432f)
        assertEquals(432f, prefs.referenceHz.first())
        prefs.setReferenceHz(431.5f)
        assertEquals(431.5f, prefs.referenceHz.first())
        prefs.setReferenceHz(440f)
        assertEquals(440f, prefs.referenceHz.first())
    }

    @Test fun `stores a tenth of a hertz, no finer`() = runTest {
        prefs.setReferenceHz(431.55f)
        assertEquals(431.6f, prefs.referenceHz.first())
        prefs.setReferenceHz(440f)
    }

    @Test fun `clamps to 392 to 494 Hz`() = runTest {
        prefs.setReferenceHz(1000f)
        assertEquals(494f, prefs.referenceHz.first())
        prefs.setReferenceHz(100f)
        assertEquals(392f, prefs.referenceHz.first())
        prefs.setReferenceHz(440f)
    }

    @Test fun `a value that is not a number stores standard`() = runTest {
        prefs.setReferenceHz(432f)
        prefs.setReferenceHz(Float.NaN)
        assertEquals(440f, prefs.referenceHz.first())
    }
}

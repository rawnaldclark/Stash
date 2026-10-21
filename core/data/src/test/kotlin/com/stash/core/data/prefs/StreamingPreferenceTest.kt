package com.stash.core.data.prefs

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
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
import java.io.File

/**
 * Unit tests for [StreamingPreference].
 *
 * The preference wraps a dedicated DataStore for the online-streaming
 * engine toggle, cellular-allow toggle, and stream-quality tier. Each
 * test deletes the underlying file so runs stay isolated.
 */
@RunWith(RobolectricTestRunner::class)
class StreamingPreferenceTest {

    private lateinit var context: Context
    private lateinit var prefs: StreamingPreference
    private lateinit var file: File

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        file = context.preferencesDataStoreFile("streaming_preference")
        // Make sure no stale file from a prior run leaks in.
        if (file.exists()) file.delete()
        prefs = StreamingPreference(context)
    }

    @After fun tearDown() {
        if (file.exists()) file.delete()
    }

    @Test fun enabled_defaultsToFalse() = runTest {
        assertFalse(prefs.enabled.first())
    }

    @Test fun enabled_roundTrips() = runTest {
        prefs.setEnabled(true)
        assertTrue(prefs.enabled.first())
        prefs.setEnabled(false)
        assertFalse(prefs.enabled.first())
    }

    @Test fun streamOnCellular_defaultsToFalse() = runTest {
        assertFalse(prefs.streamOnCellular.first())
    }

    @Test fun streamOnCellular_roundTrips() = runTest {
        prefs.setStreamOnCellular(true)
        assertTrue(prefs.streamOnCellular.first())
        prefs.setStreamOnCellular(false)
        assertFalse(prefs.streamOnCellular.first())
    }

    @Test fun streamQuality_defaultsToLossless() = runTest {
        assertEquals(StreamQualityTier.LOSSLESS, prefs.streamQuality.first())
    }

    @Test fun streamQuality_roundTrips() = runTest {
        prefs.setStreamQuality(StreamQualityTier.HIGH_QUALITY_LOSSY)
        assertEquals(StreamQualityTier.HIGH_QUALITY_LOSSY, prefs.streamQuality.first())
        prefs.setStreamQuality(StreamQualityTier.LOSSLESS)
        assertEquals(StreamQualityTier.LOSSLESS, prefs.streamQuality.first())
    }

    @Test fun forceYouTubeFallback_defaultsToFalse() = runTest {
        assertFalse(prefs.forceYouTubeFallback.first())
        assertFalse(prefs.isForceYouTubeFallback())
    }

    @Test fun forceYouTubeFallback_roundTrips() = runTest {
        prefs.setForceYouTubeFallback(true)
        assertTrue(prefs.forceYouTubeFallback.first())
        assertTrue(prefs.isForceYouTubeFallback())
        prefs.setForceYouTubeFallback(false)
        assertFalse(prefs.forceYouTubeFallback.first())
        assertFalse(prefs.isForceYouTubeFallback())
    }

    // ── Developer force toggles are inert outside debuggable builds ─────────
    //
    // #429: a user enabled force-Qobuz back when it was a visible control, the
    // row later became debug-only, and the PREF outlived its UI — honored by
    // both registries on a release build, invisible, with no way to turn it
    // off. Fourth incident of this class (force-YT release install, a force
    // toggle on a parked source). The accessors now enforce the same contract the Settings UI
    // declares: developer instruments exist only on debuggable builds.
    // Force-YouTube is a USER-FACING recovery control and stays live.

    // The preferencesDataStore delegate is a per-process singleton, so writes
    // leak across tests unless undone (see the reset calls in the existing
    // round-trip tests). Each test below clears what it sets.

    @Test fun devForceToggles_ignoredOnNonDebuggableBuilds() = runTest {
        prefs.devInstrumentsEnabled = false
        prefs.setForceQbdlxOnly(true)

        assertFalse(prefs.isForceQbdlxOnly())
        // The raw flow still reports the stored value — the debug Settings UI
        // (hidden on release) must reflect reality, and nothing is deleted.
        assertTrue(prefs.forceQbdlxOnly.first())

        prefs.setForceQbdlxOnly(false)
    }

    @Test fun devForceToggles_honoredOnDebuggableBuilds() = runTest {
        prefs.devInstrumentsEnabled = true
        prefs.setForceQbdlxOnly(true)
        assertTrue(prefs.isForceQbdlxOnly())
        prefs.setForceQbdlxOnly(false)
        assertFalse(prefs.isForceQbdlxOnly())
    }

    @Test fun skipQobuz_defaultsOff_andIsIgnoredOnNonDebuggableBuilds() = runTest {
        prefs.devInstrumentsEnabled = true
        assertFalse(prefs.isSkipQobuz())             // default OFF

        prefs.devInstrumentsEnabled = false          // a release install
        prefs.setSkipQobuz(true)
        assertFalse(prefs.isSkipQobuz())
        assertTrue(prefs.skipQobuz.first())          // stored, just not honored

        prefs.setSkipQobuz(false)
    }

    @Test fun skipQobuz_honoredOnDebuggableBuilds() = runTest {
        prefs.devInstrumentsEnabled = true
        prefs.setSkipQobuz(true)
        assertTrue(prefs.isSkipQobuz())
        prefs.setSkipQobuz(false)
        assertFalse(prefs.isSkipQobuz())
    }

    @Test fun forceYouTubeFallback_staysHonoredRegardlessOfBuildType() = runTest {
        // "Stream via YouTube" is a visible user setting, not a dev instrument:
        // the user can always see and clear it, and its failure mode is safe.
        prefs.devInstrumentsEnabled = false
        prefs.setForceYouTubeFallback(true)
        assertTrue(prefs.isForceYouTubeFallback())
        prefs.setForceYouTubeFallback(false)
    }

    @Test fun current_returnsLatestValue() = runTest {
        assertFalse(prefs.current())
        prefs.setEnabled(true)
        assertTrue(prefs.current())
        prefs.setEnabled(false)
        assertFalse(prefs.current())
    }
}

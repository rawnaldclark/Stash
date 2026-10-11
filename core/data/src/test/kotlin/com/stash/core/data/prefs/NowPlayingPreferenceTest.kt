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
class NowPlayingPreferenceTest {
    private lateinit var context: Context
    private lateinit var preference: NowPlayingPreference
    private lateinit var file: File

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        file = context.preferencesDataStoreFile("now_playing_preference")
        if (file.exists()) file.delete()
        preference = NowPlayingPreference(context)
    }

    @After fun tearDown() {
        // The preferencesDataStore delegate is one instance per process, so deleting the file does
        // not reset what it holds in memory: put the pins back for whichever test runs next.
        kotlinx.coroutines.runBlocking { preference.resetPinnedActions() }
        if (file.exists()) file.delete()
    }

    @Test fun ambientAnimation_defaultsToTrue() = runTest {
        assertTrue(preference.ambientAnimationEnabled.first())
    }

    @Test fun ambientStyle_defaultsToSoftGlow() = runTest {
        org.junit.Assert.assertEquals(AmbientStyle.SOFT_GLOW, preference.ambientStyle.first())
    }

    @Test fun ambientStyle_persistsClassicAndBack() = runTest {
        preference.setAmbientStyle(AmbientStyle.CLASSIC)
        org.junit.Assert.assertEquals(AmbientStyle.CLASSIC, preference.ambientStyle.first())
        preference.setAmbientStyle(AmbientStyle.SOFT_GLOW)
        org.junit.Assert.assertEquals(AmbientStyle.SOFT_GLOW, preference.ambientStyle.first())
    }

    @Test fun pinnedActions_defaultToRadioAndQueue() = runTest {
        org.junit.Assert.assertEquals(NowPlayingAction.DefaultPins, preference.pinnedActions.first())
    }

    @Test fun pinnedActions_persistIncludingEmpty() = runTest {
        val pins = setOf(NowPlayingAction.RADIO, NowPlayingAction.LYRICS, NowPlayingAction.SHARE)
        preference.setPinnedActions(pins)
        org.junit.Assert.assertEquals(pins, preference.pinnedActions.first())
        // Unpinning everything is a real choice, not "unset": it must not fall back to the default.
        preference.setPinnedActions(emptySet())
        org.junit.Assert.assertEquals(emptySet<NowPlayingAction>(), preference.pinnedActions.first())
    }

    @Test fun pinnedActions_resetRestoresTheDefault() = runTest {
        preference.setPinnedActions(setOf(NowPlayingAction.SHARE))
        preference.resetPinnedActions()
        org.junit.Assert.assertEquals(NowPlayingAction.DefaultPins, preference.pinnedActions.first())
    }

    @Test fun ambientAnimation_persistsFalseAndTrue() = runTest {
        preference.setAmbientAnimationEnabled(false)
        assertFalse(preference.ambientAnimationEnabled.first())
        preference.setAmbientAnimationEnabled(true)
        assertTrue(preference.ambientAnimationEnabled.first())
    }
}

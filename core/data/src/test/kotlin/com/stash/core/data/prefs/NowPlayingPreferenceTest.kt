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

    @Test fun ambientAnimation_persistsFalseAndTrue() = runTest {
        preference.setAmbientAnimationEnabled(false)
        assertFalse(preference.ambientAnimationEnabled.first())
        preference.setAmbientAnimationEnabled(true)
        assertTrue(preference.ambientAnimationEnabled.first())
    }
}

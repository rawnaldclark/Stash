package com.stash.core.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Settings › Appearance › Display size: what the store and the launch snapshot hold. */
@RunWith(RobolectricTestRunner::class)
class ThemeUiScaleTest {
    private lateinit var context: Context
    private lateinit var preference: ThemePreferencesManager

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(ThemeSnapshot.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        preference = ThemePreferencesManager(context)
    }

    @After fun tearDown() {
        // The preferencesDataStore delegate is one instance per process: put the default back
        // for whichever test runs next.
        runBlocking { preference.setUiScale(1f) }
    }

    @Test fun `the store snaps what it is given to the grid and range`() = runTest {
        assertEquals(1f, preference.uiScale.first())

        preference.setUiScale(1.27f)
        assertEquals(1.25f, preference.uiScale.first())

        preference.setUiScale(0.8f) // the floor before 0.85
        assertEquals(0.85f, preference.uiScale.first())

        preference.setUiScale(9f)
        assertEquals(1.6f, preference.uiScale.first())
    }

    @Test fun `pure black is on until someone turns it off`() = runTest {
        assertEquals(true, preference.amoledDark.first())
    }

    @Test fun `the synchronous read is the launch snapshot, defaulting to 1`() {
        assertEquals(1f, preference.uiScaleNow)

        ThemeSnapshot.writeUiScale(context, 1.3f)
        assertEquals(1.3f, preference.uiScaleNow)
    }

    @Test fun `a corrupt snapshot reads as a usable scale`() {
        context.getSharedPreferences(ThemeSnapshot.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putFloat("ui_scale", Float.NaN).commit()
        assertEquals(1f, ThemeSnapshot.readUiScale(context))

        context.getSharedPreferences(ThemeSnapshot.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putFloat("ui_scale", 0.2f).commit()
        assertEquals(0.85f, ThemeSnapshot.readUiScale(context))
    }
}

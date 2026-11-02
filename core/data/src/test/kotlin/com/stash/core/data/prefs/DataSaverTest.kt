package com.stash.core.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [DataSaver] applies only with the switch on AND a metered network; [DataSaverSnapshot] mirrors the
 * switch and reports a real change only.
 */
@RunWith(RobolectricTestRunner::class)
class DataSaverTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clear() {
        context.getSharedPreferences(DataSaverSnapshot.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `applies only with the switch on and a metered network`() {
        for (on in listOf(false, true)) {
            for (metered in listOf(false, true)) {
                val saver = DataSaver(switchOn = { on }, metered = { metered })
                assertEquals("on=$on metered=$metered", on && metered, saver.appliesNow())
                assertEquals("on=$on", on, saver.isOn)
            }
        }
    }

    @Test
    fun `read at each decision - walking onto Wi-Fi lifts it at once`() {
        var metered = true
        val saver = DataSaver(switchOn = { true }, metered = { metered })
        assertTrue(saver.appliesNow())
        metered = false
        assertFalse(saver.appliesNow())
    }

    @Test
    fun `Off never applies`() {
        assertFalse(DataSaver.Off.appliesNow())
        assertFalse(DataSaver.Off.isOn)
    }

    @Test
    fun `the mirror is off on a fresh install and mirroring off then is not a change`() {
        assertFalse(DataSaverSnapshot.isOn(context))
        assertFalse(DataSaverSnapshot.write(context, false))
        assertFalse(DataSaverSnapshot.isOn(context))
    }

    @Test
    fun `the mirror follows the switch and reports each flip once`() {
        assertTrue(DataSaverSnapshot.write(context, true))
        assertTrue(DataSaverSnapshot.isOn(context))
        assertFalse("same value again is no change", DataSaverSnapshot.write(context, true))
        assertTrue(DataSaverSnapshot.write(context, false))
        assertFalse(DataSaverSnapshot.isOn(context))
    }

    @Test
    fun `the injected DataSaver reads the mirror`() {
        DataSaverSnapshot.write(context, true)
        assertTrue(DataSaver(context).isOn)
        DataSaverSnapshot.write(context, false)
        assertFalse(DataSaver(context).isOn)
        assertFalse("off never applies, whatever the network", DataSaver(context).appliesNow())
    }
}

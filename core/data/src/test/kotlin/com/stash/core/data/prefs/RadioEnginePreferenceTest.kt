package com.stash.core.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.radio.RadioEngineId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The DataStore delegate caches its store for the whole test JVM, so deleting the
 * file between tests does not clear it. Tests run by name, and the one that needs
 * an empty store ("a...") runs first.
 */
@RunWith(RobolectricTestRunner::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class RadioEnginePreferenceTest {

    private lateinit var context: Context
    private lateinit var prefs: RadioEnginePreferencesManager
    private lateinit var file: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        file = context.preferencesDataStoreFile("radio_engine_preference")
        if (file.exists()) file.delete()
        prefs = RadioEnginePreferencesManager(context)
    }

    @After
    fun tearDown() {
        if (file.exists()) file.delete()
    }

    /** The Stash engine by default: the second engine ships opt-in, not as a replacement. */
    @Test
    fun a_stashWhenNothingStored() = runTest {
        assertEquals(RadioEngineId.STASH, prefs.engine.first())
        assertEquals(RadioEngineId.STASH, prefs.current())
    }

    @Test
    fun b_roundTrips() = runTest {
        prefs.setEngine(RadioEngineId.STASH)
        assertEquals(RadioEngineId.STASH, prefs.current())
        prefs.setEngine(RadioEngineId.YOUTUBE_MUSIC)
        assertEquals(RadioEngineId.YOUTUBE_MUSIC, prefs.current())
    }

    /**
     * A stored key this build doesn't know (a downgrade, a hand-edited store) reads as
     * the default instead of throwing: this preference sits in front of every station.
     */
    @Test
    fun c_anUnknownStoredKeyReadsAsTheDefault() = runTest {
        context.radioEngineDataStore.edit { it[stringPreferencesKey("radio_engine")] = "engine_from_the_future" }
        assertEquals(RadioEnginePreferencesManager.DEFAULT, prefs.current())
    }
}

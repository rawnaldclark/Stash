package com.stash.core.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * "Use your record in mixes" is off by default (owner 2026-11-04): nothing stored reads off, a stored on stays on (someone
 * who turned it on), a stored off stays off.
 *
 * A new store on a new file per test, so "never touched" really is: the app's own store is a process-wide singleton, which
 * remembers what an earlier test wrote even after its file is deleted. Checked by setting [RecordInMixesPreferencesManager.DEFAULT]
 * to true: `never touched reads off` then fails.
 */
class RecordInMixesPreferenceTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var store: DataStore<Preferences>
    private lateinit var preference: RecordInMixesPreferencesManager

    @Before fun setUp() {
        val file = folder.newFile("record_in_mixes_preference.preferences_pb").also { it.delete() }
        store = PreferenceDataStoreFactory.create { file }
        preference = RecordInMixesPreferencesManager(store)
    }

    @Test fun `never touched reads off`() = runBlocking {
        assertFalse(preference.current())
        assertFalse(preference.enabled.first())
    }

    @Test fun `turned on stays on, turned off stays off`() = runBlocking {
        preference.setEnabled(true)
        assertTrue(preference.current())

        preference.setEnabled(false)
        assertFalse(preference.current())
    }

    /** What's on a phone already: someone who turned it on before the default changed keeps it on. */
    @Test fun `a value stored before the update is kept`() = runBlocking {
        store.edit { it[booleanPreferencesKey("record_in_mixes")] = true }
        assertTrue(preference.current())

        store.edit { it[booleanPreferencesKey("record_in_mixes")] = false }
        assertFalse(preference.current())
    }

    @Test fun `the test double starts off too`() = runBlocking {
        assertFalse(RecordInMixesPreference.Fixed().current())
    }
}

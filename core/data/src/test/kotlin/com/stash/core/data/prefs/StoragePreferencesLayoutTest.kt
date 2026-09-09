package com.stash.core.data.prefs

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Round-trip coverage for the folder-structure preference (#198/#104):
 * defaults to Artist/Album, persists every layout key, and tolerates
 * unknown/corrupt keys by falling back to the default instead of crashing.
 */
@RunWith(RobolectricTestRunner::class)
class StoragePreferencesLayoutTest {
    private lateinit var context: Context
    private lateinit var manager: StoragePreferencesManager
    private lateinit var file: File

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        file = context.preferencesDataStoreFile("storage_preferences")
        if (file.exists()) file.delete()
        manager = StoragePreferencesManager(context)
    }

    @After fun tearDown() {
        if (file.exists()) file.delete()
    }

    @Test fun libraryLayout_defaultsToArtistAlbum() = runTest {
        assertEquals(LibraryLayout.DEFAULT, manager.libraryLayout.first())
    }

    @Test fun libraryLayout_persistsEveryLayout() = runTest {
        for (layout in LibraryLayout.entries) {
            manager.setLibraryLayout(layout)
            assertEquals(layout, manager.libraryLayout.first())
        }
    }

    @Test fun fromKey_unknownOrNullFallsBackToDefault() {
        assertEquals(LibraryLayout.DEFAULT, LibraryLayout.fromKey(null))
        assertEquals(LibraryLayout.DEFAULT, LibraryLayout.fromKey("bogus_layout"))
        // Every real key round-trips.
        for (layout in LibraryLayout.entries) {
            assertEquals(layout, LibraryLayout.fromKey(layout.key))
        }
    }
}

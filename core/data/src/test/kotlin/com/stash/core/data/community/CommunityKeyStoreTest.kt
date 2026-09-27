package com.stash.core.data.community

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CommunityKeyStoreTest {
    private lateinit var context: Context
    private lateinit var file: File

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        file = context.preferencesDataStoreFile("community_preference")
        if (file.exists()) file.delete()
    }

    @After fun tearDown() {
        if (file.exists()) file.delete()
    }

    @Test fun `new keys are 43 base64url characters, the format the Worker accepts`() {
        val keys = List(20) { newCommunityKey() }
        keys.forEach { assertThat(it).matches("[A-Za-z0-9_-]{43}") }
        assertThat(keys.toSet()).hasSize(20)
    }

    // One test for the whole life of the key: the DataStore instance outlives a single test.
    @Test fun `the key is made on first use and reused after, and reads never make one`() = runTest {
        val store = CommunityKeyStore(context)
        assertThat(store.existingKey()).isNull()
        // Separate instances: only DataStore's one-at-a-time edits can make them agree.
        val keys = List(8) { async(Dispatchers.IO) { CommunityKeyStore(context).key() } }.awaitAll()
        assertThat(keys.toSet()).hasSize(1)
        val key = keys[0]
        assertThat(store.key()).isEqualTo(key)
        assertThat(store.existingKey()).isEqualTo(key)
        assertThat(CommunityKeyStore(context).key()).isEqualTo(key)
    }
}

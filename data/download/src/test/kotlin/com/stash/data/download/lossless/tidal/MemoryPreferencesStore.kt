package com.stash.data.download.lossless.tidal

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import java.io.IOException

/**
 * A preferences store in memory, for tests that drive [TidalDebugSettings] with the test's own
 * dispatcher: no file, no thread of its own, nothing to wait for in real time. A real store's file
 * work ran on `Dispatchers.IO`, and a test that waited 5 s for it timed out on a busy CI runner
 * (review-888 round 2).
 *
 * [failReads]: the next that many reads of [data] fail, as an unreadable file does.
 */
internal class MemoryPreferencesStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
    /** What the store holds now. */
    val values = MutableStateFlow(initial)

    /** Reads of [data] still to fail. */
    @Volatile var failReads = 0

    /** How many times [data] was read (collected). */
    @Volatile var reads = 0
        private set

    override val data: Flow<Preferences> = flow {
        reads++
        if (failReads > 0) {
            failReads--
            throw IOException("store unreadable")
        }
        emitAll(values)
    }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val next = transform(values.value)
        values.value = next
        return next
    }
}

package com.stash.core.data.weblink.handoff

import com.stash.core.data.weblink.FakeSyncServer
import com.stash.core.data.weblink.WebLinkRepository
import com.stash.core.data.weblink.store.LinkIdentity
import com.stash.core.data.weblink.store.LinkStoreUnavailable
import com.stash.core.data.weblink.store.LinkedSpace
import com.stash.core.data.weblink.store.RosterEntry
import com.stash.core.data.weblink.store.WebLinkStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phase 4 review B3: a passing Keystore or database failure makes the store throw [LinkStoreUnavailable] (Phase 3 S4). The
 * handoff calls run from the playback service and the app's foreground, so they must turn it into "offline" / "nothing",
 * never an exception (which would crash the app mid-song).
 */
class HandoffSyncStoreFailureTest {
    private class BrokenStore : WebLinkStore {
        private fun fail(): Nothing = throw LinkStoreUnavailable(IllegalStateException("keystore2 busy"))
        override suspend fun identity(): LinkIdentity? = fail()
        override suspend fun createIdentity(name: String): LinkIdentity = fail()
        override suspend fun setName(name: String) = fail()
        override suspend fun space(): LinkedSpace? = fail()
        override suspend fun saveSpace(space: LinkedSpace) = fail()
        override suspend fun roster(): List<RosterEntry> = fail()
        override suspend fun saveRoster(entries: List<RosterEntry>) = fail()
        override suspend fun wipe() = fail()
    }

    private val store = BrokenStore()
    private val api = FakeSyncServer()
    private val sync = HandoffSync(api, store, WebLinkRepository(api, store))

    private val now = StashNow(true, 1_000, 1.0, 0, "q_abc", WireSong("Song", "Artist"), false, HandoffRepeat.OFF)

    @Test
    fun `every call survives a store that throws`() = runTest {
        assertFalse(sync.linked())
        assertNull(sync.scope())
        assertEquals(PublishOutcome.Offline, sync.publish(now, null))
        assertNull(sync.readNow())
        assertNull(sync.readQueue("d_web"))
    }
}

package com.stash.core.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.auth.soundcloud.SoundCloudAccountManager
import com.stash.core.auth.soundcloud.SoundCloudSession
import com.stash.core.auth.store.EncryptedTokenStore
import com.stash.core.data.db.StashDatabase
import com.stash.data.soundcloud.SoundCloudClient
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SoundCloudLikesFetcherTest {
    @Test fun `disconnect during fetch publishes no snapshot`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java).build()
        try {
            val session = SoundCloudSession("7", "Public", connectionId = "current")
            val stored = MutableStateFlow<SoundCloudSession?>(session)
            val store = mockk<EncryptedTokenStore> {
                every { soundCloudSession } returns stored
                coEvery { clearSoundCloud() } answers { stored.value = null }
            }
            val client = mockk<SoundCloudClient>()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            coEvery { client.likes("7", null) } coAnswers {
                started.complete(Unit); release.await(); emptyList()
            }
            val accounts = SoundCloudAccountManager(store, client)
            val fetcher = SoundCloudLikesFetcher(client, accounts, db)
            val result = async { fetcher.fetch(1, session) }
            started.await()
            accounts.disconnect()
            release.complete(Unit)
            assertFalse(result.await())
            assertTrue(db.remoteSnapshotDao().getPlaylistSnapshotsBySyncId(1).isEmpty())
        } finally { db.close() }
    }

    @Test fun `failed likes fetch does not publish an empty playlist`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java).build()
        try {
            val client = mockk<SoundCloudClient>()
            coEvery { client.likes(any(), any()) } throws java.io.IOException("page failed")
            val fetcher = SoundCloudLikesFetcher(client, mockk(relaxed = true), db)
            try {
                fetcher.fetch(1, SoundCloudSession("7", "Public", connectionId = "current"))
                fail("fetch failure swallowed")
            } catch (_: java.io.IOException) { }
            assertTrue(db.remoteSnapshotDao().getPlaylistSnapshotsBySyncId(1).isEmpty())
        } finally { db.close() }
    }
}

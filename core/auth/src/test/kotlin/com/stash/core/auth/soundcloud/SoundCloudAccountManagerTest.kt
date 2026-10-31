package com.stash.core.auth.soundcloud

import com.stash.core.auth.store.EncryptedTokenStore
import com.stash.data.soundcloud.SoundCloudClient
import com.stash.data.soundcloud.SoundCloudUser
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class SoundCloudAccountManagerTest {
    @Test fun `simultaneous token requests share one rotation`() = runTest {
        stored.value = old.copy(refreshToken = "refresh-old")
        every { client.tokenExpiresAt("private-secret") } returns 0L
        every { client.tokenExpiresAt("fresh") } returns Long.MAX_VALUE
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        coEvery { client.refreshCredentials(any(), any()) } coAnswers {
            started.complete(Unit); release.await(); com.stash.data.soundcloud.SoundCloudCredentials("fresh", "rotated")
        }
        coEvery { client.authenticatedUser("fresh") } returns SoundCloudUser("1", "Old", null)
        val first = async { manager.accessToken() }; val second = async { manager.accessToken() }
        started.await(); release.complete(Unit)
        assertEquals("fresh", first.await()); assertEquals("fresh", second.await())
        io.mockk.coVerify(exactly = 1) { client.refreshCredentials(any(), any()) }
    }

    @Test fun `cancelling playback does not lose a rotated credential`() = runTest {
        stored.value = old.copy(refreshToken = "refresh-old")
        every { client.tokenExpiresAt(any()) } returns 0L
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val saved = CompletableDeferred<Unit>()
        coEvery { client.refreshCredentials(any(), any()) } coAnswers {
            started.complete(Unit); release.await(); com.stash.data.soundcloud.SoundCloudCredentials("fresh", "rotated")
        }
        coEvery { client.authenticatedUser("fresh") } returns SoundCloudUser("1", "Old", null)
        coEvery { store.saveSoundCloudSession(any()) } answers { stored.value = firstArg(); saved.complete(Unit); Unit }
        val result = async { manager.accessToken() }
        started.await(); result.cancel(); release.complete(Unit); saved.await(); result.join()
        assertEquals("rotated", stored.value!!.refreshToken)
    }
    @Test fun `expired credentials rotate atomically without changing account generation`() = runTest {
        stored.value = old.copy(refreshToken = "refresh-old")
        every { client.tokenExpiresAt(any()) } returns 0L
        coEvery { client.refreshCredentials("private-secret", "refresh-old") } returns com.stash.data.soundcloud.SoundCloudCredentials("fresh", "refresh-new")
        coEvery { client.authenticatedUser("fresh") } returns SoundCloudUser("1", "Old", null)
        assertEquals("fresh", manager.accessToken())
        assertEquals("old", stored.value!!.connectionId)
        assertEquals("refresh-new", stored.value!!.refreshToken)
    }

    @Test fun `upgrade adopts a matching browser refresh credential`() = runTest {
        val browser = mockk<SoundCloudBrowserSession>(relaxed = true)
        coEvery { browser.refreshToken("private-secret") } returns "browser-refresh"
        every { client.tokenExpiresAt(any()) } returns 0L
        coEvery { client.refreshCredentials("private-secret", "browser-refresh") } returns com.stash.data.soundcloud.SoundCloudCredentials("fresh", "rotated")
        coEvery { client.authenticatedUser("fresh") } returns SoundCloudUser("1", "Old", null)
        assertEquals("fresh", SoundCloudAccountManager(store, client, browser).accessToken())
        assertEquals("rotated", stored.value!!.refreshToken)
    }

    @Test fun `refresh cannot replace a disconnected account`() = runTest {
        stored.value = old.copy(refreshToken = "refresh-old")
        every { client.tokenExpiresAt(any()) } returns 0L
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        coEvery { client.refreshCredentials(any(), any()) } coAnswers {
            started.complete(Unit); release.await(); com.stash.data.soundcloud.SoundCloudCredentials("fresh", "rotated")
        }
        coEvery { client.authenticatedUser("fresh") } returns SoundCloudUser("1", "Old", null)
        val result = async { manager.accessToken() }
        started.await(); manager.disconnect(); release.complete(Unit)
        assertNull(result.await()); assertNull(stored.value)
    }

    @Test fun `another accounts refresh response never replaces the current session`() = runTest {
        stored.value = old.copy(refreshToken = "refresh-old")
        every { client.tokenExpiresAt(any()) } returns 0L
        coEvery { client.refreshCredentials(any(), any()) } returns com.stash.data.soundcloud.SoundCloudCredentials("fresh", "rotated")
        coEvery { client.authenticatedUser("fresh") } returns SoundCloudUser("2", "Other", null)
        try { manager.accessToken(); fail("Wrong account accepted") } catch (_: IllegalStateException) { }
        assertEquals("private-secret", stored.value!!.oauthToken)
    }

    @Test fun `unexpired credentials do not request rotation`() = runTest {
        every { client.tokenExpiresAt(any()) } returns Long.MAX_VALUE
        assertEquals("private-secret", manager.accessToken())
        io.mockk.coVerify(exactly = 0) { client.refreshCredentials(any(), any()) }
    }

    private val old = SoundCloudSession("1", "Old", oauthToken = "private-secret", connectionId = "old")
    private val stored = MutableStateFlow<SoundCloudSession?>(old)
    private val store = mockk<EncryptedTokenStore> {
        every { soundCloudSession } returns stored
        coEvery { saveSoundCloudSession(any()) } answers { stored.value = firstArg() }
        coEvery { clearSoundCloud() } answers { stored.value = null }
    }
    private val client = mockk<SoundCloudClient>()
    private val manager = SoundCloudAccountManager(store, client)

    @Test fun `disconnect invalidates a pending connection`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { client.validateToken("new") } coAnswers {
            started.complete(Unit)
            release.await()
            SoundCloudUser("2", "New", null)
        }
        val pending = async { manager.connect("new") }
        started.await()
        manager.disconnect()
        release.complete(Unit)
        assertFalse(pending.await())
        assertNull(stored.value)
    }

    @Test fun `later connection wins when earlier validation finishes last`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { client.validateToken("slow") } coAnswers {
            started.complete(Unit)
            release.await()
            SoundCloudUser("2", "Slow", null)
        }
        coEvery { client.validateToken("fast") } returns SoundCloudUser("3", "Fast", null)
        val slow = async { manager.connect("slow") }
        started.await()
        assertTrue(manager.connect("fast"))
        release.complete(Unit)
        assertFalse(slow.await())
        assertEquals("3", stored.value?.userId)
        assertEquals("fast", stored.value?.oauthToken)
        assertNotEquals("old", stored.value?.connectionId)
    }

    @Test fun `failed validation preserves working account`() = runTest {
        coEvery { client.validateToken(any()) } throws java.io.IOException("offline")
        assertFalse(manager.connect("new"))
        assertEquals(old, stored.value)
    }

    @Test fun `cancelled validation propagates without replacing account`() = runTest {
        coEvery { client.validateToken(any()) } throws CancellationException()
        try { manager.connect("new"); fail("cancellation swallowed") } catch (_: CancellationException) { }
        assertEquals(old, stored.value)
    }

    @Test fun `public profile replaces credential with explicit tokenless session`() = runTest {
        val profile = Json.parseToJsonElement("""{"id":8,"username":"Public"}""").jsonObject
        coEvery { client.resolve(any(), null) } returns profile
        every { client.user(profile) } returns SoundCloudUser("8", "Public", null)
        assertTrue(manager.connectPublicProfile("https://soundcloud.com/public/likes"))
        assertEquals("8", stored.value?.userId)
        assertNull(stored.value?.oauthToken)
    }

    @Test fun `old connection cannot commit a fetched snapshot after disconnect`() = runTest {
        manager.disconnect()
        var committed = false
        manager.withCurrentSession("old") { committed = true }
        assertFalse(committed)
    }

    @Test fun `session diagnostics never print credentials`() {
        assertFalse(old.toString().contains("private-secret"))
    }
}

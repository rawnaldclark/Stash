package com.stash.core.auth

import com.stash.core.auth.model.ServiceToken
import com.stash.core.auth.model.UserInfo
import com.stash.core.auth.spotify.SpotifyAuthManager
import com.stash.core.auth.store.EncryptedTokenStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Near expiry a sync, the auto-save scrobbler and like mirroring all ask for a
 * Spotify token at once. Each used to refresh on its own: N hits on the
 * TOTP-guarded, rate-limited token endpoint and N store writes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TokenManagerRefreshSingleFlightTest {

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `concurrent callers near expiry share one refresh`() = runTest {
        val now = Instant.now().epochSecond
        val stored = MutableStateFlow<ServiceToken?>(ServiceToken("old", "sp_dc", expiresAtEpoch = now - 1))
        val store = mockk<EncryptedTokenStore>(relaxed = true) {
            every { spotifyToken } returns stored
            every { spotifyUser } returns MutableStateFlow(UserInfo("me", "Me"))
            every { youTubeToken } returns MutableStateFlow(null)
            every { youTubeUser } returns MutableStateFlow(null)
            every { discordToken } returns MutableStateFlow(null)
            every { discordUser } returns MutableStateFlow(null)
            coEvery { saveSpotifyToken(any(), any()) } answers { stored.value = firstArg() }
        }
        val gate = CompletableDeferred<Unit>()
        val auth = mockk<SpotifyAuthManager> {
            coEvery { refreshAccessToken("sp_dc") } coAnswers {
                gate.await()
                ServiceToken("new", "sp_dc", expiresAtEpoch = now + 3_600)
            }
        }
        val manager = TokenManagerImpl(store, auth, mockk(relaxed = true), mockk(relaxed = true))

        val callers = List(3) { async { manager.getSpotifyAccessToken() } }
        advanceUntilIdle()
        gate.complete(Unit)

        assertEquals(listOf("new", "new", "new"), callers.awaitAll())
        coVerify(exactly = 1) { auth.refreshAccessToken(any()) }
        coVerify(exactly = 1) { store.saveSpotifyToken(any(), any()) }
    }
}

package com.stash.core.data.sync.auth

import com.stash.core.auth.spotify.SpotifyAccounts
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyAuthHealthProbeTest {

    private val tokenManager: SpotifyAccounts = mockk {
        coEvery { rememberSpotifyAccount() } returns Unit
    }
    private val probe = SpotifyAuthHealthProbe(tokenManager)

    @Test fun `returns true when getSpotifyAccessToken returns null`() = runTest {
        // null while the worker has already gated on Connected means the
        // sp_dc refresh failed — Spotify returned anonymous, cookie is bad.
        coEvery { tokenManager.getSpotifyAccessToken() } returns null
        assertTrue(probe.isExpired())
    }

    @Test fun `returns false when getSpotifyAccessToken returns a token`() = runTest {
        coEvery { tokenManager.getSpotifyAccessToken() } returns "BQA-real-looking-token"
        assertFalse(probe.isExpired())
    }

    @Test fun `returns false when network throws (conservative)`() = runTest {
        coEvery { tokenManager.getSpotifyAccessToken() } throws RuntimeException("network down")
        assertFalse(probe.isExpired())
    }

    @Test fun `propagates CancellationException for structured concurrency`() = runTest {
        coEvery {
            tokenManager.getSpotifyAccessToken()
        } throws kotlinx.coroutines.CancellationException("cancelled")
        val thrown = runCatching { probe.isExpired() }.exceptionOrNull()
        assertTrue(thrown is kotlinx.coroutines.CancellationException)
    }

    // -- Learning whose account it is (the account-swap guard needs it later) --

    @Test fun `a working session records whose account it is`() = runTest {
        coEvery { tokenManager.getSpotifyAccessToken() } returns "BQA-real-looking-token"

        probe.isExpired()

        coVerify(exactly = 1) { tokenManager.rememberSpotifyAccount() }
    }

    @Test fun `an expired or unreachable session doesn't try to`() = runTest {
        coEvery { tokenManager.getSpotifyAccessToken() } returns null
        probe.isExpired()
        coEvery { tokenManager.getSpotifyAccessToken() } throws RuntimeException("network down")
        probe.isExpired()

        coVerify(exactly = 0) { tokenManager.rememberSpotifyAccount() }
    }

    @Test fun `a failed lookup never changes the verdict`() = runTest {
        coEvery { tokenManager.getSpotifyAccessToken() } returns "BQA-real-looking-token"
        coEvery { tokenManager.rememberSpotifyAccount() } throws RuntimeException("lookup failed")

        assertFalse(probe.isExpired())
    }

    @Test fun `a cancelled lookup still cancels`() = runTest {
        coEvery { tokenManager.getSpotifyAccessToken() } returns "BQA-real-looking-token"
        coEvery { tokenManager.rememberSpotifyAccount() } throws kotlinx.coroutines.CancellationException("cancelled")

        val thrown = runCatching { probe.isExpired() }.exceptionOrNull()
        assertTrue(thrown is kotlinx.coroutines.CancellationException)
    }
}

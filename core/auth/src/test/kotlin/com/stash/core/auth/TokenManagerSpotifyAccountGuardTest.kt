package com.stash.core.auth

import com.stash.core.auth.model.ServiceToken
import com.stash.core.auth.model.SpotifyConnectResult
import com.stash.core.auth.model.UserInfo
import com.stash.core.auth.spotify.SpotifyAuthManager
import com.stash.core.auth.spotify.SpotifyProfile
import com.stash.core.auth.store.EncryptedTokenStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * The Spotify account-swap guard. Signing in again as a DIFFERENT account used to overwrite the
 * stored one silently, and in Refresh mode the next sync then hid the first account's whole
 * library. A different account is now refused with nothing written; the same account signing in
 * again (an expired session), a first sign-in, and every case where the account can't be told
 * all save exactly as before.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TokenManagerSpotifyAccountGuardTest {

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val now = Instant.now().epochSecond

    private val rawn = SpotifyProfile(userId = "rawnclark", displayName = "Rawn")
    private val sam = SpotifyProfile(userId = "lm7d11nsf65n11sojl4twwr6t", displayName = "Sam")

    /** Spotify's tokens are opaque: no username in the scope, only "|clientId" (#429's log). */
    private fun token(access: String, spDc: String, expiresAt: Long = now + 3_600) =
        ServiceToken(access, spDc, expiresAtEpoch = expiresAt, scope = "|webclient")

    /** The connected account's stored sign-in: Rawn's, its session expired. */
    private val storedToken = MutableStateFlow<ServiceToken?>(token("rawn-old", "rawn-cookie", expiresAt = now - 60))
    private val storedAccount = MutableStateFlow<SpotifyProfile?>(rawn)

    private val store = mockk<EncryptedTokenStore>(relaxed = true) {
        every { spotifyToken } returns storedToken
        every { spotifyUser } returns MutableStateFlow(UserInfo("spotify_user", "Spotify User"))
        every { spotifyAccount } returns storedAccount
        every { youTubeToken } returns MutableStateFlow(null)
        every { youTubeUser } returns MutableStateFlow(null)
        every { discordToken } returns MutableStateFlow(null)
        every { discordUser } returns MutableStateFlow(null)
    }

    private val auth = mockk<SpotifyAuthManager> {
        every { extractUsernameFromJwt(any()) } returns null
        // The sign-ins the listener makes in the WebView, and who Spotify says each one is.
        coEvery { getAccessToken("rawn-new-cookie") } returns token("rawn-new", "rawn-new-cookie")
        coEvery { getAccessToken("sam-cookie") } returns token("sam-access", "sam-cookie")
        coEvery { fetchProfile("rawn-new", "webclient") } returns rawn
        coEvery { fetchProfile("sam-access", "webclient") } returns sam
        // Rawn's stored cookie is dead (that's why the card offered Reconnect).
        coEvery { getAccessToken("rawn-cookie") } returns null
    }

    private fun manager() = TokenManagerImpl(store, auth, mockk(relaxed = true), mockk(relaxed = true))

    private fun assertNothingWritten() {
        coVerify(exactly = 0) { store.saveSpotifyConnection(any(), any(), any()) }
        coVerify(exactly = 0) { store.saveSpotifyToken(any(), any()) }
        coVerify(exactly = 0) { store.saveSpotifyAccountFor(any(), any()) }
        coVerify(exactly = 0) { store.clearSpotify() }
    }

    // -- Refused -------------------------------------------------------------

    @Test fun `a different account is refused and the connected one is left untouched`() = runTest {
        val result = manager().connectSpotifyWithCookie("sam-cookie")

        assertEquals(SpotifyConnectResult.DifferentAccount(connectedName = "Rawn", attemptedName = "Sam"), result)
        assertNothingWritten()
    }

    @Test fun `a different account is refused through the paste dialog too, typed name or not`() = runTest {
        val result = manager().connectSpotifyWithCookie("sam-cookie", username = "rawnclark")

        assertEquals(SpotifyConnectResult.DifferentAccount(connectedName = "Rawn", attemptedName = "Sam"), result)
        assertNothingWritten()
    }

    @Test fun `two accounts sharing a display name are still told apart`() = runTest {
        coEvery { auth.fetchProfile("sam-access", "webclient") } returns sam.copy(displayName = "Rawn")

        val result = manager().connectSpotifyWithCookie("sam-cookie")

        assertEquals(SpotifyConnectResult.DifferentAccount(connectedName = "Rawn", attemptedName = "Rawn"), result)
        assertNothingWritten()
    }

    @Test fun `an account signed in before Stash recorded it is asked of Spotify, and a swap refused`() = runTest {
        // Signed in on an older version: no record. Its cookie still works (a false "expired").
        storedAccount.value = null
        coEvery { auth.getAccessToken("rawn-cookie") } returns token("rawn-live", "rawn-cookie")
        coEvery { auth.fetchProfile("rawn-live", "webclient") } returns rawn

        val result = manager().connectSpotifyWithCookie("sam-cookie")

        assertEquals(SpotifyConnectResult.DifferentAccount(connectedName = "Rawn", attemptedName = "Sam"), result)
        assertNothingWritten()
    }

    // -- Accepted ------------------------------------------------------------

    @Test fun `the connected account signing in again connects as before`() = runTest {
        val saved = slot<ServiceToken>()
        val user = slot<UserInfo>()
        coEvery { store.saveSpotifyConnection(capture(saved), capture(user), any()) } returns Unit

        val result = manager().connectSpotifyWithCookie("rawn-new-cookie")

        assertEquals(SpotifyConnectResult.Connected, result)
        coVerify(exactly = 1) { store.saveSpotifyConnection(any(), any(), rawn) }
        assertEquals("rawn-new-cookie", saved.captured.refreshToken)
        assertEquals("rawnclark|webclient", saved.captured.scope)
        // "Connected as" gets Spotify's name, no longer "Spotify User".
        assertEquals(UserInfo(id = "rawnclark", displayName = "Rawn"), user.captured)
    }

    @Test fun `signed out, any account is accepted and recorded`() = runTest {
        storedToken.value = null
        storedAccount.value = null

        val result = manager().connectSpotifyWithCookie("sam-cookie")

        assertEquals(SpotifyConnectResult.Connected, result)
        coVerify(exactly = 1) { store.saveSpotifyConnection(any(), any(), sam) }
    }

    @Test fun `a name typed in the paste dialog stays the shown name`() = runTest {
        val user = slot<UserInfo>()
        coEvery { store.saveSpotifyConnection(any(), capture(user), any()) } returns Unit

        manager().connectSpotifyWithCookie("rawn-new-cookie", username = "Rawn's phone")

        assertEquals(UserInfo(id = "Rawn's phone", displayName = "Rawn's phone"), user.captured)
    }

    @Test fun `an unrecorded account whose cookie is dead can't be told, so the sign-in is saved as before`() = runTest {
        storedAccount.value = null

        val result = manager().connectSpotifyWithCookie("sam-cookie")

        assertEquals(SpotifyConnectResult.Connected, result)
        coVerify(exactly = 1) { store.saveSpotifyConnection(any(), any(), sam) }
    }

    @Test fun `when Spotify doesn't say who signed in, the sign-in is saved and the old record dropped`() = runTest {
        // A failed lookup must never lock anyone out of reconnecting their own account.
        coEvery { auth.fetchProfile("sam-access", "webclient") } returns null

        val result = manager().connectSpotifyWithCookie("sam-cookie")

        assertEquals(SpotifyConnectResult.Connected, result)
        // Rawn's record mustn't outlive his sign-in: it would refuse Sam's own next sign-in.
        coVerify(exactly = 1) { store.saveSpotifyConnection(any(), any(), null) }
    }

    @Test fun `an invalid cookie saves nothing`() = runTest {
        coEvery { auth.getAccessToken("bad-cookie") } returns null

        val result = manager().connectSpotifyWithCookie("bad-cookie")

        assertEquals(SpotifyConnectResult.Invalid, result)
        assertNothingWritten()
    }

    // -- Learning whose account an older sign-in is --------------------------

    @Test fun `a working sign-in with no record learns its account, for that sign-in only`() = runTest {
        storedAccount.value = null
        storedToken.value = token("rawn-fresh", "rawn-cookie")
        coEvery { auth.fetchProfile("rawn-fresh", "webclient") } returns rawn
        coEvery { store.saveSpotifyAccountFor(any(), any()) } returns true

        manager().rememberSpotifyAccount()

        coVerify(exactly = 1) { store.saveSpotifyAccountFor("rawn-cookie", rawn) }
    }

    @Test fun `a recorded account isn't looked up again`() = runTest {
        storedToken.value = token("rawn-fresh", "rawn-cookie")

        manager().rememberSpotifyAccount()

        coVerify(exactly = 0) { auth.fetchProfile(any(), any()) }
        coVerify(exactly = 0) { store.saveSpotifyAccountFor(any(), any()) }
    }

    @Test fun `a failing lookup is tried once per process, not on every sync`() = runTest {
        storedAccount.value = null
        storedToken.value = token("rawn-fresh", "rawn-cookie")
        coEvery { auth.fetchProfile("rawn-fresh", "webclient") } returns null
        val manager = manager()

        manager.rememberSpotifyAccount()
        manager.rememberSpotifyAccount()

        coVerify(exactly = 1) { auth.fetchProfile(any(), any()) }
        coVerify(exactly = 0) { store.saveSpotifyAccountFor(any(), any()) }
    }

    @Test fun `signed out, there is nothing to learn`() = runTest {
        storedToken.value = null
        storedAccount.value = null

        manager().rememberSpotifyAccount()

        coVerify(exactly = 0) { auth.fetchProfile(any(), any()) }
        assertNull(storedAccount.value)
    }
}

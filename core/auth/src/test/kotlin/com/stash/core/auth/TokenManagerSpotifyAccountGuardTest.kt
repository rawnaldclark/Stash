package com.stash.core.auth

import android.util.Log
import com.stash.core.auth.model.ServiceToken
import com.stash.core.auth.model.SpotifyConnectResult
import com.stash.core.auth.model.UserInfo
import com.stash.core.auth.spotify.SpotifyAuthManager
import com.stash.core.auth.spotify.SpotifyProfile
import com.stash.core.auth.store.EncryptedTokenStore
import com.stash.core.auth.store.StoredSpotifySignIn
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        coEvery { readSpotifySignIn() } answers { StoredSpotifySignIn(storedToken.value, storedAccount.value) }
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
        coEvery { fetchProfile("rawn-new", "webclient", true) } returns rawn
        coEvery { fetchProfile("sam-access", "webclient", true) } returns sam
        // Rawn's stored cookie is dead (that's why the card offered Reconnect).
        coEvery { getAccessToken("rawn-cookie") } returns null
    }

    private fun manager() = TokenManagerImpl(store, auth, mockk(relaxed = true), mockk(relaxed = true))

    private fun assertNothingWritten() {
        coVerify(exactly = 0) { store.saveSpotifyConnection(any(), any(), any()) }
        coVerify(exactly = 0) { store.saveRefreshedSpotifyToken(any(), any()) }
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
        coEvery { auth.fetchProfile("sam-access", "webclient", true) } returns sam.copy(displayName = "Rawn")

        val result = manager().connectSpotifyWithCookie("sam-cookie")

        assertEquals(SpotifyConnectResult.DifferentAccount(connectedName = "Rawn", attemptedName = "Rawn"), result)
        assertNothingWritten()
    }

    @Test fun `an account signed in before Stash recorded it is asked of Spotify, and a swap refused`() = runTest {
        // Signed in on an older version: no record. Its cookie still works (a false "expired").
        storedAccount.value = null
        coEvery { auth.getAccessToken("rawn-cookie") } returns token("rawn-live", "rawn-cookie")
        coEvery { auth.fetchProfile("rawn-live", "webclient", false) } returns rawn

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
        coEvery { auth.fetchProfile("sam-access", "webclient", true) } returns null

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

    // -- A sign-in never waits long on identifying anyone --------------------

    private val identifyBudgetMs = 20_000L

    @Test fun `only the new sign-in's lookup may download the web player bundle`() = runTest {
        // After a hash change nothing else fills the cache: an expired session's sync stops at the
        // health probe. The connected account's lookup then reuses what the first one found.
        storedAccount.value = null
        coEvery { auth.getAccessToken("rawn-cookie") } returns token("rawn-live", "rawn-cookie")
        coEvery { auth.fetchProfile("rawn-live", "webclient", false) } returns rawn

        manager().connectSpotifyWithCookie("sam-cookie")

        coVerify(exactly = 1) { auth.fetchProfile("sam-access", "webclient", true) }
        coVerify(exactly = 1) { auth.fetchProfile("rawn-live", "webclient", false) }
        coVerify(exactly = 2) { auth.fetchProfile(any(), any(), any()) }
    }

    @Test fun `past the budget the sign-in is saved unchecked, as before the guard`() = runTest {
        coEvery { auth.fetchProfile("sam-access", "webclient", true) } coAnswers {
            delay(60_000)
            sam
        }

        val result = manager().connectSpotifyWithCookie("sam-cookie")

        assertEquals(SpotifyConnectResult.Connected, result)
        assertEquals(identifyBudgetMs, currentTime)
        coVerify(exactly = 1) { store.saveSpotifyConnection(any(), any(), null) }
    }

    @Test fun `a step stuck in a blocking call can't hold the sign-in past the budget`() = runTest {
        // A blocking execute() doesn't notice cancellation, as this doesn't.
        coEvery { auth.fetchProfile("sam-access", "webclient", true) } coAnswers {
            withContext(NonCancellable) { delay(60_000) }
            sam
        }

        val result = manager().connectSpotifyWithCookie("sam-cookie")

        assertEquals(SpotifyConnectResult.Connected, result)
        assertEquals(identifyBudgetMs, currentTime)
    }

    @Test fun `when only the connected account's lookup times out, the new account is still recorded`() = runTest {
        storedAccount.value = null
        coEvery { auth.getAccessToken("rawn-cookie") } coAnswers {
            delay(60_000)
            null
        }

        val result = manager().connectSpotifyWithCookie("sam-cookie")

        assertEquals(SpotifyConnectResult.Connected, result)
        coVerify(exactly = 1) { store.saveSpotifyConnection(any(), any(), sam) }
    }

    // -- Saying why a sign-in went unchecked ---------------------------------

    /** The warnings [block] logged. */
    private suspend fun warningsDuring(block: suspend () -> Unit): List<String> {
        val warnings = mutableListOf<String>()
        mockkStatic(Log::class)
        try {
            every { Log.w(any(), any<String>()) } answers {
                warnings += secondArg<String>()
                0
            }
            block()
        } finally {
            unmockkStatic(Log::class)
        }
        return warnings
    }

    @Test fun `an unidentified sign-in logs why it went unchecked, and nothing about anyone`() = runTest {
        coEvery { auth.fetchProfile("sam-access", "webclient", true) } returns null

        val warnings = warningsDuring { manager().connectSpotifyWithCookie("sam-cookie", username = "Sam") }

        assertTrue(warnings.toString(), warnings.any { "new sign-in unidentified" in it })
        assertNoUserData(warnings)
    }

    @Test fun `an unknown connected account logs why the sign-in went unchecked, and nothing about anyone`() = runTest {
        storedAccount.value = null

        val warnings = warningsDuring { manager().connectSpotifyWithCookie("sam-cookie") }

        assertTrue(warnings.toString(), warnings.any { "connected account unknown" in it })
        assertNoUserData(warnings)
    }

    @Test fun `running out of budget says so`() = runTest {
        coEvery { auth.fetchProfile("sam-access", "webclient", true) } coAnswers {
            delay(60_000)
            sam
        }

        val warnings = warningsDuring { manager().connectSpotifyWithCookie("sam-cookie") }

        assertTrue(warnings.toString(), warnings.any { "timed out" in it })
        assertTrue(warnings.toString(), warnings.any { "new sign-in unidentified" in it })
    }

    @Test fun `a checked sign-in or a first sign-in with a known account logs no warning`() = runTest {
        val checked = warningsDuring { manager().connectSpotifyWithCookie("rawn-new-cookie") }
        storedToken.value = null
        storedAccount.value = null
        val first = warningsDuring { manager().connectSpotifyWithCookie("sam-cookie") }

        assertEquals(emptyList<String>(), checked + first)
    }

    private fun assertNoUserData(logged: List<String>) {
        val secrets = listOf("Sam", "Rawn", "rawnclark", sam.userId, "sam-cookie", "rawn-cookie", "sam-access")
        logged.forEach { line -> secrets.forEach { assertFalse(line, it in line) } }
    }

    // -- A token refresh racing a sign-out or a new sign-in ------------------

    @Test fun `a refresh is saved only for the sign-in it refreshed, and handed out then`() = runTest {
        coEvery { auth.refreshAccessToken("rawn-cookie") } returns token("rawn-fresh", "rawn-cookie")
        coEvery { store.saveRefreshedSpotifyToken(any(), any()) } returns true

        assertEquals("rawn-fresh", manager().getSpotifyAccessToken())

        coVerify(exactly = 1) { store.saveRefreshedSpotifyToken("rawn-cookie", any()) }
    }

    /** The refresh of Rawn's sign-in finishes only after [overtakenBy] is what's stored. */
    private fun refreshOvertakenBy(overtakenBy: ServiceToken?) {
        coEvery { auth.refreshAccessToken("rawn-cookie") } returns token("rawn-fresh", "rawn-cookie")
        coEvery { store.saveRefreshedSpotifyToken(any(), any()) } answers {
            storedToken.value = overtakenBy
            false
        }
    }

    @Test fun `a refresh overtaken by a sign-out hands out nothing`() = runTest {
        refreshOvertakenBy(null)

        assertNull(manager().getSpotifyAccessToken())
    }

    @Test fun `a refresh overtaken by a new sign-in answers for that sign-in, never with the dropped token`() = runTest {
        // Null here would read as "Session expired" to a sync's health probe: a false alarm.
        refreshOvertakenBy(token("sam-access", "sam-cookie"))

        assertEquals("sam-access", manager().getSpotifyAccessToken())
    }

    @Test fun `a refresh overtaken by a sign-in already expired hands out nothing`() = runTest {
        refreshOvertakenBy(token("sam-old", "sam-cookie", expiresAt = now - 60))

        assertNull(manager().getSpotifyAccessToken())
    }

    @Test fun `a forced refresh follows the same rule`() = runTest {
        storedToken.value = token("rawn-current", "rawn-cookie")
        coEvery { auth.refreshAccessToken("rawn-cookie") } returns token("rawn-fresh", "rawn-cookie")
        coEvery { store.saveRefreshedSpotifyToken(any(), any()) } returns true
        assertEquals("rawn-fresh", manager().forceRefreshSpotifyAccessToken())

        refreshOvertakenBy(token("sam-access", "sam-cookie"))
        assertEquals("sam-access", manager().forceRefreshSpotifyAccessToken())

        storedToken.value = token("rawn-current", "rawn-cookie")
        refreshOvertakenBy(null)
        assertNull(manager().forceRefreshSpotifyAccessToken())
    }

    // -- Learning whose account an older sign-in is --------------------------

    @Test fun `a working sign-in with no record learns its account, for that sign-in only`() = runTest {
        storedAccount.value = null
        storedToken.value = token("rawn-fresh", "rawn-cookie")
        coEvery { auth.fetchProfile("rawn-fresh", "webclient", true) } returns rawn
        coEvery { store.saveSpotifyAccountFor(any(), any()) } returns true

        manager().rememberSpotifyAccount()

        coVerify(exactly = 1) { store.saveSpotifyAccountFor("rawn-cookie", rawn) }
        coVerify(exactly = 1) { store.readSpotifySignIn() }
    }

    @Test fun `a token about to expire is refreshed first`() = runTest {
        storedAccount.value = null
        coEvery { auth.refreshAccessToken("rawn-cookie") } returns token("rawn-fresh", "rawn-cookie")
        coEvery { store.saveRefreshedSpotifyToken(any(), any()) } returns true
        coEvery { auth.fetchProfile("rawn-fresh", "webclient", true) } returns rawn
        coEvery { store.saveSpotifyAccountFor(any(), any()) } returns true

        manager().rememberSpotifyAccount()

        coVerify(exactly = 1) { store.saveSpotifyAccountFor("rawn-cookie", rawn) }
    }

    @Test fun `a recorded account isn't looked up again`() = runTest {
        storedToken.value = token("rawn-fresh", "rawn-cookie")

        manager().rememberSpotifyAccount()

        coVerify(exactly = 0) { auth.fetchProfile(any(), any(), any()) }
        coVerify(exactly = 0) { store.saveSpotifyAccountFor(any(), any()) }
    }

    @Test fun `a failed lookup isn't retried on every sync, but is a few hours later`() = runTest {
        storedAccount.value = null
        storedToken.value = token("rawn-fresh", "rawn-cookie")
        coEvery { auth.fetchProfile("rawn-fresh", "webclient", true) } returns null
        coEvery { store.saveSpotifyAccountFor(any(), any()) } returns true
        var nowMs = 1_000_000_000L
        val manager = manager().also { it.nowMs = { nowMs } }

        manager.rememberSpotifyAccount()
        nowMs += 60 * 60 * 1000L
        manager.rememberSpotifyAccount()
        coVerify(exactly = 1) { auth.fetchProfile(any(), any(), any()) }

        // One network failure mustn't leave the account unrecorded for the process's life.
        coEvery { auth.fetchProfile("rawn-fresh", "webclient", true) } returns rawn
        nowMs += 3 * 60 * 60 * 1000L
        manager.rememberSpotifyAccount()

        coVerify(exactly = 2) { auth.fetchProfile(any(), any(), any()) }
        coVerify(exactly = 1) { store.saveSpotifyAccountFor("rawn-cookie", rawn) }
    }

    @Test fun `an unidentified sign-in may be looked up again at the next sync`() = runTest {
        // A failed lookup a moment ago mustn't delay learning who just signed in.
        storedAccount.value = null
        storedToken.value = token("rawn-fresh", "rawn-cookie")
        coEvery { auth.fetchProfile("rawn-fresh", "webclient", true) } returns null
        val manager = manager()
        manager.rememberSpotifyAccount()

        coEvery { auth.fetchProfile("sam-access", "webclient", true) } returns null
        manager.connectSpotifyWithCookie("sam-cookie")
        manager.rememberSpotifyAccount()

        coVerify(exactly = 2) { auth.fetchProfile("rawn-fresh", "webclient", true) }
    }

    @Test fun `signed out, there is nothing to learn`() = runTest {
        storedToken.value = null
        storedAccount.value = null

        manager().rememberSpotifyAccount()

        coVerify(exactly = 0) { auth.fetchProfile(any(), any(), any()) }
        assertNull(storedAccount.value)
    }
}

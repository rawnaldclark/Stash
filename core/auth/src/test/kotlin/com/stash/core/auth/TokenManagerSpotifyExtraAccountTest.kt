package com.stash.core.auth

import com.stash.core.auth.model.AuthService
import com.stash.core.auth.model.ServiceToken
import com.stash.core.auth.model.SpotifyCookieCheck
import com.stash.core.auth.model.UserInfo
import com.stash.core.auth.model.ValidatedSpotifySignIn
import com.stash.core.auth.spotify.SpotifyAccount
import com.stash.core.auth.spotify.SpotifyAuthManager
import com.stash.core.auth.spotify.SpotifyProfile
import com.stash.core.auth.spotify.withSpotifyAccount
import com.stash.core.auth.store.EncryptedTokenStore
import com.stash.core.auth.store.StoredSpotifySignIn
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
 * Extra Spotify accounts in the token manager (plan 2026-11-04 §2.2): the acting account
 * ([SpotifyAccount] in the coroutine context) and its no-fallback rule, per-account refresh
 * locks, and checking a cookie without saving it.
 *
 * The rule under test: with an extra acting, nothing the token manager hands out is ever the main
 * account's. An extra pass that got the main's token would read the main's library as the extra's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TokenManagerSpotifyExtraAccountTest {

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val now = Instant.now().epochSecond
    private val rawn = SpotifyProfile(userId = "rawnclark", displayName = "Rawn")
    private val sam = SpotifyProfile(userId = "lm7d11nsf65n11sojl4twwr6t", displayName = "Sam")
    private val kim = SpotifyProfile(userId = "kimk", displayName = "Kim")

    /** The extra accounts' rows: Sam is row 7, Kim row 8. */
    private val samRow = SpotifyAccount(7, sam.userId)
    private val kimRow = SpotifyAccount(8, kim.userId)

    private fun token(access: String, spDc: String, expiresAt: Long = now + 3_600, scope: String = "|webclient") =
        ServiceToken(access, spDc, expiresAtEpoch = expiresAt, scope = scope)

    /** The main account: Rawn, signed in, his token fresh. */
    private val mainToken = MutableStateFlow<ServiceToken?>(token("rawn-access", "rawn-cookie", scope = "rawnclark|main-client"))

    /** Whose the main sign-in is, as recorded (`spotify_account`); null on an install from before Stash recorded it. */
    private var recordedMain: SpotifyProfile? = rawn

    /** Extra accounts by row id: what each `spotify_extra_<rowId>` blob holds. */
    private val extras = mutableMapOf<Long, StoredSpotifySignIn>()

    private val store = mockk<EncryptedTokenStore>(relaxed = true) {
        every { spotifyToken } returns mainToken
        every { spotifyUser } returns MutableStateFlow(UserInfo("rawnclark", "Rawn"))
        coEvery { readSpotifySignIn() } answers { StoredSpotifySignIn(mainToken.value, recordedMain) }
        // Recorded only for the stored sign-in it was looked up with, as the real store does.
        coEvery { saveSpotifyAccountFor(any(), any()) } answers {
            if (mainToken.value?.refreshToken == firstArg<String>()) {
                recordedMain = secondArg()
                true
            } else {
                false
            }
        }
        every { youTubeToken } returns MutableStateFlow(null)
        every { youTubeUser } returns MutableStateFlow(null)
        every { discordToken } returns MutableStateFlow(null)
        every { discordUser } returns MutableStateFlow(null)
        coEvery { readSpotifyExtraSignIn(any()) } answers {
            extras[firstArg()] ?: StoredSpotifySignIn(null, null)
        }
        coEvery { saveRefreshedSpotifyExtraToken(any(), any(), any()) } answers {
            val rowId = firstArg<Long>()
            val current = extras[rowId]?.token
            if (current?.refreshToken == secondArg<String>()) {
                extras[rowId] = extras.getValue(rowId).copy(token = thirdArg())
                true
            } else {
                false
            }
        }
    }

    private val auth = mockk<SpotifyAuthManager> {
        every { extractUsernameFromJwt(any()) } returns null
        // Every cookie's refresh fails unless a test says otherwise: a dead sign-in.
        coEvery { refreshAccessToken(any()) } returns null
    }

    private fun manager() = TokenManagerImpl(store, auth, mockk(relaxed = true), mockk(relaxed = true))

    private fun addExtra(rowId: Long, account: SpotifyProfile, token: ServiceToken) {
        extras[rowId] = StoredSpotifySignIn(token, account)
    }

    /** Every per-account read the token manager answers, in the caller's context. */
    private suspend fun TokenManagerImpl.allReads(): List<Any?> = listOf(
        getSpotifyAccessToken(),
        forceRefreshSpotifyAccessToken(),
        getSpDcCookie(),
        getSpotifyUsername(),
        getSpotifyClientId(),
    )

    private val mainValues = setOf("rawn-access", "rawn-cookie", "rawnclark", "main-client", "rawn-fresh")

    private fun assertNoMainValue(answers: List<Any?>) {
        answers.forEach { assertFalse("the main's $it handed out to an extra", it in mainValues) }
    }

    // -- No account acting: the main, exactly as before ----------------------

    @Test fun `with no account acting, every read is the main account's as before`() = runTest {
        coEvery { auth.refreshAccessToken("rawn-cookie") } returns token("rawn-fresh", "rawn-cookie")
        coEvery { store.saveRefreshedSpotifyToken(any(), any()) } returns true
        addExtra(7, sam, token("sam-access", "sam-cookie"))
        val manager = manager()

        assertEquals("rawn-access", manager.getSpotifyAccessToken())
        assertEquals("rawn-cookie", manager.getSpDcCookie())
        assertEquals("rawnclark", manager.getSpotifyUsername())
        assertEquals("main-client", manager.getSpotifyClientId())
        assertTrue(manager.isAuthenticated(AuthService.SPOTIFY))
        assertEquals("rawn-fresh", manager.forceRefreshSpotifyAccessToken())
        coVerify(exactly = 0) { store.readSpotifyExtraSignIn(any()) }
    }

    // -- An extra acting -----------------------------------------------------

    @Test fun `an extra acting answers with its own sign-in`() = runTest {
        addExtra(7, sam, token("sam-access", "sam-cookie", scope = "|sam-client"))
        val manager = manager()

        withSpotifyAccount(samRow) {
            assertEquals("sam-access", manager.getSpotifyAccessToken())
            assertEquals("sam-cookie", manager.getSpDcCookie())
            assertEquals(sam.userId, manager.getSpotifyUsername())
            assertEquals("sam-client", manager.getSpotifyClientId())
            assertTrue(manager.isAuthenticated(AuthService.SPOTIFY))
        }
        // Back outside: the main again.
        assertEquals("rawn-access", manager.getSpotifyAccessToken())
    }

    @Test fun `an extra with no sign-in answers null to every read, never the main's`() = runTest {
        val manager = manager()

        val answers = withSpotifyAccount(samRow) { manager.allReads() }

        assertEquals(listOf(null, null, null, null, null), answers)
        assertFalse(withSpotifyAccount(samRow) { manager.isAuthenticated(AuthService.SPOTIFY) })
        coVerify(exactly = 0) { auth.refreshAccessToken(any()) }
        coVerify(exactly = 0) { store.saveRefreshedSpotifyToken(any(), any()) }
    }

    @Test fun `an extra whose sign-in died gets no token, and never the main's`() = runTest {
        // Expired, and its cookie no longer works (a password change, "sign out everywhere").
        addExtra(7, sam, token("sam-old", "sam-cookie", expiresAt = now - 60, scope = "|sam-client"))
        val manager = manager()

        val answers = withSpotifyAccount(samRow) { manager.allReads() }

        assertNull("access token", answers[0])
        assertNull("forced refresh", answers[1])
        // Its own stored identity is still its own; nothing of the main's.
        assertEquals(listOf("sam-cookie", sam.userId, "sam-client"), answers.drop(2))
        assertNoMainValue(answers)
        coVerify(exactly = 0) { auth.refreshAccessToken("rawn-cookie") }
        coVerify(exactly = 0) { store.saveRefreshedSpotifyToken(any(), any()) }
    }

    @Test fun `the rule holds across dispatchers and in child coroutines`() = runTest {
        val manager = manager()

        val answers = withSpotifyAccount(samRow) {
            listOf(
                withContext(Dispatchers.Default) { manager.getSpotifyAccessToken() },
                async { manager.getSpotifyAccessToken() }.await(),
            )
        }

        assertEquals(listOf(null, null), answers)
    }

    @Test fun `an extra's expired token is refreshed from its own cookie and saved under its row`() = runTest {
        addExtra(7, sam, token("sam-old", "sam-cookie", expiresAt = now - 60))
        coEvery { auth.refreshAccessToken("sam-cookie") } returns token("sam-fresh", "sam-cookie")
        val manager = manager()

        assertEquals("sam-fresh", withSpotifyAccount(samRow) { manager.getSpotifyAccessToken() })

        coVerify(exactly = 1) { store.saveRefreshedSpotifyExtraToken(7, "sam-cookie", any()) }
        coVerify(exactly = 0) { store.saveRefreshedSpotifyToken(any(), any()) }
        assertEquals("sam-fresh", extras.getValue(7).token?.accessToken)
        assertEquals("the main's untouched", "rawn-access", mainToken.value?.accessToken)
    }

    @Test fun `a refresh overtaken by removing the extra hands out nothing`() = runTest {
        addExtra(7, sam, token("sam-old", "sam-cookie", expiresAt = now - 60))
        coEvery { auth.refreshAccessToken("sam-cookie") } answers {
            extras.remove(7L)
            token("sam-fresh", "sam-cookie")
        }

        assertNull(withSpotifyAccount(samRow) { manager().getSpotifyAccessToken() })
    }

    @Test fun `the main's record isn't looked up while an extra acts`() = runTest {
        addExtra(7, sam, token("sam-access", "sam-cookie"))

        withSpotifyAccount(samRow) { manager().rememberSpotifyAccount() }

        coVerify(exactly = 0) { store.readSpotifySignIn() }
        coVerify(exactly = 0) { auth.fetchProfile(any(), any(), any()) }
    }

    @Test fun `a sign-in under the row that is another account's is never used`() = runTest {
        // A database restored from another phone's backup: row 7 is Kim's there, but the sign-in
        // stored under row 7 on this phone is Sam's. Kim's pass must not read Sam's library.
        addExtra(7, sam, token("sam-access", "sam-cookie", scope = "|sam-client"))
        val kimAsRow7 = SpotifyAccount(7, kim.userId)
        val manager = manager()

        val answers = withSpotifyAccount(kimAsRow7) { manager.allReads() }

        assertEquals(listOf(null, null, null, null, null), answers)
        assertFalse(withSpotifyAccount(kimAsRow7) { manager.isAuthenticated(AuthService.SPOTIFY) })
        assertFalse(manager.hasSpotifyExtraSignIn(kimAsRow7))
        assertNull(manager.getSpotifyExtraAccessToken(kimAsRow7))
        coVerify(exactly = 0) { auth.refreshAccessToken(any()) }
        // The row's own account, written in another shape of its id, is still itself.
        assertTrue(manager.hasSpotifyExtraSignIn(SpotifyAccount(7, "spotify:user:${sam.userId.uppercase()}")))
    }

    @Test fun `an extra is a positive row id with a user id, and never names the account`() {
        assertTrue(runCatching { SpotifyAccount(0, sam.userId) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { SpotifyAccount(7, " ") }.exceptionOrNull() is IllegalArgumentException)
        assertEquals("SpotifyAccount(extra #7)", samRow.toString())
        assertEquals(samRow, SpotifyAccount(7, sam.userId.uppercase()))
    }

    // -- One refresh lock per account ----------------------------------------

    @Test fun `a slow main refresh doesn't hold up an extra's, nor one extra another's`() = runTest {
        mainToken.value = token("rawn-old", "rawn-cookie", expiresAt = now - 60)
        addExtra(7, sam, token("sam-old", "sam-cookie", expiresAt = now - 60))
        addExtra(8, kim, token("kim-old", "kim-cookie", expiresAt = now - 60))
        val mainGate = CompletableDeferred<Unit>()
        val samGate = CompletableDeferred<Unit>()
        coEvery { auth.refreshAccessToken("rawn-cookie") } coAnswers {
            mainGate.await()
            token("rawn-fresh", "rawn-cookie")
        }
        coEvery { auth.refreshAccessToken("sam-cookie") } coAnswers {
            samGate.await()
            token("sam-fresh", "sam-cookie")
        }
        coEvery { auth.refreshAccessToken("kim-cookie") } returns token("kim-fresh", "kim-cookie")
        coEvery { store.saveRefreshedSpotifyToken(any(), any()) } returns true
        val manager = manager()

        val mainCall = async { manager.getSpotifyAccessToken() }
        val samCall = async { withSpotifyAccount(samRow) { manager.getSpotifyAccessToken() } }
        val kimCall = async { withSpotifyAccount(kimRow) { manager.getSpotifyAccessToken() } }
        advanceUntilIdle()

        // The main and Sam are stuck on their refreshes; Kim's went through all the same.
        assertTrue(kimCall.isCompleted)
        assertEquals("kim-fresh", kimCall.await())
        assertFalse(mainCall.isCompleted)
        assertFalse(samCall.isCompleted)

        samGate.complete(Unit)
        advanceUntilIdle()
        assertEquals("sam-fresh", samCall.await())
        assertFalse("still the main's own wait", mainCall.isCompleted)

        mainGate.complete(Unit)
        assertEquals("rawn-fresh", mainCall.await())
    }

    @Test fun `concurrent callers acting as one extra share one refresh`() = runTest {
        addExtra(7, sam, token("sam-old", "sam-cookie", expiresAt = now - 60))
        val gate = CompletableDeferred<Unit>()
        coEvery { auth.refreshAccessToken("sam-cookie") } coAnswers {
            gate.await()
            token("sam-fresh", "sam-cookie")
        }
        val manager = manager()

        val callers = List(3) { async { withSpotifyAccount(samRow) { manager.getSpotifyAccessToken() } } }
        advanceUntilIdle()
        gate.complete(Unit)

        assertEquals(listOf("sam-fresh", "sam-fresh", "sam-fresh"), callers.awaitAll())
        coVerify(exactly = 1) { auth.refreshAccessToken("sam-cookie") }
    }

    // -- Checking a cookie without saving it ---------------------------------

    private fun assertNothingSaved() {
        coVerify(exactly = 0) { store.saveSpotifyConnection(any(), any(), any()) }
        coVerify(exactly = 0) { store.saveSpotifyExtra(any(), any(), any()) }
        coVerify(exactly = 0) { store.saveRefreshedSpotifyToken(any(), any()) }
        coVerify(exactly = 0) { store.saveSpotifyAccountFor(any(), any()) }
        coVerify(exactly = 0) { store.clearSpotify() }
    }

    @Test fun `another account's cookie is identified, told apart from the main, and nothing saved`() = runTest {
        coEvery { auth.getAccessToken("sam-cookie") } returns token("sam-access", "sam-cookie")
        coEvery { auth.fetchProfile("sam-access", "webclient", true) } returns sam

        val check = manager().validateSpotifyCookie("sam-cookie") as SpotifyCookieCheck.Valid

        assertEquals(sam, check.account)
        assertEquals(false, check.isMainAccount)
        assertNothingSaved()
    }

    @Test fun `the main account's own cookie is recognised as the main`() = runTest {
        coEvery { auth.getAccessToken("rawn-cookie-2") } returns token("rawn-access-2", "rawn-cookie-2")
        coEvery { auth.fetchProfile("rawn-access-2", "webclient", true) } returns rawn.copy(userId = "RawnClark")

        val check = manager().validateSpotifyCookie("rawn-cookie-2") as SpotifyCookieCheck.Valid

        assertEquals(true, check.isMainAccount)
        assertNothingSaved()
    }

    /** An older install: the main sign-in works, but whose it is was never recorded; Spotify can say. */
    private fun olderMainSignIn() {
        recordedMain = null
        coEvery { auth.getAccessToken("rawn-cookie") } returns token("rawn-live", "rawn-cookie", scope = "rawnclark|main-client")
        coEvery { auth.fetchProfile("rawn-live", "main-client", false) } returns rawn
        // The listener signs in as the main account again, in the add-an-account flow.
        coEvery { auth.getAccessToken("rawn-cookie-2") } returns token("rawn-access-2", "rawn-cookie-2")
        coEvery { auth.fetchProfile("rawn-access-2", "webclient", true) } returns rawn
    }

    @Test fun `an older main sign-in learns its account from a validation, and can't then be saved as an extra`() = runTest {
        olderMainSignIn()
        val manager = manager()

        val check = manager.validateSpotifyCookie("rawn-cookie-2") as SpotifyCookieCheck.Valid

        assertEquals(true, check.isMainAccount)
        // Recorded for the main's own stored cookie, as rememberSpotifyAccount would.
        coVerify(exactly = 1) { store.saveSpotifyAccountFor("rawn-cookie", rawn) }
        assertEquals(rawn, recordedMain)
        // Even a caller that ignored isMainAccount can't save the main account as an extra.
        assertFalse(manager.saveSpotifyExtra(SpotifyAccount(7, rawn.userId), check.signIn))
        coVerify(exactly = 0) { store.saveSpotifyExtra(any(), any(), any()) }
        // Nothing else of the main's sign-in changed.
        coVerify(exactly = 0) { store.saveSpotifyConnection(any(), any(), any()) }
        assertEquals("rawn-access", mainToken.value?.accessToken)
    }

    @Test fun `while an extra acts, what Spotify says about the main isn't recorded`() = runTest {
        olderMainSignIn()

        withSpotifyAccount(samRow) { manager().validateSpotifyCookie("rawn-cookie-2") }

        coVerify(exactly = 0) { store.saveSpotifyAccountFor(any(), any()) }
        assertNull(recordedMain)
    }

    @Test fun `a main account Spotify can't name stays unrecorded`() = runTest {
        olderMainSignIn()
        coEvery { auth.getAccessToken("rawn-cookie") } returns null // its session really is dead

        val check = manager().validateSpotifyCookie("rawn-cookie-2") as SpotifyCookieCheck.Valid

        assertNull(check.isMainAccount)
        coVerify(exactly = 0) { store.saveSpotifyAccountFor(any(), any()) }
    }

    @Test fun `an account Spotify doesn't name can't be told apart`() = runTest {
        coEvery { auth.getAccessToken("sam-cookie") } returns token("sam-access", "sam-cookie")
        coEvery { auth.fetchProfile("sam-access", "webclient", true) } returns null

        val check = manager().validateSpotifyCookie("sam-cookie") as SpotifyCookieCheck.Valid

        assertNull(check.account)
        assertNull(check.isMainAccount)
        assertNothingSaved()
    }

    @Test fun `a dead or empty cookie is invalid, and nothing saved`() = runTest {
        coEvery { auth.getAccessToken("bad") } returns null
        val manager = manager()

        assertEquals(SpotifyCookieCheck.Invalid, manager.validateSpotifyCookie("bad"))
        assertEquals(SpotifyCookieCheck.Invalid, manager.validateSpotifyCookie("  "))
        coVerify(exactly = 1) { auth.getAccessToken(any()) }
        assertNothingSaved()
    }

    // -- Saving an extra's sign-in -------------------------------------------

    private val samSignIn = ValidatedSpotifySignIn(token("sam-access", "sam-cookie"), sam)

    @Test fun `a validated sign-in is saved under its row when it is the row's account`() = runTest {
        val manager = manager()

        assertTrue(manager.saveSpotifyExtra(SpotifyAccount(7, sam.userId.uppercase()), samSignIn))

        coVerify(exactly = 1) { store.saveSpotifyExtra(7, token("sam-access", "sam-cookie"), sam) }
        coVerify(exactly = 0) { store.saveSpotifyConnection(any(), any(), any()) }
    }

    @Test fun `a sign-in of another account than the row's, an unnamed one, or the main's, is refused`() = runTest {
        val manager = manager()

        assertFalse(manager.saveSpotifyExtra(SpotifyAccount(7, kim.userId), samSignIn))
        assertFalse(manager.saveSpotifyExtra(samRow, ValidatedSpotifySignIn(token("x", "y"), null)))
        val rawnSignIn = ValidatedSpotifySignIn(token("rawn-2", "rawn-cookie-2"), rawn)
        assertFalse(manager.saveSpotifyExtra(SpotifyAccount(7, rawn.userId), rawnSignIn))

        coVerify(exactly = 0) { store.saveSpotifyExtra(any(), any(), any()) }
    }

    @Test fun `a validated sign-in never shows its credential`() {
        assertFalse(samSignIn.toString().contains("sam-cookie"))
        assertFalse(samSignIn.toString().contains("sam-access"))
        assertFalse(SpotifyCookieCheck.Valid(samSignIn, false).toString().contains("sam-cookie"))
    }

    @Test fun `forgetting an extra clears only its row`() = runTest {
        manager().clearSpotifyExtra(7)

        coVerify(exactly = 1) { store.clearSpotifyExtra(7) }
        coVerify(exactly = 0) { store.clearSpotify() }
    }
}

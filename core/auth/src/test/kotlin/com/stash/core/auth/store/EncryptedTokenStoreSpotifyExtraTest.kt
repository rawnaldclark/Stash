package com.stash.core.auth.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.stash.core.auth.crypto.TinkEncryptionManager
import com.stash.core.auth.model.ServiceToken
import com.stash.core.auth.model.UserInfo
import com.stash.core.auth.spotify.SpotifyProfile
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Extra Spotify accounts' sign-ins in the encrypted token store (plan 2026-11-04 §2.2): one blob
 * per extra, `spotify_extra_<rowId>`, beside the main account's untouched `spotify_token`. A real
 * DataStore file; Robolectric for `android.util.Base64`; a stand-in cipher for the Keystore.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class EncryptedTokenStoreSpotifyExtraTest {

    @get:Rule val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataStore: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "auth_tokens.preferences_pb") }
    }

    /** Set to make the next write behave as the first after a keyset reset. */
    private var keysetReset = false

    /** Reverses the bytes: enough to prove nothing is stored in the clear, and to read it back. */
    private val cipher = mockk<TinkEncryptionManager> {
        every { encrypt(any()) } answers { firstArg<ByteArray>().reversedArray() }
        every { decrypt(any()) } answers { firstArg<ByteArray>().reversedArray() }
        every { consumeKeysetReset() } answers { keysetReset.also { keysetReset = false } }
    }

    private val store by lazy { EncryptedTokenStore(dataStore, cipher) }

    @After fun tearDown() = scope.cancel()

    private val main = ServiceToken("main-access", "main-cookie", expiresAtEpoch = 1_000, scope = "rawnclark|webclient")
    private val sam = SpotifyProfile(userId = "lm7d11nsf65n11sojl4twwr6t", displayName = "Sam")
    private val samToken = ServiceToken("sam-access", "sam-cookie", expiresAtEpoch = 2_000, scope = "|webclient")
    private val kim = SpotifyProfile(userId = "kimk", displayName = "Kim")
    private val kimToken = ServiceToken("kim-access", "kim-cookie", expiresAtEpoch = 3_000, scope = "|webclient")

    private suspend fun keys(): Set<String> = dataStore.data.first().asMap().keys.map { it.name }.toSet()

    private suspend fun connectMain() {
        store.saveSpotifyConnection(main, UserInfo("rawnclark", "Rawn"), SpotifyProfile("rawnclark", "Rawn"))
    }

    @Test fun `an extra's sign-in round-trips under its own key, beside the main's`() = runBlocking {
        connectMain()
        store.saveSpotifyExtra(7, samToken, sam)

        assertEquals(StoredSpotifySignIn(samToken, sam), store.readSpotifyExtraSignIn(7))
        assertEquals(setOf("spotify_token", "spotify_account", "spotify_extra_7"), keys())
        // The main's blob is exactly as it was.
        assertEquals(main, store.spotifyToken.first())
        assertEquals(UserInfo("rawnclark", "Rawn"), store.spotifyUser.first())
    }

    @Test fun `the blob is sealed, not stored in the clear`() = runBlocking {
        store.saveSpotifyExtra(7, samToken, sam)

        val raw = dataStore.data.first()[stringPreferencesKey("spotify_extra_7")]!!
        assertFalse(raw.contains("sam-cookie"))
        assertFalse(String(android.util.Base64.decode(raw, android.util.Base64.NO_WRAP)).contains("sam-cookie"))
    }

    @Test fun `another row id, or none saved, reads as no sign-in, never the main's`() = runBlocking {
        connectMain()
        store.saveSpotifyExtra(7, samToken, sam)

        assertEquals(StoredSpotifySignIn(null, null), store.readSpotifyExtraSignIn(8))
    }

    @Test fun `clearing one extra leaves the main and the other extras`() = runBlocking {
        connectMain()
        store.saveSpotifyExtra(7, samToken, sam)
        store.saveSpotifyExtra(8, kimToken, kim)

        store.clearSpotifyExtra(7)

        assertNull(store.readSpotifyExtraSignIn(7).token)
        assertEquals(StoredSpotifySignIn(kimToken, kim), store.readSpotifyExtraSignIn(8))
        assertEquals(main, store.spotifyToken.first())
        assertEquals(SpotifyProfile("rawnclark", "Rawn"), store.readSpotifySignIn().account)
    }

    @Test fun `disconnecting the main leaves the extras`() = runBlocking {
        connectMain()
        store.saveSpotifyExtra(7, samToken, sam)

        store.clearSpotify()

        assertNull(store.spotifyToken.first())
        assertEquals(StoredSpotifySignIn(samToken, sam), store.readSpotifyExtraSignIn(7))
    }

    @Test fun `signing an extra in again replaces only its own sign-in`() = runBlocking {
        connectMain()
        store.saveSpotifyExtra(7, samToken, sam)
        val renewed = samToken.copy(accessToken = "sam-access-2", refreshToken = "sam-cookie-2")

        store.saveSpotifyExtra(7, renewed, sam)

        assertEquals(StoredSpotifySignIn(renewed, sam), store.readSpotifyExtraSignIn(7))
        assertEquals(main, store.spotifyToken.first())
    }

    @Test fun `a refresh is saved only over the extra's sign-in it refreshed`() = runBlocking {
        store.saveSpotifyExtra(7, samToken, sam)
        val refreshed = ServiceToken("sam-fresh", "sam-cookie", expiresAtEpoch = 9_000, scope = "|webclient")

        assertTrue(store.saveRefreshedSpotifyExtraToken(7, "sam-cookie", refreshed))
        assertEquals(StoredSpotifySignIn(refreshed, sam), store.readSpotifyExtraSignIn(7))

        // Signed in again with a new cookie meanwhile: the old refresh is dropped.
        val again = samToken.copy(refreshToken = "sam-cookie-2")
        store.saveSpotifyExtra(7, again, sam)
        assertFalse(store.saveRefreshedSpotifyExtraToken(7, "sam-cookie", refreshed.copy(accessToken = "late")))
        assertEquals(again, store.readSpotifyExtraSignIn(7).token)

        // Removed meanwhile: nothing is put back.
        store.clearSpotifyExtra(7)
        assertFalse(store.saveRefreshedSpotifyExtraToken(7, "sam-cookie-2", refreshed))
        assertEquals(emptySet<String>(), keys())
    }

    @Test fun `a keyset reset sweeps every extra with the main and the cloud tokens`() = runBlocking {
        connectMain()
        store.saveSpotifyExtra(7, samToken, sam)
        store.saveSpotifyExtra(8, kimToken, kim)
        store.saveCloudToken("gdrive_3", ServiceToken("g", "g-refresh", expiresAtEpoch = 5))
        assertEquals(
            setOf("spotify_token", "spotify_account", "spotify_extra_7", "spotify_extra_8", "cloud_token_gdrive_3"),
            keys(),
        )

        // Sealed under a keyset that no longer exists: the next write drops them all.
        keysetReset = true
        store.saveYouTubeToken(ServiceToken("cookie_auth", "yt-cookie", expiresAtEpoch = 5))

        assertEquals(setOf("youtube_token"), keys())
        assertNull(store.readSpotifyExtraSignIn(7).token)
        assertNull(store.readSpotifyExtraSignIn(8).token)
    }

    @Test fun `the sweep knows the per-account key prefixes, and only those`() {
        assertTrue(EncryptedTokenStore.isPerAccountKey("spotify_extra_12"))
        assertTrue(EncryptedTokenStore.isPerAccountKey("cloud_token_gdrive_3"))
        assertFalse(EncryptedTokenStore.isPerAccountKey("spotify_token"))
        assertFalse(EncryptedTokenStore.isPerAccountKey("spotify_account"))
        assertFalse(EncryptedTokenStore.isPerAccountKey("youtube_token"))
    }

    @Test fun `the row ids with a stored extra sign-in are listed, and nothing else`() = runBlocking {
        assertEquals(emptySet<Long>(), store.spotifyExtraRowIds())

        connectMain()
        store.saveSpotifyExtra(7, samToken, sam)
        store.saveSpotifyExtra(12, kimToken, kim)
        store.saveCloudToken("gdrive_3", ServiceToken("g", "g-refresh", expiresAtEpoch = 5))
        assertEquals(setOf(7L, 12L), store.spotifyExtraRowIds())

        store.clearSpotifyExtra(7)
        assertEquals(setOf(12L), store.spotifyExtraRowIds())

        // Listed even when it can't be read: a dead blob is one to clean up too.
        every { cipher.decrypt(any()) } throws java.security.GeneralSecurityException("lost key")
        assertEquals(setOf(12L), store.spotifyExtraRowIds())
    }

    @Test fun `an unreadable extra blob reads as no sign-in`() = runBlocking {
        store.saveSpotifyExtra(7, samToken, sam)
        every { cipher.decrypt(any()) } throws java.security.GeneralSecurityException("lost key")

        assertEquals(StoredSpotifySignIn(null, null), store.readSpotifyExtraSignIn(7))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a row id that can't be a row is refused`() {
        runBlocking { store.readSpotifyExtraSignIn(0) }
    }
}

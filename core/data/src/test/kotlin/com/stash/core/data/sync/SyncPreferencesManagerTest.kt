package com.stash.core.data.sync

import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthService
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.model.MusicSource
import com.stash.core.model.SyncMode
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncPreferencesManagerTest {
    private lateinit var mgr: SyncPreferencesManager
    private val tokenManager = mockk<TokenManager>()
    private val playlistDao = mockk<PlaylistDao>()

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        // The sync_preferences DataStore is a per-process delegate that leaks
        // across tests. Deleting the backing file isn't enough — DataStore keeps
        // an in-memory cache that survives the delete — so clear() the live
        // store so each test genuinely starts from an empty store.
        runBlocking { ctx.syncPrefsDataStore.edit { it.clear() } }
        // Both accounts signed in unless a test says otherwise: every mode counts.
        signedIn(spotify = true, youtube = true)
        playlistsHere(spotify = false, youtube = false)
        mgr = SyncPreferencesManager(ctx, tokenManager, playlistDao)
    }

    private fun signedIn(spotify: Boolean, youtube: Boolean) {
        coEvery { tokenManager.isAuthenticated(AuthService.SPOTIFY) } returns spotify
        coEvery { tokenManager.isAuthenticated(AuthService.YOUTUBE_MUSIC) } returns youtube
    }

    private fun playlistsHere(spotify: Boolean, youtube: Boolean) {
        coEvery { playlistDao.hasLibraryPlaylists(MusicSource.SPOTIFY) } returns spotify
        coEvery { playlistDao.hasLibraryPlaylists(MusicSource.YOUTUBE) } returns youtube
    }

    @Test fun `default mode is ACCUMULATE for both sources on a fresh store`() = runTest {
        assertEquals(SyncMode.ACCUMULATE, mgr.spotifySyncMode.first())
        assertEquals(SyncMode.ACCUMULATE, mgr.youtubeSyncMode.first())
    }

    @Test fun `explicit REFRESH choice is preserved`() = runTest {
        mgr.setSpotifySyncMode(SyncMode.REFRESH)
        assertEquals(SyncMode.REFRESH, mgr.spotifySyncMode.first())
    }

    @Test fun `explicit ACCUMULATE choice is preserved`() = runTest {
        mgr.setYoutubeSyncMode(SyncMode.REFRESH)       // off the default
        mgr.setYoutubeSyncMode(SyncMode.ACCUMULATE)    // overwrite back
        assertEquals(SyncMode.ACCUMULATE, mgr.youtubeSyncMode.first())
    }

    @Test fun `legacy global sync_mode migrates into Spotify and is not overridden by the new default`() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.syncPrefsDataStore.edit { it[androidx.datastore.preferences.core.stringPreferencesKey("sync_mode")] = "REFRESH" }
        assertEquals(SyncMode.REFRESH, mgr.spotifySyncMode.first())
    }

    @Test fun `anyAccumulate true on fresh store (default ACCUMULATE)`() = runTest {
        assertTrue(mgr.anyAccumulate())
    }

    @Test fun `anyAccumulate false only when both sources are REFRESH`() = runTest {
        mgr.setSpotifySyncMode(SyncMode.REFRESH)
        mgr.setYoutubeSyncMode(SyncMode.REFRESH)
        assertFalse(mgr.anyAccumulate())
    }

    @Test fun `anyAccumulate true when one source accumulates`() = runTest {
        mgr.setSpotifySyncMode(SyncMode.REFRESH)
        mgr.setYoutubeSyncMode(SyncMode.ACCUMULATE)
        assertTrue(mgr.anyAccumulate())
    }

    // #273: an account never connected has no switch on screen, so its default
    // Accumulate must not stop a one-service library's Refresh from deleting.

    @Test fun `a YouTube-only library in Refresh is cleaned, Spotify never connected`() = runTest {
        signedIn(spotify = false, youtube = true)
        playlistsHere(spotify = false, youtube = true)
        mgr.setYoutubeSyncMode(SyncMode.REFRESH)
        // Spotify stays on its untouched default, Accumulate.
        assertEquals(SyncMode.ACCUMULATE, mgr.spotifySyncMode.first())

        assertFalse(mgr.anyAccumulate())
    }

    @Test fun `a Spotify-only library in Refresh is cleaned, YouTube never connected`() = runTest {
        signedIn(spotify = true, youtube = false)
        mgr.setSpotifySyncMode(SyncMode.REFRESH)

        assertFalse(mgr.anyAccumulate())
    }

    @Test fun `a one-service library still in Accumulate keeps everything`() = runTest {
        signedIn(spotify = false, youtube = true)

        assertTrue(mgr.anyAccumulate())
    }

    @Test fun `a disconnected account whose playlists remain still blocks while it accumulates`() = runTest {
        signedIn(spotify = false, youtube = true)
        playlistsHere(spotify = true, youtube = true)
        mgr.setYoutubeSyncMode(SyncMode.REFRESH)

        assertTrue(mgr.anyAccumulate())
    }

    @Test fun `a disconnected account whose playlists remain counts its Refresh too`() = runTest {
        signedIn(spotify = false, youtube = true)
        playlistsHere(spotify = true, youtube = true)
        mgr.setSpotifySyncMode(SyncMode.REFRESH)
        mgr.setYoutubeSyncMode(SyncMode.REFRESH)

        assertFalse(mgr.anyAccumulate())
    }

    @Test fun `with no account counting nothing is deleted, whatever the modes say`() = runTest {
        // No sign-in and no playlists: SoundCloud-only, local files only, or a fresh install.
        signedIn(spotify = false, youtube = false)
        mgr.setSpotifySyncMode(SyncMode.REFRESH)
        mgr.setYoutubeSyncMode(SyncMode.REFRESH)

        assertTrue(mgr.anyAccumulate())
    }

    @Test fun `when the sign-ins can't be read nothing is deleted`() = runTest {
        coEvery { tokenManager.isAuthenticated(AuthService.SPOTIFY) } throws IllegalStateException("Keystore unavailable")
        mgr.setSpotifySyncMode(SyncMode.REFRESH)
        mgr.setYoutubeSyncMode(SyncMode.REFRESH)

        assertTrue(mgr.anyAccumulate())
    }

    @Test fun `Undo's pause on Auto-sync is off by default, kept, and cleared`() = runTest {
        assertEquals(false, mgr.preferences.first().autoSyncPausedAfterUndo)

        mgr.setAutoSyncPausedAfterUndo(true)
        assertEquals(true, mgr.preferences.first().autoSyncPausedAfterUndo)

        mgr.setAutoSyncPausedAfterUndo(false)
        assertEquals(false, mgr.preferences.first().autoSyncPausedAfterUndo)
    }
}

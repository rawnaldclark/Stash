package com.stash.core.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.auth.TokenManager
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.SpotifyAccountEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.DownloadStatus
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Removing an extra Spotify account (plan 2026-11-04 §3.5): only its rows go out of sight, no
 * download is cancelled, no song is deleted, the main account and the other extras are untouched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SpotifyExtraAccountRemoverTest {

    private lateinit var db: StashDatabase
    private val tokenManager = mockk<TokenManager>(relaxed = true)
    private lateinit var remover: SpotifyExtraAccountRemover

    private val userB = "userb"
    private val userC = "userc"

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        remover = SpotifyExtraAccountRemover(db, tokenManager)
    }

    @After fun tearDown() = db.close()

    private suspend fun account(userId: String) =
        db.spotifyAccountDao().insert(SpotifyAccountEntity(userId = userId, label = userId, addedAt = 1L))

    private suspend fun row(
        sourceId: String,
        owner: String?,
        type: PlaylistType = PlaylistType.CUSTOM,
        source: MusicSource = MusicSource.SPOTIFY,
    ) = db.playlistDao().insert(
        PlaylistEntity(name = sourceId, source = source, sourceId = sourceId, type = type, spotifyAccount = owner),
    )

    private suspend fun song(title: String, playlistId: Long, downloaded: Boolean = false): Long {
        val trackId = db.trackDao().insert(
            TrackEntity(
                title = title, artist = "Artist", canonicalTitle = title.lowercase(), canonicalArtist = "artist",
                isDownloaded = downloaded, filePath = if (downloaded) "/music/$title.opus" else null,
            ),
        )
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = playlistId, trackId = trackId, position = 0))
        return trackId
    }

    @Test fun `removing an extra hides only its rows, cancels no download, and keeps its songs`() = runTest {
        val b = account(userB)
        account(userC)
        val a1 = row("a1", owner = null)
        val b1 = row("b1", owner = userB)
        val bLiked = row(SpotifyPlaylistIds.extraLikedSongs(userB), owner = userB, type = PlaylistType.LIKED_SONGS)
        val bPasted = row("album:spotify:p", owner = userB, source = MusicSource.BOTH)
        val c1 = row("c1", owner = userC)
        val queuedA = song("QA", a1)
        val queuedB = song("QB", b1)
        val downloadedB = song("DB", b1, downloaded = true)
        db.downloadQueueDao().insert(DownloadQueueEntity(trackId = queuedA, searchQuery = "a"))
        db.downloadQueueDao().insert(DownloadQueueEntity(trackId = queuedB, searchQuery = "b"))

        val hidden = remover.remove(b)

        assertEquals(2, hidden)
        assertFalse(db.playlistDao().getById(b1)!!.isActive)
        assertFalse(db.playlistDao().getById(bLiked)!!.isActive)
        assertTrue("the main account's rows stay", db.playlistDao().getById(a1)!!.isActive)
        assertTrue("another extra's rows stay", db.playlistDao().getById(c1)!!.isActive)
        assertTrue("a save of the listener's own stays", db.playlistDao().getById(bPasted)!!.isActive)
        assertEquals("rows keep their tag, so adding it again re-adopts them", userB, db.playlistDao().getById(b1)!!.spotifyAccount)
        assertEquals(DownloadStatus.PENDING, db.downloadQueueDao().getByTrackId(queuedA)!!.status)
        assertEquals("not cancelled by source", DownloadStatus.PENDING, db.downloadQueueDao().getByTrackId(queuedB)!!.status)
        assertNotNull("its downloaded song stays", db.trackDao().getById(downloadedB))
        assertNull(db.spotifyAccountDao().getById(b))
        assertNotNull(db.spotifyAccountDao().getByUserId(userC))
        coVerify(exactly = 1) { tokenManager.clearSpotifyExtra(b) }
    }

    @Test fun `removing an account that is already gone does nothing`() = runTest {
        val b = account(userB)
        remover.remove(b)

        assertNull(remover.remove(b))
        coVerify(exactly = 1) { tokenManager.clearSpotifyExtra(b) }
    }

    @Test fun `a sign-in that can't be forgotten doesn't undo the removal`() = runTest {
        val b = account(userB)
        val b1 = row("b1", owner = userB)
        coEvery { tokenManager.clearSpotifyExtra(any()) } throws IllegalStateException("keystore")

        assertEquals(1, remover.remove(b))
        assertFalse(db.playlistDao().getById(b1)!!.isActive)
        assertNull(db.spotifyAccountDao().getById(b))
    }
}

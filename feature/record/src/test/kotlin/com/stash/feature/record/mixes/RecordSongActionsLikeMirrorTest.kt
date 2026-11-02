package com.stash.feature.record.mixes

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.social.LikeCoordinator
import com.stash.core.model.Track
import com.stash.feature.record.bridge.RecordMatch
import com.stash.feature.record.bridge.RecordSong
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Your record's hearts are the app's hearts: built the way Hilt builds it, a row's Like and unlike go through [LikeCoordinator]
 * (which puts them in Stash's Liked Songs and, with a "Like on … too" switch on, in the mirror's outbox), never straight to
 * Stash's Liked Songs alone, as they did before (a heart there never reached Last.fm, Spotify or YouTube Music).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordSongActionsLikeMirrorTest {

    private val song = RecordSong(title = "Lost 03", artist = "Lost Band", album = "Faded", sid = "sid3", cover = "https://cdn/c.jpg")
    private val row = Track(id = 42L, title = "Lost 03", artist = "Lost Band")

    private val likes = mockk<LikeCoordinator>(relaxed = true)
    private val match = mockk<RecordMatch> { coEvery { rows(any()) } returns listOf(row) }
    private val blocklist = mockk<BlocklistGuard> { coEvery { isBlocked(any(), any(), any(), any()) } returns false }

    @Before fun mainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun actions() = RecordSongActions(
        match = match,
        likes = likes,
        player = mockk(relaxed = true),
        music = mockk(relaxed = true),
        playlistDao = mockk(relaxed = true),
        blocklist = blocklist,
        listenTogether = mockk(relaxed = true),
        context = mockk<Context>(relaxed = true),
    )

    @Test fun `a record heart goes through the coordinator, so it is mirrored like any heart`() = runTest {
        assertThat(actions().like(song).await()).isEqualTo(42L)
        coVerify(exactly = 1) { likes.setLiked(42L, liked = true) }
    }

    @Test fun `a record un-heart goes through the coordinator too, for every row of the song`() = runTest {
        assertThat(actions().unlike(listOf(42L, 43L, 42L)).await()).isTrue()
        coVerify(exactly = 1) { likes.setLiked(42L, liked = false) }
        coVerify(exactly = 1) { likes.setLiked(43L, liked = false) }
    }
}

package com.stash.core.data.social

import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.sync.TrackMatcher
import com.stash.core.data.sync.TrackIdentityEvents
import com.stash.core.model.Track
import com.stash.data.spotify.SpotifyApiClient
import com.stash.data.spotify.SpotifyTrackCandidate
import com.stash.data.ytmusic.YTMusicApiClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Cross-platform id backfill for a like. The correctness bar: NEVER resolve a
 * wrong recording (that would save the wrong song). Uses the REAL TrackMatcher
 * so the fuzzy gate under test is the one that actually ships.
 */
class CrossPlatformLikeResolverTest {

    private val spotify = mockk<SpotifyApiClient>()
    private val yt = mockk<YTMusicApiClient>()
    private val dao = mockk<TrackDao>(relaxed = true)
    private val trackIdentityEvents = mockk<TrackIdentityEvents>(relaxed = true)
    private fun resolver() = CrossPlatformLikeResolver(spotify, yt, TrackMatcher(), dao, trackIdentityEvents)

    private fun track(sp: String? = null, ytId: String? = null) = Track(
        id = 5L,
        title = "Black Out Days",
        artist = "Phantogram",
        durationMs = 220_000,
        spotifyUri = sp,
        youtubeId = ytId,
    )

    private fun candidate(
        id: String,
        name: String = "Black Out Days",
        artist: String = "Phantogram",
        durationMs: Long = 220_000,
    ) = SpotifyTrackCandidate(
        id = id, name = name, artists = listOf(artist),
        albumName = "Voices", durationMs = durationMs, isrc = null, explicit = false,
    )

    // ── ensureSpotifyUri ────────────────────────────────────────────────

    @Test fun `returns existing uri without searching`() = runTest {
        val uri = resolver().ensureSpotifyUri(track(sp = "spotify:track:existing"))
        assertEquals("spotify:track:existing", uri)
        coVerify(exactly = 0) { spotify.searchTracksGraphQL(any(), any()) }
    }

    @Test fun `confident match resolves and persists the uri`() = runTest {
        coEvery { spotify.searchTracksGraphQL(any(), any()) } returns listOf(candidate("abc123"))

        val uri = resolver().ensureSpotifyUri(track())

        assertEquals("spotify:track:abc123", uri)
        coVerify { dao.updateSpotifyUri(5L, "spotify:track:abc123") }
    }

    @Test fun `picks the closest-duration passer`() = runTest {
        coEvery { spotify.searchTracksGraphQL(any(), any()) } returns listOf(
            candidate("far", durationMs = 228_000),  // 8s off — still passes
            candidate("near", durationMs = 220_500),  // 0.5s off — closest
        )

        assertEquals("spotify:track:near", resolver().ensureSpotifyUri(track()))
    }

    @Test fun `rejects a wrong recording that fails the fuzzy gate`() = runTest {
        // Right title, wrong duration (>10s) and different artist — must not match.
        coEvery { spotify.searchTracksGraphQL(any(), any()) } returns listOf(
            candidate("wrong", name = "Black Out Days", artist = "Karaoke Band", durationMs = 260_000),
        )

        assertNull(resolver().ensureSpotifyUri(track()))
        coVerify(exactly = 0) { dao.updateSpotifyUri(any(), any()) }
    }

    @Test fun `empty candidate list yields no match`() = runTest {
        coEvery { spotify.searchTracksGraphQL(any(), any()) } returns emptyList()
        assertNull(resolver().ensureSpotifyUri(track()))
    }

    @Test fun `blank artist short-circuits without searching`() = runTest {
        assertNull(resolver().ensureSpotifyUri(track().copy(artist = "")))
        coVerify(exactly = 0) { spotify.searchTracksGraphQL(any(), any()) }
    }

    // ── ensureYoutubeId ─────────────────────────────────────────────────

    @Test fun `returns existing youtube id without searching`() = runTest {
        val id = resolver().ensureYoutubeId(track(ytId = "vidExisting"))
        assertEquals("vidExisting", id)
        coVerify(exactly = 0) { yt.searchCanonicalVideoId(any(), any(), any()) }
    }

    @Test fun `resolves and persists the youtube id from canonical search`() = runTest {
        coEvery { yt.searchCanonicalVideoId("Phantogram", "Black Out Days") } returns "vidABC"

        val id = resolver().ensureYoutubeId(track())

        assertEquals("vidABC", id)
        coVerify { dao.updateYoutubeId(5L, "vidABC") }
    }

    @Test fun `null canonical search yields no id and no persist`() = runTest {
        coEvery { yt.searchCanonicalVideoId(any(), any(), any()) } returns null

        assertNull(resolver().ensureYoutubeId(track()))
        coVerify(exactly = 0) { dao.updateYoutubeId(any(), any()) }
    }
}

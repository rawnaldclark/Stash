package com.stash.core.data.social

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.lastfm.LastFmApiException
import com.stash.core.data.lastfm.LastFmRateLimitedException
import com.stash.core.data.social.spotify.SpotifyApiException
import com.stash.core.data.social.spotify.SpotifyAuthException
import com.stash.core.data.social.spotify.SpotifyRateLimitException
import com.stash.core.data.social.ytmusic.YtMusicLibraryException
import com.stash.core.model.SyncMode
import java.net.UnknownHostException
import org.junit.Test

/** The line under each switch (from the outbox), why a try failed, and the Spotify "liked there already?" answer. */
class LikeMirrorStatusTest {

    private fun op(
        seq: Long,
        destination: Destination = Destination.SPOTIFY,
        attempts: Int = 0,
        error: LikeMirrorError? = null,
        at: Long? = null,
    ) = LikeOutboxOp(seq, trackId = seq, destination = destination, liked = true, queuedAt = 0L, attempts = attempts, lastError = error, lastAttemptAt = at)

    // -- Status ------------------------------------------------------------------------------------------------------------

    @Test fun `nothing waiting is up to date, whatever other services have`() {
        assertThat(LikeMirrorStatus.of(emptyList(), Destination.SPOTIFY)).isEqualTo(LikeMirrorStatus.UP_TO_DATE)
        assertThat(LikeMirrorStatus.of(listOf(op(1, Destination.LAST_FM)), Destination.SPOTIFY)).isEqualTo(LikeMirrorStatus.UP_TO_DATE)
    }

    @Test fun `fresh hearts are just waiting`() {
        assertThat(LikeMirrorStatus.of(listOf(op(1), op(2)), Destination.SPOTIFY))
            .isEqualTo(LikeMirrorStatus(LikeMirrorStatus.State.WAITING, waiting = 2))
    }

    @Test fun `waiting for the network says so`() {
        assertThat(LikeMirrorStatus.of(listOf(op(1, error = LikeMirrorError.OFFLINE, at = 5), op(2)), Destination.SPOTIFY))
            .isEqualTo(LikeMirrorStatus(LikeMirrorStatus.State.OFFLINE, 2, LikeMirrorError.OFFLINE))
    }

    @Test fun `a refusal or a rate limit is retrying, by the latest try`() {
        val ops = listOf(op(1, attempts = 2, error = LikeMirrorError.REFUSED, at = 5), op(2, attempts = 1, error = LikeMirrorError.RATE_LIMITED, at = 9))
        assertThat(LikeMirrorStatus.of(ops, Destination.SPOTIFY))
            .isEqualTo(LikeMirrorStatus(LikeMirrorStatus.State.RETRYING, 2, LikeMirrorError.RATE_LIMITED))
    }

    @Test fun `an expired sign-in or a parked op needs the listener`() {
        assertThat(LikeMirrorStatus.of(listOf(op(1, attempts = 1, error = LikeMirrorError.SIGNED_OUT, at = 5)), Destination.SPOTIFY))
            .isEqualTo(LikeMirrorStatus(LikeMirrorStatus.State.NEEDS_YOU, 1, LikeMirrorError.SIGNED_OUT))
        // A parked refusal wins over a newer, still-retrying one: the listener has something to do
        val ops = listOf(
            op(1, attempts = LikeOutbox.MAX_ATTEMPTS, error = LikeMirrorError.REFUSED, at = 5),
            op(2, attempts = 1, error = LikeMirrorError.RATE_LIMITED, at = 9),
        )
        assertThat(LikeMirrorStatus.of(ops, Destination.SPOTIFY))
            .isEqualTo(LikeMirrorStatus(LikeMirrorStatus.State.NEEDS_YOU, 2, LikeMirrorError.REFUSED))
    }

    // -- Why a try failed --------------------------------------------------------------------------------------------------

    @Test fun `failures are sorted into what the outbox does next`() {
        assertThat(LikeMirrorError.of(UnknownHostException("api.spotify.com"))).isEqualTo(LikeMirrorError.OFFLINE)
        assertThat(LikeMirrorError.of(SpotifyAuthException())).isEqualTo(LikeMirrorError.SIGNED_OUT)
        assertThat(LikeMirrorError.of(NoLastFmSessionException())).isEqualTo(LikeMirrorError.SIGNED_OUT)
        assertThat(LikeMirrorError.of(LastFmApiException(9, "Invalid session key"))).isEqualTo(LikeMirrorError.SIGNED_OUT)
        assertThat(LikeMirrorError.of(SpotifyRateLimitException(30))).isEqualTo(LikeMirrorError.RATE_LIMITED)
        assertThat(LikeMirrorError.of(LastFmRateLimitedException("429"))).isEqualTo(LikeMirrorError.RATE_LIMITED)
        assertThat(LikeMirrorError.of(LastFmApiException(29, "Rate limit"))).isEqualTo(LikeMirrorError.RATE_LIMITED)
        assertThat(LikeMirrorError.of(LastFmApiException(6, "Track not found"))).isEqualTo(LikeMirrorError.PERMANENT)
        assertThat(LikeMirrorError.of(YtMusicLibraryException("x", 401))).isEqualTo(LikeMirrorError.SIGNED_OUT)
        assertThat(LikeMirrorError.of(YtMusicLibraryException("x", 429))).isEqualTo(LikeMirrorError.RATE_LIMITED)
        assertThat(LikeMirrorError.of(YtMusicLibraryException("x", 500))).isEqualTo(LikeMirrorError.REFUSED)
        // A 400 might be a changed client, not this song: retried a few times and shown, never silently dropped
        assertThat(LikeMirrorError.of(YtMusicLibraryException("x", 400))).isEqualTo(LikeMirrorError.REFUSED)
        assertThat(LikeMirrorError.of(SpotifyApiException(0, "graphql errors"))).isEqualTo(LikeMirrorError.REFUSED)
        assertThat(LikeMirrorError.of(IllegalStateException("?"))).isEqualTo(LikeMirrorError.REFUSED)
    }

    // -- Spotify: liked there already? -------------------------------------------------------------------------------------

    private val now = 1_800_000_000_000L
    private val fresh = now - 60_000L
    private val stale = now - ServiceLikedThereCheck.SPOTIFY_FRESH_MS - 1

    @Test fun `a fresh Refresh-mode copy answers yes and no`() {
        assertThat(ServiceLikedThereCheck.spotifyAnswer(fresh, inLikedSongs = true, mirrorsRemovals = true, now = now)).isEqualTo(LikedThere.YES)
        assertThat(ServiceLikedThereCheck.spotifyAnswer(fresh, inLikedSongs = false, mirrorsRemovals = true, now = now)).isEqualTo(LikedThere.NO)
    }

    @Test fun `an Accumulate copy can't tell a song since unliked on Spotify, so in it is unknown`() {
        assertThat(ServiceLikedThereCheck.spotifyAnswer(fresh, inLikedSongs = true, mirrorsRemovals = false, now = now)).isEqualTo(LikedThere.UNKNOWN)
        // Not in it is still a no: Accumulate keeps every song that was ever liked there
        assertThat(ServiceLikedThereCheck.spotifyAnswer(fresh, inLikedSongs = false, mirrorsRemovals = false, now = now)).isEqualTo(LikedThere.NO)
    }

    @Test fun `no copy, or a stale one, is unknown, never a no`() {
        assertThat(ServiceLikedThereCheck.spotifyAnswer(null, inLikedSongs = false, mirrorsRemovals = true, now = now)).isEqualTo(LikedThere.UNKNOWN)
        assertThat(ServiceLikedThereCheck.spotifyAnswer(stale, inLikedSongs = false, mirrorsRemovals = true, now = now)).isEqualTo(LikedThere.UNKNOWN)
        assertThat(ServiceLikedThereCheck.spotifyAnswer(stale, inLikedSongs = true, mirrorsRemovals = true, now = now)).isEqualTo(LikedThere.UNKNOWN)
    }

    @Test fun `the sync modes the answer depends on are the real ones`() {
        // Guards the REFRESH comparison in onSpotify against a renamed mode
        assertThat(SyncMode.entries.map { it.name }).containsAtLeast("REFRESH", "ACCUMULATE")
    }
}

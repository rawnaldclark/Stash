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
    private val completeRecently = now - 10 * 60_000L
    private val completeLongAgo = now - ServiceLikedThereCheck.SPOTIFY_COMPLETE_MS - 1

    private fun answer(lastSyncedAt: Long?, completeAt: Long?, inLikedSongs: Boolean, refresh: Boolean = true) =
        ServiceLikedThereCheck.spotifyAnswer(lastSyncedAt, completeAt, inLikedSongs, mirrorsRemovals = refresh, now = now)

    @Test fun `in a fresh Refresh-mode copy is a yes`() {
        assertThat(answer(fresh, completeRecently, inLikedSongs = true)).isEqualTo(LikedThere.YES)
        // A partial fetch can only miss songs, never add one: in it is still a yes
        assertThat(answer(fresh, completeAt = null, inLikedSongs = true)).isEqualTo(LikedThere.YES)
    }

    @Test fun `an Accumulate copy can't tell a song since unliked on Spotify, so in it is unknown`() {
        assertThat(answer(fresh, completeRecently, inLikedSongs = true, refresh = false)).isEqualTo(LikedThere.UNKNOWN)
        // Not in a complete, recent copy is still a no: Accumulate keeps every song that was ever liked there
        assertThat(answer(fresh, completeRecently, inLikedSongs = false, refresh = false)).isEqualTo(LikedThere.NO)
    }

    @Test fun `not in it is a no only when the last fetch was complete and under an hour old`() {
        assertThat(answer(fresh, completeRecently, inLikedSongs = false)).isEqualTo(LikedThere.NO)
        // The last fetch was partial (its last-synced time moved anyway): the song may be on a page that never came
        assertThat(answer(fresh, completeAt = null, inLikedSongs = false)).isEqualTo(LikedThere.UNKNOWN)
        // Complete, but long enough ago for the listener to have liked it on Spotify since
        assertThat(answer(fresh, completeLongAgo, inLikedSongs = false)).isEqualTo(LikedThere.UNKNOWN)
        // A clock set back: never a no on a time from the future
        assertThat(answer(fresh, now + 60_000L, inLikedSongs = false)).isEqualTo(LikedThere.UNKNOWN)
    }

    @Test fun `no copy, or a stale one, is never a yes and never a no`() {
        assertThat(answer(null, null, inLikedSongs = false)).isEqualTo(LikedThere.UNKNOWN)
        assertThat(answer(stale, completeAt = null, inLikedSongs = false)).isEqualTo(LikedThere.UNKNOWN)
        assertThat(answer(stale, completeAt = null, inLikedSongs = true)).isEqualTo(LikedThere.UNKNOWN)
    }

    @Test fun `the sync modes the answer depends on are the real ones`() {
        // Guards the REFRESH comparison in onSpotify against a renamed mode
        assertThat(SyncMode.entries.map { it.name }).containsAtLeast("REFRESH", "ACCUMULATE")
    }
}

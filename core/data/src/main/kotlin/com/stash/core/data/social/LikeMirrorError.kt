package com.stash.core.data.social

import com.stash.core.data.lastfm.LastFmApiException
import com.stash.core.data.lastfm.LastFmRateLimitedException
import com.stash.core.data.social.spotify.SpotifyAuthException
import com.stash.core.data.social.spotify.SpotifyRateLimitException
import com.stash.core.data.social.ytmusic.YtMusicLibraryException
import com.stash.data.ytmusic.InnerTubeClient

/**
 * Why a mirrored like didn't reach its service this time. It decides what the outbox does next ([LikeOutbox]) and what the
 * status line under the switch says (see [LikeMirrorStatus]).
 */
enum class LikeMirrorError {
    /** No connection, or it dropped mid-call. The like waits for the network; this never counts as a try. */
    OFFLINE,

    /** The service no longer takes this phone's sign-in. Settings says so; a reconnect (a later success) frees the waiting likes. */
    SIGNED_OUT,

    /** The service asked Stash to slow down. Tried again later, on the worker's back-off. */
    RATE_LIMITED,

    /** The service said no for another reason (an outage, a changed private endpoint). Tried again later, a few times. */
    REFUSED,

    /** The service will never take this one (Last.fm doesn't know the song). Dropped at once, never retried. */
    PERMANENT,
    ;

    companion object {
        /** Last.fm's "Authentication failed", "Invalid session key" and "Unauthorized token" (last.fm/api/errorcodes). */
        private val LAST_FM_SIGNED_OUT = setOf(4, 9, 14)

        /**
         * What [error] (a destination's failed [Result] from [LikeDestinationDispatcher]) means for the outbox. Anything not
         * recognised is [REFUSED]: retried a bounded number of times, then shown, never silently dropped.
         */
        fun of(error: Throwable): LikeMirrorError = when (error) {
            // OkHttp's UnknownHost / ConnectException / SocketTimeout: the phone, not the service
            is java.io.IOException -> OFFLINE
            is SpotifyAuthException, is NoLastFmSessionException -> SIGNED_OUT
            is SpotifyRateLimitException, is LastFmRateLimitedException -> RATE_LIMITED
            is LastFmApiException -> when {
                error.errorCode in LAST_FM_SIGNED_OUT -> SIGNED_OUT
                error.code == LastFmApiException.RATE_LIMIT_EXCEEDED -> RATE_LIMITED
                error.code == LastFmApiException.NOT_FOUND || error.code == LastFmApiException.INVALID_RESOURCE -> PERMANENT
                else -> REFUSED
            }
            is YtMusicLibraryException -> when (error.status) {
                InnerTubeClient.RequestOutcome.STATUS_NETWORK_ERROR -> OFFLINE
                401, 403 -> SIGNED_OUT
                429 -> RATE_LIMITED
                else -> REFUSED
            }
            // The coordinator never sends a song without the id; if one slips through, retrying can't help
            is NoSpotifyUriException, is NoYouTubeIdException -> PERMANENT
            else -> REFUSED
        }
    }
}

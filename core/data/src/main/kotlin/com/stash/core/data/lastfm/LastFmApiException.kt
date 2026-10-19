package com.stash.core.data.lastfm

import kotlin.coroutines.cancellation.CancellationException

/**
 * Last.fm answered with an `{ error, message }` body. [code] is Last.fm's own
 * numeric error code (as text, the way the JSON carries it).
 *
 * Typed so callers can tell a DEFINITIVE answer ("that track/artist isn't in
 * the catalogue", error 6) from an outage (network, 5xx, 429, every read key
 * throttled). The enrichment workers write permanent "nothing to find"
 * sentinels; before this they could only see "the call failed", so a 30 s
 * Wi-Fi drop poisoned a whole batch of tracks for good.
 *
 * Extends [IllegalStateException] because that is what `error(...)` threw here
 * before, so any existing catch keeps matching.
 */
class LastFmApiException(
    val code: String,
    message: String,
) : IllegalStateException(message) {
    constructor(errorCode: Int, message: String) : this(errorCode.toString(), message)

    /**
     * [code] as a number (one of https://www.last.fm/api/errorcodes), or -1
     * when Last.fm sent something that isn't one.
     */
    val errorCode: Int get() = code.toIntOrNull() ?: -1

    companion object {
        /** Last.fm's "Track not found" / "The artist you supplied could not be found". */
        const val NOT_FOUND = "6"

        /** Last.fm's "Invalid resource specified": as final as [NOT_FOUND] for a lookup. */
        const val INVALID_RESOURCE = "7"

        /** Last.fm's "Rate limit exceeded". */
        const val RATE_LIMIT_EXCEEDED = "29"
    }
}

/**
 * A Last.fm read refused for rate limiting rather than for anything about the request: HTTP 429,
 * or every read key's breaker open so the client didn't send it at all. (Last.fm's own error 29
 * arrives as a [LastFmApiException] with [LastFmApiException.RATE_LIMIT_EXCEEDED].)
 *
 * Extends [IllegalStateException], as the plain `error(...)` it replaces did.
 */
class LastFmRateLimitedException(message: String) : IllegalStateException(message)

/**
 * Last.fm (or the proxy) answered HTTP [status] with no `{ error }` body. Typed so a caller can
 * tell a server error (5xx: worth retrying) from anything else. Extends [IllegalStateException],
 * as the plain `error(...)` it replaces did, with the same message.
 */
class LastFmHttpException(val status: Int, message: String) : IllegalStateException(message)

/**
 * True when this read failed because Last.fm (or the client's breakers) is rate limiting: the
 * request says nothing about the artist or track, so a caller must neither record "no data" nor
 * "this one fails", and should simply try again later. Rethrows a captured cancellation.
 */
fun Result<*>.isLastFmRateLimited(): Boolean {
    val error = exceptionOrNull() ?: return false
    if (error is CancellationException) throw error
    return error is LastFmRateLimitedException ||
        (error is LastFmApiException && error.code == LastFmApiException.RATE_LIMIT_EXCEEDED)
}

/**
 * True when this read failed for a reason that may go away (anything except a
 * definitive Last.fm "not found"), so the caller must NOT record "no data".
 * A success is never transient.
 *
 * The client's read methods wrap their body in `runCatching`, which also
 * catches coroutine cancellation; that is rethrown here so a cancelled worker
 * stops instead of carrying on as if one request had failed.
 */
fun Result<*>.isTransientLastFmFailure(): Boolean {
    val error = exceptionOrNull() ?: return false
    if (error is CancellationException) throw error
    return !(error is LastFmApiException && error.code == LastFmApiException.NOT_FOUND)
}

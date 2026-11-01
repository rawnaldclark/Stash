package com.stash.feature.search

import com.stash.data.ytmusic.SearchUnavailableException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.cancellation.CancellationException

/** Error-screen copy for a failed search. */
internal object SearchErrorCopy {
    // Not "You're offline": Offline is the mode's name (2026-11-01).
    const val OFFLINE = "No internet connection, or YouTube Music can't be reached."
    const val SLOW = "YouTube Music took too long to answer. Try again."
    const val BUSY = "YouTube Music is busy right now. Try again in a minute."
    const val NO_ANSWER = "YouTube Music didn't answer. Try again."
}

/**
 * Plain words for a failed search, instead of the raw exception text
 * ("Unable to resolve host music.youtube.com: No address associated…").
 *
 * Null for a cancellation: a newer keystroke superseding this search is not a
 * failure, so the caller rethrows it rather than showing an error screen (and
 * `flatMapLatest` needs it to propagate to tear the old search down).
 */
internal fun searchErrorMessage(t: Throwable): String? = when {
    t is CancellationException -> null
    t is UnknownHostException || t is ConnectException || t is NoRouteToHostException ->
        SearchErrorCopy.OFFLINE
    t is SocketTimeoutException -> SearchErrorCopy.SLOW
    // YouTube Music's client reports "no answer" as a status, not its cause:
    // no response at all is most often no connection.
    t is SearchUnavailableException && t.noResponse -> SearchErrorCopy.OFFLINE
    t is SearchUnavailableException && t.statusCode == 429 -> SearchErrorCopy.BUSY
    else -> SearchErrorCopy.NO_ANSWER
}

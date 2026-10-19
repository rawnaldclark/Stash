package com.stash.core.data.lastfm.recap

import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmRecentTracksPage
import com.stash.core.data.lastfm.LastFmTag

/**
 * What the Last.fm recap reads from Last.fm, as an interface so the tests can stand in for it.
 * Every call is a read: nothing is ever written to Last.fm (no scrobble, no love). History reads
 * are unsigned, except that the connected account may read its OWN history signed with its own
 * session key (a profile that hides its listening).
 */
interface LastFmRecapRemote {
    /**
     * One page of [user]'s scrobbles. [sessionKey] non-null = this request is signed with it
     * (the repository only passes the connected account's own key, for that account's own name).
     */
    suspend fun recentTracks(user: String, fromSec: Long, toSec: Long, page: Int, limit: Int, sessionKey: String?): Result<LastFmRecentTracksPage>

    suspend fun registeredAt(user: String): Result<Long?>

    /** track.getInfo's length (cacheable: the proxy and the shared lastfm_cache table keep it). */
    suspend fun trackDurationMs(artist: String, title: String): Result<Long?>

    /** artist.getTopTags (cacheable, as above). */
    suspend fun artistTags(artist: String): Result<List<LastFmTag>>

    /** How long until a history read may go out again after a rate limit (0 = now). */
    fun busyWaitMs(signed: Boolean): Long
}

/** [LastFmRecapRemote] over [LastFmApiClient]. */
internal class LastFmRecapApiRemote(private val api: LastFmApiClient) : LastFmRecapRemote {
    override suspend fun recentTracks(user: String, fromSec: Long, toSec: Long, page: Int, limit: Int, sessionKey: String?) =
        if (sessionKey != null) {
            api.getUserRecentTracksSigned(sessionKey, user, fromSec, toSec, page, limit)
        } else {
            api.getUserRecentTracks(user, fromSec, toSec, page, limit)
        }

    override suspend fun registeredAt(user: String) = api.getUserRegisteredAt(user)

    override suspend fun trackDurationMs(artist: String, title: String): Result<Long?> =
        api.getTrackInfo(artist, title).map { it.durationMs }

    override suspend fun artistTags(artist: String): Result<List<LastFmTag>> = api.getArtistTopTags(artist)

    override fun busyWaitMs(signed: Boolean): Long = api.recapBusyWaitMs(signed)
}

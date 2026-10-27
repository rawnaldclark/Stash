package com.stash.feature.record.lastfm

import android.util.Log
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmApiException
import com.stash.core.data.lastfm.recap.LastFmHistoryPacer
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import java.io.IOException

/** The connected Last.fm account (Settings › Accounts & Sync): its name and its session key, for a private profile's own reads. */
data class LastfmAccount(val name: String, val sessionKey: String?) {
    override fun toString(): String = "LastfmAccount($name, session=${sessionKey != null})"
}

/** How the reader talks to Last.fm: the app's one client ([LastFmApiClient]), as an interface so the tests can stand in for it. */
interface LastfmRemote {
    suspend fun page(user: String, from: Long?, to: Long, page: Int): JsonObject

    suspend fun signedPage(sessionKey: String, user: String, from: Long?, to: Long, page: Int): JsonObject
}

/** [LastfmRemote] over [LastFmApiClient], every request through the pace the Last.fm recap shares ([LastFmHistoryPacer]). */
internal class ClientLastfmRemote(private val api: LastFmApiClient, private val pacer: LastFmHistoryPacer) : LastfmRemote {
    override suspend fun page(user: String, from: Long?, to: Long, page: Int): JsonObject =
        pacer.paced { api.userRecentTracksJson(user, from, to, page, LastfmHistory.HISTORY_LIMIT) }

    override suspend fun signedPage(sessionKey: String, user: String, from: Long?, to: Long, page: Int): JsonObject =
        pacer.paced { api.userRecentTracksSignedJson(sessionKey, user, from, to, page, LastfmHistory.HISTORY_LIMIT) }
}

/**
 * The connected listener's scrobbles, a page at a time (the web's `httpPageReader` and its Worker's `historyPage` together):
 * straight from Last.fm, never through the Stash proxy, whose load budget is spoken for. Unsigned first, on the read keys the
 * Last.fm recap pages with, so a long history never throttles the key scrobbling and sign-in depend on; a profile that hides
 * its listening (error 17 unsigned) reads itself signed with its own session key, as the Last.fm recap does, and every later page
 * goes signed straight away. (The web's Worker always reads signed: the same pages, the same answers.)
 *
 * What an answer means is the Worker's rule ([LastfmHistory.error], [LastfmHistory.read]): 6 or 17 on page 1 is no history; a
 * session Last.fm no longer takes (9, on a signed read), or an account that changed under the run, is
 * [LastfmFailure.RECONNECT]; everything else Last.fm or the network can say (busy, rate limited, an outage, an answer that isn't
 * a page, 6 or 17 past page 1, an empty page before the last) is [LastfmFailure.BUSY], which the import waits out and asks again.
 *
 * [account] is asked before every page: the run stops for a reconnect the moment the account it reads is gone.
 */
class LastfmPages(
    private val remote: LastfmRemote,
    private val user: String,
    private val account: suspend () -> LastfmAccount?,
    private val nowS: () -> Long,
) : PageReader {
    @Volatile private var signed = false

    override suspend fun read(page: Int, to: Long, from: Long?): HistoryPage {
        val acc = account()
        if (acc == null || acc.name != user) throw LastfmError(LastfmFailure.RECONNECT)
        val key = acc.sessionKey
        var (answer, code) = ask(key, page, to, from)
        if (code == ERROR_LOGIN_REQUIRED && !signed && key != null) {
            // A private profile: its own session shows it its history (and every later page goes signed)
            signed = true
            Log.i(TAG, "Last.fm keeps this profile's listening private; reading it with its own session")
            answer = ask(key, page, to, from).first
        }
        return when (answer) {
            is HistoryAnswer.Page -> answer.page
            HistoryAnswer.Retry -> throw LastfmError(LastfmFailure.BUSY)
            is HistoryAnswer.Empty -> throw LastfmError(LastfmFailure.BUSY, empty = true, pages = answer.pages)
            HistoryAnswer.Reconnect -> throw LastfmError(LastfmFailure.RECONNECT)
        }
    }

    /** One read of the page, and Last.fm's error code if it answered with one (0: none). */
    private suspend fun ask(key: String?, page: Int, to: Long, from: Long?): Pair<HistoryAnswer, Int> {
        val sign = signed && key != null
        val body = try {
            if (sign) remote.signedPage(key!!, user, from, to, page) else remote.page(user, from, to, page)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LastFmApiException) {
            // Last.fm's `{ error }`, kept by the client as it threw
            return LastfmHistory.error(e.errorCode, page, sign) to e.errorCode
        } catch (e: Exception) {
            throw busy(e)
        }
        val code = LastfmHistory.int(body["error"]).let { if (it.isFinite()) it.toInt() else 0 }
        return LastfmHistory.read(body, page, nowS(), sign) to code
    }

    /**
     * The Worker's "try again" for what the network or Last.fm can throw: no connection, a rate limit, an HTTP failure, a body
     * that isn't JSON. Anything else (a fault of this code) isn't Last.fm being busy: it goes on as it is ([LastfmFailure.OTHER]).
     */
    private fun busy(e: Exception): Exception = when (e) {
        is IOException, is IllegalStateException, is IllegalArgumentException -> {
            Log.i(TAG, "Last.fm didn't answer this page: ${e.javaClass.simpleName}")
            LastfmError(LastfmFailure.BUSY)
        }
        else -> e
    }

    private companion object {
        const val TAG = "YourRecord"

        /** Last.fm's "Login: User required to be logged in": the profile hides its listening. */
        const val ERROR_LOGIN_REQUIRED = 17
    }
}

package com.stash.core.data.youtube

import android.util.Log
import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthService
import com.stash.core.auth.youtube.YouTubeCookieHelper
import com.stash.core.data.db.dao.ListeningEventDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.ListeningEventEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.YouTubeHistoryPreference
import com.stash.data.ytmusic.CanonicalLookup
import com.stash.data.ytmusic.InnerTubeClient
import com.stash.data.ytmusic.YouTubeCredentialUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Abstraction over the single HTTP ping used to record a play in YouTube Music's
 * Watch History. Extracted for testability — tests inject a fake that returns
 * canned HTTP status codes; production uses [OkHttpPingSubmitter].
 *
 * @param url      The `videostatsPlaybackUrl` tracking URL from InnerTube.
 * @param cookies  Full browser cookie string.
 * @param sapiSid  The SAPISID value extracted from [cookies].
 * @return The HTTP response code, or throws on network error.
 */
fun interface PingSubmitter {
    suspend fun submit(url: String, cookies: String, sapiSid: String): Int
}

/**
 * Production [PingSubmitter] backed by the app-wide [OkHttpClient].
 *
 * Issues the SAPISIDHASH-authenticated GET that registers a watch-history
 * event with YT Music. The request shape mirrors `ytmusicapi.add_history_item`
 * and SimpMusic's implementation:
 *
 *   - **GET** (not POST). The `api/stats/playback` endpoint is a tracking-
 *     pixel style GET that reads its state from the query string; the
 *     `videostatsPlaybackUrl.baseUrl` InnerTube returns already contains
 *     `docid` + the signed session id.
 *   - Append **`cpn`** (16-char random content-playback-nonce), **`ver=2`**,
 *     and **`c=WEB_REMIX`** as query params. Without these YT silently
 *     accepts the request (2xx) but never records it in history. This is
 *     what caused the initial "pings marked scrobbled but YT history
 *     stays empty" bug during end-to-end testing.
 *   - Standard SAPISIDHASH auth header with origin `https://music.youtube.com`
 *     (matches the hash computed by [YouTubeCookieHelper.generateAuthHeader]).
 *   - `X-Origin` mirrors `Origin` — real YT Music clients send both.
 *
 * Only a URL that may receive YouTube credentials ([YouTubeCredentialUrl])
 * is pinged: for any other, [submit] throws before a request is built, and
 * no ping is sent. The ping never follows a redirect, so the credentials go
 * only to the URL that was checked.
 */
class OkHttpPingSubmitter @Inject constructor(
    okHttpClient: OkHttpClient,
    private val cookieHelper: YouTubeCookieHelper,
    /** The picked YouTube profile (#664), for its session index and Brand page id. Null in tests. */
    private val tokenManager: com.stash.core.auth.TokenManager? = null,
) : PingSubmitter {

    /** The shared client, without redirects (see the class doc). */
    internal val client: OkHttpClient = okHttpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override suspend fun submit(url: String, cookies: String, sapiSid: String): Int {
        val base = YouTubeCredentialUrl.parse(url)
            ?: throw IllegalArgumentException(
                "Refused to ping ${YouTubeCredentialUrl.describeForLog(url)}: not a YouTube URL",
            )
        val request = Request.Builder()
            .url(withPlaybackParams(base))
            .get()
            .header("Authorization", cookieHelper.generateAuthHeader(sapiSid))
            .header("Cookie", cookies)
            .header("Origin", "https://music.youtube.com")
            .header("X-Origin", "https://music.youtube.com")
            .header("User-Agent", USER_AGENT)
            .apply {
                // The play lands in the picked profile's history: its owner's session index, and a
                // Brand Account's page id (#664).
                val identity = runCatching { tokenManager?.getYouTubeIdentity() }.getOrNull()
                header("X-Goog-AuthUser", (identity?.authUser ?: 0).toString())
                identity?.pageId?.let { header("X-Goog-PageId", it) }
            }
            .build()
        return client.newCall(request).execute().use { it.code }
    }

    /**
     * Adds the three query parameters YT's stats endpoint requires to
     * actually record a play: `cpn` (new 16-char random), `ver=2`, and
     * `c=WEB_REMIX`. The `videostatsPlaybackUrl` baseUrl already contains
     * `docid`, signed state, etc.; we're layering on the per-request fields.
     */
    private fun withPlaybackParams(base: HttpUrl): HttpUrl = base.newBuilder()
        .addQueryParameter("ver", "2")
        .addQueryParameter("c", "WEB_REMIX")
        .addQueryParameter("cpn", generateCpn())
        .build()

    /**
     * 16-char content-playback-nonce using YT's charset. Matches the
     * generation code in `ytmusicapi.mixins.library.add_history_item`.
     */
    private fun generateCpn(): String {
        val sb = StringBuilder(16)
        repeat(16) {
            sb.append(CPN_ALPHABET[Random.nextInt(256) and 63])
        }
        return sb.toString()
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

        /**
         * 64-character alphabet YT uses for cpn generation (Base64-ish:
         * A-Z, a-z, 0-9, `-`, `_`). The `& 63` in [generateCpn] selects a
         * 6-bit slice from each random byte to index into this string.
         */
        private const val CPN_ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
    }
}

/**
 * Drains unscrobbled listening events and submits them to YouTube Music's
 * Watch History via the `videostatsPlaybackUrl` tracking endpoint.
 *
 * Structurally parallel to [com.stash.core.data.lastfm.LastFmScrobbler]:
 * a `combine(enabled, ytConnected, pendingCount, disabledReason)` flow
 * triggers [drainQueue] whenever conditions are met.
 *
 * A behavior-driven kill-switch prevents runaway protocol errors from
 * spamming YouTube's API. Five consecutive non-auth failures trip the
 * switch ([YouTubeScrobblerState.setDisabledReason]); the switch resets
 * automatically on the next app update ([maybeClearKillSwitchOnUpdate]).
 */
@Singleton
class YouTubeHistoryScrobbler @Inject constructor(
    private val preference: YouTubeHistoryPreference,
    private val state: YouTubeScrobblerState,
    private val listeningEventDao: ListeningEventDao,
    private val trackDao: TrackDao,
    private val resolver: YtCanonicalResolver,
    private val innerTubeClient: InnerTubeClient,
    private val cookieHelper: YouTubeCookieHelper,
    private val tokenManager: TokenManager,
    private val pingSubmitter: PingSubmitter,
    /** Indirection over `BuildConfig.VERSION_CODE` — allows tests to control the value. */
    private val versionCodeProvider: () -> Int,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _health = MutableStateFlow(YouTubeScrobblerHealth.DISABLED)
    val health: StateFlow<YouTubeScrobblerHealth> = _health.asStateFlow()

    /**
     * Outstanding plays: only those since the opt-in instant
     * ([YouTubeHistoryPreference.sendPlaysSince]), so Settings never counts the
     * history from before the user turned the feature on, which is never sent.
     * A function, not a property, so constructing the scrobbler reads no flow.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun pendingCount(): Flow<Int> = preference.sendPlaysSince.flatMapLatest { sinceMs ->
        if (sinceMs == null) flowOf(0) else listeningEventDao.pendingYtScrobbleCount(sinceMs)
    }

    /** Must be called once from `Application.onCreate`. */
    fun start() {
        scope.launch { maybeClearKillSwitchOnUpdate() }
        // Stamps the cutoff for an install that turned the feature on before it
        // existed, from app start rather than from the first drain (which waits
        // for a YouTube connection), so plays made meanwhile still count.
        scope.launch { readCutoff() }
        scope.launch {
            combine(
                preference.enabled,
                tokenManager.youTubeAuthState,
                pendingCount().distinctUntilChanged(),
                state.disabledReason,
            ) { enabled, _, _, disabledReason ->
                Triple(enabled, tokenManager.isAuthenticated(AuthService.YOUTUBE_MUSIC), disabledReason)
            }.collect { (enabled, ytConnected, disabledReason) ->
                when {
                    disabledReason != null -> _health.value = YouTubeScrobblerHealth.PROTOCOL_BROKEN
                    !enabled || !ytConnected -> _health.value = YouTubeScrobblerHealth.DISABLED
                    else -> {
                        // Flip to OK on entering the active branch so the
                        // UI reflects "enabled, nothing pending" immediately.
                        // submit() will overwrite with AUTH_FAILED / OFFLINE /
                        // PROTOCOL_BROKEN if subsequent pings fail.
                        _health.value = YouTubeScrobblerHealth.OK
                        drainQueue()
                    }
                }
            }
        }
    }

    // Internal visibility: `@VisibleForTesting` doesn't exist in :core:data; use internal.
    internal suspend fun maybeClearKillSwitchOnUpdate() {
        val current = versionCodeProvider()
        val known = state.currentLastKnownVersionCode()
        if (current > known) {
            state.setDisabledReason(null)
            state.resetConsecutiveFailures()
            state.setLastKnownVersionCode(current)
        }
    }

    /** Test entry-point: allows tests to call [maybeClearKillSwitchOnUpdate] directly. */
    @Suppress("FunctionName")
    suspend fun maybeClearKillSwitchOnUpdateForTest() = maybeClearKillSwitchOnUpdate()

    /** The opt-in cutoff, or null when the feature is off (or it cannot be read). */
    private suspend fun readCutoff(): Long? = try {
        preference.sendPlaysSinceMs()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "could not read the history cutoff", e)
        null
    }

    /** An unreadable kill-switch counts as tripped: stopping is the safe side. */
    private suspend fun killSwitchTripped(): Boolean = try {
        state.currentDisabledReason() != null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "could not read the kill-switch", e)
        true
    }

    internal suspend fun drainQueue() {
        // No cutoff means the feature is off: never fall back to "everything",
        // which would send the user's whole history (see YouTubeHistoryPreference).
        val sinceMs = readCutoff() ?: return
        val lookups = LookupFailures(readLookupFailures())
        // Room for every play whose lookup failed on top of a full batch, so
        // those (taken last, below) can't crowd out the plays behind them.
        val limit = BATCH_SIZE + lookups.size
        val pending = runCatching { listeningEventDao.pendingYtScrobbles(sinceMs = sinceMs, limit = limit) }
            .getOrElse {
                Log.w(TAG, "drainQueue: failed to load pending events", it)
                return
            }
        // Every queued play fit, so a recorded one that isn't among them was
        // sent, dropped or is from before the opt-in: forget it.
        if (pending.size < limit) lookups.forgetAllBut(pending.mapTo(HashSet()) { it.id })
        if (pending.isEmpty()) return
        // Oldest first, but plays whose lookup failed before go last (a stable
        // sort): one that keeps failing headed every pass and ended it, so no
        // play behind it was ever sent.
        val ordered = pending.sortedBy { lookups.failedBefore(it.id) }.take(BATCH_SIZE)
        for (event in ordered) {
            // Re-checked before every event, not once per pass: the collector only
            // sees a turn-off or a tripped kill-switch after this pass returns, and
            // a 50-event pass runs for about a minute. A cutoff that moved means the
            // user turned the feature off and on again, so older plays are skipped.
            val cutoff = readCutoff() ?: break
            if (event.startedAt < cutoff) continue
            if (killSwitchTripped()) break

            val track = runCatching { trackDao.getById(event.trackId) }.getOrNull()
            if (track == null) {
                // Track deleted between recording and scrobbling — mark handled.
                runCatching { listeningEventDao.markYtScrobbled(event.id) }
                lookups.forget(event.id)
                continue
            }
            if (!lookups.goOn(event.id, submit(event, track))) break
            delay(REQUEST_INTERVAL_MS + Random.nextLong(-JITTER_MS, JITTER_MS))
        }
        lookups.finish()
    }

    /**
     * What one [submit] means for the rest of a drain pass. A failure every
     * later event would hit too (no network, dead cookies, the kill-switch
     * tripping) ends the pass; otherwise each new listen re-walked the whole
     * batch, about three requests per event, against a YouTube that could not
     * accept any of them. Outcomes tied to one track do not. A failed lookup
     * can be either, and [LookupFailures] tells which.
     */
    internal enum class SubmitOutcome {
        /** YouTube answered: the play was sent, or has no canonical version. */
        ANSWERED,

        /** Settled or skipped without word from YouTube; the pass goes on. */
        CONTINUE,

        /** The search did not answer: offline, an outage, or a request refused for this play alone. */
        LOOKUP_UNANSWERED,

        /** The lookup threw on what the search answered: this play's own failure. */
        LOOKUP_BROKEN,

        /** A failure every later event would hit too. */
        STOP_PASS,
    }

    /**
     * One drain pass's handling of failed canonical lookups. A failed lookup
     * used to end the pass, and the queue is oldest first, so a play whose
     * lookup always failed (a search YouTube refuses for that query, an answer
     * the resolver can't read) headed every pass: nothing was ever sent, and
     * Settings said "offline".
     *
     * A play whose search went unanswered moves to the back of the queue
     * (recorded in [YouTubeScrobblerState.lookupFailures]) and the next play
     * is tried. If another search goes unanswered before anything answers,
     * nothing is answering: the pass ends, offline, and no play is charged. Once
     * YouTube answers in a pass, a failed lookup is the play's own and is
     * charged an attempt; after [MAX_LOOKUP_ATTEMPTS] the play is dropped. A
     * lookup that threw is always the play's own. [MAX_CHARGED_IN_A_ROW]
     * charged failures in a row end the pass, so a connection lost mid-pass
     * charges a few plays once, not the whole batch.
     *
     * Every state write is best effort: a lost one costs a play its place at
     * the back of the queue or one attempt, never the play.
     */
    private inner class LookupFailures(recorded: Map<Long, Int>) {
        /** Event id → attempts charged, for plays whose lookup failed (this pass or before). */
        private val attempts = recorded.toMutableMap()

        /** YouTube answered a request this pass, so a failed lookup is the play's own. */
        private var answered = false

        /** A play whose search went unanswered before anything answered this pass. */
        private var unproven: Long? = null

        /** Charged lookup failures in a row. */
        private var chargedInARow = 0

        val size: Int get() = attempts.size

        fun failedBefore(eventId: Long): Boolean = eventId in attempts

        /** True when the pass goes on after [eventId]'s [outcome]. */
        suspend fun goOn(eventId: Long, outcome: SubmitOutcome): Boolean = when (outcome) {
            SubmitOutcome.ANSWERED -> {
                onAnswered()
                chargedInARow = 0
                forget(eventId)
                true
            }
            SubmitOutcome.CONTINUE -> {
                // Its lookup worked: it goes back in line with the others.
                chargedInARow = 0
                forget(eventId)
                true
            }
            SubmitOutcome.LOOKUP_BROKEN -> charge(eventId)
            SubmitOutcome.LOOKUP_UNANSWERED -> when {
                answered -> charge(eventId)
                unproven == null -> {
                    // Offline, or this play alone: the next play's search tells.
                    unproven = eventId
                    moveBack(eventId)
                    true
                }
                else -> {
                    // A second search unanswered, nothing answered since: offline.
                    moveBack(eventId)
                    unproven = null
                    _health.value = YouTubeScrobblerHealth.OFFLINE
                    false
                }
            }
            SubmitOutcome.STOP_PASS -> {
                // The stop set its own health (auth, network, kill-switch).
                unproven = null
                false
            }
        }

        /** Ends the pass: an unanswered search nothing answered after says offline. */
        fun finish() {
            if (unproven != null) _health.value = YouTubeScrobblerHealth.OFFLINE
        }

        /** Forgets [eventId] (sent, dropped, or its lookup works now). */
        suspend fun forget(eventId: Long) {
            if (attempts.remove(eventId) != null) writeState { state.clearLookupFailures(listOf(eventId)) }
        }

        /** Forgets every recorded play but [queued]. */
        suspend fun forgetAllBut(queued: Set<Long>) {
            val gone = attempts.keys.filterNot { it in queued }
            if (gone.isEmpty()) return
            attempts.keys.removeAll(gone.toSet())
            writeState { state.clearLookupFailures(gone) }
        }

        private suspend fun onAnswered() {
            answered = true
            // The play before this one failed while YouTube was answering.
            unproven?.let {
                unproven = null
                charge(it)
            }
        }

        /** Puts [eventId] at the back of the queue, charging nothing. */
        private suspend fun moveBack(eventId: Long) {
            if (eventId in attempts) return
            attempts[eventId] = 0
            writeState { state.setLookupFailure(eventId, 0) }
        }

        /**
         * Charges [eventId] one attempt; at [MAX_LOOKUP_ATTEMPTS] it is dropped
         * (marked handled). True when the pass goes on.
         */
        private suspend fun charge(eventId: Long): Boolean {
            val charged = (attempts[eventId] ?: 0) + 1
            if (charged >= MAX_LOOKUP_ATTEMPTS) {
                Log.w(TAG, "dropping event=$eventId: its canonical lookup failed $charged times while YouTube answered")
                try {
                    listeningEventDao.markYtScrobbled(eventId)
                    forget(eventId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "could not drop event=$eventId", e)
                }
            } else {
                attempts[eventId] = charged
                writeState { state.setLookupFailure(eventId, charged) }
            }
            return ++chargedInARow < MAX_CHARGED_IN_A_ROW
        }
    }

    /** The recorded lookup failures; an unreadable record is an empty one. */
    private suspend fun readLookupFailures(): Map<Long, Int> = try {
        state.lookupFailures()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "could not read the lookup failures", e)
        emptyMap()
    }

    /** Runs a [state] write, logging instead of failing the pass. */
    private suspend fun writeState(write: suspend () -> Unit) {
        try {
            write()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "could not record a lookup failure", e)
        }
    }

    /**
     * Resolves the canonical video id, fetches its tracking URL, then fires
     * the authenticated history ping. Delegates the outcome to one of the
     * `on*` handlers which update [_health] and, when appropriate, the
     * kill-switch in [state].
     */
    internal suspend fun submit(event: ListeningEventEntity, track: TrackEntity): SubmitOutcome {
        val lookup = try {
            resolver.resolve(track)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // InnerTubeClient reads a request that failed as Failed, without
            // throwing; a throw is an answer this play's lookup can't read (a
            // response shape it doesn't expect). It fails the same way every
            // time, so it must not stand for "offline" and stop the pass.
            Log.w(TAG, "submit: canonical lookup threw for event=${event.id}", e)
            return SubmitOutcome.LOOKUP_BROKEN
        }
        val canonicalId = when (lookup) {
            is CanonicalLookup.Found -> lookup.match.videoId
            CanonicalLookup.NoMatch -> {
                // No ATV/OMV version found — mark handled to avoid UGC pollution.
                runCatching { listeningEventDao.markYtScrobbled(event.id) }
                return SubmitOutcome.ANSWERED
            }
            CanonicalLookup.Failed -> {
                // The search did not answer (offline, 429, 5xx). That says nothing
                // about whether a canonical version exists, so the play stays
                // queued; marking it handled here dropped every offline listen.
                // drainQueue tells an outage from a failure of this play alone.
                return SubmitOutcome.LOOKUP_UNANSWERED
            }
        }

        val trackingUrl = runCatching {
            innerTubeClient.getPlaybackTracking(canonicalId)
        }.getOrNull()
        if (trackingUrl.isNullOrBlank()) {
            // Also returned for one unplayable video, so it does not end the pass:
            // that would let a single such play at the head block the whole queue.
            onTransientFailure("no_tracking_url")
            return SubmitOutcome.CONTINUE
        }

        val cookies = tokenManager.getYouTubeCookie() ?: run {
            onAuthFailure()
            return SubmitOutcome.STOP_PASS
        }
        val sapiSid = cookieHelper.extractSapiSid(cookies) ?: run {
            onAuthFailure()
            return SubmitOutcome.STOP_PASS
        }

        // PlaybackTrackingParser returns only URLs that may receive the user's
        // YouTube credentials, and the submitter sends no ping to any other.
        val code = try {
            pingSubmitter.submit(trackingUrl, cookies, sapiSid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "submit: network error for event=${event.id}", e)
            onTransientFailure("io_error")
            return SubmitOutcome.STOP_PASS
        }

        return when (code) {
            in 200..299 -> {
                onSuccess(event.id)
                SubmitOutcome.ANSWERED
            }
            401, 403 -> {
                onAuthFailure()
                SubmitOutcome.STOP_PASS
            }
            else -> {
                Log.w(TAG, "submit: unexpected HTTP $code for event=${event.id}")
                if (onProtocolFailure()) SubmitOutcome.STOP_PASS else SubmitOutcome.CONTINUE
            }
        }
    }

    /** Test entry-point: exposes [submit] to the test package. */
    @Suppress("FunctionName")
    suspend fun submitForTest(event: ListeningEventEntity, track: TrackEntity) {
        submit(event, track)
    }

    private suspend fun onSuccess(eventId: Long) {
        runCatching { listeningEventDao.markYtScrobbled(eventId) }
        state.resetConsecutiveFailures()
        _health.value = YouTubeScrobblerHealth.OK
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onTransientFailure(reason: String) {
        _health.value = YouTubeScrobblerHealth.OFFLINE
    }

    private fun onAuthFailure() {
        _health.value = YouTubeScrobblerHealth.AUTH_FAILED
    }

    /** @return true when this failure tripped the kill-switch. */
    private suspend fun onProtocolFailure(): Boolean {
        val count = state.incrementConsecutiveFailures()
        return if (count >= KILL_SWITCH_THRESHOLD) {
            state.setDisabledReason("protocol_errors")
            _health.value = YouTubeScrobblerHealth.PROTOCOL_BROKEN
            true
        } else {
            _health.value = YouTubeScrobblerHealth.OFFLINE
            false
        }
    }

    companion object {
        private const val TAG = "YouTubeHistoryScrobbler"
        private const val REQUEST_INTERVAL_MS = 750L
        private const val JITTER_MS = 250L
        private const val KILL_SWITCH_THRESHOLD = 5

        /** Plays one drain pass submits at most. */
        private const val BATCH_SIZE = 50

        /**
         * Failed lookups of its own a play gets before it is dropped. Charged
         * only while YouTube answers other plays, so time offline costs none.
         */
        internal const val MAX_LOOKUP_ATTEMPTS = 5

        /** Charged lookup failures in a row that end a pass (a connection lost mid-pass). */
        private const val MAX_CHARGED_IN_A_ROW = 3
    }
}

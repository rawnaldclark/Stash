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
import com.stash.data.ytmusic.InnerTubeClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
 * Assembles the SAPISIDHASH-authenticated POST that registers a watch-history
 * event, then executes it synchronously inside the scrobbler's IO dispatcher.
 */
class OkHttpPingSubmitter @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val cookieHelper: YouTubeCookieHelper,
) : PingSubmitter {
    override suspend fun submit(url: String, cookies: String, sapiSid: String): Int {
        val request = Request.Builder()
            .url(url)
            .post("".toRequestBody())
            .header("Authorization", cookieHelper.generateAuthHeader(sapiSid))
            .header("Cookie", cookies)
            .header("Origin", "https://music.youtube.com")
            .header("X-Goog-AuthUser", "0")
            .header("User-Agent", USER_AGENT)
            .build()
        return okHttpClient.newCall(request).execute().use { it.code }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
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

    /** Must be called once from `Application.onCreate`. */
    fun start() {
        scope.launch { maybeClearKillSwitchOnUpdate() }
        scope.launch {
            combine(
                preference.enabled,
                tokenManager.youTubeAuthState,
                listeningEventDao.pendingYtScrobbleCount().distinctUntilChanged(),
                state.disabledReason,
            ) { enabled, _, _, disabledReason ->
                Triple(enabled, tokenManager.isAuthenticated(AuthService.YOUTUBE_MUSIC), disabledReason)
            }.collect { (enabled, ytConnected, disabledReason) ->
                when {
                    disabledReason != null -> _health.value = YouTubeScrobblerHealth.PROTOCOL_BROKEN
                    !enabled || !ytConnected -> _health.value = YouTubeScrobblerHealth.DISABLED
                    else -> drainQueue()
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

    private suspend fun drainQueue() {
        val pending = runCatching { listeningEventDao.pendingYtScrobbles(limit = 50) }
            .getOrElse {
                Log.w(TAG, "drainQueue: failed to load pending events", it)
                return
            }
        if (pending.isEmpty()) return
        for (event in pending) {
            val track = runCatching { trackDao.getById(event.trackId) }.getOrNull()
            if (track == null) {
                // Track deleted between recording and scrobbling — mark handled.
                runCatching { listeningEventDao.markYtScrobbled(event.id) }
                continue
            }
            submit(event, track)
            delay(REQUEST_INTERVAL_MS + Random.nextLong(-JITTER_MS, JITTER_MS))
        }
    }

    /**
     * Resolves the canonical video id, fetches its tracking URL, then fires
     * the authenticated history ping. Delegates the outcome to one of the
     * `on*` handlers which update [_health] and, when appropriate, the
     * kill-switch in [state].
     */
    internal suspend fun submit(event: ListeningEventEntity, track: TrackEntity) {
        val canonicalId = runCatching { resolver.resolve(track) }.getOrNull()
        if (canonicalId.isNullOrBlank()) {
            // No ATV/OMV version found — mark handled to avoid UGC pollution.
            runCatching { listeningEventDao.markYtScrobbled(event.id) }
            return
        }

        val trackingUrl = runCatching {
            innerTubeClient.getPlaybackTracking(canonicalId)
        }.getOrNull()
        if (trackingUrl.isNullOrBlank()) {
            onTransientFailure("no_tracking_url")
            return
        }

        val cookies = tokenManager.getYouTubeCookie() ?: run {
            onAuthFailure()
            return
        }
        val sapiSid = cookieHelper.extractSapiSid(cookies) ?: run {
            onAuthFailure()
            return
        }

        val code = runCatching {
            pingSubmitter.submit(trackingUrl, cookies, sapiSid)
        }.getOrElse { e ->
            Log.w(TAG, "submit: network error for event=${event.id}", e)
            onTransientFailure("io_error")
            return
        }

        when (code) {
            in 200..299 -> onSuccess(event.id)
            401, 403 -> onAuthFailure()
            else -> {
                Log.w(TAG, "submit: unexpected HTTP $code for event=${event.id}")
                onProtocolFailure()
            }
        }
    }

    /** Test entry-point: exposes [submit] to the test package. */
    @Suppress("FunctionName")
    suspend fun submitForTest(event: ListeningEventEntity, track: TrackEntity) =
        submit(event, track)

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

    private suspend fun onProtocolFailure() {
        val count = state.incrementConsecutiveFailures()
        if (count >= KILL_SWITCH_THRESHOLD) {
            state.setDisabledReason("protocol_errors")
            _health.value = YouTubeScrobblerHealth.PROTOCOL_BROKEN
        } else {
            _health.value = YouTubeScrobblerHealth.OFFLINE
        }
    }

    companion object {
        private const val TAG = "YouTubeHistoryScrobbler"
        private const val REQUEST_INTERVAL_MS = 750L
        private const val JITTER_MS = 250L
        private const val KILL_SWITCH_THRESHOLD = 5
    }
}

package com.stash.core.data.youtube

import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthService
import com.stash.core.auth.youtube.YouTubeCookieHelper
import com.stash.core.data.db.dao.ListeningEventDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.ListeningEventEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.YouTubeHistoryPreference
import com.stash.data.ytmusic.InnerTubeClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Unit tests for [YouTubeHistoryScrobbler] kill-switch transitions.
 *
 * The HTTP path is exercised on-device (Task 19). Here we only test the
 * branching logic driven by [PingSubmitter] status codes and [YouTubeScrobblerState].
 */
class YouTubeHistoryScrobblerTest {

    // ---- mocks ----
    private val preference = mock<YouTubeHistoryPreference>()
    private val state = mock<YouTubeScrobblerState>()
    private val listeningEventDao = mock<ListeningEventDao>()
    private val trackDao = mock<TrackDao>()
    private val resolver = mock<YtCanonicalResolver>()
    private val innerTubeClient = mock<InnerTubeClient>()
    private val cookieHelper = mock<YouTubeCookieHelper>()
    private val tokenManager = mock<TokenManager>()

    // Controllable PingSubmitter: tests set this to return their desired HTTP code.
    private var pingStatusCode = 200
    private val fakePingSubmitter: PingSubmitter = PingSubmitter { _, _, _ -> pingStatusCode }

    // Version code provider — default: same version (no update)
    private var fakeVersionCode = 1
    private var fakeStoredVersionCode = 1

    private lateinit var scrobbler: YouTubeHistoryScrobbler

    private fun event(id: Long = 1L) = ListeningEventEntity(
        id = id, trackId = 10L, startedAt = 1000L,
    )

    private fun track() = TrackEntity(
        id = 10L, title = "Song", artist = "Artist",
        youtubeId = "vid123", musicVideoType = "MUSIC_VIDEO_TYPE_ATV",
    )

    @Before
    fun setUp() = runTest {
        whenever(state.currentLastKnownVersionCode()).thenReturn(fakeStoredVersionCode)
        whenever(state.currentDisabledReason()).thenReturn(null)
        whenever(state.incrementConsecutiveFailures()).thenReturn(1)
        whenever(tokenManager.getYouTubeCookie()).thenReturn("SAPISID=abc; LOGIN_INFO=xyz")
        whenever(cookieHelper.extractSapiSid(any())).thenReturn("abc")
        whenever(cookieHelper.generateAuthHeader(any())).thenReturn("SAPISIDHASH 1234_hash")
        whenever(resolver.resolve(any())).thenReturn("vid123")
        whenever(innerTubeClient.getPlaybackTracking(any()))
            .thenReturn("https://tracking.youtube.com/ptracking")

        scrobbler = YouTubeHistoryScrobbler(
            preference = preference,
            state = state,
            listeningEventDao = listeningEventDao,
            trackDao = trackDao,
            resolver = resolver,
            innerTubeClient = innerTubeClient,
            cookieHelper = cookieHelper,
            tokenManager = tokenManager,
            pingSubmitter = fakePingSubmitter,
            versionCodeProvider = { fakeVersionCode },
        )
    }

    // ── Test 1: health starts DISABLED (initial value) ───────────────────────
    // The scrobbler initialises to DISABLED; it transitions only after the
    // combine flow fires. We verify the initial contract here — the reactive
    // transitions are covered by the kill-switch tests below.

    @Test
    fun `health is DISABLED by default before start`() {
        // Initial value is DISABLED — no start() needed.
        assertEquals(YouTubeScrobblerHealth.DISABLED, scrobbler.health.value)
    }

    // ── Test 2: PROTOCOL_BROKEN when disabledReason is set (via kill-switch) ─
    // The kill-switch path is exercised by onProtocolFailure reaching threshold.
    // This test drives it directly via the 5th-failure scenario (covered in
    // Test 5 block). Here we additionally verify via onProtocolFailure side-effect.

    @Test
    fun `health becomes PROTOCOL_BROKEN after kill-switch trips via onProtocolFailure`() = runTest {
        pingStatusCode = 500
        whenever(state.incrementConsecutiveFailures()).thenReturn(5)

        scrobbler.submitForTest(event(), track())

        assertEquals(YouTubeScrobblerHealth.PROTOCOL_BROKEN, scrobbler.health.value)
    }

    // ── Test 3: OK after successful ping resets counter ───────────────────────

    @Test
    fun `onSuccess resets counter and sets health OK`() = runTest {
        pingStatusCode = 200

        scrobbler.submitForTest(event(), track())

        verify(listeningEventDao).markYtScrobbled(1L)
        verify(state).resetConsecutiveFailures()
        assertEquals(YouTubeScrobblerHealth.OK, scrobbler.health.value)
    }

    // ── Test 4: AUTH_FAILED on 401/403, counter NOT incremented ──────────────

    @Test
    fun `401 sets AUTH_FAILED and does not increment failure counter`() = runTest {
        pingStatusCode = 401

        scrobbler.submitForTest(event(), track())

        verify(state, never()).incrementConsecutiveFailures()
        assertEquals(YouTubeScrobblerHealth.AUTH_FAILED, scrobbler.health.value)
    }

    @Test
    fun `403 sets AUTH_FAILED and does not increment failure counter`() = runTest {
        pingStatusCode = 403

        scrobbler.submitForTest(event(), track())

        verify(state, never()).incrementConsecutiveFailures()
        assertEquals(YouTubeScrobblerHealth.AUTH_FAILED, scrobbler.health.value)
    }

    // ── Test 5: Protocol failure increments counter; 5th trips kill-switch ───

    @Test
    fun `protocol failure increments counter and sets OFFLINE before threshold`() = runTest {
        pingStatusCode = 500
        whenever(state.incrementConsecutiveFailures()).thenReturn(3)

        scrobbler.submitForTest(event(), track())

        verify(state).incrementConsecutiveFailures()
        assertEquals(YouTubeScrobblerHealth.OFFLINE, scrobbler.health.value)
    }

    @Test
    fun `5th consecutive protocol failure trips kill-switch to PROTOCOL_BROKEN`() = runTest {
        pingStatusCode = 500
        whenever(state.incrementConsecutiveFailures()).thenReturn(5)

        scrobbler.submitForTest(event(), track())

        verify(state).setDisabledReason("protocol_errors")
        assertEquals(YouTubeScrobblerHealth.PROTOCOL_BROKEN, scrobbler.health.value)
    }

    // ── Test 6: start() clears kill-switch on version bump ───────────────────

    @Test
    fun `start() clears kill-switch when versionCode is newer than stored`() = runTest {
        fakeVersionCode = 10
        // Re-create scrobbler so versionCodeProvider picks up the new value,
        // and state reports the old stored version.
        whenever(state.currentLastKnownVersionCode()).thenReturn(5)

        val freshScrobbler = YouTubeHistoryScrobbler(
            preference = preference,
            state = state,
            listeningEventDao = listeningEventDao,
            trackDao = trackDao,
            resolver = resolver,
            innerTubeClient = innerTubeClient,
            cookieHelper = cookieHelper,
            tokenManager = tokenManager,
            pingSubmitter = fakePingSubmitter,
            versionCodeProvider = { fakeVersionCode },
        )

        freshScrobbler.maybeClearKillSwitchOnUpdateForTest()

        verify(state).setDisabledReason(null)
        verify(state).resetConsecutiveFailures()
        verify(state).setLastKnownVersionCode(10)
    }

    @Test
    fun `start() does NOT clear kill-switch when versionCode is unchanged`() = runTest {
        fakeVersionCode = 5
        whenever(state.currentLastKnownVersionCode()).thenReturn(5)

        val freshScrobbler = YouTubeHistoryScrobbler(
            preference = preference,
            state = state,
            listeningEventDao = listeningEventDao,
            trackDao = trackDao,
            resolver = resolver,
            innerTubeClient = innerTubeClient,
            cookieHelper = cookieHelper,
            tokenManager = tokenManager,
            pingSubmitter = fakePingSubmitter,
            versionCodeProvider = { fakeVersionCode },
        )

        freshScrobbler.maybeClearKillSwitchOnUpdateForTest()

        verify(state, never()).setDisabledReason(any())
        verify(state, never()).resetConsecutiveFailures()
    }
}

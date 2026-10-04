package com.stash.core.data.youtube

import com.stash.core.auth.TokenManager
import com.stash.core.auth.youtube.YouTubeCookieHelper
import com.stash.core.data.db.dao.ListeningEventDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.ListeningEventEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.YouTubeHistoryPreference
import com.stash.data.ytmusic.InnerTubeClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The scrobbler pings the tracking URL the /player response named, with the
 * user's credentials. Which URLs may receive them is enforced by
 * PlaybackTrackingParser and OkHttpPingSubmitter (see their tests).
 */
class YouTubeHistoryScrobblerTrackingUrlTest {

    private val preference = mock<YouTubeHistoryPreference>()
    private val state = mock<YouTubeScrobblerState>()
    private val listeningEventDao = mock<ListeningEventDao>()
    private val trackDao = mock<TrackDao>()
    private val resolver = mock<YtCanonicalResolver>()
    private val innerTubeClient = mock<InnerTubeClient>()
    private val cookieHelper = mock<YouTubeCookieHelper>()
    private val tokenManager = mock<TokenManager>()

    /** Every URL the scrobbler asked to ping. */
    private val pinged = mutableListOf<String>()
    private val recordingSubmitter = PingSubmitter { url, _, _ -> pinged += url; 204 }

    private lateinit var scrobbler: YouTubeHistoryScrobbler

    private fun event(id: Long) = ListeningEventEntity(id = id, trackId = 10L, startedAt = 1000L)

    private fun track() = TrackEntity(
        id = 10L, title = "Song", artist = "Artist",
        youtubeId = "dQw4w9WgXcQ", musicVideoType = "MUSIC_VIDEO_TYPE_ATV",
    )

    @Before
    fun setUp() = runTest {
        whenever(state.currentDisabledReason()).thenReturn(null)
        whenever(state.incrementConsecutiveFailures()).thenReturn(1)
        whenever(tokenManager.getYouTubeCookie()).thenReturn("SAPISID=abc; LOGIN_INFO=xyz")
        whenever(cookieHelper.extractSapiSid(any())).thenReturn("abc")
        whenever(resolver.resolve(any())).thenReturn("dQw4w9WgXcQ")

        scrobbler = YouTubeHistoryScrobbler(
            preference = preference,
            state = state,
            listeningEventDao = listeningEventDao,
            trackDao = trackDao,
            resolver = resolver,
            innerTubeClient = innerTubeClient,
            cookieHelper = cookieHelper,
            tokenManager = tokenManager,
            pingSubmitter = recordingSubmitter,
            versionCodeProvider = { 1 },
        )
    }

    @Test
    fun `a YouTube tracking URL is pinged`() = runTest {
        val url = "https://s.youtube.com/api/stats/playback?docid=dQw4w9WgXcQ&ns=yt"
        whenever(innerTubeClient.getPlaybackTracking(any())).thenReturn(url)

        scrobbler.submitForTest(event(1L), track())

        assertEquals(listOf(url), pinged)
        verify(listeningEventDao).markYtScrobbled(1L)
        assertEquals(YouTubeScrobblerHealth.OK, scrobbler.health.value)
    }
}

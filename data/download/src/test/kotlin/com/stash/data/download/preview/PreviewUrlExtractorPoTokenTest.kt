package com.stash.data.download.preview

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.stash.core.auth.TokenManager
import com.stash.data.download.ytdlp.YtDlpManager
import com.stash.data.ytmusic.AudioPlayerResponse
import com.stash.data.ytmusic.InnerTubeClient
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/**
 * The InnerTube fast lane returns a direct googlevideo URL that YouTube
 * gates behind a GVS PO token: without `pot=` it serves ~1 MB and 403s
 * (2026-09-06: 0.8 s to the 403, then 13.8 s of yt-dlp). When the client
 * minted a session token, the URL must carry it BEFORE the tail probe
 * judges it, and the stamped URL is what playback gets.
 */
class PreviewUrlExtractorPoTokenTest {

    private val response = Json.parseToJsonElement(
        """{"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[""" +
            """{"mimeType":"audio/webm; codecs=\"opus\"","bitrate":140000,""" +
            """"url":"https://rr1.googlevideo.com/videoplayback?id=1","contentLength":"1000"}]}}""",
    ).jsonObject

    private val innerTube: InnerTubeClient = mockk()
    private val tailProbe: AudioUrlTailProbe = mockk()

    private fun extractor() = PreviewUrlExtractor(
        context = mockk<Context>(relaxed = true),
        ytDlpManager = mockk<YtDlpManager>(relaxed = true),
        tokenManager = mockk<TokenManager>(relaxed = true),
        innerTubeClient = innerTube,
        tailProbe = tailProbe,
    )

    @Test
    fun `the session token is stamped on the url before the probe and in the result`() = runTest {
        coEvery { innerTube.playerForAudio("vid1") } returns AudioPlayerResponse(response, streamPot = "SESSION")
        val probed = slot<String>()
        coEvery { tailProbe.servesFullFile(capture(probed), any()) } returns true

        val url = extractor().extractStreamUrl("vid1", allowYtDlp = false)

        assertThat(probed.captured).isEqualTo("https://rr1.googlevideo.com/videoplayback?id=1&pot=SESSION")
        assertThat(url).isEqualTo("https://rr1.googlevideo.com/videoplayback?id=1&pot=SESSION")
    }

    @Test
    fun `without a session token the url is probed as it came`() = runTest {
        coEvery { innerTube.playerForAudio("vid1") } returns AudioPlayerResponse(response, streamPot = null)
        val probed = slot<String>()
        coEvery { tailProbe.servesFullFile(capture(probed), any()) } returns true

        val url = extractor().extractStreamUrl("vid1", allowYtDlp = false)

        assertThat(probed.captured).isEqualTo("https://rr1.googlevideo.com/videoplayback?id=1")
        assertThat(url).isEqualTo("https://rr1.googlevideo.com/videoplayback?id=1")
    }
}

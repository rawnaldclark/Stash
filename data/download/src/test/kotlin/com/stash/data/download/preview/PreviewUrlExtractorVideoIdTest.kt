package com.stash.data.download.preview

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.stash.core.auth.TokenManager
import com.stash.data.download.ytdlp.YtDlpManager
import com.stash.data.ytmusic.InnerTubeClient
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

/**
 * Both entry points take an id only with the YouTube video-id shape: any other
 * fails with [IllegalArgumentException] before the InnerTube lane, the yt-dlp
 * lane or the single-flight map sees it.
 */
class PreviewUrlExtractorVideoIdTest {

    private val innerTube: InnerTubeClient = mockk()
    private val ytDlpManager: YtDlpManager = mockk()

    private fun extractor() = PreviewUrlExtractor(
        context = mockk<Context>(relaxed = true),
        ytDlpManager = ytDlpManager,
        tokenManager = mockk<TokenManager>(relaxed = true),
        innerTubeClient = innerTube,
        tailProbe = mockk(),
    )

    private val malformed = listOf("../x", "a/b", "a%(title)s", "x\\y", "dQw4w9WgXc", "dQw4w9WgXcQ\n", "")

    @Test
    fun `a malformed id never reaches InnerTube or yt-dlp`() = runBlocking {
        val extractor = extractor()

        malformed.forEach { id ->
            listOf(true, false).forEach { allowYtDlp ->
                listOf(true, false).forEach { lowestQuality ->
                    val error = runCatching {
                        extractor.extractStreamUrl(id, allowYtDlp = allowYtDlp, lowestQuality = lowestQuality)
                    }.exceptionOrNull()
                    assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
                }
            }
            assertThat(runCatching { extractor.extractStreamUrlViaYtDlp(id) }.exceptionOrNull())
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThat(runCatching { extractor.extractViaYtDlpForRetry(id) }.exceptionOrNull())
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        coVerify(exactly = 0) { innerTube.playerForAudio(any(), any()) }
        coVerify(exactly = 0) { ytDlpManager.initialize() }
    }

    @Test
    fun `a malformed id fails at once, without waiting for the yt-dlp slot`() = runBlocking {
        val extractor = extractor()
        val holding = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = object : PreviewUrlExtractor.TestHooks {
            override suspend fun innerTubeExtract(id: String): String? = null
            override suspend fun ytDlpExtract(id: String): String {
                holding.complete(Unit)
                release.await()
                return "https://rr1.googlevideo.com/videoplayback?id=$id"
            }
        }
        // A real id holds the single yt-dlp slot.
        val held = async(Dispatchers.Default) { extractor.extractStreamUrlViaYtDlpForTest(holder, "dQw4w9WgXcQ") }
        try {
            withTimeout(5_000) { holding.await() }

            val error = withTimeout(2_000) {
                runCatching { extractor.extractStreamUrlViaYtDlp("../x") }.exceptionOrNull()
            }

            assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        } finally {
            release.complete(Unit)
            held.await()
        }
    }
}

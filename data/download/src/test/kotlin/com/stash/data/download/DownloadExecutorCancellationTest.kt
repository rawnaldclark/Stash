package com.stash.data.download

import android.content.Context
import com.stash.core.auth.TokenManager
import com.stash.data.download.files.WebmAudioRemuxer
import com.stash.data.download.ytdlp.YtDlpManager
import com.yausername.youtubedl_android.YoutubeDL
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * yt-dlp's execute() blocks its thread until the process exits. Run as a plain
 * call, cancelling a download (Stop, or WorkManager losing the Wi-Fi constraint)
 * left every yt-dlp running to the end, over cellular. youtubedl-android destroys
 * the process when that thread is interrupted, so cancellation must interrupt it.
 */
class DownloadExecutorCancellationTest {

    @get:Rule val tmp = TemporaryFolder()

    private val ytDlpManager: YtDlpManager = mockk(relaxed = true)
    private val tokenManager: TokenManager = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true)
    private val remuxer: WebmAudioRemuxer = mockk(relaxed = true)

    @After
    fun tearDown() {
        unmockkObject(YoutubeDL)
    }

    @Test fun `SoundCloud disk full removes attempt files without touching other downloads`() = runBlocking {
        val executor = DownloadExecutor(ytDlpManager, context, tokenManager, remuxer)
        val unrelated = tmp.newFile("another-download.m4a").apply { writeText("keep this audio") }
        val partial = File(tmp.root, "sc_42_attempt.m4a.part")
        val state = File(tmp.root, "sc_42_attempt.m4a.ytdl")
        mockkObject(YoutubeDL)
        every { YoutubeDL.execute(any(), any(), any()) } answers {
            partial.writeText("incomplete")
            state.writeText("resume state")
            throw java.io.IOException("No space left on device")
        }

        val result = executor.downloadSoundCloud("https://soundcloud.com/artist/song", null, tmp.root, "sc_42_attempt")

        assertTrue(result !is DownloadResult.Success)
        assertTrue("partial audio must be removed", !partial.exists())
        assertTrue("resume state must be removed", !state.exists())
        assertTrue("another download must remain unchanged", unrelated.readText() == "keep this audio")
        io.mockk.verify(exactly = 1) { YoutubeDL.execute(any(), any(), any()) }
    }

    @Test(timeout = 30_000)
    fun `cancelling a download interrupts the running yt-dlp call`() = runBlocking {
        coEvery { tokenManager.getYouTubeCookie() } returns null
        every { context.noBackupFilesDir } returns tmp.root
        val executor = DownloadExecutor(ytDlpManager, context, tokenManager, remuxer)

        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        mockkObject(YoutubeDL)
        every { YoutubeDL.execute(any(), any(), any()) } answers {
            entered.countDown()
            try {
                // A long download. Only an interrupt ends it early, as
                // youtubedl-android's own process wait does.
                Thread.sleep(60_000)
            } catch (e: InterruptedException) {
                interrupted.countDown()
                throw e
            }
            error("the download was not interrupted")
        }

        val job = launch(Dispatchers.Default) {
            executor.download(
                url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                outputDir = tmp.root,
                filename = "dl_42",
                qualityArgs = emptyList(),
            )
        }
        assertTrue("yt-dlp never started", entered.await(10, TimeUnit.SECONDS))

        job.cancel()

        assertTrue("yt-dlp's thread was not interrupted", interrupted.await(10, TimeUnit.SECONDS))
        job.join()
    }
}

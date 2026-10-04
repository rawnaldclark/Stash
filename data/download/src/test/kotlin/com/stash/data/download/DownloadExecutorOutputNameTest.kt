package com.stash.data.download

import android.content.Context
import com.stash.core.auth.TokenManager
import com.stash.data.download.files.WebmAudioRemuxer
import com.stash.data.download.ytdlp.YtDlpManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [DownloadExecutor.download] accepts only plain base names inside the output
 * folder and https URLs; anything else is refused before yt-dlp, the cookie
 * file or the freshen step run.
 */
class DownloadExecutorOutputNameTest {

    @get:Rule val tmp = TemporaryFolder()

    private val ytDlpManager: YtDlpManager = mockk(relaxed = true)
    private val tokenManager: TokenManager = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true)
    private val remuxer: WebmAudioRemuxer = mockk(relaxed = true)

    private fun executor(): DownloadExecutor {
        // No cookie: a download that wrongly got past the checks goes straight
        // to yt-dlp (uninitialised under JVM tests) instead of writing a file.
        coEvery { tokenManager.getYouTubeCookie() } returns null
        every { context.noBackupFilesDir } returns tmp.root
        return DownloadExecutor(ytDlpManager, context, tokenManager, remuxer)
    }

    private val url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"

    /** Thrown by the freshen step to show a download got past the checks. */
    private class ReachedYtDlp : RuntimeException()

    private suspend fun refuses(filename: String, target: String = url) {
        val result = executor().download(
            url = target,
            outputDir = tmp.root,
            filename = filename,
            qualityArgs = emptyList(),
        )
        assertTrue("expected a refusal for '$filename', got $result", result is DownloadResult.Error)
    }

    @Test
    fun `a name with a path separator or dot-dot is refused before yt-dlp runs`() = runTest {
        listOf(
            "../x",
            "a/b",
            "search_../x",
            "x/../../y",
            "/a/b",
            "a\\b",
            "..",
            ".",
            "",
        ).forEach { refuses(it) }

        coVerify(exactly = 0) { ytDlpManager.ensureFreshened() }
        coVerify(exactly = 0) { tokenManager.getYouTubeCookie() }
    }

    @Test
    fun `a name carrying a yt-dlp template field is refused`() = runTest {
        refuses("a%(title)s")
        refuses("search_%(uploader)s")

        coVerify(exactly = 0) { ytDlpManager.ensureFreshened() }
    }

    @Test
    fun `a URL that isn't https is refused`() = runTest {
        refuses("dl_7", target = "--x")
        refuses("dl_7", target = "file:///a/b")

        coVerify(exactly = 0) { ytDlpManager.ensureFreshened() }
    }

    @Test
    fun `only plain base names are safe`() {
        listOf("dl_42", "search_dQw4w9WgXcQ", "swap_7_dQw4w9WgXcQ", "import_v1.tmp", "a-b_C9")
            .forEach { assertTrue(it, DownloadExecutor.isSafeFilename(it)) }
        listOf(
            "", ".", "..", ".hidden", "a..b", "../x", "a/b", "a\\b", "a%(id)s", "a b", "a:b",
            "a\u0000b", "dl_é", "dl_３", "x".repeat(101),
        ).forEach { assertTrue("'$it' must be refused", !DownloadExecutor.isSafeFilename(it)) }
    }

    @Test
    fun `the template stays directly inside the output folder`() {
        val template = DownloadExecutor.outputTemplateFor(tmp.root, "dl_42")
        assertTrue("got $template", template == java.io.File(tmp.root, "dl_42.%(ext)s").absolutePath)
        assertTrue(DownloadExecutor.outputTemplateFor(tmp.root, "../dl_42") == null)
        assertTrue(DownloadExecutor.outputTemplateFor(tmp.root, "sub/dl_42") == null)
    }

    @Test
    fun `only https URLs are accepted`() {
        assertTrue(DownloadExecutor.isAcceptedUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertTrue(DownloadExecutor.isAcceptedUrl("https://music.youtube.com/watch?v=dQw4w9WgXcQ"))
        listOf(
            "--x",
            "-x",
            "http://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "file:///a/b",
            "https:///no-host",
            "dQw4w9WgXcQ",
            "",
        ).forEach { assertTrue("'$it' must be refused", !DownloadExecutor.isAcceptedUrl(it)) }
    }

    @Test
    fun `the names callers build still reach yt-dlp`() = runTest {
        coEvery { ytDlpManager.ensureFreshened() } throws ReachedYtDlp()

        listOf("dl_42", "search_dQw4w9WgXcQ", "swap_7_dQw4w9WgXcQ", "approve_a-b_C9d").forEach { name ->
            val thrown = runCatching {
                executor().download(url = url, outputDir = tmp.root, filename = name, qualityArgs = emptyList())
            }.exceptionOrNull()
            assertTrue("'$name' should get past the checks, got $thrown", thrown is ReachedYtDlp)
        }
    }
}

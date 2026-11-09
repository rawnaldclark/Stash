package com.stash.data.download

import com.stash.core.data.sync.classifier.DownloadFailureClassifier
import com.stash.core.data.sync.classifier.DownloadPhase
import com.stash.core.data.sync.classifier.FailureContext
import com.stash.core.model.DownloadFailureType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a yt-dlp failure keeps of its stderr ([ytDlpFailureText]), and what the classifier makes
 * of it. yt-dlp prints its WARNING lines first (a missing JS runtime alone is ~500 characters),
 * so keeping the first 500 characters kept the warnings and dropped the ERROR line: a 429 or a
 * full phone read as UNKNOWN, and the batch never stopped.
 */
class YtDlpFailureTextTest {

    private val warnings = (1..3).joinToString(separator = "") {
        """
        WARNING: [youtube] No supported JavaScript runtime could be found. Only deno is enabled by default; to use another runtime add --js-runtimes RUNTIME[:PATH] to your command/config. YouTube extraction without a JS runtime has been deprecated, and some formats may be missing. See https://github.com/yt-dlp/yt-dlp/wiki/EJS for details on installing one. $it
        WARNING: [youtube] abc123: nsig extraction failed: Some formats may be missing
        """.trimIndent() + "\n"
    }

    private fun classified(stderr: String): DownloadFailureType = DownloadFailureClassifier().classify(
        FailureContext(phase = DownloadPhase.DOWNLOADING, errorText = "yt-dlp: ${ytDlpFailureText(stderr)}"),
    )

    @Test fun `a 429 after the warnings is a rate limit`() {
        val stderr = warnings + "ERROR: [youtube] abc123: Unable to download webpage: HTTP Error 429: Too Many Requests"
        assertTrue(ytDlpFailureText(stderr).contains("HTTP Error 429"))
        assertFalse("only the error lines", ytDlpFailureText(stderr).contains("WARNING"))
        assertEquals(DownloadFailureType.RATE_LIMITED, classified(stderr))
    }

    @Test fun `the bot check after the warnings is a rate limit, not a login`() {
        val stderr = warnings + "ERROR: [youtube] abc123: Sign in to confirm you’re not a bot. Use --cookies-from-browser or --cookies for the authentication."
        assertEquals(DownloadFailureType.RATE_LIMITED, classified(stderr))
    }

    @Test fun `a full phone after the warnings is a full phone`() {
        val stderr = warnings + """
            [download] Destination: /data/user/0/com.stash.app/cache/abc123.webm
            ERROR: unable to write data: [Errno 28] No space left on device
        """.trimIndent()
        assertEquals(DownloadFailureType.STORAGE_FULL, classified(stderr))
    }

    @Test fun `a full phone that broke ffmpeg is a full phone, not a song that failed for good`() {
        val stderr = warnings + "ERROR: Postprocessing: ffmpeg exited with code 1: av_interleaved_write_frame(): No space left on device"
        assertEquals(DownloadFailureType.STORAGE_FULL, classified(stderr))
    }

    @Test fun `a full phone whose retry timed out is a full phone, not a network blip`() {
        val stderr = warnings + """
            ERROR: [download] Got error: The read operation timed out. Retrying (1/10)...
            ERROR: unable to write data: [Errno 28] No space left on device
        """.trimIndent()
        assertEquals(DownloadFailureType.STORAGE_FULL, classified(stderr))
    }

    @Test fun `with no ERROR line, the end of what yt-dlp printed is kept`() {
        val stderr = warnings + "x".repeat(800) + " No space left on device"
        val text = ytDlpFailureText(stderr)
        assertTrue(text.endsWith("No space left on device"))
        assertTrue(text.length <= 500)
        assertEquals(DownloadFailureType.STORAGE_FULL, classified(stderr))
    }
}

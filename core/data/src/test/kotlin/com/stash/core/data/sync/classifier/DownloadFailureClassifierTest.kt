package com.stash.core.data.sync.classifier

import com.stash.core.model.DownloadFailureType
import org.junit.Assert.assertEquals
import org.junit.Test
import com.stash.core.data.sync.classifier.DownloadFailureClassifier.FailureLane as Lane

class DownloadFailureClassifierTest {

    private val classifier = DownloadFailureClassifier()

    @Test fun `401 maps to AUTH_EXPIRED regardless of phase`() {
        val result = classifier.classify(FailureContext(
            phase = DownloadPhase.DOWNLOADING,
            errorText = "HTTP Error 401: Unauthorized",
            httpStatus = 401,
        ))
        assertEquals(DownloadFailureType.AUTH_EXPIRED, result)
    }

    @Test fun `login-required text maps to AUTH_EXPIRED`() {
        val result = classifier.classify(FailureContext(
            phase = DownloadPhase.DOWNLOADING,
            errorText = "ERROR: [youtube] abc: Login required to view this video",
        ))
        assertEquals(DownloadFailureType.AUTH_EXPIRED, result)
    }

    // ── Rate limits: YouTube slowing us down is not a login problem ─────

    @Test fun `HTTP 429 text maps to RATE_LIMITED`() {
        val result = classifier.classify(FailureContext(
            phase = DownloadPhase.DOWNLOADING,
            errorText = "ERROR: Unable to download webpage: HTTP Error 429: Too Many Requests",
        ))
        assertEquals(DownloadFailureType.RATE_LIMITED, result)
    }

    @Test fun `status 429 maps to RATE_LIMITED`() {
        val result = classifier.classify(FailureContext(
            phase = DownloadPhase.MATCHING,
            errorText = null,
            httpStatus = 429,
        ))
        assertEquals(DownloadFailureType.RATE_LIMITED, result)
    }

    @Test fun `the bot check maps to RATE_LIMITED, with either apostrophe`() {
        for (text in listOf(
            "Sign in to confirm you're not a bot. Use --cookies",
            "ERROR: [youtube] abc: Sign in to confirm you’re not a bot",
        )) {
            val result = classifier.classify(FailureContext(phase = DownloadPhase.DOWNLOADING, errorText = text))
            assertEquals(text, DownloadFailureType.RATE_LIMITED, result)
        }
    }

    @Test fun `video unavailable maps to PROVIDER_UNAVAILABLE`() {
        val result = classifier.classify(FailureContext(
            phase = DownloadPhase.DOWNLOADING,
            errorText = "ERROR: Video unavailable. This video is no longer available.",
        ))
        assertEquals(DownloadFailureType.PROVIDER_UNAVAILABLE, result)
    }

    @Test fun `socket timeout cause maps to NETWORK`() {
        val result = classifier.classify(FailureContext(
            phase = DownloadPhase.DOWNLOADING,
            errorText = null,
            causeChain = listOf("SocketTimeoutException"),
        ))
        assertEquals(DownloadFailureType.NETWORK, result)
    }

    @Test fun `unknown host text maps to NETWORK`() {
        val result = classifier.classify(FailureContext(
            phase = DownloadPhase.MATCHING,
            errorText = "Unable to resolve host \"i.ytimg.com\": No address associated with hostname",
        ))
        assertEquals(DownloadFailureType.NETWORK, result)
    }

    @Test fun `ffmpeg exit code at PROCESSING maps to FFMPEG_ERROR`() {
        val result = classifier.classify(FailureContext(
            phase = DownloadPhase.PROCESSING,
            errorText = "ffmpeg exited with code 234: muxer not found",
        ))
        assertEquals(DownloadFailureType.FFMPEG_ERROR, result)
    }

    @Test fun `ffmpeg exit code at DOWNLOADING maps to FFMPEG_ERROR`() {
        val result = classifier.classify(FailureContext(
            phase = DownloadPhase.DOWNLOADING,
            errorText = "ffmpeg exited with code 234: muxer not found",
        ))
        assertEquals(DownloadFailureType.FFMPEG_ERROR, result)
    }

    // ── Storage: a full phone stops the batch, other storage errors don't ─

    @Test fun `ENOSPC at STORAGE phase maps to STORAGE_FULL`() {
        val result = classifier.classify(FailureContext(
            phase = DownloadPhase.STORAGE,
            errorText = "ENOSPC: no space left on device",
        ))
        assertEquals(DownloadFailureType.STORAGE_FULL, result)
    }

    @Test fun `no space left while downloading maps to STORAGE_FULL`() {
        val result = classifier.classify(FailureContext(
            phase = DownloadPhase.DOWNLOADING,
            errorText = "java.io.IOException: write failed: No space left on device",
        ))
        assertEquals(DownloadFailureType.STORAGE_FULL, result)
    }

    @Test fun `permission denied at STORAGE phase stays STORAGE_ERROR`() {
        val result = classifier.classify(FailureContext(
            phase = DownloadPhase.STORAGE,
            errorText = "open failed: EACCES (Permission denied)",
        ))
        assertEquals(DownloadFailureType.STORAGE_ERROR, result)
    }

    @Test fun `a SAF write failure maps to STORAGE_ERROR`() {
        val result = classifier.classify(FailureContext(
            phase = DownloadPhase.DOWNLOADING,
            errorText = "Could not create SAF file 'Song.opus' under content://tree",
        ))
        assertEquals(DownloadFailureType.STORAGE_ERROR, result)
    }

    @Test fun `text containing unsafe is no longer STORAGE_ERROR`() {
        val result = classifier.classify(FailureContext(
            phase = DownloadPhase.DOWNLOADING,
            errorText = "WARNING: unsafe file extension, skipping",
        ))
        assertEquals(DownloadFailureType.UNKNOWN, result)
    }

    @Test fun `unrecognised text maps to UNKNOWN`() {
        val result = classifier.classify(FailureContext(
            phase = DownloadPhase.DOWNLOADING,
            errorText = "some weird error nobody has ever seen",
        ))
        assertEquals(DownloadFailureType.UNKNOWN, result)
    }

    @Test fun `null errorText with no cause and no status maps to UNKNOWN`() {
        val result = classifier.classify(FailureContext(
            phase = DownloadPhase.DOWNLOADING,
            errorText = null,
        ))
        assertEquals(DownloadFailureType.UNKNOWN, result)
    }

    // ── isTerminal ─────────────────────────────────────────────────────
    // Terminal == the track itself is broken (provider refused / ffmpeg
    // refuses). Non-terminal == the sync got interrupted; the next sync
    // will pick the row back up at PENDING.

    @Test fun `isTerminal PROVIDER_UNAVAILABLE is true`() {
        assertEquals(true, DownloadFailureClassifier.isTerminal(DownloadFailureType.PROVIDER_UNAVAILABLE))
    }

    @Test fun `isTerminal FFMPEG_ERROR is true`() {
        assertEquals(true, DownloadFailureClassifier.isTerminal(DownloadFailureType.FFMPEG_ERROR))
    }

    @Test fun `isTerminal NETWORK is false`() {
        assertEquals(false, DownloadFailureClassifier.isTerminal(DownloadFailureType.NETWORK))
    }

    @Test fun `isTerminal AUTH_EXPIRED is false`() {
        assertEquals(false, DownloadFailureClassifier.isTerminal(DownloadFailureType.AUTH_EXPIRED))
    }

    @Test fun `isTerminal STORAGE_ERROR is false`() {
        assertEquals(false, DownloadFailureClassifier.isTerminal(DownloadFailureType.STORAGE_ERROR))
    }

    @Test fun `isTerminal UNKNOWN is false`() {
        assertEquals(false, DownloadFailureClassifier.isTerminal(DownloadFailureType.UNKNOWN))
    }

    @Test fun `isTerminal NO_MATCH is false (lane separation - owned by FailedMatchesScreen)`() {
        assertEquals(false, DownloadFailureClassifier.isTerminal(DownloadFailureType.NO_MATCH))
    }

    @Test fun `isTerminal NONE is false`() {
        assertEquals(false, DownloadFailureClassifier.isTerminal(DownloadFailureType.NONE))
    }

    @Test fun `isTerminal RATE_LIMITED and STORAGE_FULL are false`() {
        assertEquals(false, DownloadFailureClassifier.isTerminal(DownloadFailureType.RATE_LIMITED))
        assertEquals(false, DownloadFailureClassifier.isTerminal(DownloadFailureType.STORAGE_FULL))
    }

    // ── laneFor: one rule for every runner ──────────────────────────────

    @Test fun `a transient failure retries until the limit, then fails for good`() {
        val limit = DownloadFailureClassifier.TRANSIENT_RETRY_LIMIT
        for (type in listOf(
            DownloadFailureType.NETWORK, DownloadFailureType.AUTH_EXPIRED,
            DownloadFailureType.STORAGE_ERROR, DownloadFailureType.UNKNOWN,
        )) {
            assertEquals(Lane.RETRY, DownloadFailureClassifier.laneFor(type, retryCount = limit - 1))
            assertEquals(Lane.FINAL, DownloadFailureClassifier.laneFor(type, retryCount = limit))
        }
    }

    @Test fun `a source failure is final at once`() {
        assertEquals(Lane.FINAL, DownloadFailureClassifier.laneFor(DownloadFailureType.PROVIDER_UNAVAILABLE, 0))
        assertEquals(Lane.FINAL, DownloadFailureClassifier.laneFor(DownloadFailureType.FFMPEG_ERROR, 0))
    }

    @Test fun `a rate limit has its own lane until the limit`() {
        assertEquals(Lane.RATE_LIMITED, DownloadFailureClassifier.laneFor(DownloadFailureType.RATE_LIMITED, 0))
        assertEquals(
            Lane.FINAL,
            DownloadFailureClassifier.laneFor(DownloadFailureType.RATE_LIMITED, DownloadFailureClassifier.TRANSIENT_RETRY_LIMIT),
        )
    }

    @Test fun `a full phone never ends a song's retries`() {
        assertEquals(Lane.STORAGE_FULL, DownloadFailureClassifier.laneFor(DownloadFailureType.STORAGE_FULL, 0))
        assertEquals(Lane.STORAGE_FULL, DownloadFailureClassifier.laneFor(DownloadFailureType.STORAGE_FULL, 99))
    }
}

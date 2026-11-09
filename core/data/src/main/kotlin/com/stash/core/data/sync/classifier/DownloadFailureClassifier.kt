package com.stash.core.data.sync.classifier

import android.util.Log
import com.stash.core.model.DownloadFailureType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure classifier that maps a [FailureContext] to a [DownloadFailureType]
 * bucket. Patterns are ordered — first match wins. Add new patterns to the
 * top of the chain unless the new pattern is strictly more specific than
 * an existing one.
 *
 * Every UNKNOWN bucket assignment logs the raw context to logcat so we can
 * iterate the pattern table release-over-release.
 *
 * One taxonomy for every way a song downloads (the sync, the resume run, the
 * drain outside a sync, a single retry): [laneFor] says what each runner does
 * with the row.
 */
@Singleton
class DownloadFailureClassifier @Inject constructor() {

    fun classify(ctx: FailureContext): DownloadFailureType {
        val text = ctx.errorText?.lowercase().orEmpty()
        val causes = ctx.causeChain

        // 0. A full phone, before everything: "No space left on device" is unambiguous, and a
        // message that also mentions ffmpeg or a timeout (the muxer's write failed, a retry
        // timed out) must not read as a song that failed for good, or as a network blip.
        if (STORAGE_FULL_TEXT.any { it in text }) return DownloadFailureType.STORAGE_FULL

        // 1. Rate limit, before auth: YouTube's bot check says "Sign in to confirm
        // you're not a bot", which the auth text below would take for a login.
        if (ctx.httpStatus == 429) return DownloadFailureType.RATE_LIMITED
        if (RATE_LIMIT_TEXT.any { it in text }) return DownloadFailureType.RATE_LIMITED

        // 2. Auth (status takes priority over text)
        if (ctx.httpStatus in 401..403) return DownloadFailureType.AUTH_EXPIRED
        if (AUTH_TEXT.any { it in text }) return DownloadFailureType.AUTH_EXPIRED

        // 3. Provider unavailability
        if (PROVIDER_TEXT.any { it in text }) return DownloadFailureType.PROVIDER_UNAVAILABLE

        // 4. Network
        if (NETWORK_TEXT.any { it in text }) return DownloadFailureType.NETWORK
        if (causes.any { it in NETWORK_CAUSES }) return DownloadFailureType.NETWORK

        // 5. ffmpeg — text patterns are specific enough that phase gating
        // isn't needed (worker can't distinguish sub-phases anyway).
        if (FFMPEG_TEXT.any { it in text }) return DownloadFailureType.FFMPEG_ERROR

        // 6. Storage
        if (ctx.phase == DownloadPhase.STORAGE) return DownloadFailureType.STORAGE_ERROR
        if (STORAGE_TEXT.any { it in text } || SAF_WORD.containsMatchIn(text)) return DownloadFailureType.STORAGE_ERROR

        // 7. Catchall
        Log.w(TAG, "UNKNOWN failure: phase=${ctx.phase} status=${ctx.httpStatus} text=${ctx.errorText}")
        return DownloadFailureType.UNKNOWN
    }

    /** What a runner does with a row that failed. */
    enum class FailureLane {
        /** The song failed for good: FAILED, listed in Failed downloads with its Retry. */
        FINAL,

        /** Something was in the way, not the song: PENDING again with one retry used, and the run tries again later. */
        RETRY,

        /** YouTube asked us to wait: as [RETRY], and the run starts nothing new and tries again in about 15 minutes. */
        RATE_LIMITED,

        /** Out of space: PENDING again with no retry used, and the batch stops until Resume or a cold start. */
        STORAGE_FULL,
    }

    companion object {
        /**
         * Cap on the number of transient interruptions (network drop,
         * storage hiccup, mid-run auth expiry, rate limit, UNKNOWN) we'll
         * tolerate before escalating a queue row from PENDING to FAILED. Once
         * this many attempts have ended in non-terminal failure, the row
         * surfaces in the Failed Downloads viewer so the user can see and act
         * on it instead of silently re-retrying forever.
         */
        const val TRANSIENT_RETRY_LIMIT = 10

        /**
         * True only for failure types that represent a terminal track-level
         * failure: the provider says no, or ffmpeg refuses to process. Other
         * types (AUTH_EXPIRED / NETWORK / STORAGE_ERROR / RATE_LIMITED /
         * STORAGE_FULL / UNKNOWN) are interruptions — the track itself isn't
         * broken and a later run will pick it up.
         *
         * NO_MATCH is also a terminal failure but owned by FailedMatchesScreen,
         * not the classified-failures viewer; treat it as non-terminal here so
         * lane separation is preserved at the worker layer.
         */
        fun isTerminal(type: DownloadFailureType): Boolean = when (type) {
            DownloadFailureType.PROVIDER_UNAVAILABLE,
            DownloadFailureType.FFMPEG_ERROR -> true
            else -> false
        }

        /**
         * The one rule every runner follows for a failed row of [type] that has used
         * [retryCount] retries. A full phone never uses a retry, however many came before:
         * the song isn't the problem, and burning them turned every queued song FAILED.
         */
        fun laneFor(type: DownloadFailureType, retryCount: Int): FailureLane = when {
            type == DownloadFailureType.STORAGE_FULL -> FailureLane.STORAGE_FULL
            isTerminal(type) -> FailureLane.FINAL
            retryCount >= TRANSIENT_RETRY_LIMIT -> FailureLane.FINAL
            type == DownloadFailureType.RATE_LIMITED -> FailureLane.RATE_LIMITED
            else -> FailureLane.RETRY
        }

        /** The cause chain [classify] reads: class names, cycle-safe (a cause can be itself) and depth-capped. */
        fun causeChainOf(error: Throwable): List<String> =
            generateSequence(error) { it.cause.takeIf { c -> c !== it } }
                .take(16)
                .map { it::class.java.simpleName }
                .toList()

        private const val TAG = "FailureClassifier"

        /**
         * yt-dlp's words for YouTube slowing us down. "not a bot" rather than the whole
         * sentence: YouTube writes "you're" with a curly apostrophe as often as a straight one.
         */
        private val RATE_LIMIT_TEXT = listOf(
            "http error 429", "too many requests", "not a bot",
        )
        private val AUTH_TEXT = listOf(
            "login required", "sign in", "captcha", "403 forbidden",
            "unauthorized", "401",
        )
        private val PROVIDER_TEXT = listOf(
            "video unavailable", "unable to extract", "private video",
            "this video has been removed", "not available in your country",
            "copyright", "region",
        )
        private val NETWORK_TEXT = listOf(
            "timed out", "timeout", "unable to resolve host", "no address",
            "connection reset", "connection refused", "unreachable",
        )
        private val NETWORK_CAUSES = setOf(
            "SocketTimeoutException", "UnknownHostException",
            "ConnectException", "SSLException",
        )
        private val FFMPEG_TEXT = listOf(
            "ffmpeg", "exit code", "muxer", "codec",
        )
        private val STORAGE_FULL_TEXT = listOf(
            "enospc", "no space left",
        )
        private val STORAGE_TEXT = listOf(
            "permission denied", "no such file", "eacces",
        )

        /** "SAF" as a word ("Could not create SAF file"): a bare "saf" also matched "unsafe". */
        private val SAF_WORD = Regex("""\bsaf\b""")
    }
}

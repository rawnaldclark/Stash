package com.stash.data.download.preview

import android.content.Context
import android.util.Log
import com.stash.core.auth.TokenManager
import com.stash.data.download.CookieFileWriter
import com.stash.data.download.ytdlp.YtDlpManager
import com.stash.data.ytmusic.InnerTubeClient
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thrown by [PreviewUrlExtractor.extractStreamUrl] when called in fast-only
 * mode (`allowYtDlp = false`) and the InnerTube fast path yields no URL.
 *
 * Callers (e.g. background queue fill) map this to a null/unavailable result
 * rather than blocking on the serialized yt-dlp slow lane; the track is
 * recovered later by the 1-ahead prefetch which uses `allowYtDlp = true`.
 */
class NoFastStreamException(videoId: String) :
    Exception("no fast (InnerTube) stream for $videoId")

/**
 * Extracts a direct audio stream URL from YouTube for preview playback.
 *
 * ## Strategy: InnerTube vs yt-dlp race (parallel, split concurrency)
 *
 * Both extractors run concurrently, each bounded by its own shared
 * [Semaphore] so the two pools don't starve each other:
 *
 *  - **InnerTube player API (~1-2s)**: Calls the YouTube Music player
 *    endpoint with authenticated cookies. Parses
 *    `streamingData.adaptiveFormats` for audio-only URLs. These URLs may
 *    be n-parameter throttled (~50KB/s) but that's more than enough for
 *    audio preview (Opus is ~20KB/s). Cap: 8 concurrent.
 *
 *  - **yt-dlp fallback (~15-35s)**: Heavier path using QuickJS for full
 *    signature solving. Slow but reliable, so we cap it at 2 concurrent
 *    to avoid thrashing CPU / the yt-dlp JNI surface.
 *
 * The race returns whichever extractor succeeds first:
 *  - InnerTube returns a non-null URL → cancel yt-dlp and return it.
 *  - InnerTube returns null (ciphered/geo-blocked/unavailable) or
 *    throws → await yt-dlp and return its result (throws on hard fail).
 *
 * Any non-cancellation failure in InnerTube is rescued inside the async
 * as a null result, so the race transparently falls back to yt-dlp
 * without poisoning the enclosing [coroutineScope].
 *
 * Because the InnerTube fast path succeeds for most YouTube Music
 * tracks, previews feel near-instant while preserving yt-dlp as a
 * correctness backstop.
 */
@Singleton
class PreviewUrlExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ytDlpManager: YtDlpManager,
    private val tokenManager: TokenManager,
    private val innerTubeClient: InnerTubeClient,
) {
    /** Test-only injection point for race logic. Not wired in production. */
    internal interface TestHooks {
        suspend fun innerTubeExtract(id: String): String?
        suspend fun ytDlpExtract(id: String): String
    }

    /**
     * Shared scope for in-flight extracts. Caller cancellation only stops
     * the *caller's* await(); the underlying extract keeps running so any
     * other awaiter (or a future tap) still gets the URL. The scope lives
     * for the singleton's lifetime; entries clean up via invokeOnCompletion.
     */
    private val extractorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Single-flight cache. Key = videoId; value = the in-flight Deferred.
     * On completion (success OR failure) the entry self-removes via
     * invokeOnCompletion so the next call for the same videoId starts a
     * fresh extract.
     */
    private val inFlightExtracts = ConcurrentHashMap<String, Deferred<String>>()

    companion object {
        private const val TAG = "PreviewUrlExtractor"
        private const val YTDLP_TIMEOUT_MS = 60_000L
        private const val INNERTUBE_TIMEOUT_MS = 10_000L
        private const val FORMAT_SELECTOR = "251/250/bestaudio"

        /**
         * Concurrency caps for the two extractors. Shared process-wide.
         *
         * yt-dlp-android throws YoutubeDLException at ~120ms when a second
         * execute() runs while one is in flight (or shortly after a cancelled
         * one). On-device LATDIAG from feat/tap-cancel-hardening showed
         * cascading failures with cap=2. Cap=1 forces serialization at the
         * JNI boundary; coalescing (above) ensures redundant calls for the
         * same videoId never reach the semaphore at all.
         */
        private const val INNERTUBE_CONCURRENCY = 8
        private const val YTDLP_CONCURRENCY = 1   // was 2 — see spec

        /**
         * Shared so parallel callers (e.g. `PreviewPrefetcher`) respect the
         * cap regardless of how many [PreviewUrlExtractor] instances exist.
         */
        private val innerTubeSemaphore = Semaphore(INNERTUBE_CONCURRENCY)
        private val ytDlpSemaphore = Semaphore(YTDLP_CONCURRENCY)

        /**
         * Picks the best audio stream URL from a list of InnerTube
         * `adaptiveFormats` objects.
         *
         * YouTube's best free audio is Opus itag 251 (~160k). Opus sounds
         * better than AAC at equal/lower bitrate, so we prefer the
         * highest-bitrate Opus stream and only fall back to the
         * highest-bitrate non-Opus (AAC) stream when no Opus is available.
         * Mirrors yt-dlp's own `251/250/bestaudio` selector.
         *
         * Only formats with a direct `url` (not signatureCipher) are
         * considered; ciphered formats are skipped.
         */
        internal fun selectBestAudioUrl(formats: List<JsonObject>): String? {
            val audio = formats.filter { f ->
                (f["mimeType"]?.jsonPrimitive?.content ?: "").startsWith("audio/") && f["url"] != null
            }
            fun bitrate(f: JsonObject) = f["bitrate"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            fun isOpus(f: JsonObject) = (f["mimeType"]?.jsonPrimitive?.content ?: "").contains("opus")
            val opusBest = audio.filter(::isOpus).maxByOrNull(::bitrate)
            val best = opusBest ?: audio.maxByOrNull(::bitrate)
            if (best != null) {
                val mime = best["mimeType"]?.jsonPrimitive?.content
                Log.d(TAG, "InnerTube selected mime=$mime bitrate=${bitrate(best)} opusPreferred=${opusBest != null}")
            }
            return best?.get("url")?.jsonPrimitive?.content
        }

        /**
         * Test-only: exercises [race] directly without Android deps. Reuses
         * the shared semaphores so the tests also assert the real caps.
         */
        internal suspend fun raceForTest(hooks: TestHooks, videoId: String): String =
            race(
                videoId = videoId,
                innerTubeExtract = hooks::innerTubeExtract,
                ytDlpExtract = hooks::ytDlpExtract,
                itSem = innerTubeSemaphore,
                ytSem = ytDlpSemaphore,
            )

        /**
         * Races the two extractors. InnerTube is preferred when it succeeds
         * (non-null return); otherwise yt-dlp's result wins. Each extractor
         * acquires/releases its own semaphore, so a flood of InnerTube
         * requests can never block yt-dlp (or vice versa).
         *
         * Wrapped in [coroutineScope] so a cancel on either side also tears
         * down the sibling job — important so `yt.cancel()` actually stops
         * work and frees the yt-dlp permit.
         *
         * Any non-cancellation failure in InnerTube is rescued *inside* the
         * async (via [runCatching]) and surfaced as a null result. This
         * keeps the exception from escaping the async and poisoning the
         * enclosing [coroutineScope] (which would cancel yt-dlp and rethrow),
         * so the race transparently falls back to yt-dlp on any throw.
         */
        private suspend fun race(
            videoId: String,
            innerTubeExtract: suspend (String) -> String?,
            ytDlpExtract: suspend (String) -> String,
            itSem: Semaphore,
            ytSem: Semaphore,
        ): String = coroutineScope {
            val inner = async {
                itSem.acquire()
                try {
                    // Treat any non-cancellation failure as null so the
                    // race falls back to yt-dlp. CancellationException MUST
                    // propagate to preserve structured concurrency.
                    runCatching { innerTubeExtract(videoId) }
                        .getOrElse { t ->
                            if (t is CancellationException) throw t
                            null
                        }
                } finally {
                    itSem.release()
                }
            }
            val yt = async {
                ytSem.acquire()
                try { ytDlpExtract(videoId) } finally { ytSem.release() }
            }
            val itResult = inner.await()
            if (itResult != null) {
                yt.cancel(CancellationException("InnerTube won the race"))
                itResult
            } else {
                yt.await()
            }
        }
    }

    /**
     * Extracts a direct audio stream URL for the given YouTube video ID.
     *
     * Races InnerTube (fast, ~1-2s) against yt-dlp (slow, ~15-35s). See
     * the class KDoc for the full strategy.
     *
     * Concurrent calls for the same [videoId] are coalesced via [coalesce]
     * so only one extract runs at a time per ID.
     *
     * When [allowYtDlp] is false ("fast lane only"), the serialized yt-dlp
     * slow lane is never touched: InnerTube runs alone and a miss throws
     * [NoFastStreamException]. Fast-only and full-race calls for the same
     * [videoId] use distinct coalesce keys so they never share a Deferred.
     */
    suspend fun extractStreamUrl(videoId: String, allowYtDlp: Boolean = true): String =
        coalesce(coalesceKey(videoId, allowYtDlp)) { doExtract(videoId, allowYtDlp) }

    // The `#fast` suffix is collision-safe: YouTube videoIds are `#`-free
    // 11-char base64url, so a suffixed fast-only key can never equal a real
    // (full-race) videoId key.
    private fun coalesceKey(videoId: String, allowYtDlp: Boolean) =
        if (allowYtDlp) videoId else "$videoId#fast"

    /**
     * Test-only: exercises the coalescing wrapper with the existing
     * TestHooks SPI. The race body is replaced by a hook-driven function
     * so the test can observe coalescing without needing real Android deps.
     *
     * Does NOT exercise the real race(...) semantics — those are tested
     * by raceForTest in the same file. This helper tests only the
     * single-flight / coalescing layer above the race.
     *
     * Instance member (not companion) because `coalesce` needs `this`.
     */
    internal suspend fun extractStreamUrlForTest(
        hooks: TestHooks,
        videoId: String,
        allowYtDlp: Boolean = true,
    ): String = coalesce(coalesceKey(videoId, allowYtDlp)) {
        if (allowYtDlp) {
            hooks.innerTubeExtract(videoId) ?: hooks.ytDlpExtract(videoId)
        } else {
            hooks.innerTubeExtract(videoId) ?: throw NoFastStreamException(videoId)
        }
    }

    /**
     * Single-flight wrapper. If an extract for [videoId] is already in flight,
     * the new caller awaits its result; otherwise a fresh Deferred is started
     * on [extractorScope] and other callers join it.
     *
     * The Deferred lives on a scope independent of any caller's lifetime —
     * caller cancellation only stops the caller's own await(); the underlying
     * extract continues to completion so any other awaiter still gets a URL.
     *
     * **Completion ordering.** `invokeOnCompletion` fires synchronously when
     * the Deferred completes, *before* any suspended awaiter is resumed. So
     * by the time `await()` returns to any caller, the map entry is already
     * gone. A racing caller arriving in that microscopic window starts a
     * brand-new extract — which is benign because the just-completed URL
     * is in `PreviewUrlCache` (populated by the prefetcher and other
     * post-success caching paths), so the redundant extract returns fast
     * from cache lookup rather than re-running yt-dlp.
     *
     * **Two-arg `remove(key, value)`** is used so a stale cleanup callback
     * cannot accidentally remove a *successor's* Deferred if a new caller
     * for the same videoId raced in just after the prior one completed.
     */
    private suspend fun coalesce(
        key: String,
        doRace: suspend () -> String,
    ): String {
        // Fast path — share an in-flight extract if one exists.
        inFlightExtracts[key]?.let { return it.await() }

        // Create a LAZY Deferred so a putIfAbsent loser's Deferred never starts.
        val freshlyCreated = extractorScope.async(start = CoroutineStart.LAZY) {
            doRace()
        }
        val deferred = inFlightExtracts.putIfAbsent(key, freshlyCreated)
            ?: freshlyCreated.also { d ->
                d.invokeOnCompletion { inFlightExtracts.remove(key, d) }
                d.start()
            }
        return deferred.await()
    }

    /**
     * Underlying race body — original `extractStreamUrl` implementation.
     * Called via [coalesce] from the public entry point.
     */
    private suspend fun doExtract(videoId: String, allowYtDlp: Boolean): String {
        // Fast-only lane: InnerTube under its own semaphore, never the
        // serialized yt-dlp slot. A miss is signalled (not retried here) so
        // the caller can drop the track from background fill; the 1-ahead
        // prefetch (allowYtDlp = true) recovers it later.
        if (!allowYtDlp) {
            val t0 = System.currentTimeMillis()
            Log.d("LATDIAG", "extract-start videoId=$videoId fastOnly=1")
            val url = innerTubeSemaphore.withPermit { extractViaInnerTube(videoId) } ?: run {
                Log.d("LATDIAG", "extract-fail videoId=$videoId dt=${System.currentTimeMillis() - t0}ms fastOnly=1 err=NoFastStream")
                throw NoFastStreamException(videoId)
            }
            Log.d("LATDIAG", "extract-end videoId=$videoId dt=${System.currentTimeMillis() - t0}ms fastOnly=1")
            return url
        }

        val t0 = System.currentTimeMillis()
        Log.d("LATDIAG", "extract-start videoId=$videoId")
        return try {
            val url = race(
                videoId = videoId,
                innerTubeExtract = { id ->
                    val it0 = System.currentTimeMillis()
                    val result = runCatching { extractViaInnerTube(id) }
                    val dt = System.currentTimeMillis() - it0
                    val outcome = result.fold(
                        onSuccess = { if (it != null) "url" else "null" },
                        onFailure = { "throw:${it.javaClass.simpleName}" },
                    )
                    Log.d("LATDIAG", "innertube-end videoId=$id dt=${dt}ms outcome=$outcome")
                    result.getOrThrow()
                },
                ytDlpExtract = { id ->
                    val yt0 = System.currentTimeMillis()
                    val result = runCatching { extractViaYtDlp(id) }
                    val dt = System.currentTimeMillis() - yt0
                    val outcome = result.fold(
                        onSuccess = { "url" },
                        onFailure = { "throw:${it.javaClass.simpleName}" },
                    )
                    Log.d("LATDIAG", "ytdlp-end videoId=$id dt=${dt}ms outcome=$outcome")
                    result.getOrThrow()
                },
                itSem = innerTubeSemaphore,
                ytSem = ytDlpSemaphore,
            )
            Log.d("LATDIAG", "extract-end videoId=$videoId dt=${System.currentTimeMillis() - t0}ms")
            url
        } catch (t: Throwable) {
            Log.d("LATDIAG", "extract-fail videoId=$videoId dt=${System.currentTimeMillis() - t0}ms err=${t.javaClass.simpleName}")
            throw t
        }
    }

    /**
     * Streaming-playback extraction: yt-dlp DIRECT, bypassing the InnerTube
     * fast lane entirely.
     *
     * InnerTube/iOS URLs are PO-token-gated to a ~1 MB preview and return
     * HTTP 403 on any full-file / open-ended request (proven on-device
     * 2026-06-08: every offset past ~1.1 MB 403s, via Range header, `&range=`
     * query, and sequential chunking alike). They cannot stream a whole
     * track, so the player path must never use them. yt-dlp solves the
     * n-sig / PO challenge and yields full-range-playable URLs, at the cost
     * of ~11 s extraction.
     *
     * **Cap-1 safety.** Holds the shared [ytDlpSemaphore] — a second
     * concurrent yt-dlp `execute()` throws YoutubeDLException at the JNI
     * boundary (the cap=2 crash). The serialized [race] arm takes this
     * permit too; the older [extractViaYtDlpForRetry] did NOT, which this
     * path fixes by routing through here.
     *
     * **Coalescing.** Concurrent callers for the same [videoId] share one
     * extract under a dedicated `#ytdlp` key so they never collide with the
     * race-mode (`videoId`) or fast-only (`videoId#fast`) Deferreds.
     */
    suspend fun extractStreamUrlViaYtDlp(videoId: String): String =
        coalesce("$videoId#ytdlp") {
            ytDlpSemaphore.withPermit { extractViaYtDlp(videoId) }
        }

    /**
     * Test-only mirror of [extractStreamUrlViaYtDlp] driven by the [TestHooks]
     * SPI (the real path needs Android + JNI). Shares the exact coalesce +
     * cap-1 semaphore wrapper so tests assert the real concurrency contract.
     */
    internal suspend fun extractStreamUrlViaYtDlpForTest(hooks: TestHooks, videoId: String): String =
        coalesce("$videoId#ytdlp") {
            ytDlpSemaphore.withPermit { hooks.ytDlpExtract(videoId) }
        }

    /**
     * Retry-only entry point: bypass the InnerTube race and go straight to
     * yt-dlp. Used by [com.stash.feature.search.SearchViewModel.onPreviewError]
     * when ExoPlayer rejects an InnerTube URL (typically because the URL is
     * n-parameter-throttled past what ExoPlayer is willing to wait for).
     *
     * Delegates to [extractStreamUrlViaYtDlp] so it shares the cap-1
     * semaphore + coalescing (it previously called [extractViaYtDlp]
     * directly, bypassing both).
     */
    suspend fun extractViaYtDlpForRetry(videoId: String): String =
        extractStreamUrlViaYtDlp(videoId)

    /**
     * Prepares yt-dlp for any path that needs a full YouTube stream URL.
     *
     * Downloads already use [YtDlpManager.ensureFreshened] so their first
     * real extraction runs on the latest nightly extractor and a warmed EJS
     * solver. Streaming must use the same gate; otherwise a valid YT Music
     * search result can fail because the bundled yt-dlp snapshot is stale
     * after a YouTube player/signature change.
     */
    internal suspend fun prepareYtDlpForExtraction() {
        ytDlpManager.ensureFreshened()
    }

    /**
     * Fast path: extract stream URL via InnerTube player API.
     *
     * Calls `/youtubei/v1/player` with the user's YouTube session cookies.
     * Parses `streamingData.adaptiveFormats` for audio-only formats with
     * direct `url` fields (not `signatureCipher`).
     *
     * Returns null if no usable URL is found (all formats ciphered, video
     * unavailable, etc.).
     */
    private suspend fun extractViaInnerTube(videoId: String): String? {
        return withTimeout(INNERTUBE_TIMEOUT_MS) {
            Log.d(TAG, "Trying InnerTube fast path for videoId=$videoId")

            // Ordered client-variant attempt. ANDROID_VR / IOS frequently
            // return unciphered adaptiveFormats urls, letting us skip the
            // ~14 s yt-dlp + QuickJS cipher-solve path entirely. Falls
            // back transparently to WEB_REMIX if neither yields a direct URL.
            val response = innerTubeClient.playerForAudio(videoId) ?: run {
                Log.w(TAG, "InnerTube playerForAudio returned null for $videoId")
                return@withTimeout null
            }

            // Check playability
            val status = response["playabilityStatus"]
                ?.jsonObject?.get("status")
                ?.jsonPrimitive?.content
            if (status != "OK") {
                Log.w(TAG, "InnerTube: video $videoId status=$status")
                return@withTimeout null
            }

            // Extract streamingData.adaptiveFormats
            val streamingData = response["streamingData"]?.jsonObject ?: run {
                Log.w(TAG, "InnerTube: no streamingData for $videoId")
                return@withTimeout null
            }

            val adaptiveFormats = streamingData["adaptiveFormats"]?.jsonArray ?: run {
                Log.w(TAG, "InnerTube: no adaptiveFormats for $videoId")
                return@withTimeout null
            }

            // Find the best audio format with a direct URL (not signatureCipher).
            // Prefers Opus 251/250 over AAC even at equal/lower bitrate.
            val streamUrl = selectBestAudioUrl(adaptiveFormats.filterIsInstance<JsonObject>()) ?: run {
                Log.d(TAG, "InnerTube: no audio formats with direct URL for $videoId " +
                    "(${adaptiveFormats.size} total formats, all may be ciphered)")
                return@withTimeout null
            }

            Log.d(TAG, "InnerTube: SUCCESS videoId=$videoId urlLen=${streamUrl.length}")
            streamUrl
        }
    }

    /**
     * Slow fallback: extract stream URL via yt-dlp with QuickJS cipher solving.
     */
    private suspend fun extractViaYtDlp(videoId: String): String {
        return withTimeout(YTDLP_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                prepareYtDlpForExtraction()

                // Fast path: query ONLY the android_vr client. It returns a
                // working itag-251 URL directly — no PO token, no m3u8 manifest,
                // no QuickJS signature/n-challenge solve — so it resolves in
                // ~3.6s vs ~12.8s for yt-dlp's default multi-client + m3u8 +
                // challenge path (measured on-device 2026-06-26, flat across
                // tracks). Swallow an android_vr-specific failure and fall back
                // to the default clients so coverage never regresses (the broad
                // catalog reach is the whole reason YT fallback exists).
                val fast = try {
                    runYtDlp(videoId, playerClient = "android_vr")
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    Log.d(TAG, "yt-dlp android_vr attempt failed for $videoId: ${e.message}")
                    null
                }
                fast ?: (runYtDlp(videoId, playerClient = null)
                    ?: throw IllegalStateException("yt-dlp returned no stream URL for videoId=$videoId"))
            }
        }
    }

    /**
     * One yt-dlp `execute()` for [videoId]. [playerClient] pins the YouTube
     * extractor to a single client (e.g. `android_vr`); null leaves yt-dlp's
     * default client set. Returns the stream URL, or null when this client set
     * produced no URL (caller decides whether to fall back). Holds no semaphore
     * — the caller already owns the cap-1 [ytDlpSemaphore] permit.
     */
    private suspend fun runYtDlp(videoId: String, playerClient: String?): String? {
        val cookieFile = File(context.noBackupFilesDir, "yt_preview_cookies_${System.nanoTime()}.txt")
        return try {
            val url = "https://www.youtube.com/watch?v=$videoId"

            val request = YoutubeDLRequest(url).apply {
                addOption("-f", FORMAT_SELECTOR)
                addOption("--print", "urls")
                addOption("--no-download")
                playerClient?.let { addOption("--extractor-args", "youtube:player_client=$it") }

                val qjsPath = ytDlpManager.quickJsPath
                if (qjsPath != null) {
                    addOption("--js-runtimes", "quickjs:$qjsPath")
                    addOption("--remote-components", "ejs:github")
                }
            }

            val cookie = tokenManager.getYouTubeCookie()
            if (cookie != null) {
                CookieFileWriter.write(cookie, cookieFile)
                cookieFile.setReadable(false, false)
                cookieFile.setReadable(true, true)
                cookieFile.setWritable(false, false)
                cookieFile.setWritable(true, true)
                request.addOption("--cookies", cookieFile.absolutePath)
            }

            Log.d(TAG, "yt-dlp: invoking for videoId=$videoId client=${playerClient ?: "default"}")
            val response = YoutubeDL.getInstance().execute(request, url, null)

            val stdout = response.out.orEmpty()
            val stderr = response.err.orEmpty()
            Log.d(TAG, "yt-dlp: exit=${response.exitCode} client=${playerClient ?: "default"} stdoutLen=${stdout.length}")
            if (stderr.isNotBlank()) {
                Log.d(TAG, "yt-dlp stderr: ${stderr.take(500)}")
            }

            val streamUrl = stdout.trim().lines().firstOrNull { it.startsWith("http") }
            if (streamUrl.isNullOrBlank()) {
                Log.d(TAG, "yt-dlp: no URL videoId=$videoId client=${playerClient ?: "default"}")
                null
            } else {
                Log.d(TAG, "yt-dlp: SUCCESS videoId=$videoId urlLen=${streamUrl.length}")
                streamUrl
            }
        } finally {
            if (cookieFile.exists()) cookieFile.delete()
        }
    }
}

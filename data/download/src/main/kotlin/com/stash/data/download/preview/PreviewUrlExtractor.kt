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
    private val tailProbe: AudioUrlTailProbe,
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

    /**
     * Last observed audio codec per videoId ("opus", "aac", …), written by
     * whichever lane produced the URL that was actually handed back.
     *
     * Exists because [com.stash.core.media.streaming.YouTubeStreamResolver]
     * hardcoded `codec = "aac"` — an honest guess from when the extractor
     * really did return nothing but a URL. It has printed the format for a
     * while now, so Now Playing can state what is playing instead of
     * mislabelling every Opus stream as AAC (which, since the client chain
     * settled on itag 251, was every YouTube stream).
     *
     * ponytail: plain map with a size cap, not an LRU — values are 3-5 char
     * strings and the cap exists only so a long session can't grow it without
     * bound. Swap for an LRU if eviction ORDER ever matters.
     */
    private val observedCodecs = ConcurrentHashMap<String, String>()

    /** Codec last seen for [videoId], or null if nothing has resolved it yet. */
    fun observedCodec(videoId: String): String? = observedCodecs[videoId]

    private fun recordCodec(videoId: String, raw: String?) {
        val codec = normalizeCodec(raw) ?: return
        if (observedCodecs.size > OBSERVED_CODEC_CAP) observedCodecs.clear()
        observedCodecs[videoId] = codec
    }

    companion object {
        private const val TAG = "PreviewUrlExtractor"
        private const val YTDLP_TIMEOUT_MS = 60_000L
        private const val INNERTUBE_TIMEOUT_MS = 10_000L
        private const val FORMAT_SELECTOR = "251/250/bestaudio"

        /**
         * Selector for the pinned fast client. Audio-only itags first (251/250
         * opus, 140 m4a); `best` last.
         *
         * That trailing `best` is a deliberate compromise, measured on-device
         * 2026-07-29: android_vr currently advertises NO audio-only format, so an
         * audio-only-only selector fails the attempt and the track falls to the
         * default client at ~13s. With `best` it serves itag 18 — a combined 360p
         * MP4, AAC ~96k — in ~2.8s. So the choice on this path is 2.8s at 96k AAC
         * (plus discarded video bytes) versus 13s at 160k opus.
         *
         * Fast wins here because this is already the lossy last-resort path — it
         * only runs when the track has no lossless source at all, and Now Playing
         * badges it "via YT". A 13-second stall is the worse experience. Flip the
         * order (drop `/best`) to trade back to quality.
         */
        private const val FAST_CLIENT_FORMAT_SELECTOR = "251/250/140/bestaudio/best"

        /**
         * Save Data on the lossy path: the same "low" the download tier means
         * (QualityTier.LOW → 250/249, Opus ~70 / ~50 kbps), per client shape.
         */
        private const val LOW_FORMAT_SELECTOR = "250/249/bestaudio"
        private const val LOW_FAST_CLIENT_FORMAT_SELECTOR = "250/249/140/bestaudio/best"

        /**
         * Ordered client chain for the pinned fast attempts. The first client
         * that yields a URL surviving [AudioUrlTailProbe] wins; a gated or
         * empty answer falls through to the next.
         *
         * ⚠️ 2026-08-21: `android_vr` — the single pinned client since June —
         * STOPPED being PO-token-free. yt-dlp now warns
         *   "android_vr client https formats require a GVS PO Token which was
         *    not provided. They will be skipped as they may yield HTTP Error 403"
         * so it drops the audio-only itags and serves combined itag 18, whose URL
         * 403s on open. With InnerTube's own URLs gated too, NOTHING played: every
         * YouTube-sourced track 403'd at ExoPlayer and skipped (code=2004), for a
         * week, for every user. That is why this is a LIST now — pinning one
         * client makes a single YouTube policy change a total outage.
         *
         * Candidates are the three clients yt-dlp's PO-Token-Guide lists as
         * needing no PO token. Order is MEASURED on-device 2026-08-21, not
         * guessed from the guide:
         *  - `web_embedded` — **the one that works.** Serves itag 251 (Opus
         *    ~160k, `vcodec=none`), passes the tail probe, plays. Note it also
         *    restores AUDIO-ONLY: the broken android_vr path had degraded to
         *    itag 18, a combined 360p MP4 whose video bytes we downloaded and
         *    threw away. ~11.7 s.
         *  - `android_vr` — last. Token-free per the guide, but that is exactly
         *    the claim that expired above; kept only as a long-tail backstop.
         *
         * `tv` is NOT here, despite leading on paper (token-free, DRM'd only
         * without cookies, which this path has). It failed EVERY attempt with
         * "ERROR: [youtube] The page needs to be reloaded" at ~5.2 s a go. A
         * client with a 0% observed success rate is not a free backstop: this
         * chain runs inside [LazyResolvingDataSource]'s 45 s
         * RESOLVE_DEADLINE_MS, so every doomed member is worst-case budget
         * spent on the way to a SKIPPED TRACK. Two candidates plus the default
         * client set fit; three did not reliably. Add it back the day
         * web_embedded dies, not before.
         *
         * Every entry must be tail-probed before use — "no PO token required" is
         * a claim about YouTube's current policy, not a guarantee, and this
         * outage is what an untested claim costs.
         */
        internal val FAST_PLAYER_CLIENTS = listOf("web_embedded", "android_vr")

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

        /** Upper bound on [observedCodecs] before it is dropped wholesale. */
        private const val OBSERVED_CODEC_CAP = 512

        /**
         * Folds a yt-dlp `acodec` (`opus`, `mp4a.40.2`) or an InnerTube
         * mimeType codec into the short label the Now Playing badge shows.
         * `none`/`NA` — what yt-dlp prints for a video-only or unknown track —
         * map to null so a missing value never overwrites a real one.
         */
        internal fun normalizeCodec(raw: String?): String? {
            val v = raw?.trim()?.trim('"')?.lowercase()
                ?.takeIf { it.isNotBlank() && it != "none" && it != "na" }
                ?: return null
            return when {
                v.startsWith("opus") -> "opus"
                v.startsWith("mp4a") || v.contains("aac") -> "aac"
                v.startsWith("vorbis") -> "vorbis"
                v.startsWith("flac") -> "flac"
                v.startsWith("mp3") -> "mp3"
                else -> v
            }
        }

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
        internal fun selectBestAudioUrl(formats: List<JsonObject>, lowestQuality: Boolean = false): String? =
            selectBestAudioFormat(formats, lowestQuality)?.get("url")?.jsonPrimitive?.content

        /**
         * Format-returning form of [selectBestAudioUrl]. Playback needs the whole
         * format object, not just its `url`, so the caller can read
         * `contentLength` and tail-probe it — see [AudioUrlTailProbe].
         */
        internal fun selectBestAudioFormat(formats: List<JsonObject>, lowestQuality: Boolean = false): JsonObject? {
            val audio = formats.filter { f ->
                (f["mimeType"]?.jsonPrimitive?.content ?: "").startsWith("audio/") && f["url"] != null
            }
            fun bitrate(f: JsonObject) = f["bitrate"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            fun isOpus(f: JsonObject) = (f["mimeType"]?.jsonPrimitive?.content ?: "").contains("opus")
            // Save Data on the lossy path asks for the LOWEST stream; Opus still wins its rank
            // for quality per byte.
            fun pick(l: List<JsonObject>) = if (lowestQuality) l.minByOrNull(::bitrate) else l.maxByOrNull(::bitrate)
            val opusBest = pick(audio.filter(::isOpus))
            val best = opusBest ?: pick(audio)
            if (best != null) {
                val mime = best["mimeType"]?.jsonPrimitive?.content
                Log.d(TAG, "InnerTube selected mime=$mime bitrate=${bitrate(best)} opusPreferred=${opusBest != null}")
            }
            return best
        }

        /**
         * Clients yt-dlp will not run with a `--cookies` file. Passing both makes
         * yt-dlp skip the client entirely ("Skipping client X since it does not
         * support cookies") rather than dropping the cookies, so a pinned
         * cookie-incompatible client silently becomes a wasted round-trip plus a
         * fallback. These clients are unauthenticated by design — that is why
         * they return PO-token-free URLs — so they lose nothing without cookies.
         */
        private val COOKIE_INCOMPATIBLE_CLIENTS = setOf("android_vr")

        internal fun supportsCookies(playerClient: String?): Boolean =
            playerClient == null || playerClient !in COOKIE_INCOMPATIBLE_CLIENTS

        /**
         * Per-client format selector. The default client set reliably serves
         * Opus itag 251/250, so ask for it by number and get the best free audio.
         *
         * android_vr does NOT (as of 2026-07-29 it answers
         * "Requested format is not available" for 251/250/bestaudio), so pinning
         * those itags failed the entire attempt and sent every extraction to the
         * slow default path. Ask it for the best audio it actually has, falling
         * back to a combined stream only if it offers no audio-only format.
         */
        internal fun formatSelectorFor(playerClient: String?, lowestQuality: Boolean = false): String = when {
            lowestQuality && playerClient != null -> LOW_FAST_CLIENT_FORMAT_SELECTOR
            lowestQuality -> LOW_FORMAT_SELECTOR
            playerClient != null -> FAST_CLIENT_FORMAT_SELECTOR
            else -> FORMAT_SELECTOR
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
    suspend fun extractStreamUrl(videoId: String, allowYtDlp: Boolean = true, lowestQuality: Boolean = false): String =
        coalesce(coalesceKey(videoId, allowYtDlp, lowestQuality)) { doExtract(videoId, allowYtDlp, lowestQuality) }

    // The `#fast` suffix is collision-safe: YouTube videoIds are `#`-free
    // 11-char base64url, so a suffixed fast-only key can never equal a real
    // (full-race) videoId key.
    private fun coalesceKey(videoId: String, allowYtDlp: Boolean, lowestQuality: Boolean = false) =
        (if (allowYtDlp) videoId else "$videoId#fast") + (if (lowestQuality) "#low" else "")

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
    private suspend fun doExtract(videoId: String, allowYtDlp: Boolean, lowestQuality: Boolean = false): String {
        // Fast-only lane: InnerTube under its own semaphore, never the
        // serialized yt-dlp slot. A miss is signalled (not retried here) so
        // the caller can drop the track from background fill; the 1-ahead
        // prefetch (allowYtDlp = true) recovers it later.
        if (!allowYtDlp) {
            val t0 = System.currentTimeMillis()
            Log.d("LATDIAG", "extract-start videoId=$videoId fastOnly=1")
            val url = innerTubeSemaphore.withPermit { extractViaInnerTube(videoId, lowestQuality) } ?: run {
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
                    val result = runCatching { extractViaInnerTube(id, lowestQuality) }
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
                    val result = runCatching { extractViaYtDlp(id, lowestQuality) }
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
     * Fast path: extract stream URL via InnerTube player API.
     *
     * Calls `/youtubei/v1/player` with the user's YouTube session cookies.
     * Parses `streamingData.adaptiveFormats` for audio-only formats with
     * direct `url` fields (not `signatureCipher`).
     *
     * Returns null if no usable URL is found (all formats ciphered, video
     * unavailable, etc.).
     */
    private suspend fun extractViaInnerTube(videoId: String, lowestQuality: Boolean = false): String? {
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
            val bestFormat = selectBestAudioFormat(adaptiveFormats.filterIsInstance<JsonObject>(), lowestQuality) ?: run {
                Log.d(TAG, "InnerTube: no audio formats with direct URL for $videoId " +
                    "(${adaptiveFormats.size} total formats, all may be ciphered)")
                return@withTimeout null
            }
            val streamUrl = bestFormat["url"]?.jsonPrimitive?.content ?: return@withTimeout null

            // A PO-token-gated URL serves ~1MB then 403s. Handing one back is what
            // killed playback on 2026-06-08 and cost us the fast lane; the probe is
            // what makes this path safe for whole tracks rather than previews only.
            // Rejection just defers to yt-dlp, exactly as a cipher miss does.
            val contentLength = bestFormat["contentLength"]?.jsonPrimitive?.content?.toLongOrNull()
            if (!tailProbe.servesFullFile(streamUrl, contentLength)) {
                Log.i(TAG, "InnerTube: URL failed the tail probe for $videoId — deferring to yt-dlp")
                return@withTimeout null
            }

            // Only recorded once the probe has passed — an unusable URL must
            // not get to name the codec for a track it will never play.
            recordCodec(
                videoId,
                bestFormat["mimeType"]?.jsonPrimitive?.content?.substringAfter("codecs=", ""),
            )
            Log.d(TAG, "InnerTube: SUCCESS videoId=$videoId urlLen=${streamUrl.length}")
            streamUrl
        }
    }

    /**
     * Slow fallback: extract stream URL via yt-dlp with QuickJS cipher solving.
     */
    private suspend fun extractViaYtDlp(videoId: String, lowestQuality: Boolean = false): String {
        return withTimeout(YTDLP_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                ytDlpManager.initialize()

                // Walk the pinned clients in order and take the first URL that
                // PROVES it can serve its final byte. Pinning one client is
                // ~3.6s vs ~12.8s for yt-dlp's default multi-client + m3u8 +
                // challenge path (measured on-device 2026-06-26), so the leader
                // still pays for itself; the rest of the chain only runs when
                // YouTube has gated the leader.
                //
                // The probe is the point. Before it, this path returned the first
                // URL any client produced — so when android_vr started handing
                // back PO-token-gated itag-18 URLs, every one of them sailed
                // through to ExoPlayer and 403'd there as a skipped track. A URL
                // that cannot serve its last byte is not a stream, and finding
                // that out here costs one range request instead of a dead track.
                for (client in FAST_PLAYER_CLIENTS) {
                    val stream = try {
                        runYtDlp(videoId, playerClient = client, lowestQuality = lowestQuality)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        Log.d(TAG, "yt-dlp $client attempt failed for $videoId: ${e.message}")
                        null
                    } ?: continue

                    // Cheap offline filter BEFORE the probe (credit: ParaliyzedEvo,
                    // PR #450). A combined audio+video answer is the selector's
                    // trailing `best` catch-all firing — the fast client had no
                    // audio-only format to give — and that is the exact shape the
                    // PO-token gate has been landing on (itag 18). Rejecting it
                    // costs no network call at all.
                    //
                    // Tested on `vcodec` rather than an itag allowlist: the
                    // selector ends `bestaudio/best`, so a legitimate answer can
                    // be 139/249/774 or whatever YouTube adds next, and a static
                    // {251,250,140} list would throw those away. `vcodec` is
                    // already printed on the same diagnostic line and is correct
                    // for every itag. Null fails OPEN, matching the probe's bias.
                    if (stream.vcodec != null && stream.vcodec != "none") {
                        Log.i(TAG, "yt-dlp: $client returned combined vcodec=${stream.vcodec} for $videoId — next client")
                        continue
                    }

                    // Then the probe, which catches what the format check cannot:
                    // a genuinely audio-only URL that is nonetheless gated. The
                    // format test has no signal for that case, and it is what
                    // arrives the next time YouTube rotates the gate.
                    if (tailProbe.servesFullFile(stream.url, stream.sizeBytes)) {
                        Log.i(TAG, "yt-dlp: $client served a full-file URL for $videoId")
                        return@withContext stream.url
                    }
                    Log.i(TAG, "yt-dlp: $client URL failed the tail probe for $videoId — next client")
                }

                // Last resort: yt-dlp's own default client set. Deliberately NOT
                // probed — nothing follows it, so a false rejection would turn a
                // playable track into a skip. Let ExoPlayer have its try.
                (runYtDlp(videoId, playerClient = null, lowestQuality = lowestQuality)
                    ?: throw IllegalStateException("yt-dlp returned no stream URL for videoId=$videoId")).url
            }
        }
    }

    /**
     * A yt-dlp answer: the stream URL plus the format's byte size when yt-dlp
     * knows it. [sizeBytes] is null for size-less formats (HLS/DASH manifests,
     * or a `NA` print), which makes [AudioUrlTailProbe] fail open for that URL —
     * the deliberate bias, since a false rejection costs a working stream.
     */
    private data class YtDlpStream(
        val url: String,
        val sizeBytes: Long?,
        /** yt-dlp's `vcodec`; `"none"` for a true audio-only format, null if unprinted. */
        val vcodec: String?,
    )

    /**
     * One yt-dlp `execute()` for [videoId]. [playerClient] pins the YouTube
     * extractor to a single client (e.g. `tv`); null leaves yt-dlp's
     * default client set. Returns the stream, or null when this client set
     * produced no URL (caller decides whether to fall back). Holds no semaphore
     * — the caller already owns the cap-1 [ytDlpSemaphore] permit.
     */
    private suspend fun runYtDlp(videoId: String, playerClient: String?, lowestQuality: Boolean = false): YtDlpStream? {
        val cookieFile = File(context.noBackupFilesDir, "yt_preview_cookies_${System.nanoTime()}.txt")
        return try {
            val url = "https://www.youtube.com/watch?v=$videoId"

            val request = YoutubeDLRequest(url).apply {
                addOption("-f", formatSelectorFor(playerClient, lowestQuality))
                addOption("--print", "urls")
                // Diagnostic: which format the client actually served. android_vr
                // stopped offering itag 251/250, so a selector that demands them
                // fails the whole attempt — this makes the next such drift legible
                // instead of a bare "Requested format is not available".
                addOption("--print", "fmt=%(format_id)s acodec=%(acodec)s vcodec=%(vcodec)s")
                // Byte size for the tail probe. Without it the probe has nothing
                // to seek to and accepts every URL, which is how gated URLs
                // reached the player. `NA` (size-less formats) parses to null.
                addOption("--print", "size=%(filesize,filesize_approx)s")
                addOption("--no-download")
                playerClient?.let { addOption("--extractor-args", "youtube:player_client=$it") }

                val qjsPath = ytDlpManager.quickJsPath
                if (qjsPath != null) {
                    addOption("--js-runtimes", "quickjs:$qjsPath")
                    addOption("--remote-components", "ejs:github")
                }
            }

            // Cookies and android_vr are mutually exclusive: yt-dlp refuses the
            // client outright rather than dropping the cookies —
            //   WARNING: [youtube] Skipping client "android_vr" since it does not
            //   support cookies
            // — so passing both silently disabled the whole fast path. Measured
            // on-device 2026-07-29: 3.5s burned being refused, then 11.6s on the
            // default multi-client path, ~15s per track, for every YouTube
            // extraction since the two features met. android_vr needs no auth
            // (that's why it's PO-token-free), so dropping cookies for that
            // attempt costs nothing; the default-client fallback still sends them
            // for age-gated / private content.
            val cookie = tokenManager.getYouTubeCookie()
            if (cookie != null && supportsCookies(playerClient)) {
                CookieFileWriter.write(cookie, cookieFile)
                cookieFile.setReadable(false, false)
                cookieFile.setReadable(true, true)
                cookieFile.setWritable(false, false)
                cookieFile.setWritable(true, true)
                request.addOption("--cookies", cookieFile.absolutePath)
            }

            Log.d(TAG, "yt-dlp: invoking for videoId=$videoId client=${playerClient ?: "default"}")
            // null processId: sharing the video url as the id collides with a
            // concurrent download of the same track ("Process ID already
            // exists"). The id is never used to cancel. (#210)
            val response = YoutubeDL.getInstance().execute(request, null, null)

            val stdout = response.out.orEmpty()
            val stderr = response.err.orEmpty()
            Log.d(TAG, "yt-dlp: exit=${response.exitCode} client=${playerClient ?: "default"} stdoutLen=${stdout.length}")
            if (stderr.isNotBlank()) {
                Log.d(TAG, "yt-dlp stderr: ${stderr.take(500)}")
            }

            // The `fmt=` line comes from the diagnostic --print above. Logging it
            // tells us whether a client served audio-only or fell back to a
            // combined (video-carrying) stream, which matters for a music app's
            // data use.
            val fmtLine = stdout.trim().lines().firstOrNull { it.startsWith("fmt=") }
            // The `fmt=` line comes from the diagnostic --print above. Logging it
            // tells us whether a client served audio-only or fell back to a
            // combined (video-carrying) stream, which matters for a music app's
            // data use.
            fmtLine?.let { line ->
                Log.d(TAG, "yt-dlp: $line client=${playerClient ?: "default"}")
                // The winning client writes last: a client whose URL later fails
                // the tail probe is overwritten by the one that replaces it, so
                // the recorded codec always describes the URL actually returned.
                recordCodec(videoId, line.substringAfter("acodec=", "").substringBefore(' '))
            }
            val vcodec = fmtLine
                ?.substringAfter("vcodec=", "")
                ?.substringBefore(' ')
                ?.trim()
                ?.takeIf { it.isNotBlank() && it != "NA" }

            val streamUrl = stdout.trim().lines().firstOrNull { it.startsWith("http") }
            if (streamUrl.isNullOrBlank()) {
                Log.d(TAG, "yt-dlp: no URL videoId=$videoId client=${playerClient ?: "default"}")
                null
            } else {
                val size = stdout.trim().lines()
                    .firstOrNull { it.startsWith("size=") }
                    ?.removePrefix("size=")?.trim()?.toLongOrNull()
                Log.d(TAG, "yt-dlp: SUCCESS videoId=$videoId urlLen=${streamUrl.length} size=$size vcodec=$vcodec")
                YtDlpStream(streamUrl, size, vcodec)
            }
        } finally {
            if (cookieFile.exists()) cookieFile.delete()
        }
    }
}

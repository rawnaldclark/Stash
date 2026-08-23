package com.stash.core.media.streaming

import android.util.Log
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.StreamingPreference
import com.stash.data.download.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Walks Stash's streaming-source roster in priority order and returns
 * the first match. Each resolver internally handles its own enablement
 * (captcha cookies, circuit-breaker state for non-streaming paths, etc.)
 * — null from one resolver just means "try the next one".
 *
 * Current order:
 *   1. [QbdlxStreamResolver]   — `qbdlx`, the DIRECT Qobuz API (signed
 *      requests + a rotating per-account token pool). Primary lossless source:
 *      plain Range-seekable FLAC, no proxy operator and no client-side decrypt,
 *      so it's the fastest path. Foreground-only (allowYtDlp) since it spends
 *      pool-account quota.
 *   2. [AmzStreamResolver]     — `amz.squid.wtf`, Amazon Music lossless FLAC.
 *      Consulted when qbdlx has no confident match, so an Amazon-only track
 *      still streams lossless before dropping to lossy YouTube. Its resolver
 *      decrypts the whole file client-side (slow), so it sits LAST among the
 *      lossless sources and is foreground-only too.
 *   3. [ArcodStreamResolver]   — ARCOD, an authenticated per-user-account
 *      lossless fallback. NOT parked, but conditional: [resolve] only adds it
 *      when the build bundles the private stream base
 *      (`BuildConfig.ARCOD_CONFIGURED`), so an unconfigured build skips it
 *      entirely. Foreground/next-up only. Its participation is therefore
 *      build-dependent — tests must not assert it unconditionally.
 *   PARKED (2026-07-01, hosts down for us — commented out of the chain in
 *   [resolve], kept for re-enablement): [KennyyStreamResolver] (`kennyy.com.br`),
 *   [QobuzStreamResolver] (`qobuz.squid.wtf`).
 *   4. [YouTubeStreamResolver] — yt-dlp / InnerTube extraction. Last
 *      resort, reached only when the track genuinely isn't in the Qobuz
 *      catalog (Bandcamp re-uploads, region-exclusive, underground
 *      releases). Lossy quality (AAC/Opus ~128-160 kbps), surfaced as a
 *      "via YT" badge in Now Playing so the user knows.
 *
 * Exposes the same `resolve(track) -> StreamUrl?` shape as the individual
 * resolvers so callers ([PlayerRepositoryImpl], [StreamingMediaSourceFactory],
 * [RefreshingDataSourceFactory], [PrefetchOrchestrator]) can swap in by
 * type without changing call-site logic.
 *
 * Result caching is the caller's responsibility — [StreamUrlCache] sits
 * on the player side and stores the first source's success keyed by track
 * id. Subsequent plays of the same track hit the cache and bypass the
 * registry entirely until the URL's `etsp` expires.
 *
 * Test toggles (off for normal use):
 *  - [StreamingPreference.isForceArcodOnly]: [resolve] routes through arcod
 *    ONLY — kennyy/squid/youtube removed from play so a track either streams
 *    via arcod or fails visibly. Takes precedence over force-amz and
 *    force-YouTube. Used to exercise the arcod source on demand.
 *  - [StreamingPreference.isForceAmzOnly]: [resolve] routes through amz
 *    ONLY — kennyy/squid/youtube removed from play so a track either streams
 *    via amz or fails visibly. Takes precedence over force-YouTube. Used to
 *    exercise the amz source on demand.
 *  - [StreamingPreference.isForceYouTubeFallback]: [resolve] skips Kennyy
 *    and Squid entirely and routes every track through the YouTube resolver
 *    only — reproduces the lossless-down fallback path on demand.
 */
@Singleton
class StreamSourceRegistry @Inject constructor(
    private val kennyy: KennyyStreamResolver,
    private val qobuz: QobuzStreamResolver,
    private val arcod: ArcodStreamResolver,
    private val amz: AmzStreamResolver,
    private val qbdlx: QbdlxStreamResolver,
    private val jiosaavn: JioSaavnStreamResolver,
    private val youtube: YouTubeStreamResolver,
    private val streamingPreference: StreamingPreference,
    private val losslessSourceHealth: LosslessSourceHealth,
) {
    /**
     * Shared scope for in-flight resolves — mirrors
     * [com.stash.data.download.preview.PreviewUrlExtractor.extractorScope].
     * A resolve must outlive any single caller's cancellation: if the tap
     * that started it gets superseded, a concurrent prefetch (or vice versa)
     * waiting on the SAME track must still get the result rather than
     * restarting the whole chain from scratch.
     */
    private val resolveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Single-flight cache, keyed by track id + the (allowYouTube, allowYtDlp)
     * combo — two different call sites resolving the SAME track with
     * DIFFERENT flags (e.g. background-fill's fast lane vs. a foreground
     * tap's full chain) must not share a result, since a fast-lane call
     * deliberately accepts a narrower source set. Entries self-remove on
     * completion via invokeOnCompletion, same lifecycle as
     * [PreviewUrlExtractor.inFlightExtracts].
     */
    private val inFlightResolves = ConcurrentHashMap<String, Deferred<StreamUrl?>>()

    private fun resolveKey(trackId: Long, allowYouTube: Boolean, allowYtDlp: Boolean) =
        "$trackId:$allowYouTube:$allowYtDlp"

    /**
     * Try each resolver in priority order; return the first non-null
     * [StreamUrl]. Returns null when no source produced a match — caller
     * should surface this as [StreamRoutingResult.NotAvailable].
     *
     * Concurrent callers resolving the SAME track (with the same
     * [allowYouTube]/[allowYtDlp] combo) are coalesced into one resolver
     * chain — see [inFlightResolves]. Without this, a foreground tap and a
     * next-up prefetch landing on the same track within a few hundred ms of
     * each other each ran their own independent chain: doubled network calls
     * to every resolver (including a rate-limited search API), and each
     * independently drove the YouTube resolver's own internal coalescing to
     * start, complete, and tear down before the other call even began —
     * turning one ~6s yt-dlp extraction into two sequential ones instead of
     * one shared result. Device-confirmed 2026-08-22: track 916 resolved
     * successfully via yt-dlp, then a second full chain (through jiosaavn)
     * started 325ms later for the identical track.
     *
     * @param allowYouTube pass `false` to skip lossy fallbacks (JioSaavn
     *   and YouTube), leaving only configured lossless sources. Used by
     *   [PlayerRepositoryImpl.setQueue]'s background-fill path so
     *   yt-dlp's limited 2-slot extraction semaphore stays available
     *   for the foreground user-tap critical path. Foreground (tapped
     *   track) calls leave this true.
     * @param allowYtDlp pass `false` to make the YouTube fallback resolve
     *   via the fast InnerTube engine only (no slow yt-dlp). Used by the
     *   background-fill path so a 15-35s yt-dlp invocation never sits on
     *   the queue's critical path. Foreground calls leave this true.
     */
    suspend fun resolve(
        track: TrackEntity,
        allowYouTube: Boolean = true,
        allowYtDlp: Boolean = true,
    ): StreamUrl? {
        val key = resolveKey(track.id, allowYouTube, allowYtDlp)
        inFlightResolves[key]?.let { return it.await() }

        val freshlyCreated = resolveScope.async(start = CoroutineStart.LAZY) {
            doResolve(track, allowYouTube, allowYtDlp)
        }
        val deferred = inFlightResolves.putIfAbsent(key, freshlyCreated)
            ?: freshlyCreated.also { d ->
                d.invokeOnCompletion { inFlightResolves.remove(key, d) }
                d.start()
            }
        return deferred.await()
    }

    /**
     * Underlying resolver-chain walk — original `resolve` implementation.
     * Called via the single-flight wrapper above.
     */
    private suspend fun doResolve(
        track: TrackEntity,
        allowYouTube: Boolean,
        allowYtDlp: Boolean,
    ): StreamUrl? {
        val resolvers = buildList<Pair<String, suspend (TrackEntity) -> StreamUrl?>> {
            if (streamingPreference.isForceQbdlxOnly()) {
                // Test toggle: qbdlx (direct-Qobuz) ONLY — skip every other source
                // so qbdlx can be exercised even when the proxies are healthy.
                // Takes precedence over the other force toggles. Gated by
                // allowYtDlp like arcod/amz so speculative background fill spends
                // no pool-account quota (only foreground/next-up resolves hit it).
                if (allowYtDlp) add("qbdlx" to qbdlx::resolve)
                // Same guarantee as the force-arcod branch below: a force toggle
                // is a TEST instrument, but the pref outlives the build — and the
                // qbdlx pool can die under it (it did; #429's reporter had this
                // toggle on and got an infinite spinner on every track). Keep the
                // lossy safety net so no stale preference means silence.
                if (allowYouTube && allowYtDlp) add("jiosaavn" to jiosaavn::resolve)
                if (allowYouTube) add("youtube" to { t: TrackEntity -> youtube.resolve(t, allowYtDlp) })
            } else if (streamingPreference.isForceArcodOnly()) {
                // Test toggle: ARCOD ONLY — skip kennyy/squid/YouTube so the
                // ARCOD path can be exercised even when the Qobuz proxies are
                // healthy. Takes precedence over forceAmzOnly and
                // forceYouTubeFallback. Still gated by allowYtDlp so the
                // speculative background fill resolves NOTHING (matching
                // forceYt) — without this, flipping the toggle and tapping a
                // playlist would spend a search call + the user's arcod account
                // on every queue track speculatively, not just the ones played.
                if (allowYtDlp) add("arcod" to arcod::resolve)
                // ...and YouTube stays available behind it. A force toggle is a
                // TEST instrument, but the pref outlives the build that showed it.
                // While arcod was parked, a stale `force_arcod_only = true` meant
                // every track resolved through a dead source with no fallback and
                // no UI left to switch it off — silence, permanently. arcod is
                // live again now, but the guarantee has to survive the NEXT time a
                // source is parked, which is the amz failure described below.
                //
                // Keeping the fallback costs the toggle a little of its "fails
                // visibly" sharpness and buys back the guarantee that no
                // preference, however stale, can leave a user unable to play music.
                // That trade is not close.
                if (allowYouTube && allowYtDlp) add("jiosaavn" to jiosaavn::resolve)
                if (allowYouTube) add("youtube" to { t: TrackEntity -> youtube.resolve(t, allowYtDlp) })
                // NOTE: the force-amz branch is deliberately gone (2026-07-31).
                //
                // amz is parked, and its Settings toggle was removed with it — but a
                // user who had already switched it on still has `force_amz_only = true`
                // sitting in DataStore, with no UI left to switch it off. Had this
                // branch survived, those users would route every track through a
                // parked source and get silence, permanently, with no way out.
                //
                // Parking a source therefore has to mean parking its force branch in
                // the same change. This is the exact failure shape that took a full
                // debugging session when force-YouTube was left enabled in a release
                // install: a stale preference quietly disabling lossless. The pref key
                // and StreamingPreference accessor stay for re-enablement.
            } else if (streamingPreference.isForceYouTubeFallback()) {
                // Test toggle: skip the lossless sources, forcing the
                // YouTube fallback path. Still gated by allowYouTube so the
                // background-fill keeps resolving nothing (matching a genuine
                // both-sources-down outage).
                if (allowYouTube) add("youtube" to { t: TrackEntity -> youtube.resolve(t, allowYtDlp) })
            } else {
                // PARKED 2026-07-01: kennyy/squid hosts are down for us. Kept in
                // sync with LosslessSourceRegistry.PARKED_SOURCE_IDS (download
                // side) — re-enabling is uncommenting these two lines.
                // add("kennyy" to kennyy::resolve)
                // add("squid" to qobuz::resolve)

                // qbdlx (direct Qobuz API, per-account token pool) is the
                // primary lossless source: plain Range-seekable FLAC, no proxy,
                // no client-side decrypt — the fastest path. Foreground/next-up
                // only (allowYtDlp = true), never the speculative background
                // fill, and only when the build actually bundles qbdlx creds —
                // an unconfigured build can never get a match here, so skipping
                // the attempt avoids a wasted round-trip.
                if (allowYtDlp && BuildConfig.QBDLX_CONFIGURED) {
                    add("qbdlx" to qbdlx::resolve)
                }
                // PARKED 2026-07-30: amz and arcod are no longer working lossless
                // providers — qbdlx is the only one. Leaving them in the chain cost
                // every YouTube-fallback play a wait for two sources that cannot
                // succeed. Measured on-device that day:
                //
                //   57.772  chain [qbdlx,amz,arcod,youtube]
                //   58.148  qbdlx 403                     (0.4s)
                //   59.391  amz attempted                 (1.2s)
                //   02.991  youtube served                (arcod 3.6s)
                //
                // ~4.8s of the 5.2s resolve spent on dead sources, on top of the
                // yt-dlp extraction itself. Removing them is a larger win than the
                // extraction fix that preceded it.
                //
                // amz stays parked (client-side decrypt, no working operator).
                // add("amz" to amz::resolve)
                //
                // arcod UNPARKED 2026-08-01: the operator rotated the integration
                // key and moved us to /v2/stash — verified live (stream returns
                // audio/flac, fLaC-magic byte-checked). Sits AFTER qbdlx (qbdlx is
                // plain Range-seekable FLAC with no per-user login) and before the
                // lossy YouTube fallback, so a track qbdlx misses can still play
                // lossless for anyone who connected an ARCOD account.
                //
                // Foreground/next-up only (allowYtDlp), like qbdlx: it spends the
                // user's own arcod quota, so the speculative background fill must
                // not touch it. Also build-gated — an APK without the private key
                // can only 403, so skipping it avoids a guaranteed-wasted round trip.
                if (allowYtDlp && BuildConfig.ARCOD_CONFIGURED) {
                    add("arcod" to arcod::resolve)
                }
                // Fixed-quality AAC 320 fallback. Foreground/next-up only: a
                // speculative full-queue fill must not turn into one metadata
                // search + media probe per track. Any miss/outage continues to
                // the existing YouTube fallback below.
                if (allowYouTube && allowYtDlp) {
                    add("jiosaavn" to jiosaavn::resolve)
                }
                if (allowYouTube) add("youtube" to { t: TrackEntity -> youtube.resolve(t, allowYtDlp) })
            }
        }
        // Which sources are actually IN PLAY for this resolve, and why.
        //
        // Added after a "works in debug, dead in release" hunt where qbdlx never
        // appeared in the logs at all and there was no way to tell whether it had
        // been tried and failed, or silently excluded before it ever ran. The two
        // gates that can drop a source here are invisible from the outside:
        // `allowYtDlp` (false for speculative background fill) and the
        // compile-time BuildConfig flags. Info level so a release build says so.
        Log.i(
            TAG,
            "chain for ${track.id}: [${resolvers.joinToString(",") { it.first }}] " +
                "allowYouTube=$allowYouTube allowYtDlp=$allowYtDlp " +
                "qbdlxConfigured=${BuildConfig.QBDLX_CONFIGURED} " +
                "arcodConfigured=${BuildConfig.ARCOD_CONFIGURED}",
        )

        for ((name, fn) in resolvers) {
            val result = runCatching { fn(track) }
                .onFailure { e ->
                    // Preemption (user tapped another track) cancels this job;
                    // the CE MUST propagate, not be swallowed and logged as a
                    // resolver failure — otherwise resolve() returns null and
                    // the caller fires a bogus "Couldn't find this track."
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // Resolvers should never throw — they catch and return
                    // null. Defensive log so an unexpected throw from one
                    // source doesn't break the chain.
                    Log.w(TAG, "$name threw on resolve for ${track.id} '${track.title}'", e)
                }
                .getOrNull()
            // Feed the Home rescue-banner signal: a qbdlx null here is a miss
            // (dead token OR catalog gap — the streak threshold tells them
            // apart), a non-null is a serve that resets the streak.
            if (name == "qbdlx") {
                if (result != null) {
                    losslessSourceHealth.recordQbdlxServed()
                } else {
                    losslessSourceHealth.recordQbdlxMiss()
                }
            }
            if (result != null) {
                // Diagnostic: which source actually served the stream. Helps
                // explain "this track played but at lower quality" reports.
                Log.i(TAG, "$name served ${track.id} '${track.title}'")
                return result
            }
        }
        return null
    }

    private companion object {
        private const val TAG = "StreamSourceRegistry"
    }
}

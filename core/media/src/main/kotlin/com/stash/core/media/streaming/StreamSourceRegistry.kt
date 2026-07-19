package com.stash.core.media.streaming

import android.util.Log
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.StreamingPreference
import com.stash.data.download.BuildConfig  
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
 *   PARKED (2026-07-01, hosts down for us — commented out of the chain in
 *   [resolve], kept for re-enablement): [KennyyStreamResolver] (`kennyy.com.br`),
 *   [QobuzStreamResolver] (`qobuz.squid.wtf`), [ArcodStreamResolver] (ARCOD).
 *   3. [YouTubeStreamResolver] — yt-dlp / InnerTube extraction. Last
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
    private val youtube: YouTubeStreamResolver,
    private val streamingPreference: StreamingPreference,
) {

    /**
     * Try each resolver in priority order; return the first non-null
     * [StreamUrl]. Returns null when no source produced a match — caller
     * should surface this as [StreamRoutingResult.NotAvailable].
     *
     * @param allowYouTube pass `false` to skip the YouTube fallback
     *   resolver, leaving only the two Qobuz operators. Used by
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
        val resolvers = buildList<Pair<String, suspend (TrackEntity) -> StreamUrl?>> {
            if (streamingPreference.isForceQbdlxOnly()) {
                // Test toggle: qbdlx (direct-Qobuz) ONLY — skip every other source
                // so qbdlx can be exercised even when the proxies are healthy.
                // Takes precedence over the other force toggles. Gated by
                // allowYtDlp like arcod/amz so speculative background fill spends
                // no pool-account quota (only foreground/next-up resolves hit it).
                if (allowYtDlp) add("qbdlx" to qbdlx::resolve)
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
            } else if (streamingPreference.isForceAmzOnly()) {
                // Test toggle (outage drill): amz ONLY — kennyy/squid/youtube
                // removed from play so a track either streams via amz or fails
                // visibly. Ignores allowYouTube/allowYtDlp — it's amz or nothing.
                add("amz" to amz::resolve)
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
                // amz (Amazon Music) is the SLOWEST lossless source: its stream
                // resolver decrypts the whole FLAC to a local cache file before
                // returning a URL (tens of seconds), serialized behind a single
                // captcha / per-asin lock. Foreground/next-up only, and skipped
                // on cellular fast-start so it doesn't starve the YouTube
                // fallback (observed on-device 2026-06-21: 52s to resolve one
                // next-up).
                if (allowYtDlp) {
                    add("amz" to amz::resolve)
                }
                // ARCOD is an authenticated, per-user-account fallback. Foreground/
                // next-up only, and only when the build bundles the private
                // stream base — an unconfigured build can never get a match.
                if (allowYtDlp && BuildConfig.ARCOD_CONFIGURED) {
                    add("arcod" to arcod::resolve)
                }
                if (allowYouTube) add("youtube" to { t: TrackEntity -> youtube.resolve(t, allowYtDlp) })
            }
        }
        for ((name, fn) in resolvers) {
            val result = runCatching { fn(track) }
                .onFailure { e ->
                    // Resolvers should never throw — they catch and return
                    // null. Defensive log so an unexpected throw from one
                    // source doesn't break the chain.
                    Log.w(TAG, "$name threw on resolve for ${track.id} '${track.title}'", e)
                }
                .getOrNull()
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

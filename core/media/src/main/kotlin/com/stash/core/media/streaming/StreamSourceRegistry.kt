package com.stash.core.media.streaming

import android.util.Log
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.media.diagnostics.PlaybackDiagnosticsLog
import com.stash.data.download.lossless.LosslessAvailability
import com.stash.data.download.lossless.LosslessBusyNote
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.PlayOrder
import com.stash.data.download.lossless.quota.LosslessQuota
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Walks Stash's streaming-source roster in priority order and returns
 * the first match. Each resolver internally handles its own enablement
 * (a connected account, circuit-breaker state for non-streaming paths, etc.)
 * — null from one resolver just means "try the next one".
 *
 * The lossless rungs (1, 1b, 1c) are asked in the PLAY ORDER ([PlayOrder], add-ons plan phase 4
 * §2.8), the one order Settings › Add-ons shows and the download chain follows too. Until the user
 * drags it, it is the order below. Whatever the order: Tidal placed ahead of Qobuz is asked for
 * FLAC first and for its AAC only after Qobuz misses; a look-ahead stops at a metered add-on and
 * never makes Stash's shared Tidal relay search; the lossy pass (1d) follows the lossless rungs.
 *
 * Current order:
 *   0. Opt-in only ("Prefer Tidal AAC while streaming", off by default):
 *      [TidalStreamResolver.resolveAacOnly], Tidal's 320 kbps AAC ahead of
 *      Qobuz to save data. Any miss goes straight on to 1.
 *   1. [QbdlxStreamResolver]   — `qbdlx`, the DIRECT Qobuz API, signed with the
 *      user's own connected account; off without one. Plain Range-seekable FLAC,
 *      no proxy operator and no client-side decrypt, so it's the fastest path.
 *      Foreground-only (allowYtDlp) since it spends that account's quota.
 *   1b. [TidalStreamResolver]  — Tidal lossless through a BitChord server: the
 *      user's own, or Stash's from the signed config (progressive FLAC or a DASH
 *      MPD). The lossless source for everyone without a Qobuz account. Same gates
 *      as qbdlx; inert with no server. The user's own server, with none of their own Qobuz set
 *      up ([LosslessAvailability.ownTidalFirst]), goes ahead of 1 instead above CD
 *      quality (FLAC only), and is asked again for its AAC after 1, when "Tidal AAC
 *      when no FLAC" is on.
 *   Where Data saver applies ([StreamQualityPolicy.dataSaverApplies]: the switch on, a metered
 *   network) neither rung, nor a cloud copy, is asked: no FLAC on mobile data. Rung 0 stays when
 *   opted into; JioSaavn and YouTube serve at [StreamQualityPolicy.lossyQuality].
 *   While the day's Tidal allowance is spent ([LosslessQuota]) the Tidal rung asks Stash's
 *   relay for neither FLAC nor AAC, since AAC counts toward the same allowance (a user's own
 *   server still serves, and so does a user's own Qobuz account). Playback falls to JioSaavn
 *   and YouTube.
 *   1c. Add-ons ([AddonStreamRungs], Stash Add-ons v1) that stream lossless, after 1b: the
 *      user's own in the order they were added, then the defaults. A new add-on therefore only
 *      sees the songs Stash's own lossless sources miss, until the user drags it up. Same gates
 *      as 1b; a metered one never spends its allowance on a look-ahead. With no add-on on, the
 *      chain is exactly the one above.
 *   1d. The add-ons' lossy pass (`lossy:addon:<id>`, add-ons plan phase 4 §2.4): each asked for
 *      a lossy stream at [StreamQualityPolicy.lossyQuality], before JioSaavn, under JioSaavn's
 *      gate. With Lossless off or Data saver on, only this pass of the add-ons runs. Never a
 *      lossless serve for the Home banner.
 *   2. [YouTubeStreamResolver] — yt-dlp / InnerTube extraction. Last
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
 *  - [StreamingPreference.isForceYouTubeFallback]: [resolve] skips the
 *    lossless rungs entirely and routes every track through the YouTube resolver
 *    only — reproduces the lossless-down fallback path on demand.
 *  - [StreamingPreference.isSkipQobuz] (debug builds only): the qbdlx rung is
 *    left out, so Tidal is asked first — Tidal can be seen serving at all.
 */
@Singleton
class StreamSourceRegistry internal constructor(
    private val qbdlx: QbdlxStreamResolver,
    private val jiosaavn: JioSaavnStreamResolver,
    private val youtube: YouTubeStreamResolver,
    private val streamingPreference: StreamingPreference,
    private val losslessSourceHealth: LosslessSourceHealth,
    private val losslessPrefs: LosslessSourcePreferences,
    /** Where resolves run; tests pass a test dispatcher so the hedge is measured in virtual time. */
    resolveDispatcher: CoroutineDispatcher,
    /** The diagnostics bundle's "Recent resolves". A fresh, unshared one in hand-built tests. */
    private val diagnosticsLog: PlaybackDiagnosticsLog = PlaybackDiagnosticsLog(),
    /** Tidal via the user's BitChord server; null in hand-built tests (rung absent). */
    private val tidal: TidalStreamResolver? = null,
    /** The daily lossless allowance; null in hand-built tests (never spent). */
    private val losslessQuota: LosslessQuota? = null,
    /** Streaming quality settings ("Prefer Tidal AAC"); null in hand-built tests (setting off). */
    private val qualityPolicy: StreamQualityPolicy? = null,
    /** "Play my cloud copies first" (#679); null in hand-built tests (no cloud copies). */
    private val cloudCopies: CloudCopyFinder? = null,
    private val soundCloud: SoundCloudStreamResolver? = null,
    /**
     * Whose lossless paths are set up ([LosslessAvailability.ownTidalFirst]); null in hand-built
     * tests (Qobuz first). Read with [qualityPolicy]'s tier: without a policy, Qobuz stays first too.
     */
    private val availability: LosslessAvailability? = null,
    /** Stash Add-ons v1 lossless rungs; null in hand-built tests (none). Empty in phase 1. */
    private val addons: AddonStreamRungs? = null,
    /** The play order (add-ons phase 4 §2.8); null in hand-built tests (the automatic order). */
    private val playOrder: PlayOrder? = null,
) {
    @Inject
    constructor(
        qbdlx: QbdlxStreamResolver,
        jiosaavn: JioSaavnStreamResolver,
        youtube: YouTubeStreamResolver,
        streamingPreference: StreamingPreference,
        losslessSourceHealth: LosslessSourceHealth,
        losslessPrefs: LosslessSourcePreferences,
        diagnosticsLog: PlaybackDiagnosticsLog,
        tidal: TidalStreamResolver,
        losslessQuota: LosslessQuota,
        qualityPolicy: StreamQualityPolicy,
        cloudCopies: CloudCopyFinder,
        soundCloud: SoundCloudStreamResolver,
        availability: LosslessAvailability,
        addons: AddonStreamRungs,
        playOrder: PlayOrder,
    ) : this(
        qbdlx, jiosaavn, youtube, streamingPreference,
        losslessSourceHealth, losslessPrefs, Dispatchers.IO, diagnosticsLog, tidal, losslessQuota,
        qualityPolicy, cloudCopies, soundCloud, availability, addons, playOrder,
    )

    /** Where the chain line goes; tests read it. */
    internal var chainLog: (String) -> Unit = { Log.i(TAG, it) }

    /**
     * The user's own lossless copy of [track] in their cloud storage, as a [StreamUrl] whose url is
     * its `stash-cloud://` address ([CloudCopyFinder.ORIGIN]); [LazyResolvingDataSource] opens it,
     * and falls back to this chain when it can't. Only with the setting and Lossless on: with
     * Lossless off nothing is spent that a cloud copy would save.
     */
    private suspend fun cloudCopy(track: TrackEntity): StreamUrl? {
        val finder = cloudCopies ?: return null
        if (qualityPolicy?.preferCloudCopies() != true || !losslessPrefs.enabledNow()) return null
        // A cloud copy is a lossless file too: where Data saver applies, it waits for Wi-Fi.
        if (qualityPolicy?.dataSaverApplies() == true) return null
        val copy = finder.find(track.title, track.artist, track.durationMs) ?: return null
        return StreamUrl(
            url = copy.cloudUri,
            // The address doesn't lapse; its link is fetched (and cached) when it's opened.
            expiresAtMs = System.currentTimeMillis() + CLOUD_COPY_TTL_MS,
            codec = copy.fileFormat,
            bitsPerSample = copy.bitsPerSample,
            sampleRateHz = copy.sampleRateHz,
            origin = CloudCopyFinder.ORIGIN,
        )
    }

    /**
     * Try each resolver in priority order; return the first non-null
     * [StreamUrl]. Returns null when no source produced a match — caller
     * should surface this as [StreamRoutingResult.NotAvailable].
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
        /**
         * A look-ahead resolve (next-up prefetch) that the user may never play. It never
         * makes the Tidal relay search: when Qobuz misses and Tidal's answer for the track is
         * not cached, it returns null (nothing cached, no lossy fallback) and leaves the
         * track to the real play, which asks Tidal. The shared Tidal pool is small. The one
         * exception is the user's own server leading the chain ([LosslessAvailability.ownTidalFirst]):
         * it is asked like any first rung.
         */
        speculative: Boolean = false,
    ): StreamUrl? = resolveOnce(track, allowYouTube, allowYtDlp, speculative, secondAsk = false)

    /**
     * [resolve]'s work, single-flighted per song and flags. [secondAsk]: [resolveLosslessOnly]'s walk,
     * the same song asked again after a busy answer. It counts no lossless miss for the Home banner;
     * the walk before it already did.
     */
    private suspend fun resolveOnce(
        track: TrackEntity,
        allowYouTube: Boolean,
        allowYtDlp: Boolean,
        speculative: Boolean,
        secondAsk: Boolean,
    ): StreamUrl? {
        // Last line of defence: a cloud song (the user's own file) is never looked up on the
        // lossless relays, JioSaavn or YouTube. Its player item streams from stash-cloud://.
        if (track.soundCloudId != null || track.source == com.stash.core.model.MusicSource.SOUNDCLOUD) return soundCloud?.resolve(track)
        if (track.source == com.stash.core.model.MusicSource.CLOUD) return null
        // "Play my cloud copies first": the user's own lossless file, before any relay is asked.
        // A local lookup (no network), so it runs ahead of the single-flight and for look-aheads too.
        cloudCopy(track)?.let { return it }
        // Single-flight. A foreground tap and the next-up prefetch landing on the
        // same track within a few hundred ms each ran their OWN full resolver
        // chain: doubled calls to every source (including rate-limited search
        // APIs and the qbdlx account's quota), and because each drove the YouTube
        // resolver's own per-videoId coalescing to start and tear down before
        // the other began, one ~13 s yt-dlp extraction became two sequential
        // ones. Device-observed 2026-08-22: a track resolved via yt-dlp, then a
        // second full chain started 325 ms later for the identical track.
        //
        // Keyed on the FLAGS as well as the id: background fill calls with
        // allowYouTube/allowYtDlp = false deliberately accept a narrower source
        // set, and must never be handed a foreground result (or vice versa).
        val key = resolveKey(track.id, allowYouTube, allowYtDlp, speculative) + if (secondAsk) ":again" else ""
        inFlightResolves[key]?.let { return it.await() }

        val fresh = resolveScope.async(start = CoroutineStart.LAZY) {
            // The timeout MUST live INSIDE the shared coroutine, not at the
            // caller. LazyResolvingDataSource wraps its call in
            // withTimeout(RESOLVE_DEADLINE_MS) as the documented guarantee that
            // "a hung resolver can NEVER wedge playback" — but that bounds the
            // caller's await(), not this detached job. Without the timeout here
            // a wedged chain would run forever, invokeOnCompletion would never
            // fire, the map entry would never clear, and EVERY later attempt at
            // that track would coalesce onto the same dead Deferred and time
            // out — turning a transient hang into a permanently unplayable
            // track, which is worse than the duplicate resolve this fixes.
            //
            // OrNull: a hit deadline is a miss, not a cancellation. withTimeout's
            // TimeoutCancellationException reached every waiter through await(),
            // and callers read a CancellationException as "a newer tap preempted
            // us" — so a 45 s hang ended with the spinner cleared and no "Couldn't
            // find this track", and RefreshingDataSource's open() threw a
            // non-IOException Media3 never retries. doResolve still records it.
            withTimeoutOrNull(LazyResolvingDataSource.RESOLVE_DEADLINE_MS) {
                doResolve(track, allowYouTube, allowYtDlp, speculative, secondAsk)
            }
        }
        val existing = inFlightResolves.putIfAbsent(key, fresh)
        val deferred = if (existing != null) {
            // Lost the race. `async` attaches the child to resolveScope's job at
            // CONSTRUCTION even with CoroutineStart.LAZY, so an unstarted,
            // uncancelled loser stays in the parent's child list — with its
            // captured TrackEntity — for the process lifetime.
            fresh.cancel()
            existing
        } else {
            fresh.also { d ->
                d.invokeOnCompletion { inFlightResolves.remove(key, d) }
                d.start()
            }
        }
        // Caller cancellation still propagates out of await(), so tap-preemption
        // keeps surfacing as a CancellationException rather than a bogus
        // "Couldn't find this track" — see the chain loop below.
        return deferred.await()
    }

    /**
     * Re-resolves a YouTube-origin item for [RefreshingDataSource]'s 403 refresh:
     * the YouTube rung ONLY, never the chain.
     *
     * The refresh re-opens at the byte offset the dead URL failed on, into an
     * extractor (and a stream-cache key) set up for the YouTube file. The full chain
     * puts qbdlx and JioSaavn first, so a source that missed at the first resolve and
     * hits now handed FLAC or AAC/MP4 bytes from offset N to a WebM extractor, and
     * read a JioSaavn URL through the redirect-following HTTP client that host must
     * never get. The caller still checks the result is the same file
     * ([isSameFileAs] / [sameUrlFileParams]); this only stops it asking the wrong
     * sources.
     */
    suspend fun resolveYouTubeRefresh(track: TrackEntity): StreamUrl? =
        youtube.resolve(track, allowYtDlp = true)

    /**
     * The lossless sources alone, asked again for [track]: the chain as [resolve] builds it now
     * (the play order, the Lossless switch, Data saver, the daily allowance all as they are at
     * this moment), without JioSaavn, YouTube or an add-on's lossy pass, and without the hedged
     * lanes. Null when none serves (still busy, no match, switched off).
     *
     * For a look-ahead that got a lossy fallback because Tidal was busy and waited out its
     * Retry-After ([BusyLookAhead]): it already holds that fallback, and only a lossless answer
     * would change what it plays.
     */
    suspend fun resolveLosslessOnly(track: TrackEntity): StreamUrl? =
        resolveOnce(track, allowYouTube = false, allowYtDlp = true, speculative = false, secondAsk = true)

    /**
     * Shared scope for in-flight resolves. Mirrors
     * [com.stash.data.download.preview.PreviewUrlExtractor]'s `extractorScope`
     * deliberately: a resolve has to outlive any ONE caller's cancellation, or
     * coalescing would be pointless — the whole value is that a second waiter on
     * the same track gets the in-flight result instead of restarting the chain.
     */
    private val resolveScope = CoroutineScope(SupervisorJob() + resolveDispatcher)

    /** In-flight resolves, keyed by [resolveKey]; entries self-remove on completion. */
    private val inFlightResolves = ConcurrentHashMap<String, Deferred<StreamUrl?>>()

    private fun resolveKey(trackId: Long, allowYouTube: Boolean, allowYtDlp: Boolean, speculative: Boolean) =
        "$trackId:$allowYouTube:$allowYtDlp:$speculative"

    /** The resolver-chain walk itself; reached only via [resolve]'s single-flight. */
    private suspend fun doResolve(
        track: TrackEntity,
        allowYouTube: Boolean,
        allowYtDlp: Boolean,
        speculative: Boolean,
        secondAsk: Boolean,
    ): StreamUrl? {
        val startedNs = System.nanoTime()
        // The Lossless switch (Settings › Audio & Quality) governs streaming as well as
        // downloads (2026-09-05): off means no FLAC anywhere, so the lossless sources
        // leave the chain and the track streams from the lossy rungs. It outranks the
        // force-source test toggles: a stale force pref must not override a user choice.
        val lossless = losslessPrefs.enabledNow()
        // Data saver on a metered network (StreamQualityPolicy): no FLAC. The lossless relay rungs
        // leave the chain and the song streams from the lossy rungs at the policy's LossyQuality,
        // as with Lossless off. On unmetered Wi-Fi this is false and nothing below changes.
        val dataSaver = qualityPolicy?.dataSaverApplies() == true
        val losslessRungs = lossless && !dataSaver
        // "Prefer Tidal AAC while streaming" (Settings › Audio & Quality, off by default). Read
        // through the policy on every resolve, so a flip also drops the cached URLs of the
        // other kind. Off: the chain below is exactly what it was before the setting existed.
        val preferTidalAac = qualityPolicy?.preferTidalAac() == true
        // "Test: skip Qobuz (Tidal first)" — debug builds only ([StreamingPreference.isSkipQobuz]
        // returns false on a release build whatever is stored). Drops the qbdlx rung so Tidal
        // gets asked first; everything else in the normal chain is unchanged. The force-qbdlx
        // toggle outranks it (both on is contradictory; the older instrument wins).
        val skipQobuz = streamingPreference.isSkipQobuz()
        val resolvers = buildList<Pair<String, suspend (TrackEntity) -> StreamUrl?>> {
            if (streamingPreference.isForceQbdlxOnly()) {
                // Test toggle: qbdlx (direct-Qobuz) ONLY — skip every other source
                // so qbdlx can be exercised even when the proxies are healthy.
                // Takes precedence over the other force toggles. Gated by
                // allowYtDlp so speculative background fill spends none of that
                // account's quota (only foreground/next-up resolves hit it).
                if (allowYtDlp && lossless) add("qbdlx" to qbdlx::resolve)
                // A force toggle is a TEST instrument, but the pref outlives the
                // build — and qbdlx can die under it (it did; #429's reporter had
                // this toggle on and got an infinite spinner on every track). Keep
                // the lossy safety net so no stale preference means silence.
                // Retiring a source has to mean retiring its force branch in the
                // same change, or a stale pref with no UI left to clear it routes
                // every track through a dead source.
                if (allowYouTube && allowYtDlp) add("jiosaavn" to jiosaavn::resolve)
                if (allowYouTube) add("youtube" to { t: TrackEntity -> youtube.resolve(t, allowYtDlp) })
            } else if (streamingPreference.isForceYouTubeFallback()) {
                // Test toggle: skip the lossless sources, forcing the
                // YouTube fallback path. Still gated by allowYouTube so the
                // background-fill keeps resolving nothing (matching a genuine
                // both-sources-down outage).
                if (allowYouTube) add("youtube" to { t: TrackEntity -> youtube.resolve(t, allowYtDlp) })
            } else {
                // Prefer Tidal AAC: Tidal's 320 kbps AAC (`?aac=only&hires=0`, the AAC
                // download mode's request) ahead of Qobuz, for a listener saving data. Only
                // where the Tidal rung itself runs (foreground, Lossless on, a relay set), and
                // a miss, busy pool, spent day or error falls straight on to the lossless rungs
                // below with no wait. Its own in-flight key and miss stamp in TidalClient, so it never
                // hides the Tidal FLAC lookup further down (or the other way round).
                // Kept where Data saver applies: AAC 320 is not FLAC, and the user who turned
                // this on chose to spend the daily allowance on it (Data saver alone never does).
                // Tidal switched off in Settings › Add-ons: its rungs are not in the chain at all, so
                // it is never asked and never named in the chain line below (a switched-off source
                // answered nothing anyway, but the line said it was in play).
                val tidalOn = playOrder?.switchedOffNow()?.contains(PlayOrder.TIDAL) != true
                val tidalAacRung = tidal?.takeIf { tidalOn }
                if (preferTidalAac && allowYtDlp && lossless && tidalAacRung != null) {
                    // Speculative: like the Tidal rung, never make the relay search for a
                    // look-ahead. Stopping here (rather than skipping) keeps a Qobuz FLAC from
                    // being cached for a track the real play would stream as AAC.
                    if (speculative && tidalAacRung.needsRelayAacOnly(track)) {
                        add(TIDAL_DEFERRED to { _: TrackEntity -> null })
                    } else {
                        add(TIDAL_AAC to { t: TrackEntity -> tidalAacRung.resolveAacOnly(t) })
                    }
                }

                // Tidal through a BitChord server: the user's own, or Stash's from the
                // signed config. Same gates as qbdlx (foreground only, Lossless switch
                // on, Data saver not applying): it spends a Tidal account. Off (returns null at once) when no
                // server is configured.
                val tidalRung = tidal?.takeIf { allowYtDlp && losslessRungs && tidalOn }
                // The play order (add-ons plan phase 4 §2.8): ONE order for this chain and the
                // download chain. Hand-built tests pass none: the automatic order.
                val order = playOrder?.now() ?: PlayOrder.Order.AUTOMATIC
                // Tidal in the lead, ahead of Qobuz. While the order is automatic that is the
                // own-server rule: the user's own server, with none of their own Qobuz set up
                // (qbdlx is then off anyway: it needs the user's own account), above CD only.
                // Tidal serves its best (usually 24-bit) where qbdlx asks for this tier, so at CD
                // (Save Data, mobile data by default) qbdlx keeps the lead. Once the user has
                // dragged the order, it is the order: Tidal leads where they put it ahead.
                // In the lead it is asked for FLAC only: Tidal's AAC for a CD-only song must not
                // stand in for a FLAC Qobuz still has.
                val tidalLeads = tidalRung != null && if (order.automatic) {
                    availability != null && qualityPolicy?.streamingTier()?.let { availability.ownTidalFirstNow(it) } == true
                } else {
                    order.tidalBeforeQobuz
                }
                // Add-ons that stream lossless (Stash Add-ons v1): the tidal rung's gates. None
                // on: empty, nothing else is read, and the chain is unchanged.
                val addonRungs = if (allowYtDlp && losslessRungs) addons?.rungs().orEmpty() else emptyList()
                val addonTier = if (addonRungs.isEmpty()) null else qualityPolicy?.streamingTier()
                val addAddon = { rung: AddonStreamRung ->
                    // A look-ahead stops at a metered add-on, as at Tidal: no allowance spent on
                    // a song the user may never play, and no lossy URL cached ahead of it.
                    if (speculative && rung.spendsQuota) {
                        add(ADDON_DEFERRED to { _: TrackEntity -> null })
                    } else {
                        add(rung.name to { t: TrackEntity -> rung.resolve(t, addonTier) })
                    }
                }
                // While automatic, the own-server rule moves Tidal to just ahead of Qobuz;
                // nothing else moves.
                val keys = if (order.automatic && tidalLeads) {
                    listOf(PlayOrder.TIDAL) + (order.keys - PlayOrder.TIDAL)
                } else {
                    order.keys
                }
                val rungByKey = addonRungs.associateBy { it.name }
                val placed = HashSet<String>()
                for (key in keys) {
                    when (key) {
                        // qbdlx (direct Qobuz API): plain Range-seekable FLAC, no proxy, no
                        // client-side decrypt, the fastest path. Foreground/next-up only
                        // (allowYtDlp = true), never the speculative background fill. It
                        // self-gates on LosslessAvailability (the user's own Qobuz account).
                        PlayOrder.QOBUZ -> {
                            if (allowYtDlp && losslessRungs && !skipQobuz) add("qbdlx" to qbdlx::resolve)
                            // Tidal led, FLAC only: its AAC 320 for a song with no lossless version
                            // anywhere ("Tidal AAC when no FLAC"), now that Qobuz missed too.
                            if (tidalRung != null && tidalLeads && losslessPrefs.tidalAacFallbackNow()) {
                                add("tidal" to { t: TrackEntity -> tidalRung.resolve(t) })
                            }
                        }
                        PlayOrder.TIDAL -> if (tidalRung != null) {
                            // A look-ahead never makes Stash's shared relay search (see [resolve]'s
                            // `speculative`): the walk stops here, so no lossy URL is cached for a
                            // track Tidal may serve when it is really played. The user's own server
                            // in the lead is asked like any first rung: stopping there would leave
                            // every next-up song unprefetched.
                            val spare = speculative &&
                                !(tidalLeads && (order.automatic || availability?.ownTidalServerNow() == true))
                            when {
                                spare && tidalRung.needsRelay(track) -> add(TIDAL_DEFERRED to { _: TrackEntity -> null })
                                tidalLeads -> add("tidal" to { t: TrackEntity -> tidalRung.resolveLossless(t) })
                                // The lossless rung for what the ones ahead of it miss.
                                else -> add("tidal" to { t: TrackEntity -> tidalRung.resolve(t) })
                            }
                        }
                        else -> rungByKey[key]?.let { rung ->
                            addAddon(rung)
                            placed += key
                        }
                    }
                }
                // An add-on the order doesn't name yet (it came on a moment ago): last.
                addonRungs.filterNot { it.name in placed }.forEach { addAddon(it) }
                // The lossy pass (add-ons plan phase 4 §2.4): add-ons that have only MP3 or AAC
                // (or have a song only in lossy) are asked at the policy's LossyQuality, in play
                // order, after the lossless rungs and before JioSaavn, under JioSaavn's gate. With Lossless off or
                // Data saver on, only this pass runs. None on: empty, nothing else is read.
                val lossyRungs = if (allowYouTube && allowYtDlp) order.sorted(addons?.lossyRungs().orEmpty()) { it.name } else emptyList()
                if (lossyRungs.isNotEmpty()) {
                    val lossyQuality = qualityPolicy?.lossyQuality() ?: LossyQuality.FULL
                    lossyRungs.forEach { rung ->
                        if (speculative && rung.spendsQuota) {
                            add(ADDON_LOSSY_DEFERRED to { _: TrackEntity -> null })
                        } else {
                            add(rung.lossyName to { t: TrackEntity -> rung.resolveLossy(t, lossyQuality) })
                        }
                    }
                }
                // AAC fallback at the policy's LossyQuality (320; 160 or 96 where Data saver
                // applies). Foreground/next-up only: a
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
        // `allowYtDlp` (false for speculative background fill) and the Lossless
        // switch. Info level so a release build says so.
        // Only when it actually shaped the chain (force-qbdlx outranks it).
        if (skipQobuz && resolvers.none { it.first == "qbdlx" }) {
            Log.i(TAG, "TEST skip-qobuz on → chain [${resolvers.joinToString(",") { it.first }}]")
        }
        chainLog(
            "chain for ${track.id}: [${resolvers.joinToString(",") { it.first }}] " +
                "allowYouTube=$allowYouTube allowYtDlp=$allowYtDlp lossless=$lossless" +
                (if (preferTidalAac) " preferTidalAac=true" else "") +
                if (dataSaver) " dataSaver=metered" else "",
        )

        // P3 (2026-09-06): the YouTube fast lane (one InnerTube round trip and a
        // probe, never yt-dlp) and the JioSaavn search start NOW, alongside the
        // rungs ahead of them, instead of after those rungs miss. Measured before
        // this: a relay miss of 2.0 s plus a JioSaavn miss of 1.3 s before the
        // YouTube resolve even started. A lossless hit cancels both lanes; a miss
        // consults JioSaavn first (its rank is unchanged), then the fast lane, and
        // only then the yt-dlp-capable rung. Foreground resolves only: the
        // speculative background fill keeps its cheap sequential chain.
        val hedged = allowYouTube && allowYtDlp && resolvers.size > 1 && resolvers.last().first == "youtube" &&
            resolvers.none { it.first == TIDAL_DEFERRED || it.first == ADDON_DEFERRED || it.first == ADDON_LOSSY_DEFERRED }

        // For the diagnostics bundle's "Recent resolves": the rung that served, set at each
        // exit below. Still null when the walk ends means every rung missed.
        var servedBy: String? = null
        // A lossless source that serves nothing because it is BUSY (Tidal's relay: 503 / 429 with a
        // Retry-After) writes here (TidalClient.resolve). A lossy fallback this walk serves is then
        // marked for this play only ([forThisPlay]); a genuine "no lossless version" leaves it empty
        // and the fallback is kept as before.
        val busyNote = LosslessBusyNote()

        /** [result], served by [rung]: marked for this play only when it is lossy and a lossless source was busy. */
        fun forThisPlay(result: StreamUrl, rung: String): StreamUrl {
            if (!busyNote.heard || isLosslessRung(rung)) return result
            Log.i(
                TAG,
                "$rung served ${track.id} only because a lossless source is busy " +
                    "(for ${((busyNote.untilMs - System.currentTimeMillis()) / 1000).coerceAtLeast(0)} s more): for this play only",
            )
            return result.copy(losslessBusyUntilMs = busyNote.untilMs)
        }
        return try {
            // The note rides on the walk's context, so every rung below (and the hedged lanes) can write it.
            withContext(busyNote) {
                val jioLane = if (hedged && resolvers.any { it.first == "jiosaavn" }) {
                    async { quietly("jiosaavn", track) { jiosaavn.resolve(track) } }
                } else {
                    null
                }
                val fastLane = if (hedged) {
                    async { quietly("youtube-fast", track) { youtube.resolve(track, allowYtDlp = false) } }
                } else {
                    null
                }

                // The lossless rungs the banner signal reads (a deliberate Prefer Tidal AAC serve counts as neither).
                // A walk stopped before Tidal (speculative) records nothing: Tidal wasn't asked.
                val lastLossless = resolvers.indexOfLast { isLosslessRung(it.first) || it.first == TIDAL_DEFERRED || it.first == ADDON_DEFERRED }
                for ((index, rung) in resolvers.withIndex()) {
                    val (name, fn) = rung
                    if (hedged && (name == "jiosaavn" || name == "youtube")) continue // consulted below
                    if (name == TIDAL_DEFERRED) {
                        Log.i(TAG, "speculative resolve of ${track.id} stops before Tidal; the real play asks it")
                        servedBy = "none (speculative; Tidal waits for the play)"
                        return@withContext null
                    }
                    if (name == ADDON_DEFERRED || name == ADDON_LOSSY_DEFERRED) {
                        Log.i(TAG, "speculative resolve of ${track.id} stops before a metered add-on; the real play asks it")
                        servedBy = "none (speculative; an add-on waits for the play)"
                        return@withContext null
                    }
                    val result = quietly(name, track) { fn(track) }
                    // Feed the Home lossless-offline banner signal: a serve by any lossless rung
                    // resets the streak; the walk getting past the last of them with nothing is
                    // a miss (dead credential OR catalog gap; the streak threshold tells them
                    // apart). Not while the day's lossless allowance is spent: the relays are then
                    // skipped on purpose (no request), and lossless is not "offline".
                    if (isLosslessRung(name) && result != null) {
                        losslessSourceHealth.recordLosslessServed()
                    } else if (index == lastLossless && result == null && losslessQuota?.isSpent() != true && !secondAsk) {
                        // Not for [resolveLosslessOnly]'s second ask: the walk before it counted this song's miss.
                        losslessSourceHealth.recordLosslessMiss()
                    }
                    if (result != null) {
                        // Diagnostic: which source actually served the stream. Helps
                        // explain "this track played but at lower quality" reports.
                        Log.i(TAG, "$name served ${track.id} '${track.title}'")
                        servedBy = name
                        jioLane?.cancel()
                        fastLane?.cancel()
                        return@withContext forThisPlay(result, name)
                    }
                }
                jioLane?.await()?.let {
                    Log.i(TAG, "jiosaavn served ${track.id} '${track.title}' (hedged)")
                    servedBy = "jiosaavn"
                    fastLane?.cancel()
                    return@withContext forThisPlay(it, "jiosaavn")
                }
                fastLane?.await()?.let {
                    Log.i(TAG, "youtube served ${track.id} '${track.title}' (fast lane, hedged)")
                    servedBy = "youtube (fast lane)"
                    return@withContext forThisPlay(it, "youtube")
                }
                if (hedged) {
                    quietly("youtube", track) { youtube.resolve(track, allowYtDlp = true) }?.let {
                        Log.i(TAG, "youtube served ${track.id} '${track.title}'")
                        servedBy = "youtube"
                        forThisPlay(it, "youtube")
                    }
                } else {
                    null
                }
            }.also {
                diagnosticsLog.recordResolve(track.id, servedBy ?: "none (all missed)", msSince(startedNs), lossless)
            }
        } catch (e: TimeoutCancellationException) {
            // resolve()'s deadline fired mid-walk. A hang is an outcome, and the player only sees
            // it as a generic IO error, so it gets its own line. Any other cancellation is not an
            // outcome (see quietly) and is never recorded.
            diagnosticsLog.recordResolve(track.id, "none (timed out)", msSince(startedNs), lossless)
            throw e
        }
    }

    private fun msSince(startedNs: Long): Long = (System.nanoTime() - startedNs) / 1_000_000

    /**
     * Runs one rung. Preemption (the user tapped another track) cancels this job
     * and the CancellationException MUST propagate, or resolve() would return
     * null and the caller fire a bogus "Couldn't find this track". Any other
     * throw is a resolver bug: logged, and read as a miss so the chain goes on.
     */
    private suspend fun quietly(name: String, track: TrackEntity, block: suspend () -> StreamUrl?): StreamUrl? =
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$name threw on resolve for ${track.id} '${track.title}'", e)
            null
        }

    private companion object {
        private const val TAG = "StreamSourceRegistry"
        private const val TIDAL_DEFERRED = "tidal-deferred"
        private const val TIDAL_AAC = "tidal-aac"

        private const val ADDON_DEFERRED = "addon-deferred"

        /** The lossy pass's stop before a metered add-on: not a lossless rung, so the banner's miss is unaffected. */
        private const val ADDON_LOSSY_DEFERRED = "addon-lossy-deferred"

        /** The rungs whose serve is lossless, for the Home banner's signal ([LosslessSourceHealth]). */
        private val LOSSLESS_RUNGS = setOf("qbdlx", "tidal")

        /** [LOSSLESS_RUNGS], plus the add-on rungs (all lossless: they ask `minTier=lossless`). */
        private fun isLosslessRung(name: String) = name in LOSSLESS_RUNGS || name.startsWith(AddonStreamRung.ORIGIN_PREFIX)
        /** A cloud copy's StreamUrl lifetime: its address is stable, so a long one. */
        private const val CLOUD_COPY_TTL_MS = 6 * 60 * 60 * 1000L
    }
}

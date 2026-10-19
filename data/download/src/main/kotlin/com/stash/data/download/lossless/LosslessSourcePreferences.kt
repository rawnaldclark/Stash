package com.stash.data.download.lossless

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.model.DownloadMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** DataStore for lossless-source preferences (priority order, min quality, etc). */
private val Context.losslessDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "lossless_source_preferences",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Persists user preferences for the lossless-source resolver chain:
 *
 *  - Priority order (which source the registry tries first, second, etc.)
 *  - Minimum quality threshold (skip sources whose stated format is lower)
 *
 * Per-source enable/disable lives on the [LosslessSource] implementation
 * itself (typically gated on whether the user has provided required
 * credentials or a configured URL template). This preference store is
 * only for the cross-source ordering and threshold.
 *
 * Order is stored as a comma-joined string of source ids — survives across
 * app versions, tolerates unknown ids gracefully (registry filters them
 * out before resolving), and keeps the schema trivially debuggable.
 */
@Singleton
class LosslessSourcePreferences @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadQueueDao: DownloadQueueDao,
) {
    private val priorityKey = stringPreferencesKey("priority_order")
    private val minQualityKey = stringPreferencesKey("min_quality")
    private val enabledKey = booleanPreferencesKey("enabled")
    private val customLosslessEndpointKey = stringPreferencesKey("custom_lossless_endpoint")
    private val bitchordEndpointKey = stringPreferencesKey("bitchord_endpoint")
    private val bitchordAccessTokenKey = stringPreferencesKey("bitchord_access_token")
    private val captchaCookieKey = stringPreferencesKey("squid_wtf_captcha_verified_at")
    private val captchaCookieSetAtKey = longPreferencesKey("squid_wtf_captcha_set_at_ms")
    private val bannerDismissedKey = booleanPreferencesKey("home_banner_dismissed")
    private val losslessOfflineDismissedKey = booleanPreferencesKey("lossless_offline_dismissed")
    private val qualityTierKey = stringPreferencesKey("lossless_quality_tier")
    private val youtubeFallbackKey = booleanPreferencesKey("youtube_fallback_enabled")
    private val tidalAacFallbackKey = booleanPreferencesKey("tidal_aac_fallback")
    private val downloadModeKey = stringPreferencesKey("download_mode")
    // Retained only so [purgeRetiredSourceKeys] can delete them from existing
    // installs; the antra and arcod sources were removed.
    private val antraSessionKey = stringPreferencesKey("antra_session_cookie")
    private val antraCfClearanceKey = stringPreferencesKey("antra_cf_clearance_cookie")
    private val antraUsernameKey = stringPreferencesKey("antra_username")
    private val arcodRescueDismissedKey = booleanPreferencesKey("arcod_rescue_dismissed")

    /**
     * Master switch for the lossless-source pipeline. When false, the
     * download path skips the registry entirely and goes straight to
     * yt-dlp — same behaviour as before the lossless feature shipped.
     *
     * Defaults to true (v0.9.8+): fresh installs land lossless-ready;
     * existing v0.9.7 users who explicitly toggled it off keep their
     * saved value (DataStore preserves explicit writes). Users who
     * never opened the toggle pick up the new default — functionally
     * identical to v0.9.7 behaviour because the captcha is unverified
     * (silent yt-dlp/MP3 fallback via SquidWtfCaptchaInterceptor).
     *
     * The toggle lives on this preferences class (rather than its own
     * DataStore) so all lossless-related settings stay in one place
     * and the schema can evolve together.
     *
     * Deduped, and caught so a read error cannot terminate the chain: this feeds
     * two of Home's banner combines, and a DataStore IOException that killed the
     * flow would leave Home stuck in `isLoading` forever.
     *
     * The catch fails OPEN — `emit(true)`, the same default the `map` documents
     * above. Deliberate: failing to `false` would switch lossless off for a
     * paying user on one transient IOException. (Contrast [customLosslessEndpoint]
     * and the dismissal flags below, whose defaults happen to be the closed ones.)
     * Note `catch {}` COMPLETES the flow: after an error these emit their fallback
     * once and never re-emit for the process lifetime.
     */
    val enabled: Flow<Boolean> = context.losslessDataStore.data.map { prefs ->
        prefs[enabledKey] ?: true
    }.distinctUntilChanged().catch { emit(true) }

    suspend fun enabledNow(): Boolean = enabled.first()

    /**
     * A user-supplied lossless relay base URL (Settings › Audio › Advanced), or
     * null. Outranks the public relay from runtime config when set — an explicit
     * choice beats an implicit one. Stored normalised: https only, no trailing
     * slash, no query. The APK ships no default. Deduped (the store re-emits on
     * every unrelated write) and fail-closed (a read error must not terminate a
     * `combine`).
     */
    val customLosslessEndpoint: Flow<String?> = context.losslessDataStore.data.map { prefs ->
        prefs[customLosslessEndpointKey]?.takeIf { it.isNotBlank() }
    }.distinctUntilChanged().catch { emit(null) }

    suspend fun customLosslessEndpointNow(): String? = customLosslessEndpoint.first()

    suspend fun setCustomLosslessEndpoint(raw: String?) {
        val v = normaliseEndpoint(raw)
        context.losslessDataStore.edit { prefs ->
            if (v == null) prefs.remove(customLosslessEndpointKey) else prefs[customLosslessEndpointKey] = v
        }
    }

    /**
     * The user's own self-hosted BitChord Lossless Relay (Tidal), or null = the Tidal
     * rung is off. Same normalisation as [customLosslessEndpoint] (https only, no
     * query, no trailing slash), so a path prefix survives: BitChord's own
     * `https://host/<ACCESS_TOKEN>` form works as-is. The APK ships no default —
     * a BitChord server spends ONE person's Tidal account, so it is never shared
     * through the signed runtime config. Deduped and fail-closed.
     */
    val bitchordEndpoint: Flow<String?> = context.losslessDataStore.data.map { prefs ->
        prefs[bitchordEndpointKey]?.takeIf { it.isNotBlank() }
    }.distinctUntilChanged().catch { emit(null) }

    suspend fun bitchordEndpointNow(): String? = bitchordEndpoint.first()

    suspend fun setBitchordEndpoint(raw: String?) {
        val v = normaliseEndpoint(raw)
        context.losslessDataStore.edit { prefs ->
            if (v == null) prefs.remove(bitchordEndpointKey) else prefs[bitchordEndpointKey] = v
        }
    }

    /**
     * The server's `ACCESS_TOKEN`, sent as `X-Access-Token` — or null when the
     * server runs open, or the token is already the endpoint's path prefix. A
     * secret: never logged, never in the diagnostics bundle.
     */
    val bitchordAccessToken: Flow<String?> = context.losslessDataStore.data.map { prefs ->
        prefs[bitchordAccessTokenKey]?.takeIf { it.isNotBlank() }
    }.distinctUntilChanged().catch { emit(null) }

    suspend fun bitchordAccessTokenNow(): String? = bitchordAccessToken.first()

    suspend fun setBitchordAccessToken(raw: String?) {
        val v = raw?.trim()?.takeIf { it.isNotEmpty() }
        context.losslessDataStore.edit { prefs ->
            if (v == null) prefs.remove(bitchordAccessTokenKey) else prefs[bitchordAccessTokenKey] = v
        }
    }

    /**
     * The Lossless switch, which now governs STREAMING only: downloads follow [downloadMode].
     * The download mode is pinned first, in the same edit, so turning lossless streaming
     * off or on never changes what a download saves (a user who never had a stored mode
     * would otherwise have it follow this switch through [legacyDownloadMode]). It no longer
     * releases waiting downloads: they wait under the download mode, which this doesn't touch.
     */
    suspend fun setEnabled(value: Boolean) {
        context.losslessDataStore.edit { prefs ->
            if (prefs[downloadModeKey] == null) prefs[downloadModeKey] = legacyDownloadMode(prefs).name
            prefs[enabledKey] = value
        }
    }

    /**
     * What a download saves: Lossless, AAC 320 or Opus ([DownloadMode]). One setting for every
     * download path; streaming keeps [enabled].
     *
     * Before this setting, the Lossless switch decided downloads too: on = FLAC first, off = the
     * lossy chain. Until a mode is stored it reads from that switch ([legacyDownloadMode]), and
     * [migrateDownloadMode] stores it once at app start, so an existing user's downloads carry
     * on as they were.
     */
    val downloadMode: Flow<DownloadMode> = context.losslessDataStore.data.map { prefs ->
        DownloadMode.fromName(prefs[downloadModeKey]) ?: legacyDownloadMode(prefs)
    }.distinctUntilChanged().catch { emit(DownloadMode.LOSSLESS) }

    suspend fun downloadModeNow(): DownloadMode = downloadMode.first()

    suspend fun setDownloadMode(mode: DownloadMode) {
        context.losslessDataStore.edit { prefs -> prefs[downloadModeKey] = mode.name }
    }

    /**
     * Stores the download mode the old Lossless switch implied (on = [DownloadMode.LOSSLESS],
     * off = [DownloadMode.OPUS]) when none is stored yet. Once, silently; a no-op afterwards.
     * Returns the stored mode.
     */
    suspend fun migrateDownloadMode(): DownloadMode {
        var mode = DownloadMode.LOSSLESS
        context.losslessDataStore.edit { prefs ->
            val stored = DownloadMode.fromName(prefs[downloadModeKey])
            mode = stored ?: legacyDownloadMode(prefs).also { prefs[downloadModeKey] = it.name }
        }
        return mode
    }

    /** The mode the old Lossless switch meant for downloads: on (the default) = Lossless, off = Opus. */
    private fun legacyDownloadMode(prefs: Preferences): DownloadMode =
        if (prefs[enabledKey] ?: true) DownloadMode.LOSSLESS else DownloadMode.OPUS

    /** Tests: write keys as an older build stored them, bypassing today's setters. */
    internal suspend fun writeRawForTest(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.losslessDataStore.edit { block(it) }
    }

    /**
     * When the lossless registry can't serve a track, whether to fall
     * through to the yt-dlp pipeline (m4a / Opus) instead of deferring
     * the download. Defaults to `false` (v0.9.17+) — strict FLAC is the
     * new contract.
     *
     * Existing v0.9.16 users who flip this to true match the previous
     * silent-fallback behaviour exactly; the legacy yt-dlp tier picker
     * (MAX/BEST/HIGH/NORMAL/LOW) governs the fallback's quality.
     */
    /**
     * Tidal's 320 kbps AAC when a song has no lossless version, for streaming and
     * downloads. Default ON; OFF keeps Tidal FLAC-only.
     */
    val tidalAacFallback: Flow<Boolean> = context.losslessDataStore.data.map { prefs ->
        prefs[tidalAacFallbackKey] ?: true
    }

    suspend fun tidalAacFallbackNow(): Boolean = tidalAacFallback.first()

    suspend fun setTidalAacFallback(value: Boolean) {
        context.losslessDataStore.edit { prefs -> prefs[tidalAacFallbackKey] = value }
    }

    val youtubeFallbackEnabled: Flow<Boolean> = context.losslessDataStore.data.map { prefs ->
        prefs[youtubeFallbackKey] ?: false
    }

    suspend fun youtubeFallbackEnabledNow(): Boolean = youtubeFallbackEnabled.first()

    suspend fun setYoutubeFallbackEnabled(value: Boolean) {
        context.losslessDataStore.edit { prefs -> prefs[youtubeFallbackKey] = value }
        // Enabling YouTube fallback means deferred rows can now be
        // satisfied via the legacy yt-dlp pipeline — release them back
        // to PENDING. Disabling fallback is a no-op for the queue.
        if (value) downloadQueueDao.requeueWaitingForLossless()
    }

    /**
     * Value of the `captcha_verified_at` cookie that qobuz.squid.wtf sets
     * after a successful ALTCHA solve. The download-music endpoint reads
     * this cookie (HttpOnly, ~30-min sliding window) to gate access; our
     * OkHttp client attaches it to every squid.wtf request via
     * [com.stash.data.download.lossless.squid.SquidWtfCaptchaInterceptor].
     *
     * Workflow until WebView automation lands:
     *   1. User opens qobuz.squid.wtf in their phone's browser
     *   2. Solves the captcha by clicking Download on any track
     *   3. Copies the cookie value (e.g. via a "Cookie Editor" extension)
     *   4. Pastes it into the Settings field that backs this preference
     *
     * Cookie expires after ~30 min server-side; user repastes when
     * downloads start failing. WebView automation will replace this
     * manual flow but the storage shape is the same.
     *
     * Stored as a plain string — it's not a security secret (HttpOnly
     * means the BROWSER doesn't expose it to JS, but server treats it
     * as a freely-pasteable token).
     */
    val captchaCookieValue: Flow<String?> = context.losslessDataStore.data.map { prefs ->
        prefs[captchaCookieKey]?.takeIf { it.isNotBlank() }
    }

    /**
     * Wall-clock epoch-millis when the current captcha cookie was set.
     * Emits 0L when no cookie has ever been stored or the most recent
     * value was cleared. Used by SquidCookieAutoRefresher to derive
     * cookie age and schedule the next refresh ~25 min after set.
     */
    val captchaCookieSetAtMs: Flow<Long> = context.losslessDataStore.data.map { prefs ->
        prefs[captchaCookieSetAtKey] ?: 0L
    }

    suspend fun captchaCookieValueNow(): String? = captchaCookieValue.first()

    suspend fun setCaptchaCookieValue(value: String?) {
        val now = System.currentTimeMillis()
        context.losslessDataStore.edit { prefs ->
            val trimmed = value?.trim()?.takeIf { it.isNotEmpty() }
            if (trimmed == null) {
                prefs.remove(captchaCookieKey)
                prefs.remove(captchaCookieSetAtKey)
            } else {
                prefs[captchaCookieKey] = trimmed
                prefs[captchaCookieSetAtKey] = now
            }
        }
    }

    /**
     * One-shot cleanup for removed sources: deletes the harvested antra.hoshi.cfd
     * session cookie, cf_clearance cookie and username, and arcod's retired banner
     * flag, so no stale login state lingers on disk. Called once at startup (see
     * StashApplication). No-op when the keys are absent.
     */
    suspend fun purgeRetiredSourceKeys() {
        context.losslessDataStore.edit { prefs ->
            prefs.remove(antraSessionKey)
            prefs.remove(antraCfClearanceKey)
            prefs.remove(antraUsernameKey)
            prefs.remove(arcodRescueDismissedKey)
        }
    }

    /**
     * Whether the user has dismissed the "Try lossless audio" Home
     * banner. Once dismissed, the banner never shows again — same
     * forever-dismissed semantics as `LastFmSessionPreference.bannerDismissed`.
     *
     * Defaults to false. Only read by [com.stash.feature.home.HomeViewModel];
     * Settings has no UI for un-dismissing. Deduped and fail-closed for the
     * same reason as [enabled] — it feeds Home's banner combine.
     */
    val bannerDismissed: Flow<Boolean> = context.losslessDataStore.data.map { prefs ->
        prefs[bannerDismissedKey] ?: false
    }.distinctUntilChanged().catch { emit(false) }

    suspend fun setBannerDismissed(dismissed: Boolean) {
        context.losslessDataStore.edit { prefs -> prefs[bannerDismissedKey] = dismissed }
    }

    /**
     * Whether the user has dismissed the "No lossless right now" Home banner.
     * Forever-dismissed. Deduped and fail-closed for the same reason as [enabled].
     */
    val losslessOfflineDismissed: Flow<Boolean> = context.losslessDataStore.data.map { prefs ->
        prefs[losslessOfflineDismissedKey] ?: false
    }.distinctUntilChanged().catch { emit(false) }

    suspend fun setLosslessOfflineDismissed(dismissed: Boolean) {
        context.losslessDataStore.edit { prefs -> prefs[losslessOfflineDismissedKey] = dismissed }
    }

    /**
     * User-selected lossless quality tier. Default behaviour:
     *   - Fresh installs (no `enabled` key written) → [LosslessQualityTier.HI_RES]
     *     (sensible-storage middle ground; ~70 MB / 4 min vs MAX's ~140 MB).
     *   - v0.9.8+ users with `enabled` explicitly true → [LosslessQualityTier.MAX]
     *     (matches v0.9.10 historical behaviour for users who opted in).
     *   - v0.9.8+ users with `enabled` explicitly false → [LosslessQualityTier.HI_RES]
     *     (they're not downloading lossless anyway; sensible default if they
     *     later flip the switch).
     *
     * Once the user picks a tier in Settings, [setQualityTier] writes the key
     * and this default logic is no longer consulted.
     */
    val qualityTier: Flow<LosslessQualityTier> = context.losslessDataStore.data.map { prefs ->
        prefs[qualityTierKey]
            ?.let { runCatching { LosslessQualityTier.valueOf(it) }.getOrNull() }
            ?: defaultQualityTier(prefs)
    }

    suspend fun qualityTierNow(): LosslessQualityTier = qualityTier.first()

    suspend fun setQualityTier(tier: LosslessQualityTier) {
        context.losslessDataStore.edit { prefs -> prefs[qualityTierKey] = tier.name }
    }

    private fun defaultQualityTier(prefs: Preferences): LosslessQualityTier {
        val enabledExplicitlyTrue = prefs[enabledKey] == true
        return if (enabledExplicitlyTrue) LosslessQualityTier.MAX else LosslessQualityTier.HI_RES
    }

    /**
     * Emits the user's source-id priority order. Falls back to
     * [DEFAULT_PRIORITY] when no order has been explicitly saved —
     * fresh installs get a known, stable resolution order rather than
     * relying on Hilt registration order.
     */
    val priorityOrder: Flow<List<String>> = context.losslessDataStore.data.map { prefs ->
        prefs[priorityKey]
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.ifEmpty { DEFAULT_PRIORITY }
            ?: DEFAULT_PRIORITY
    }

    /** One-shot read for callers that want the current value without subscribing. */
    suspend fun priorityOrderNow(): List<String> = priorityOrder.first()

    suspend fun setPriorityOrder(order: List<String>) {
        context.losslessDataStore.edit { prefs ->
            prefs[priorityKey] = order.joinToString(",")
        }
    }

    /**
     * Minimum acceptable quality. Sources whose [SourceResult.format]
     * doesn't meet this threshold are skipped during resolve, so the
     * chain never accidentally swaps a user's AAC 128 for an AAC 128
     * from a different host. Defaults to [MinQuality.LOSSLESS] — opt-in
     * to lossy sources via Settings if you want them.
     */
    val minQuality: Flow<MinQuality> = context.losslessDataStore.data.map { prefs ->
        val raw = prefs[minQualityKey]
        raw?.let { runCatching { MinQuality.valueOf(it) }.getOrNull() }
            ?: MinQuality.LOSSLESS
    }

    suspend fun minQualityNow(): MinQuality = minQuality.first()

    suspend fun setMinQuality(value: MinQuality) {
        context.losslessDataStore.edit { prefs -> prefs[minQualityKey] = value.name }
    }

    /**
     * Threshold an [AudioFormat] must meet to be accepted by the chain.
     *
     *  - [LOSSLESS]: codec must be in [AudioFormat.LOSSLESS_CODECS]
     *  - [HIGH_LOSSY]: bitrate >= 256 kbps OR lossless
     *  - [ANY]: anything goes (lossy or lossless)
     */
    enum class MinQuality {
        LOSSLESS, HIGH_LOSSY, ANY;

        fun accepts(format: AudioFormat): Boolean = when (this) {
            LOSSLESS -> format.isLossless
            HIGH_LOSSY -> format.isLossless || format.bitrateKbps >= 256
            ANY -> true
        }
    }

    companion object {
        /**
         * Default priority order used by fresh installs (no DataStore
         * entry yet). Existing users with explicitly-saved priority
         * preserve their value.
         *
         * Order:
         * 1. qbdlx_qobuz — Qobuz Hi-Res FLAC via a direct www.qobuz.com call
         *    (MD5 request signing with the user's own credentials, or a relay).
         *    Ranked FIRST: it's
         *    the fastest lossless path — plain Range-seekable FLAC, no proxy
         *    operator and no client-side decryption.
         * 2. squid_qobuz — Qobuz Hi-Res FLAC via qobuz.squid.wtf.
         * 3. kennyy_qobuz — Qobuz Hi-Res FLAC via qobuz.kennyy.com.br.
         *    2–3 are currently PARKED (hosts down for us) — see
         *    [LosslessSourceRegistry.PARKED_SOURCE_IDS]; the code + this ranking
         *    stay so re-enabling is a one-line change when they recover.
         *
         * Every rung is user-owned OR build-carried: 1 self-gates off without a
         * connected account, a custom endpoint or a relay from the signed config,
         * and 2 and 3 are parked. With nothing of the user's own
         * connected, a build carrying a relay config still resolves through 1 with
         * no user action (see [LosslessAvailability] and [com.stash.data.download.lossless.qbdlx.QbdlxFileUrlRouter]); a
         * build without one reaches NO lossless source at all and falls back to lossy.
         */
        val DEFAULT_PRIORITY: List<String> = listOf(
            "qbdlx_qobuz",
            "tidal_bitchord",
            "squid_qobuz",
            "kennyy_qobuz",
        )

        /**
         * Validates + normalises a relay base URL: must parse as an https URL
         * (OkHttp's rules — a bad host is rejected here, never at request time),
         * scheme/host lower-cased, query and fragment dropped, trailing slashes
         * trimmed. A path prefix is kept (a relay behind a reverse-proxy subpath
         * is legitimate). Null when unusable.
         */
        fun normaliseEndpoint(raw: String?): String? {
            val u = raw?.trim()?.toHttpUrlOrNull()?.takeIf { it.scheme == "https" } ?: return null
            return u.newBuilder().query(null).fragment(null).build().toString().trimEnd('/')
        }
    }
}

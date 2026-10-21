package com.stash.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Dedicated DataStore for the online-streaming engine toggle, cellular
 *  allow-toggle, and stream-quality tier. */
private val Context.streamingDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "streaming_preference",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Stream-quality tier. Persisted as the enum's `name` string. */
enum class StreamQualityTier { LOSSLESS, HIGH_QUALITY_LOSSY }

/**
 * User-facing streaming preferences:
 *
 *  - [enabled] — the app's ONE mode switch, shown to the user inverted as
 *    "Download". `false` = Download mode: sync writes switched-on playlists
 *    and mixes to disk. `true` = sync only keeps the streamable index. Since
 *    2026-09-17 this no longer gates playback: streaming is always allowed,
 *    refused only by connectivity or [streamOnCellular]. (Before that — and
 *    still in this property's name — it was the Online/Offline switch.)
 *  - [streamOnCellular] — whether streaming is allowed on a metered
 *    network. Default `false` so users don't burn data unintentionally.
 *  - [streamQuality] — preferred lossless vs high-quality-lossy tier
 *    used when resolving stream URLs.
 *
 * Default is `enabled = false` — Download mode for the existing install base.
 */
@Singleton
class StreamingPreference @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val enabledKey = booleanPreferencesKey("streaming_enabled")
    private val cellularKey = booleanPreferencesKey("streaming_on_cellular")
    private val qualityKey = stringPreferencesKey("streaming_quality_tier")
    private val forceYouTubeFallbackKey = booleanPreferencesKey("force_youtube_fallback")
    private val forceQbdlxOnlyKey = booleanPreferencesKey("force_qbdlx_only")
    private val skipQobuzKey = booleanPreferencesKey("test_skip_qobuz")
    // Retained only so [purgeRetiredKeys] can delete them from existing
    // installs; the sources were removed (antra: fix/remove-antra; amz:
    // Plan C; arcod: chore/remove-arcod). The literal names must stay until
    // upgrades from older versions stop mattering — deleting a key requires
    // naming it.
    private val forceAntraOnlyKey = booleanPreferencesKey("force_antra_only")
    private val forceAmzOnlyKey = booleanPreferencesKey("force_amz_only")
    private val forceArcodOnlyKey = booleanPreferencesKey("force_arcod_only")

    val enabled: Flow<Boolean> = context.streamingDataStore.data.map { prefs ->
        prefs[enabledKey] ?: false
    }

    val streamOnCellular: Flow<Boolean> = context.streamingDataStore.data.map { prefs ->
        prefs[cellularKey] ?: false
    }

    val streamQuality: Flow<StreamQualityTier> = context.streamingDataStore.data.map { prefs ->
        runCatching { StreamQualityTier.valueOf(prefs[qualityKey] ?: "") }
            .getOrDefault(StreamQualityTier.LOSSLESS)
    }

    /**
     * Test-only toggle. When `true`, [StreamSourceRegistry] skips Kennyy
     * and Squid and resolves every streaming track via the YouTube
     * fallback resolver only — used to reproduce the lossless-down path
     * on demand. Default `false` (normal use).
     */
    val forceYouTubeFallback: Flow<Boolean> = context.streamingDataStore.data.map { prefs ->
        prefs[forceYouTubeFallbackKey] ?: false
    }

    /**
     * Test-only toggle. When `true`, BOTH the streaming ([StreamSourceRegistry])
     * and lossless-download ([LosslessSourceRegistry]) registries route through
     * the qbdlx (direct-Qobuz) source ONLY — kennyy/squid/YouTube are
     * removed from play so a track either resolves via qbdlx or fails visibly.
     * Used to exercise qbdlx on demand (it normally ranks last and is hard to
     * trigger). Default `false` (normal use).
     */
    val forceQbdlxOnly: Flow<Boolean> = context.streamingDataStore.data.map { prefs ->
        prefs[forceQbdlxOnlyKey] ?: false
    }

    /**
     * Test-only toggle ("Test: skip Qobuz (Tidal first)", debug builds only). When `true`,
     * the streaming chain ([StreamSourceRegistry]) drops the qbdlx rung and the download
     * chain ([LosslessSourceRegistry]) drops the `qbdlx_qobuz` source, so Tidal is asked
     * first — Qobuz answers almost every track, so without this nobody can see Tidal serve.
     * Unlike [forceQbdlxOnly] nothing else is removed: Tidal → JioSaavn → YouTube stays
     * intact. Raw stored value; consumers read [isSkipQobuz], which is debug-gated.
     * Default `false`.
     */
    val skipQobuz: Flow<Boolean> = context.streamingDataStore.data.map { prefs ->
        prefs[skipQobuzKey] ?: false
    }

    /**
     * Whether DEVELOPER force toggles (force-qbdlx, skip-Qobuz) are honored. Defaults to
     * the installed app's debuggable flag — the same source of truth the Settings
     * screen uses to decide whether to SHOW those rows, so visibility and effect
     * can never disagree again.
     *
     * Why this gate exists (#429, fourth incident of the class): these prefs
     * persist in DataStore, but the rows that set them have been hidden, removed,
     * or debug-gated over time. A pref that outlives its UI is honored forever,
     * invisibly, with no way to turn it off — one user spent months with a
     * `[qbdlx]`-only chain from a toggle set back when it was still a visible
     * control. Enforced HERE, at the accessors, so both registries and any future
     * consumer inherit it and no call site can forget.
     *
     * Force-YouTube is deliberately NOT gated: "Stream via YouTube" is a visible
     * user-facing recovery control, so the user can always see and clear it.
     *
     * internal var: test seam only.
     */
    internal var devInstrumentsEnabled: Boolean =
        context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0

    /** One log per process, not per resolve — the chain is rebuilt constantly. */
    private val suppressionLogged = java.util.concurrent.atomic.AtomicBoolean(false)

    private suspend fun devForceToggle(flow: Flow<Boolean>, name: String): Boolean {
        if (!flow.first()) return false
        if (devInstrumentsEnabled) return true
        if (suppressionLogged.compareAndSet(false, true)) {
            android.util.Log.w(
                "StreamingPreference",
                "developer force toggle '$name' is set but this build is not debuggable — " +
                    "ignoring it (stale pref from an older version or a debug session; it would " +
                    "otherwise route every track through one source with no fallback)",
            )
        }
        return false
    }

    suspend fun current(): Boolean = enabled.first()

    suspend fun isForceYouTubeFallback(): Boolean = forceYouTubeFallback.first()

    suspend fun isForceQbdlxOnly(): Boolean = devForceToggle(forceQbdlxOnly, "force_qbdlx_only")

    /** [skipQobuz], honored only on debuggable builds (see [devInstrumentsEnabled]). */
    suspend fun isSkipQobuz(): Boolean = devForceToggle(skipQobuz, "test_skip_qobuz")

    suspend fun setEnabled(value: Boolean) {
        context.streamingDataStore.edit { it[enabledKey] = value }
    }

    suspend fun setStreamOnCellular(value: Boolean) {
        context.streamingDataStore.edit { it[cellularKey] = value }
    }

    suspend fun setForceYouTubeFallback(value: Boolean) {
        context.streamingDataStore.edit { it[forceYouTubeFallbackKey] = value }
    }

    suspend fun setForceQbdlxOnly(value: Boolean) {
        context.streamingDataStore.edit { it[forceQbdlxOnlyKey] = value }
    }

    suspend fun setSkipQobuz(value: Boolean) {
        context.streamingDataStore.edit { it[skipQobuzKey] = value }
    }

    suspend fun setStreamQuality(tier: StreamQualityTier) {
        context.streamingDataStore.edit { it[qualityKey] = tier.name }
    }

    /**
     * One-shot cleanup for removed sources: deletes their retired force-only
     * toggles from existing installs. Called once at startup (see
     * StashApplication). No-op when the keys are already absent.
     */
    suspend fun purgeRetiredKeys() {
        context.streamingDataStore.edit {
            it.remove(forceAntraOnlyKey)
            it.remove(forceAmzOnlyKey)
            it.remove(forceArcodOnlyKey)
        }
    }
}

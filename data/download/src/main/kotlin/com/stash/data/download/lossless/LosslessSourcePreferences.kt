package com.stash.data.download.lossless

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** DataStore for lossless-source preferences (priority order, min quality, etc). */
private val Context.losslessDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "lossless_source_preferences",
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
) {
    private val priorityKey = stringPreferencesKey("priority_order")
    private val minQualityKey = stringPreferencesKey("min_quality")
    private val enabledKey = androidx.datastore.preferences.core.booleanPreferencesKey("enabled")
    private val captchaCookieKey = stringPreferencesKey("squid_wtf_captcha_verified_at")

    /**
     * Master switch for the lossless-source pipeline. When false, the
     * download path skips the registry entirely and goes straight to
     * yt-dlp — same behaviour as before the lossless feature shipped.
     *
     * Defaults to false: lossless files are 5-10× larger than Opus, so
     * users opt in explicitly via Settings rather than getting a
     * surprise storage hit. The toggle lives on this preferences class
     * (rather than its own DataStore) so all lossless-related settings
     * stay in one place and the schema can evolve together.
     */
    val enabled: Flow<Boolean> = context.losslessDataStore.data.map { prefs ->
        prefs[enabledKey] ?: false
    }

    suspend fun enabledNow(): Boolean = enabled.first()

    suspend fun setEnabled(value: Boolean) {
        context.losslessDataStore.edit { prefs -> prefs[enabledKey] = value }
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

    suspend fun captchaCookieValueNow(): String? = captchaCookieValue.first()

    suspend fun setCaptchaCookieValue(value: String?) {
        context.losslessDataStore.edit { prefs ->
            val trimmed = value?.trim()?.takeIf { it.isNotEmpty() }
            if (trimmed == null) prefs.remove(captchaCookieKey)
            else prefs[captchaCookieKey] = trimmed
        }
    }

    /**
     * Emits the user's source-id priority order. Empty list means "no
     * order set" — registry treats this as "use registration order" so a
     * fresh install with default sources Just Works.
     */
    val priorityOrder: Flow<List<String>> = context.losslessDataStore.data.map { prefs ->
        prefs[priorityKey]
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
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
}

package com.stash.core.data.youtube

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Dedicated DataStore for YT scrobbler kill-switch + failure-counter state. */
private val Context.youtubeScrobblerStateDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "youtube_scrobbler_state",
)

/**
 * Persistent state for [YouTubeHistoryScrobbler]'s behavior-driven
 * kill-switch. Kept separate from the opt-in preference so that disabling
 * via kill-switch (protocol broken) and disabling via user toggle don't
 * collide semantically.
 *
 * Fields:
 *  - [disabledReason]: null when healthy; "protocol_errors" when the
 *    kill-switch has tripped. Scrobbler no-ops while this is non-null.
 *  - `consecutive_failures`: sliding counter of non-auth (!= 401/403)
 *    failures since the last successful ping. Reset to 0 on every
 *    success. Trips to `disabledReason = "protocol_errors"` at >= 5.
 *  - `last_known_version_code`: the `BuildConfig.VERSION_CODE` the
 *    scrobbler saw on its most recent start. When the installed app's
 *    version is newer, the kill-switch flag clears automatically — the
 *    "self-heal on app update" mechanism from the spec.
 */
@Singleton
class YouTubeScrobblerState @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val disabledReasonKey = stringPreferencesKey("disabled_reason")
    private val consecutiveFailuresKey = intPreferencesKey("consecutive_failures")
    private val lastKnownVersionCodeKey = intPreferencesKey("last_known_version_code")

    val disabledReason: Flow<String?> = context.youtubeScrobblerStateDataStore.data.map {
        it[disabledReasonKey]
    }

    suspend fun currentDisabledReason(): String? = disabledReason.first()

    suspend fun currentConsecutiveFailures(): Int =
        context.youtubeScrobblerStateDataStore.data.first()[consecutiveFailuresKey] ?: 0

    suspend fun currentLastKnownVersionCode(): Int =
        context.youtubeScrobblerStateDataStore.data.first()[lastKnownVersionCodeKey] ?: 0

    suspend fun setDisabledReason(reason: String?) {
        context.youtubeScrobblerStateDataStore.edit {
            if (reason == null) it.remove(disabledReasonKey) else it[disabledReasonKey] = reason
        }
    }

    suspend fun incrementConsecutiveFailures(): Int {
        var newValue = 0
        context.youtubeScrobblerStateDataStore.edit { prefs ->
            val cur = prefs[consecutiveFailuresKey] ?: 0
            newValue = cur + 1
            prefs[consecutiveFailuresKey] = newValue
        }
        return newValue
    }

    suspend fun resetConsecutiveFailures() {
        context.youtubeScrobblerStateDataStore.edit { it[consecutiveFailuresKey] = 0 }
    }

    suspend fun setLastKnownVersionCode(versionCode: Int) {
        context.youtubeScrobblerStateDataStore.edit {
            it[lastKnownVersionCodeKey] = versionCode
        }
    }
}

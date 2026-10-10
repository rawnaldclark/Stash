package com.stash.core.data.weblink.handoff

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * This phone's handoff settings and memory (spec §2.5, §4.4), never synced and never in a backup (the library backup carries
 * the library database only; `allowBackup` is off):
 * - **Pick up where you left off**: on by default once linked.
 * - The states the user dismissed (`"<deviceId>@<serverAt>"`, the newest [MAX_DISMISSED]).
 * - When this phone last played (its own clock), so a state older than that isn't offered.
 */
@Singleton
class HandoffPrefs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, on).apply()
        _enabled.value = on
    }

    val dismissed: List<String> get() = prefs.getString(KEY_DISMISSED, "").orEmpty().split('\n').filter { it.isNotEmpty() }

    fun dismiss(stateKey: String) {
        val list = (dismissed - stateKey + stateKey).takeLast(MAX_DISMISSED)
        prefs.edit().putString(KEY_DISMISSED, list.joinToString("\n")).apply()
    }

    /** This phone's clock when it last played (0 = never). Written on play/pause edges only, never on a timer. */
    var lastPlayedAtLocal: Long
        get() = prefs.getLong(KEY_LAST_PLAYED, 0L)
        set(v) {
            prefs.edit().putLong(KEY_LAST_PLAYED, v).apply()
        }

    private companion object {
        const val FILE = "weblink_handoff"
        const val KEY_ENABLED = "enabled"
        const val KEY_DISMISSED = "dismissed"
        const val KEY_LAST_PLAYED = "last_played_local"
        const val MAX_DISMISSED = 20
    }
}

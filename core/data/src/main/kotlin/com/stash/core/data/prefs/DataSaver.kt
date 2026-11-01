package com.stash.core.data.prefs

import android.content.Context
import com.stash.core.common.isActiveNetworkMetered
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Data saver (Settings › Audio & Quality): on a metered network (mobile data, a metered hotspot)
 * Stash streams smaller, loads smaller pictures, prefetches nothing and leaves background art and
 * metadata work for Wi-Fi. Downloads are never affected (a download is the user's own choice), and
 * on unmetered Wi-Fi nothing changes.
 *
 * The switch is stored in `:data:download`'s `StreamingQualityPreferences.saveData` (DataStore key
 * `streaming_save_data`, the same preference Now Playing's quality sheet writes). Streaming reads it
 * there through `StreamQualityPolicy`. This class is for everything else, which can't wait on a
 * DataStore read or can't see that module: it reads [DataSaverSnapshot], a synchronous mirror the
 * app keeps in step with the DataStore.
 *
 * @param switchOn the switch as stored ([DataSaverSnapshot] in production).
 * @param metered whether the active network is metered right now.
 */
@Singleton
class DataSaver(
    private val switchOn: () -> Boolean,
    private val metered: () -> Boolean,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        switchOn = { DataSaverSnapshot.isOn(context) },
        metered = { isActiveNetworkMetered(context) },
    )

    /** The switch is on, whatever the network. Background work is held for Wi-Fi while it is. */
    val isOn: Boolean get() = switchOn()

    /**
     * The switch is on AND the active network is metered: what streams, pictures and prefetches act
     * on. Read at the moment of the decision (a binder call and an in-memory read), never cached, so
     * walking onto Wi-Fi lifts it at the next decision.
     */
    fun appliesNow(): Boolean = switchOn() && metered()

    companion object {
        /** Never applies: the default for classes built by hand in tests. */
        val Off = DataSaver(switchOn = { false }, metered = { false })
    }
}

/**
 * The Data saver switch in plain SharedPreferences, so callers that can't suspend (WorkManager
 * constraint builders called with only a Context, per-image art sizing) read it synchronously.
 * `StreamingQualityPreferences.saveData` (DataStore) stays the source of truth; the app collects it
 * for its lifetime into [DataSaverBackgroundWork.onSwitch], which writes this mirror. Same pattern
 * as [ThemeSnapshot].
 */
object DataSaverSnapshot {
    const val PREFS_NAME = "data_saver_snapshot"
    private const val KEY_ON = "on"

    /** The switch as last mirrored; off until the first mirror write (a fresh install). */
    fun isOn(context: Context): Boolean = prefs(context).getBoolean(KEY_ON, false)

    /**
     * Mirrors [on]. Returns true when it differs from what was mirrored before (a fresh install
     * mirroring "off" is not a change). `apply()`: every reader in this process shares the in-memory
     * copy, which is updated before this returns; only the disk write is deferred.
     */
    fun write(context: Context, on: Boolean): Boolean {
        val prefs = prefs(context)
        val changed = prefs.getBoolean(KEY_ON, false) != on
        if (changed || !prefs.contains(KEY_ON)) prefs.edit().putBoolean(KEY_ON, on).apply()
        return changed
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

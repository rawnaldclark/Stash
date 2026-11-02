package com.stash.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.stash.feature.nowplaying.ui.NpSpacingOverrides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * DEBUG BUILDS ONLY (app/src/debug). Sets Now Playing's text-block spacing live, for the browser
 * tuner (tools/np-tuner), which sends these as its sliders move:
 *
 *   adb shell am broadcast -a com.stash.app.debug.NP_SPACING -p com.stash.app.debug --es json '{"titleSize":24,"gapArt":30}'
 *   adb shell am broadcast -a com.stash.app.debug.NP_SPACING -p com.stash.app.debug --ez reset true
 *   adb shell am broadcast -a com.stash.app.debug.NP_SPACING -p com.stash.app.debug   (logs what is in force)
 *
 * The JSON goes over what is already set (one slider can send one value); keys it doesn't know
 * are ignored and every number is clamped to its range (NpSpacingJson). Now Playing relayouts as
 * soon as the store is written. Each call logs the full JSON now in force under the tag below.
 *
 * Declared only in the debug manifest, behind the DUMP permission (adb holds it; ordinary apps
 * can't get it); release builds bind no overrides at all (NpSpacingModule).
 */
class DebugNpSpacing : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun overrides(): NpSpacingOverrides
    }

    override fun onReceive(context: Context, intent: Intent) {
        val overrides = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java).overrides()
        val json = intent.getStringExtra(EXTRA_JSON)
        val reset = intent.getBooleanExtra(EXTRA_RESET, false)
        val pending = goAsync()
        scope.launch {
            try {
                when {
                    reset -> {
                        overrides.reset()
                        Log.i(TAG, "reset to the production values: ${overrides.current()}")
                    }
                    !json.isNullOrBlank() -> Log.i(TAG, "in force: ${overrides.apply(json)}")
                    else -> Log.i(TAG, "in force: ${overrides.current()}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "couldn't apply the spacing", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "DebugNpSpacing"
        const val EXTRA_JSON = "json"
        const val EXTRA_RESET = "reset"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

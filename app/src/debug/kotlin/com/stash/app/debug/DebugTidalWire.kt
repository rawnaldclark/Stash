package com.stash.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.stash.data.download.lossless.tidal.TidalDebugSettings
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
 * DEBUG BUILDS ONLY (app/src/debug). The Tidal wire instruments of Settings › Lossless sources ›
 * Advanced (add-ons plan phase 2 §5.4), from adb, for the phone runs:
 *
 *   adb shell am broadcast -a com.stash.debug.TIDAL_WIRE --es wire V1 --es origin http://127.0.0.1:8797 -p com.stash.app.debug
 *   adb shell am broadcast -a com.stash.debug.TIDAL_WIRE --es wire AUTO --es origin "" -p com.stash.app.debug   (back to normal)
 *
 * `wire`: AUTO, LEGACY or V1 (left alone when absent). `origin`: an owner server override, blank
 * clears it (left alone when absent). Only an origin: the token and the relay key stay the live
 * config's. Same DUMP guard as the other debug receivers.
 */
class DebugTidalWire : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun tidalDebug(): TidalDebugSettings
    }

    override fun onReceive(context: Context, intent: Intent) {
        val debug = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java).tidalDebug()
        val wire = intent.getStringExtra("wire")?.let { w -> TidalDebugSettings.Wire.entries.firstOrNull { it.name.equals(w, true) } }
        val hasOrigin = intent.hasExtra("origin")
        val origin = intent.getStringExtra("origin")
        val pending = goAsync()
        scope.launch {
            try {
                wire?.let { debug.setWire(it) }
                val stored = if (hasOrigin) debug.setOrigin(origin?.takeIf { it.isNotBlank() }) else null
                Log.i(TAG, "wire=${wire ?: "unchanged"} override=${if (!hasOrigin) "unchanged" else if (stored != null) "set" else "cleared"}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "couldn't set the Tidal wire: ${e.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "DebugTidalWire"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

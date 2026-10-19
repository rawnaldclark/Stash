package com.stash.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.stash.core.data.lastfm.recap.LastFmRecapDebugUserStore
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
 * DEBUG BUILDS ONLY (app/src/debug). Points the Last.fm recap at a Last.fm username for testing,
 * without connecting an account (no session key is ever involved: public profiles are read
 * unsigned):
 *
 *   adb shell am broadcast -a com.stash.debug.LASTFM_RECAP_USER --es username rawnclark -p com.stash.app.debug
 *   adb shell am broadcast -a com.stash.debug.LASTFM_RECAP_USER -p com.stash.app.debug   (no username: clear)
 *
 * While set, it wins over the connected account (Your listening → Past recaps → On Last.fm).
 * Declared only in the debug manifest, behind the DUMP permission (adb holds it; ordinary apps
 * can't get it); release builds bind no override at all (LastFmRecapDebugModule).
 */
class DebugLastFmRecapUser : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun store(): LastFmRecapDebugUserStore
    }

    override fun onReceive(context: Context, intent: Intent) {
        val store = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java).store()
        val username = intent.getStringExtra(EXTRA_USERNAME)
        val pending = goAsync()
        scope.launch {
            try {
                store.set(username)
                Log.i(TAG, if (username.isNullOrBlank()) "test username cleared" else "test username set to '$username'")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "couldn't set the test username", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "DebugLastFmRecapUser"
        const val EXTRA_USERNAME = "username"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

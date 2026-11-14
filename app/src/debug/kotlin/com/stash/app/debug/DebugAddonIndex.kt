package com.stash.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.stash.data.download.lossless.addon.AddonIndexFetcher
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
 * DEBUG BUILDS ONLY (app/src/debug). A staging add-on index for the phone runs (add-ons plan phase 4
 * §1.10): the signed index is read from a local server over `adb reverse` and verified with a
 * throwaway key, instead of the address and the key the release app uses.
 *
 *   adb shell am broadcast -a com.stash.debug.ADDON_INDEX --es url http://127.0.0.1:8799/index.json --es pubkey <base64 SPKI> -p com.stash.app.debug
 *   adb shell am broadcast -a com.stash.debug.ADDON_INDEX -p com.stash.app.debug                 (read it again now)
 *   adb shell am broadcast -a com.stash.debug.ADDON_INDEX --es url "" -p com.stash.app.debug     (back to normal)
 *
 * `url` with `pubkey` sets the staging source; a blank `url` clears it. Either one forgets the kept
 * index and its rollback floor, so a test never leaves its blocks behind. With no `url` the index is
 * only read again. Every call ends with a fetch. Same DUMP guard as the other debug receivers.
 */
class DebugAddonIndex : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun addonIndex(): AddonIndexFetcher
    }

    override fun onReceive(context: Context, intent: Intent) {
        val fetcher = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java).addonIndex()
        val hasUrl = intent.hasExtra("url")
        val url = intent.getStringExtra("url")
        val key = intent.getStringExtra("pubkey")
        val pending = goAsync()
        scope.launch {
            try {
                if (hasUrl) {
                    val taken = fetcher.setDebugSource(url, key)
                    Log.i(TAG, if (taken) "source ${if (url.isNullOrBlank()) "cleared" else "set"}" else "source refused (it needs a url and a pubkey)")
                }
                val applied = fetcher.refresh(force = true)
                Log.i(TAG, "read again: ${if (applied) "applied" else "nothing applied"}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "couldn't set the staging index: ${e.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "DebugAddonIndex"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

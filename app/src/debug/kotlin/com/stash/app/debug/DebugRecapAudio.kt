package com.stash.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.mapper.toDomain
import com.stash.core.media.recap.RECAP_DEFAULT_START_FRACTION
import com.stash.core.media.recap.RecapAudio
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
 * DEBUG BUILDS ONLY (app/src/debug). Drives the Recap story audio ([RecapAudio]) from adb, so it
 * can be proven on a phone before the story screen exists:
 *
 *   adb shell am broadcast -a com.stash.debug.RECAP_AUDIO --es action begin  -p com.stash.app.debug
 *   adb shell am broadcast -a com.stash.debug.RECAP_AUDIO --es action play --el trackId 123 -p com.stash.app.debug
 *   adb shell am broadcast -a com.stash.debug.RECAP_AUDIO --es action pause|resume -p com.stash.app.debug
 *   adb shell am broadcast -a com.stash.debug.RECAP_AUDIO --es action end [--ez restore false] -p com.stash.app.debug
 *   adb shell am broadcast -a com.stash.debug.RECAP_AUDIO --es action background|foreground -p com.stash.app.debug
 *
 * `play` also takes `--ef fraction 0.3` (where in the song to start). Logcat tags: [TAG] for this
 * hook, `RecapAudio` for the engine's decisions. Declared only in the debug manifest, behind the
 * DUMP permission (adb holds it; ordinary apps can't get it).
 */
class DebugRecapAudio : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun recapAudio(): RecapAudio
        fun trackDao(): TrackDao
    }

    override fun onReceive(context: Context, intent: Intent) {
        val deps = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java)
        val audio = deps.recapAudio()
        val action = intent.getStringExtra(EXTRA_ACTION)
        val pending = goAsync()
        scope.launch {
            try {
                when (action) {
                    "begin" -> audio.begin()
                    "play" -> {
                        val id = intent.getLongExtra(EXTRA_TRACK_ID, -1L)
                        val fraction = intent.getFloatExtra(EXTRA_FRACTION, RECAP_DEFAULT_START_FRACTION)
                        val track = deps.trackDao().getById(id)?.toDomain()
                        if (track == null) {
                            Log.w(TAG, "play: no track $id")
                        } else {
                            Log.i(
                                TAG,
                                "play $id '${track.title}' downloaded=${track.isDownloaded} " +
                                    "file=${track.filePath != null}",
                            )
                            audio.play(track, fraction)
                        }
                    }
                    "pause" -> audio.pause()
                    "resume" -> audio.resume()
                    "end" -> audio.end(restoreMain = intent.getBooleanExtra(EXTRA_RESTORE, true))
                    "background" -> audio.onAppBackground()
                    "foreground" -> audio.onAppForeground()
                    else -> Log.w(TAG, "unknown action '$action' (begin|play|pause|resume|end|background|foreground)")
                }
                Log.i(TAG, "$action done (story active=${audio.active.value})")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "$action failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "DebugRecapAudio"
        const val EXTRA_ACTION = "action"
        const val EXTRA_TRACK_ID = "trackId"
        const val EXTRA_FRACTION = "fraction"
        const val EXTRA_RESTORE = "restore"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}

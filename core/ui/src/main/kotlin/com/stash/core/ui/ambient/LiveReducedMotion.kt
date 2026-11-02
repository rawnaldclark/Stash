package com.stash.core.ui.ambient

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * [rememberReducedMotion], kept live: the system "Remove animations" switch is re-read whenever the
 * setting changes, so an endless animation (the radio Signal glyph's breath) stops, or starts, the
 * moment it is flipped, not when the screen is next opened. Watches the setting only while the
 * caller is composed.
 *
 * Its own file, so it merges cleanly beside #783 (feat/battery-saver-follow), which rewrites
 * [rememberReducedMotion]'s body to stand still under Battery Saver too.
 */
@Composable
fun rememberLiveReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    var removeAnimations by remember(resolver) { mutableStateOf(readRemoveAnimations(resolver)) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                removeAnimations = readRemoveAnimations(resolver)
            }
        }
        val registered = try {
            resolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer,
            )
            true
        } catch (_: Exception) {
            false
        }
        // A flip between the first read and the registration would otherwise be missed.
        removeAnimations = readRemoveAnimations(resolver)
        onDispose { if (registered) resolver.unregisterContentObserver(observer) }
    }
    // TODO(#783): stand still under Battery Saver too, as rememberReducedMotion does there. Whichever
    //  of #780 / #783 merges second makes this line `return motionStill(removeAnimations, LocalBatterySaverOn.current)`.
    return removeAnimations
}

/** The system "Remove animations" switch right now (animator duration scale 0). */
private fun readRemoveAnimations(resolver: ContentResolver): Boolean = try {
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
} catch (_: Exception) {
    false
}

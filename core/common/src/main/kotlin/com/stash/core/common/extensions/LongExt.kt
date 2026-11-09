package com.stash.core.common.extensions

import java.util.Locale
import java.util.concurrent.TimeUnit

fun Long.formatDuration(): String {
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(this)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

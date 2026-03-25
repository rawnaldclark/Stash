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

fun Long.formatFileSize(): String {
    if (this < 1024) return "$this B"
    val units = arrayOf("KB", "MB", "GB")
    var value = this.toDouble()
    var unitIndex = -1
    do {
        value /= 1024.0
        unitIndex++
    } while (value >= 1024 && unitIndex < units.lastIndex)
    return String.format(Locale.US, "%.1f %s", value, units[unitIndex])
}

fun Long.toRelativeTimeString(): String {
    val now = System.currentTimeMillis()
    val diff = now - this
    return when {
        diff < TimeUnit.MINUTES.toMillis(1) -> "Just now"
        diff < TimeUnit.HOURS.toMillis(1) -> {
            val mins = TimeUnit.MILLISECONDS.toMinutes(diff)
            "$mins min${if (mins != 1L) "s" else ""} ago"
        }
        diff < TimeUnit.DAYS.toMillis(1) -> {
            val hours = TimeUnit.MILLISECONDS.toHours(diff)
            "$hours hour${if (hours != 1L) "s" else ""} ago"
        }
        diff < TimeUnit.DAYS.toMillis(7) -> {
            val days = TimeUnit.MILLISECONDS.toDays(diff)
            "$days day${if (days != 1L) "s" else ""} ago"
        }
        else -> {
            val days = TimeUnit.MILLISECONDS.toDays(diff)
            val weeks = days / 7
            "$weeks week${if (weeks != 1L) "s" else ""} ago"
        }
    }
}

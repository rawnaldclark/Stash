package com.stash.core.ui.util

/**
 * Formats a duration in milliseconds to a human-readable string.
 *
 * - Under 1 hour: "M:SS" (e.g. "3:42")
 * - 1 hour or more: "H:MM:SS" (e.g. "1:23:05")
 *
 * @param durationMs Duration in milliseconds.
 * @return Formatted duration string.
 */
fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1_000
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/**
 * Formats a total duration in milliseconds to a friendly summary string.
 *
 * - Under 1 hour: "42 min" (rounded to nearest minute)
 * - 1 hour or more: "1 hr 23 min"
 *
 * @param totalMs Total duration in milliseconds.
 * @return Formatted summary string (e.g. "1 hr 23 min").
 */
fun formatTotalDuration(totalMs: Long): String {
    val totalMinutes = (totalMs / 60_000).toInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 && minutes > 0 -> "$hours hr $minutes min"
        hours > 0 -> "$hours hr"
        else -> "$minutes min"
    }
}

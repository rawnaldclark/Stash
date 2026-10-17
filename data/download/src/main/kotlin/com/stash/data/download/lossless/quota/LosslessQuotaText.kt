package com.stash.data.download.lossless.quota

import android.content.Context
import android.text.format.DateFormat
import java.util.Date

/**
 * The words for the daily lossless allowance, in one place so the global message and the
 * Settings line say the same thing. Times arrive already formatted ([resetTime]) so the copy
 * stays testable on the JVM.
 */
object LosslessQuotaText {

    /** The message for [n]; [time] is its reset in the user's local time ("8:00 PM"). */
    fun notice(n: LosslessQuotaNotice, time: String): String = when (n) {
        is LosslessQuotaNotice.RunningLow ->
            "${n.left} lossless ${if (n.left == 1) "song" else "songs"} left today"
        is LosslessQuotaNotice.Spent ->
            "Daily lossless limit reached — playing in high quality until $time. " +
                "Unlimited lossless for supporters: any amount on Ko-fi"
    }

    /**
     * Settings → Audio & Quality: "Lossless today: 412 of 500 · resets 8:00 PM", or
     * "Lossless today: Unlimited (supporter)". [time] is [a]'s reset in local time.
     */
    fun todayLine(a: LosslessAllowance, time: String): String =
        if (a.limit == null) "Lossless today: Unlimited (supporter)"
        else "Lossless today: ${a.used} of ${a.limit} · resets $time"

    /** [epochMs] as a short local time, following the device's 12 / 24-hour setting. */
    fun resetTime(context: Context, epochMs: Long): String =
        DateFormat.getTimeFormat(context).format(Date(epochMs))
}

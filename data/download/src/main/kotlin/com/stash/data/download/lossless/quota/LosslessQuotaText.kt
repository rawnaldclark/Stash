package com.stash.data.download.lossless.quota

import android.content.Context
import android.text.format.DateFormat
import java.util.Date

/**
 * The words for the daily allowance, in one place so the app-wide snackbar and the Settings line
 * say the same thing. It counts every Tidal and Qobuz answer, FLAC or AAC, so it is named for them,
 * not for lossless. Times arrive already formatted ([resetTime]) so the copy stays testable on the JVM.
 */
object LosslessQuotaText {

    /** Both heads-ups end with it. */
    const val SUPPORTER_NUDGE = "Unlimited for supporters: any amount on Ko-fi"

    /** The snackbar action: opens Settings → Audio & Quality, where the supporter claim is. */
    const val NOTICE_ACTION = "Unlock"

    /** The snackbar for [n]; [time] is its reset in the user's local time ("8:00 PM"). */
    fun notice(n: LosslessQuotaNotice, time: String): String = when (n) {
        is LosslessQuotaNotice.RunningLow ->
            "${counter(n.limit - n.left, n.limit)}. $SUPPORTER_NUDGE"
        // Past the limit neither Tidal nor Qobuz answers (AAC included): JioSaavn and YouTube play.
        is LosslessQuotaNotice.Spent ->
            "${counter(n.limit, n.limit)} — lower quality until $time. $SUPPORTER_NUDGE"
    }

    /** "Tidal & Qobuz today: 120 / 500", the counter as every line names it. */
    fun counter(used: Int, limit: Int): String = "Tidal & Qobuz today: $used / $limit"

    /**
     * Settings → Audio & Quality: "Tidal & Qobuz today: 412 / 500 · resets 8:00 PM", or
     * "Tidal & Qobuz today: Unlimited (supporter)". [time] is [a]'s reset in local time.
     */
    fun todayLine(a: LosslessAllowance, time: String): String =
        if (a.limit == null) "Tidal & Qobuz today: Unlimited (supporter)"
        else "${counter(a.used, a.limit)} · resets $time"

    /** [epochMs] as a short local time, following the device's 12 / 24-hour setting. */
    fun resetTime(context: Context, epochMs: Long): String =
        DateFormat.getTimeFormat(context).format(Date(epochMs))
}

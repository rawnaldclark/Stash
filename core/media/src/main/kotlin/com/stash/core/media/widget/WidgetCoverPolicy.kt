package com.stash.core.media.widget

import java.io.IOException

/**
 * When the widget fetches a cover, and which failures it remembers. Pure; see [WidgetCoverPolicyTest].
 *
 * The title, the artist and the play state are drawn first; the cover follows when it lands, so a
 * slow or failing fetch never holds the song's name back.
 */
object WidgetCoverPolicy {

    /**
     * The cover URL to fetch now, or null: the song has no cover, the one on hand is already the
     * song's ([coverKey] equals [artUrl] and [haveCover]), or this URL failed for good ([failedKey]).
     */
    fun wanted(artUrl: String?, coverKey: String?, haveCover: Boolean, failedKey: String?): String? = when {
        artUrl.isNullOrBlank() -> null
        artUrl == coverKey && haveCover -> null
        artUrl == failedKey -> null
        else -> artUrl
    }

    /**
     * Whether a failed fetch is remembered, so the URL isn't tried again until the song changes.
     * Only when the fetch got an answer that isn't a picture (a 404, a file that won't decode). A
     * timeout or a dropped connection ([IOException]) passes: the next update tries again.
     */
    fun isLastingFailure(error: Throwable?, timedOut: Boolean): Boolean = !timedOut && error !is IOException
}

/** The wide widget's cover size. Pure; see [WidgetCoverPolicyTest]. */
object WidgetWideLayout {

    /** The card's inset, the gap after the cover, and the buttons' share (8 + 40 + 4 + 40), in dp. */
    const val INSET_DP = 8f
    const val COVER_GAP_DP = 12f
    const val BUTTONS_DP = 92f

    /** The title keeps at least this much width; the cover gives way first. */
    const val MIN_TEXT_DP = 48f

    const val MIN_COVER_DP = 36f
    const val MAX_COVER_DP = 88f

    /**
     * The cover's edge for a wide widget [widthDp] × [heightDp], or null for no cover: as tall as
     * the card allows (36 to 88 dp, as before), and never wider than what the text and the buttons
     * leave. At 2×1 (about 195 dp) a cover sized by height alone pushed Next off the card; there,
     * with less than 36 dp to spare, the widget drops the cover and keeps the title and both buttons.
     */
    fun coverDp(widthDp: Float, heightDp: Float, hasButtons: Boolean): Float? {
        val byHeight = (heightDp - INSET_DP * 2).coerceIn(MIN_COVER_DP, MAX_COVER_DP)
        val byWidth = widthDp - INSET_DP * 2 - COVER_GAP_DP - (if (hasButtons) BUTTONS_DP else 0f) - MIN_TEXT_DP
        return if (byWidth < MIN_COVER_DP) null else minOf(byHeight, byWidth)
    }
}

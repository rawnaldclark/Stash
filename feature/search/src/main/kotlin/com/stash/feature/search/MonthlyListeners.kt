package com.stash.feature.search

import android.icu.text.CompactDecimalFormat
import com.stash.data.ytmusic.model.ArtistProfile
import java.util.Locale

/**
 * The one listener count the artist page shows, as "1.7M monthly listeners", never naming where
 * it came from (owner, 2026-11-01):
 *  1. Spotify's monthly listeners ([com.stash.data.ytmusic.model.ArtistAbout.monthlyListeners]),
 *     the number people quote, when Spotify is connected and found exactly this artist;
 *  2. else YouTube's monthly audience ([ArtistProfile.monthlyAudience]);
 *  3. else nothing (null): no "0", and never the YouTube subscriber count, which is a different,
 *     smaller number (Sonic Youth: 290K subscribers against 574K monthly and 1.7M on Spotify).
 *
 * A profile cached before these fields existed decodes them as null and falls through the same
 * way, so it shows YouTube's number when it has one, or nothing.
 */
internal fun monthlyListenersOf(profile: ArtistProfile): Long? =
    profile.about?.monthlyListeners?.takeIf { it > 0 }
        ?: profile.monthlyAudience?.takeIf { it > 0 }

/**
 * [count] the way the services write it, in the app's language (ICU's short compact style):
 * whole units, "574K", "12K", "999", and one decimal only when the whole number would be a single
 * digit, "1.7M" rather than "2M". ICU's own rounding keeps two significant digits, which made
 * 574 000 "570K" (caught by MonthlyListenersTest); one decimal everywhere made 574 321 "574.3K".
 * Digits are counted with [Char.isDigit], so a locale's own numerals count too.
 */
internal fun formatMonthlyListeners(count: Long, locale: Locale): String {
    val format = CompactDecimalFormat.getInstance(locale, CompactDecimalFormat.CompactStyle.SHORT)
    format.maximumFractionDigits = 0
    val whole = format.format(count)
    if (whole.count(Char::isDigit) > 1) return whole
    format.maximumFractionDigits = 1
    return format.format(count)
}

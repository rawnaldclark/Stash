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
 * digit, "1.7M" rather than "2M".
 *
 * Android's ICU rounds compact numbers to two significant digits and ignores the fraction-digit
 * setters (574 000 read "570K" either way; caught by MonthlyListenersTest), so this works in
 * significant digits, the knob it honours: three of them show how many whole digits the number
 * has in its unit ("574K", "12.3K", "1.73M", before the locale's decimal separator), and the
 * final format keeps exactly those, at least two. Digits are counted with [Char.isDigit], so a
 * locale's own numerals count too.
 */
internal fun formatMonthlyListeners(count: Long, locale: Locale): String {
    val format = CompactDecimalFormat.getInstance(locale, CompactDecimalFormat.CompactStyle.SHORT)
    format.minimumSignificantDigits = 1
    format.maximumSignificantDigits = 3
    val separator = format.decimalFormatSymbols.decimalSeparator
    val wholeDigits = format.format(count).substringBefore(separator).count(Char::isDigit)
    format.maximumSignificantDigits = maxOf(wholeDigits, 2)
    return format.format(count)
}

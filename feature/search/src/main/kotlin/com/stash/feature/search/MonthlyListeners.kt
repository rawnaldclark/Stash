package com.stash.feature.search

import android.icu.text.CompactDecimalFormat
import com.stash.data.ytmusic.model.ArtistAbout
import com.stash.data.ytmusic.model.ArtistProfile
import com.stash.data.ytmusic.model.TrackSummary
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.Locale

/**
 * The one listener count the artist page shows, as "1.7M monthly listeners", never naming where
 * it came from (owner, 2026-11-01):
 *  1. Spotify's monthly listeners ([ArtistAbout.monthlyListeners]), the number people quote, when
 *     Spotify is connected ([spotifyConnected]) and its artist is this one ([spotifyIsThisArtist]);
 *  2. else YouTube's monthly audience ([ArtistProfile.monthlyAudience]);
 *  3. else nothing (null): no "0", and never the YouTube subscriber count, which is a different,
 *     smaller number (Sonic Youth: 290K subscribers against 574K monthly and 1.7M on Spotify).
 *
 * Signed out of Spotify, a Spotify number still in the cache is not shown: it would only age.
 * A profile cached before these fields existed decodes them as null and falls through the same
 * way, so it shows YouTube's number when it has one, or nothing.
 */
internal fun monthlyListenersOf(profile: ArtistProfile, spotifyConnected: Boolean): Long? {
    val youTube = profile.monthlyAudience?.takeIf { it > 0 }
    val spotify = profile.about?.takeIf { spotifyConnected }?.let { about ->
        about.monthlyListeners?.takeIf { it > 0 && spotifyIsThisArtist(about, profile.popular, it, youTube) }
    }
    return spotify ?: youTube
}

/**
 * Whether Spotify's artist (found by name alone) is the one the page is about, so its count may
 * stand for it (review on #770: a namesake's number is worse than YouTube's):
 *  - one of Spotify's top tracks is on YouTube's Popular shelf (titles compared by [titleKey]);
 *    with either list empty nothing confirms it, and YouTube's number shows;
 *  - and, when YouTube has a monthly audience, the two are within [NAMESAKE_RATIO] of each other.
 */
internal fun spotifyIsThisArtist(
    about: ArtistAbout,
    popular: List<TrackSummary>,
    spotify: Long,
    youTube: Long?,
): Boolean {
    if (youTube != null && (spotify > youTube * NAMESAKE_RATIO || spotify * NAMESAKE_RATIO < youTube)) return false
    val spotifyTitles = about.topTrackTitles.mapNotNullTo(HashSet()) { titleKey(it) }
    return popular.any { titleKey(it.title) in spotifyTitles }
}

/** How far apart Spotify's and YouTube's monthly numbers may be and still be one artist's. */
private const val NAMESAKE_RATIO = 30L

private val BRACKETED = Regex("""[(\[][^)\]]*[)\]]""")
private val NOT_A_WORD = Regex("""[^\p{L}\p{N}]+""")

/**
 * A title as both services can agree on it: lower case, without bracketed parts or a " - "
 * suffix ("Teen Age Riot (Remastered 2014)", "Schizophrenia - Remastered"), letters and digits
 * only. Null when nothing is left.
 */
internal fun titleKey(title: String): String? =
    title.lowercase(Locale.ROOT)
        .replace(BRACKETED, " ")
        .substringBefore(" - ")
        .replace(NOT_A_WORD, " ")
        .trim()
        .takeIf { it.isNotEmpty() }

/**
 * A count as the hero shows it: [text] ("1.7M"), and the number that text stands for, [shown]
 * (1 700 000), which picks the plural. Russian and Polish need the shown number: after "млн" or
 * "tys." the noun takes the form for 1 700 000, not the exact 1 734 561's ("one" form).
 */
internal data class ShortCount(val text: String, val shown: Long)

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
internal fun shortMonthlyListeners(count: Long, locale: Locale): ShortCount {
    val format = CompactDecimalFormat.getInstance(locale, CompactDecimalFormat.CompactStyle.SHORT)
    format.minimumSignificantDigits = 1
    format.maximumSignificantDigits = 3
    val separator = format.decimalFormatSymbols.decimalSeparator
    val wholeDigits = format.format(count).substringBefore(separator).count(Char::isDigit)
    val digits = maxOf(wholeDigits, 2)
    format.maximumSignificantDigits = digits
    // ICU rounds half to even, as BigDecimal does here.
    val shown = BigDecimal.valueOf(count).round(MathContext(digits, RoundingMode.HALF_EVEN)).toLong()
    return ShortCount(text = format.format(count), shown = shown)
}

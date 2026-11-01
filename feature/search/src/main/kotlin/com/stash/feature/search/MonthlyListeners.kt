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
 * [count] the way the services write it, in the app's language: "574K", "1.7M", "12K", "999"
 * (ICU's short compact style, its own rounding: two significant digits, whole numbers from 10 up,
 * so 574 321 is "574K", not "574.3K").
 */
internal fun formatMonthlyListeners(count: Long, locale: Locale): String =
    CompactDecimalFormat.getInstance(locale, CompactDecimalFormat.CompactStyle.SHORT).format(count)

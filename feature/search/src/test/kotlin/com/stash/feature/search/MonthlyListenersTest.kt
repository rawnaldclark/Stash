package com.stash.feature.search

import com.google.common.truth.Truth.assertThat
import com.stash.data.ytmusic.model.ArtistAbout
import com.stash.data.ytmusic.model.ArtistProfile
import java.util.Locale
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The artist page's one listener count: which number wins ([monthlyListenersOf]) and how it reads
 * ([formatMonthlyListeners]). Robolectric for the framework's ICU, which formats the number.
 */
@RunWith(RobolectricTestRunner::class)
class MonthlyListenersTest {

    private fun profile(spotify: Long? = null, youtube: Long? = null, subscribers: String? = "290K", about: Boolean = true) =
        ArtistProfile(
            id = "UC1", name = "Sonic Youth", avatarUrl = null, subscribersText = subscribers,
            popular = emptyList(), albums = emptyList(), singles = emptyList(), related = emptyList(),
            about = if (about) ArtistAbout(bio = "b", monthlyListeners = spotify) else null,
            monthlyAudience = youtube,
        )

    @Test fun `Spotify's monthly listeners win when both exist`() {
        assertThat(monthlyListenersOf(profile(spotify = 1_734_567L, youtube = 574_000L))).isEqualTo(1_734_567L)
    }

    @Test fun `YouTube's monthly audience when Spotify has none`() {
        assertThat(monthlyListenersOf(profile(spotify = null, youtube = 574_000L))).isEqualTo(574_000L)
        assertThat(monthlyListenersOf(profile(youtube = 574_000L, about = false))).isEqualTo(574_000L)
    }

    @Test fun `neither number is no number, never the subscriber count`() {
        assertThat(monthlyListenersOf(profile(spotify = null, youtube = null, subscribers = "290K"))).isNull()
    }

    @Test fun `a zero is no number, and falls through to the next source`() {
        assertThat(monthlyListenersOf(profile(spotify = 0L, youtube = 574_000L))).isEqualTo(574_000L)
        assertThat(monthlyListenersOf(profile(spotify = 0L, youtube = 0L))).isNull()
    }

    @Test fun `a profile cached before the counts existed shows nothing, without a crash`() {
        val legacy = profile(spotify = null, youtube = null, about = true)
        assertThat(monthlyListenersOf(legacy)).isNull()
    }

    @Test fun `counts read the way the services write them`() {
        val en = Locale.US
        assertThat(formatMonthlyListeners(1_734_567L, en)).isEqualTo("1.7M")
        assertThat(formatMonthlyListeners(574_000L, en)).isEqualTo("574K")
        assertThat(formatMonthlyListeners(574_321L, en)).isEqualTo("574K")
        assertThat(formatMonthlyListeners(12_345L, en)).isEqualTo("12K")
        assertThat(formatMonthlyListeners(1_234L, en)).isEqualTo("1.2K")
        assertThat(formatMonthlyListeners(999L, en)).isEqualTo("999")
        assertThat(formatMonthlyListeners(7L, en)).isEqualTo("7")
        assertThat(formatMonthlyListeners(1_000L, en)).isEqualTo("1K")
        assertThat(formatMonthlyListeners(17_400_000L, en)).isEqualTo("17M")
        assertThat(formatMonthlyListeners(2_100_000_000L, en)).isEqualTo("2.1B")
    }

    @Test fun `the count follows the app's language`() {
        // German writes the decimal comma and its own abbreviation; the exact suffix is ICU's.
        val de = formatMonthlyListeners(1_734_567L, Locale.GERMANY)
        assertThat(de).startsWith("1,7")
        assertThat(de).isNotEqualTo("1.7M")
    }
}

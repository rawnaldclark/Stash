package com.stash.feature.search

import com.google.common.truth.Truth.assertThat
import com.stash.data.ytmusic.model.ArtistAbout
import com.stash.data.ytmusic.model.ArtistProfile
import com.stash.data.ytmusic.model.TrackSummary
import java.util.Locale
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The artist page's one listener count: which number wins ([monthlyListenersOf]), whether
 * Spotify's artist is this one ([spotifyIsThisArtist]), and how it reads
 * ([shortMonthlyListeners]). Robolectric for the framework's ICU, which formats the number, on
 * the oldest and a current Android (their ICUs round compact numbers differently).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34])
class MonthlyListenersTest {

    private fun song(title: String) = TrackSummary(
        videoId = title.hashCode().toString(), title = title, artist = "Sonic Youth", album = null,
        durationSeconds = 200.0, thumbnailUrl = null,
    )

    private val popular = listOf(song("Teen Age Riot (Remastered 2014)"), song("Kool Thing"))
    private val spotifyTop = listOf("Teen Age Riot", "Schizophrenia - Remastered")

    private fun profile(
        spotify: Long? = null,
        youtube: Long? = null,
        subscribers: String? = "290K",
        about: Boolean = true,
        top: List<String> = spotifyTop,
        shelf: List<TrackSummary> = popular,
    ) = ArtistProfile(
        id = "UC1", name = "Sonic Youth", avatarUrl = null, subscribersText = subscribers,
        popular = shelf, albums = emptyList(), singles = emptyList(), related = emptyList(),
        about = if (about) ArtistAbout(bio = "b", monthlyListeners = spotify, topTrackTitles = top) else null,
        monthlyAudience = youtube,
    )

    // -- Which number --------------------------------------------------------

    @Test fun `Spotify's monthly listeners win when both exist`() {
        assertThat(monthlyListenersOf(profile(spotify = 1_734_567L, youtube = 574_000L), spotifyConnected = true))
            .isEqualTo(1_734_567L)
    }

    @Test fun `YouTube's monthly audience when Spotify has none`() {
        assertThat(monthlyListenersOf(profile(spotify = null, youtube = 574_000L), true)).isEqualTo(574_000L)
        assertThat(monthlyListenersOf(profile(youtube = 574_000L, about = false), true)).isEqualTo(574_000L)
    }

    @Test fun `neither number is no number, never the subscriber count`() {
        assertThat(monthlyListenersOf(profile(spotify = null, youtube = null, subscribers = "290K"), true)).isNull()
    }

    @Test fun `a zero is no number, and falls through to the next source`() {
        assertThat(monthlyListenersOf(profile(spotify = 0L, youtube = 574_000L), true)).isEqualTo(574_000L)
        assertThat(monthlyListenersOf(profile(spotify = 0L, youtube = 0L), true)).isNull()
    }

    @Test fun `a profile cached before the counts existed shows nothing, without a crash`() {
        assertThat(monthlyListenersOf(profile(spotify = null, youtube = null, top = emptyList()), true)).isNull()
    }

    @Test fun `signed out of Spotify, its number is not shown`() {
        assertThat(monthlyListenersOf(profile(spotify = 1_734_567L, youtube = 574_000L), spotifyConnected = false))
            .isEqualTo(574_000L)
        assertThat(monthlyListenersOf(profile(spotify = 1_734_567L, youtube = null), spotifyConnected = false)).isNull()
    }

    // -- A namesake's number ---------------------------------------------------

    @Test fun `no top track on YouTube's Popular shelf, not this artist`() {
        val namesake = profile(spotify = 1_734_567L, youtube = 574_000L, top = listOf("Some Other Song"))
        assertThat(monthlyListenersOf(namesake, true)).isEqualTo(574_000L)
    }

    @Test fun `nothing to compare, nothing confirms it`() {
        assertThat(monthlyListenersOf(profile(spotify = 1_734_567L, youtube = 574_000L, top = emptyList()), true))
            .isEqualTo(574_000L)
        assertThat(monthlyListenersOf(profile(spotify = 1_734_567L, youtube = 574_000L, shelf = emptyList()), true))
            .isEqualTo(574_000L)
    }

    @Test fun `a count more than 30 times apart from YouTube's is a namesake's`() {
        assertThat(monthlyListenersOf(profile(spotify = 20_000_000L, youtube = 574_000L), true)).isEqualTo(574_000L)
        assertThat(monthlyListenersOf(profile(spotify = 15_000L, youtube = 574_000L), true)).isEqualTo(574_000L)
        // Within 30 times either way: the same artist.
        assertThat(monthlyListenersOf(profile(spotify = 17_000_000L, youtube = 574_000L), true)).isEqualTo(17_000_000L)
    }

    @Test fun `without YouTube's number, the titles alone decide`() {
        assertThat(monthlyListenersOf(profile(spotify = 1_734_567L, youtube = null), true)).isEqualTo(1_734_567L)
    }

    @Test fun `titles match across the services' spellings`() {
        assertThat(titleKey("Teen Age Riot (Remastered 2014)")).isEqualTo(titleKey("Teen Age Riot"))
        assertThat(titleKey("Schizophrenia - Remastered")).isEqualTo(titleKey("Schizophrenia"))
        assertThat(titleKey("Kool Thing [Live]")).isEqualTo("kool thing")
        assertThat(titleKey("(Untitled)")).isNull()
    }

    // -- How it reads ------------------------------------------------------------

    private fun text(n: Long, locale: Locale = Locale.US) = shortMonthlyListeners(n, locale).text

    @Test fun `counts read the way the services write them`() {
        assertThat(text(1_734_567L)).isEqualTo("1.7M")
        assertThat(text(574_000L)).isEqualTo("574K")
        assertThat(text(574_321L)).isEqualTo("574K")
        assertThat(text(12_345L)).isEqualTo("12K")
        assertThat(text(1_234L)).isEqualTo("1.2K")
        assertThat(text(999L)).isEqualTo("999")
        assertThat(text(7L)).isEqualTo("7")
        assertThat(text(1_000L)).isEqualTo("1K")
        assertThat(text(17_400_000L)).isEqualTo("17M")
        assertThat(text(9_999L)).isEqualTo("10K")
        assertThat(text(999_999L)).isEqualTo("1M")
        assertThat(text(2_100_000_000L)).isEqualTo("2.1B")
    }

    @Test fun `the plural follows the number shown`() {
        assertThat(shortMonthlyListeners(1_734_567L, Locale.US).shown).isEqualTo(1_700_000L)
        assertThat(shortMonthlyListeners(574_321L, Locale.US).shown).isEqualTo(574_000L)
        assertThat(shortMonthlyListeners(1_000_001L, Locale.US).shown).isEqualTo(1_000_000L)
        assertThat(shortMonthlyListeners(999L, Locale.US).shown).isEqualTo(999L)
        assertThat(shortMonthlyListeners(1L, Locale.US).shown).isEqualTo(1L)
    }

    @Test fun `the count follows the app's language`() {
        // German writes the decimal comma and its own abbreviation; the exact suffix is ICU's.
        val de = text(1_734_567L, Locale.GERMANY)
        assertThat(de).startsWith("1,7")
        assertThat(de).isNotEqualTo("1.7M")
    }
}

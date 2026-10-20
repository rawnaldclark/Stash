package com.stash.core.data.lastfm

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/** What the art backfill never looks up, and the title it looks up instead. */
class LastFmLookupRulesTest {

    @Test fun `voice notes and recorder files are not looked up`() {
        listOf(
            "AUD-20240101-WA0001",
            "PTT-20231212-WA0042",
            "VID-20230505-WA0007",
            "REC_20240101_101500",
            "Recording_12",
            "Voice 001",
            "some clip-WA0003",
            "my song.mp3",
            "Track 01.M4A",
            "voice note.opus",
            "20240101_101500",
        ).forEach { title ->
            assertWithMessage(title).that(LastFmLookupRules.isUnlookable("Some Artist", title)).isTrue()
        }
    }

    @Test fun `placeholder artists are not looked up`() {
        listOf("Unknown Artist", "<unknown>", "unknown", "  UNKNOWN ARTIST ", "").forEach { artist ->
            assertWithMessage(artist).that(LastFmLookupRules.isUnlookable(artist, "Real Song")).isTrue()
        }
        assertThat(LastFmLookupRules.isUnlookable("Artist", "")).isTrue()
    }

    @Test fun `real songs are looked up, including ones that look a bit like file names`() {
        listOf(
            "Audition",
            "Recording Angel",
            "Video Games",
            "Voices",
            "1979",
            "2112",
            "99 Luftballons",
            "Track 01",
            "Paint a Face",
            "Mp3 Player",
        ).forEach { title ->
            assertWithMessage(title).that(LastFmLookupRules.isUnlookable("Some Artist", title)).isFalse()
        }
        assertThat(LastFmLookupRules.isUnlookable("The Unknown Artists Band", "Song")).isFalse()
    }

    @Test fun `YouTube presentation tags are stripped before the lookup`() {
        mapOf(
            "Bad Guy (Official Audio)" to "Bad Guy",
            "Bad Guy (Official Video)" to "Bad Guy",
            "Bad Guy [Official Music Video]" to "Bad Guy",
            "Bad Guy (Official Lyric Video)" to "Bad Guy",
            "Bad Guy (Lyric Video)" to "Bad Guy",
            "Bad Guy (Official Visualizer)" to "Bad Guy",
            "Bad Guy (Visualiser)" to "Bad Guy",
            "Bad Guy (Official HD Video)" to "Bad Guy",
            "Bad Guy (Audio)" to "Bad Guy",
            "Bad Guy (Lyrics)" to "Bad Guy",
            "Bad Guy (official music video) (Lyrics)" to "Bad Guy",
        ).forEach { (raw, clean) ->
            assertWithMessage(raw).that(LastFmLookupRules.cleanTitle(raw)).isEqualTo(clean)
        }
    }

    @Test fun `version words stay, they name another recording`() {
        listOf(
            "Bad Guy (Remix)",
            "Bad Guy (Live)",
            "Bad Guy (Acoustic)",
            "Bad Guy (feat. Justin Bieber)",
            "Video Killed the Radio Star",
            "Audio Love",
        ).forEach { title ->
            assertWithMessage(title).that(LastFmLookupRules.cleanTitle(title)).isEqualTo(title)
        }
        assertThat(LastFmLookupRules.cleanTitle("Bad Guy (Remix) (Official Video)")).isEqualTo("Bad Guy (Remix)")
    }

    @Test fun `a title that is only a tag is left alone`() {
        assertThat(LastFmLookupRules.cleanTitle("(Official Video)")).isEqualTo("(Official Video)")
    }

    @Test fun `the YouTube Topic channel suffix is dropped from the artist`() {
        assertThat(LastFmLookupRules.cleanArtist("Billie Eilish - Topic")).isEqualTo("Billie Eilish")
        assertThat(LastFmLookupRules.cleanArtist("Topic")).isEqualTo("Topic")
        assertThat(LastFmLookupRules.cleanArtist("Billie Eilish")).isEqualTo("Billie Eilish")
    }
}

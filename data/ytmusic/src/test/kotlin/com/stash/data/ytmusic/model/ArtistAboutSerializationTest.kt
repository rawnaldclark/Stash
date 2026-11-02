package com.stash.data.ytmusic.model

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class ArtistAboutSerializationTest {
    private val json = Json { ignoreUnknownKeys = true } // mirrors ArtistCache codec

    @Test fun `old blob without about deserializes to null`() {
        val legacy = """{"id":"a1","name":"X","avatarUrl":null,"subscribersText":null,
            "popular":[],"albums":[],"singles":[],"related":[]}""".trimIndent()
        val p = json.decodeFromString<ArtistProfile>(legacy)
        assertThat(p.about).isNull()
    }

    @Test fun `about round-trips`() {
        val about = ArtistAbout(
            bio = "hello",
            socials = listOf(SocialLink("instagram", "https://instagram.com/x")),
            photoUrl = null,
        )
        val p = ArtistProfile("a1", "X", null, null, emptyList(), emptyList(), emptyList(), emptyList(), about)
        val restored = json.decodeFromString<ArtistProfile>(json.encodeToString(ArtistProfile.serializer(), p))
        assertThat(restored.about).isEqualTo(about)
    }

    @Test fun `a profile cached before the monthly listeners decodes with neither count`() {
        // The 10-31 shape: subscribers, an About from Spotify, no monthly audience, no listeners.
        val legacy = """{"id":"a1","name":"X","avatarUrl":null,"subscribersText":"290K",
            "popular":[],"albums":[],"singles":[],"related":[],
            "about":{"bio":"b","socials":[],"photoUrl":null,"gallery":["https://i/1"],"headerUrl":null}}""".trimIndent()
        val p = json.decodeFromString<ArtistProfile>(legacy)
        assertThat(p.monthlyAudience).isNull()
        assertThat(p.about!!.monthlyListeners).isNull()
    }

    @Test fun `both counts round-trip, and the subscriber count stays for older builds`() {
        val p = ArtistProfile(
            "a1", "X", null, "290K", emptyList(), emptyList(), emptyList(), emptyList(),
            about = ArtistAbout(bio = "b", monthlyListeners = 1_734_567L),
            monthlyAudience = 574_000L,
        )
        val blob = json.encodeToString(ArtistProfile.serializer(), p)
        val restored = json.decodeFromString<ArtistProfile>(blob)
        assertThat(restored.monthlyAudience).isEqualTo(574_000L)
        assertThat(restored.about!!.monthlyListeners).isEqualTo(1_734_567L)
        // An older build's model requires subscribersText and has no default for it.
        assertThat(blob).contains("\"subscribersText\":\"290K\"")
        // hasSection is derived, never written.
        assertThat(blob).doesNotContain("hasSection")
    }

    @Test fun `a listener count alone is no About section`() {
        assertThat(ArtistAbout(monthlyListeners = 1_000L).hasSection).isFalse()
        assertThat(ArtistAbout(bio = "b", monthlyListeners = 1_000L).hasSection).isTrue()
        assertThat(ArtistAbout(gallery = listOf("https://i/1")).hasSection).isTrue()
        assertThat(ArtistAbout(headerUrl = "https://i/h").hasSection).isTrue()
    }

    @Test fun `unknown social kind string does not crash decode`() {
        val blob = """{"id":"a1","name":"X","avatarUrl":null,"subscribersText":null,
            "popular":[],"albums":[],"singles":[],"related":[],
            "about":{"bio":null,"socials":[{"kind":"threads","url":"https://t.co/x"}],"photoUrl":null}}""".trimIndent()
        val p = json.decodeFromString<ArtistProfile>(blob)
        assertThat(p.about!!.socials.single().kind).isEqualTo("threads")
    }
}

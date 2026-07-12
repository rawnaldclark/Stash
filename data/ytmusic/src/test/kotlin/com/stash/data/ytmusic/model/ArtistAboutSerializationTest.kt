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

    @Test fun `unknown social kind string does not crash decode`() {
        val blob = """{"id":"a1","name":"X","avatarUrl":null,"subscribersText":null,
            "popular":[],"albums":[],"singles":[],"related":[],
            "about":{"bio":null,"socials":[{"kind":"threads","url":"https://t.co/x"}],"photoUrl":null}}""".trimIndent()
        val p = json.decodeFromString<ArtistProfile>(blob)
        assertThat(p.about!!.socials.single().kind).isEqualTo("threads")
    }
}

package com.stash.core.data.musicbrainz

import com.google.common.truth.Truth.assertThat
import com.stash.data.ytmusic.model.SocialLink
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Test

class MusicBrainzMapperTest {
    private fun rels(s: String): JsonObject = Json.parseToJsonElement(s) as JsonObject

    @Test fun `maps hosts and skips ended`() {
        val socials = mapSocials(rels("""{"relations":[
            {"type":"social network","url":{"resource":"https://instagram.com/a"}},
            {"type":"youtube","url":{"resource":"https://youtube.com/@a"}},
            {"type":"social network","ended":true,"url":{"resource":"https://myspace.com/a"}},
            {"type":"official homepage","url":{"resource":"https://a.com"}}
        ]}"""))
        assertThat(socials).containsExactly(
            SocialLink("instagram", "https://instagram.com/a"),
            SocialLink("youtube", "https://youtube.com/@a"),
            SocialLink("website", "https://a.com"),
        ).inOrder()
    }

    @Test fun `dedupes and drops unknown hosts`() {
        val socials = mapSocials(rels("""{"relations":[
            {"type":"social network","url":{"resource":"https://x.com/a"}},
            {"type":"social network","url":{"resource":"https://x.com/a"}},
            {"type":"streaming","url":{"resource":"https://unknownhost.example/a"}}
        ]}"""))
        assertThat(socials).containsExactly(SocialLink("x", "https://x.com/a"))
    }

    @Test fun `escapes lucene specials`() {
        assertThat(escapeLucene("""Panic! at the "Disco" (2005)"""))
            .isEqualTo("""Panic\! at the \"Disco\" \(2005\)""")
    }
}

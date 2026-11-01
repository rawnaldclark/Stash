package com.stash.data.ytmusic

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/**
 * The like button in a `next` answer, which like mirroring reads to learn whether a song is already liked on YouTube Music
 * before liking it there (a like that was already there is never Stash's to take away).
 */
class LikeStatusParserTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) { "missing fixture $name" }
            .bufferedReader().use { it.readText() }

    @Test fun `a recorded next answer carries the like button's state`() {
        // Recorded signed out, so INDIFFERENT: the reason likeStatusOf never asks without a sign-in
        val root = Json.parseToJsonElement(fixture("link_next_song.json")).jsonObject
        assertThat(likeStatusIn(root)).isEqualTo("INDIFFERENT")
    }

    @Test fun `a liked song reads LIKE`() {
        val liked = fixture("link_next_song.json").replace("\"likeStatus\":\"INDIFFERENT\"", "\"likeStatus\":\"LIKE\"")
            .replace("\"likeStatus\": \"INDIFFERENT\"", "\"likeStatus\": \"LIKE\"")
        assertThat(likeStatusIn(Json.parseToJsonElement(liked).jsonObject)).isEqualTo("LIKE")
    }

    @Test fun `an answer with no like button is null, never a guess`() {
        assertThat(likeStatusIn(JsonObject(emptyMap()))).isNull()
        val noButton = Json.parseToJsonElement("""{"playerOverlays":{"playerOverlayRenderer":{"actions":[{"other":{}}]}}}""").jsonObject
        assertThat(likeStatusIn(noButton)).isNull()
    }
}

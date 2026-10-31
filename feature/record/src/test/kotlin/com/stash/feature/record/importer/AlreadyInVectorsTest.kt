package com.stash.feature.record.importer

import com.google.common.truth.Truth.assertWithMessage
import com.stash.core.data.record.HavePlaylist
import com.stash.core.data.record.SpotifyLibraryImport
import com.stash.core.model.weblink.SongRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.File

/**
 * Owner decision D3, the same on both sides: the preview's "Already in your library" (stash-player importer.ts `alreadyIn`, PR
 * #27) and this port ([SpotifyLibraryImport.alreadyIn]) give the same answer for every case `scripts/spotify-vectors.ts` ran
 * through the web's own function (`already.json`): exactly half and under, odd counts, the identity fold of the names (case,
 * accents, punctuation, a featuring credit, full-width forms; names that fold to nothing), empty playlists, a song twice, an
 * ISRC on the library's side, other artists and version words, two playlists of one name.
 */
class AlreadyInVectorsTest {
    private val cases = Json.parseToJsonElement(
        File(requireNotNull(javaClass.getResource("/spotify-export/vectors/already.json")).toURI()).readText(),
    ).jsonArray.map { it.jsonObject }

    private fun playlist(o: JsonObject) = HavePlaylist(
        o["name"]!!.jsonPrimitive.content,
        o["items"]!!.jsonArray.map { s ->
            s.jsonObject.let { SongRef(it["title"]!!.jsonPrimitive.content, it["artist"]!!.jsonPrimitive.content, it["isrc"]?.jsonPrimitive?.content) }
        },
    )

    @Test fun `every case gives the web's answer`() {
        assertWithMessage("cases").that(cases.size).isAtLeast(20)
        for (c in cases) {
            val name = c["name"]!!.jsonPrimitive.content
            val got = SpotifyLibraryImport.alreadyIn(playlist(c["p"]!!.jsonObject), c["have"]!!.jsonArray.map { playlist(it.jsonObject) })
            assertWithMessage(name).that(got).isEqualTo(c["expect"]!!.jsonPrimitive.boolean)
        }
    }

    @Test fun `both answers come up`() {
        val answers = cases.map { it["expect"]!!.jsonPrimitive.boolean }.toSet()
        assertWithMessage("answers").that(answers).containsExactly(true, false)
    }
}

package com.stash.feature.record.imports

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The word after an import says what the web says, word for word in English (`vectors/words.json`, written by
 * spotify-vectors.ts from stash-player's `src/lib/ui/spotify/years.ts` `doneLine`): the new plays, the playlists and the songs
 * newly liked, as a list ("Brought in 1,234 plays, 3 playlists and 9 liked songs"), an import of playlists or likes alone saying
 * those, and "Your record is up to date" when it brought nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImportWordsTest {
    private val res = ApplicationProvider.getApplicationContext<Context>().resources
    private val words = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/spotify-export/vectors/words.json")).readText()).jsonObject

    @Test fun `the done line, for plays, playlists and likes in every mix`() {
        val cases = words["done"]!!.jsonArray.map { it.jsonObject }
        assertThat(cases.size).isAtLeast(30)
        for (c in cases) {
            val plays = c["plays"]!!.jsonPrimitive.int
            val playlists = c["playlists"]!!.jsonPrimitive.int
            val likes = c["likes"]!!.jsonPrimitive.int
            assertWithMessage("plays $plays, playlists $playlists, likes $likes").that(ImportText.doneLine(res, plays, playlists, likes))
                .isEqualTo(c["line"]!!.jsonPrimitive.content)
        }
    }

    @Test fun `playlists and likes alone are never "up to date"`() {
        assertThat(ImportText.doneLine(res, 0, 2, 4)).isEqualTo("Brought in 2 playlists and 4 liked songs")
    }
}

package com.stash.feature.record.lastfm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.stash.feature.record.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Last.fm in Your record says what the web says, word for word in English (`words.json`, written by lastfm-vectors.ts from
 * stash-player's `src/lib/ui/spotify/lastfm.ts`): the done line for every kind of run, the reconnect line and the hours note.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LastfmWordsTest {
    private val res = ApplicationProvider.getApplicationContext<Context>().resources
    private val words = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/lastfm/words.json")).readText()).jsonObject

    @Test fun `the done line, for a top-up, a whole read, a run carried on, one that found nothing, and part still missing`() {
        val cases = words["done"]!!.jsonArray.map { it.jsonObject }
        assertThat(cases.size).isAtLeast(15)
        assertThat(cases.count { it["missing"]!!.jsonPrimitive.boolean }).isAtLeast(5)
        for (c in cases) {
            val added = c["added"]!!.jsonPrimitive.int
            val total = (c["total"] as? JsonPrimitive)?.takeIf { it.content != "null" }?.int
            val missing = c["missing"]!!.jsonPrimitive.boolean
            assertWithMessage("added $added, total $total, missing $missing").that(LastfmText.doneLine(res, added, total, missing))
                .isEqualTo(c["line"]!!.jsonPrimitive.content)
        }
        assertThat(res.getString(R.string.record_lastfm_done_missing)).isEqualTo(words["missing"]!!.jsonPrimitive.content)
    }

    @Test fun `the sheet's line with Last fm open says where the scrobbles and the lengths come from, as the web's does`() {
        // stash-player ImportFlow.svelte, with Last.fm open (no file there)
        assertThat(res.getString(R.string.record_import_private_lastfm)).isEqualTo("Your scrobbles come from Last.fm. Song lengths are looked up by song name.")
    }

    @Test fun `the reconnect line and the hours note`() {
        assertThat(res.getString(R.string.record_lastfm_reconnect_body)).isEqualTo(words["reconnect"]!!.jsonPrimitive.content)
        assertThat(res.getString(R.string.record_lastfm_hours_note)).isEqualTo(words["hours"]!!.jsonPrimitive.content)
    }

    @Test fun `the idle lines - another account starts over beside what came in, and the same account is named to connect`() {
        val mark = LastfmMark("alice", to = 1, next = 4, pages = 3, done = true, newest = 5, added = 450, covered = 0, at = 1_761_000_000)
        assertThat(LastfmText.idle(res, connected = null, mark = mark).line).isEqualTo("450 scrobbles in from alice. Connect Last.fm as alice to bring in newer ones.")
        assertThat(LastfmText.idle(res, connected = "bob", mark = mark).line).isEqualTo("Bring in everything you’ve scrobbled as bob. The scrobbles already in your record stay.")
        assertThat(LastfmText.idle(res, connected = "bob", mark = null).line).isEqualTo("Bring in everything you’ve scrobbled as bob.")
    }
}

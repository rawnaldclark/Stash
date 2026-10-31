package com.stash.feature.record.taste

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.feature.record.R
import com.stash.feature.record.importer.ImportProgress
import com.stash.feature.record.imports.ImportText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Settings' taste rows and the import's taste step say what the web says, word for word in English (`words.json`, written by
 * taste-vectors.ts from stash-player's `src/routes/settings/RecordInMixes.svelte`, read as the file is, and `years.ts`'s
 * `whyLine` and `progressOf`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TasteWordsTest {
    private val res = ApplicationProvider.getApplicationContext<Context>().resources
    private val words = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/taste/words.json")).readText()).jsonObject
    private fun said(k: String) = words["settings"]!!.jsonObject[k]!!.jsonPrimitive.content

    @Test fun `the switch, the list's lines and its buttons`() {
        assertThat(res.getString(R.string.record_in_mixes_title)).isEqualTo(said("switch"))
        // The app's own line: every Stash Mix takes the record here, not only Daily Discover as on the web
        assertThat(said("switchLine")).startsWith("Daily Discover leans on")
        assertThat(res.getString(R.string.record_in_mixes_subtitle)).isEqualTo(
            "Your mixes, Daily Discover and your Stash Mixes, lean on the artists you’ve loved for years and leave out the songs you skip. Off, they go by what you play here.",
        )
        assertThat(res.getString(R.string.record_kept_out_title, "1,204")).isEqualTo("${said("title")}1,204)")
        assertThat(res.getString(R.string.record_kept_out_on)).isEqualTo(said("on"))
        assertThat(res.getString(R.string.record_kept_out_off)).isEqualTo(said("off"))
        assertThat(res.getString(R.string.record_kept_out_empty)).isEqualTo(said("empty"))
        assertThat(res.getString(R.string.record_kept_out_show)).isEqualTo(said("show"))
        assertThat(res.getString(R.string.record_kept_out_hide)).isEqualTo(said("hide"))
        assertThat(res.getString(R.string.record_kept_out_more)).isEqualTo(said("more"))
        assertThat(res.getString(R.string.record_kept_out_put_back)).isEqualTo(said("putBack"))
        assertThat(res.getString(R.string.record_kept_out_putting_back)).isEqualTo(said("putting"))
        assertThat(res.getString(R.string.record_kept_out_put_back_label, "Ivy", "Frank Ocean"))
            .isEqualTo(said("label").replace("{s.title}", "Ivy").replace("{s.artist}", "Frank Ocean"))
        assertThat(res.getString(R.string.record_kept_out_put_back_done, "Ivy")).isEqualTo(said("done").replace("\${s.title}", "Ivy"))
        assertThat(res.getString(R.string.record_kept_out_put_back_failed)).isEqualTo(said("failed"))
    }

    @Test fun `why each song is kept out`() {
        val why = words["why"]!!.jsonObject
        assertThat(whyLine(res, KeptOutWhy.Hidden, Locale.US)).isEqualTo(why["hidden"]!!.jsonPrimitive.content)
        assertThat(whyLine(res, KeptOutWhy.Interlude, Locale.US)).isEqualTo(why["interlude"]!!.jsonPrimitive.content)
        for (c in why["skips"]!!.jsonArray.map { it.jsonObject }) {
            val w = KeptOutWhy.Skips(c["skips"]!!.jsonPrimitive.int, c["starts"]!!.jsonPrimitive.int)
            assertThat(whyLine(res, w, Locale.US)).isEqualTo(c["line"]!!.jsonPrimitive.content)
        }
    }

    @Test fun `the import sheet's taste step`() {
        assertThat(ImportText.progressLine(res, ImportProgress.Taste)).isEqualTo(words["step"]!!.jsonPrimitive.content)
        assertThat(ImportText.part(ImportProgress.Taste)).isNull()
    }
}

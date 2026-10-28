package com.stash.feature.record.mixes

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Every language the record speaks makes every word of the mixes without a format error (an argument of the wrong kind, or one
 * too few), and says it in its own words: the titles aren't the English left in place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MixWordsLocalesTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val tags = listOf("en-US", "ar-SA", "de-DE", "es-ES", "fa-IR", "fil-PH", "fr-FR", "hi-IN", "ko-KR", "mr-IN", "pl-PL", "pt-BR", "ru-RU", "zh-CN")

    private fun words(tag: String): ResourceMixWords {
        val c = Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return ResourceMixWords(app.createConfigurationContext(c).resources)
    }

    /** Every word with every count that picks another plural form somewhere (0, 1, 2, 3, 5, 11, 21, 22, 100). */
    private fun all(w: MixWords): List<String> {
        val counts = listOf(0, 1, 2, 3, 5, 11, 21, 22, 100)
        return listOf(
            w.monthYear(1_656_633_600_000), w.span("June 2022", "March 2023"), w.early(2018), w.mid(2018), w.late(2018), w.dot("A", "B"),
            w.and("A, B", "C"), w.comma, w.constantsTitle, w.constantsSub, w.lostTitle, w.lostSub, w.repeatTitle, w.repeatSub, w.startedTitle,
            w.startedSub, w.year(2019), w.yearSoFar(2026), w.artistTitle("Radiohead"), w.artistSub(2009), w.weekTitle, w.weekOneYear(9, 28, 2019),
            w.weekOneYear(1, 29, 2024), w.about(5), w.about(60), w.about(170), w.cardWith("A & B"), w.cardShare(34), w.cardConstants, w.cardRepeat,
            w.cardWeek, w.cardYearNow, w.cardYear, w.cardStarted, w.cardFromTo("Radiohead", 2009, "Mitski", 2021), w.cardLost, w.number(2019),
        ) + counts.flatMap { n ->
            listOf(w.weekYears(9, 28, n), w.songs(n), w.cardUpToYears("Sub", n), w.cardObsession("Ivy", n, "June 2022"), w.cardHours(n.toLong()))
        }
    }

    @Test fun `every language makes every word`() {
        val english = all(words("en-US"))
        for (tag in tags) {
            val said = all(words(tag))
            assertWithMessage(tag).that(said.none { it.isEmpty() }).isTrue()
            if (tag != "en-US") {
                // the mixes' own titles are translated
                val w = words(tag)
                for (title in listOf(w.constantsTitle, w.lostTitle, w.repeatTitle, w.startedTitle, w.weekTitle)) {
                    assertWithMessage("$tag: $title").that(title).isNotIn(english)
                }
            }
        }
    }

    @Test fun `the day of the year is the language's own, and 29 February is one`() {
        assertWithMessage("en").that(words("en-GB").weekOneYear(9, 28, 2019)).isEqualTo("Around October 28, 2019")
        assertWithMessage("ru").that(words("ru-RU").weekOneYear(9, 28, 2019)).contains("28 октября")
        assertWithMessage("pl").that(words("pl-PL").weekOneYear(9, 28, 2019)).contains("28 października")
        assertWithMessage("de").that(words("de-DE").weekOneYear(9, 28, 2019)).contains("28. Oktober")
        assertWithMessage("zh").that(words("zh-CN").weekOneYear(9, 28, 2019)).contains("10月28日")
        assertWithMessage("29 Feb").that(words("en-US").weekOneYear(1, 29, 2024)).isEqualTo("Around February 29, 2024")
    }
}

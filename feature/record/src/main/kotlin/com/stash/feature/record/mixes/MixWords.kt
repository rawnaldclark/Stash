package com.stash.feature.record.mixes

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import android.text.format.DateFormat
import com.stash.feature.record.R
import com.stash.feature.record.card.RecordCard
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.util.Locale

/**
 * The words of the history mixes (stash-player `src/lib/spotify/mixes.ts`, `src/lib/ui/spotify/mix.ts` `card`, `aboutLength`,
 * `years.ts`): titles, lines, lengths and the Mixes tab's cards. [ResourceMixWords] reads them from the app's strings, whose
 * English is the web's character for character (’ · –; `HistoryMixesVectorsTest` holds them to it).
 */
interface MixWords {
    /** years.ts `monthYear`: "June 2022", in UTC. */
    fun monthYear(t: Long): String

    /** An era across years: "June 2022 – March 2023". */
    fun span(a: String, b: String): String
    fun early(year: Int): String
    fun mid(year: Int): String
    fun late(year: Int): String

    /** "Radiohead · Late 2016". */
    fun dot(a: String, b: String): String

    /** andList's last join, "A & B", and the comma before it. */
    fun and(a: String, b: String): String
    val comma: String

    val constantsTitle: String
    val constantsSub: String
    val lostTitle: String
    val lostSub: String
    val repeatTitle: String
    val repeatSub: String
    val startedTitle: String
    val startedSub: String
    fun year(year: Int): String
    fun yearSoFar(year: Int): String
    fun artistTitle(name: String): String
    fun artistSub(since: Int): String
    val weekTitle: String

    /** [month0] 0..11, [day] of the month (UTC). */
    fun weekOneYear(month0: Int, day: Int, year: Int): String
    fun weekYears(month0: Int, day: Int, years: Int): String

    /** aboutLength: "about 45 min", "about 2 hr 50 min", "about 3 hr". */
    fun about(minutes: Int): String

    /** format.ts `songs`: "1 song", "12 songs". */
    fun songs(n: Int): String

    // ── the Mixes tab's cards (mix.ts `card`) ──
    fun cardWith(list: String): String
    fun cardShare(percent: Int): String
    val cardConstants: String
    fun cardUpToYears(sub: String, years: Int): String
    val cardRepeat: String
    fun cardObsession(title: String, plays: Int, monthYear: String): String
    val cardWeek: String
    val cardYearNow: String
    val cardYear: String
    fun cardHours(hours: Long): String
    val cardStarted: String
    fun cardFromTo(a: String, aYear: Int, b: String, bYear: Int): String
    val cardLost: String

    /** A year as a card's big words ("2019"). */
    fun number(n: Int): String
}

/**
 * [MixWords] from the app's strings, in the language of [context] (the screen's: the app's own language setting reaches the
 * Activity). Every date, number and plural is written in the language the strings are in (`record_mix_locale`), never in a
 * device language the app has no strings for: on an Italian or Bengali phone the English falls back whole, months and digits
 * included.
 */
class ResourceMixWords(context: Context) : MixWords {
    /** The language of the strings in use. */
    private val locale: Locale = Locale.forLanguageTag(context.resources.getString(R.string.record_mix_locale))

    /** The strings, formatted and pluralised in [locale] (the same strings: [locale] names the folder they come from). */
    private val res: Resources = context.resources.let { base ->
        val strings = locale // (inside Configuration's apply, a bare `locale` would be its own, the device's)
        if (base.configuration.locales[0] == strings) {
            base
        } else {
            context.createConfigurationContext(Configuration(base.configuration).apply { setLocales(LocaleList(strings)) }).resources
        }
    }

    private fun s(id: Int) = res.getString(id)

    /** A string with its numbers and words put in, in [locale] (what getString does, in its configuration's locale). */
    private fun s(id: Int, vararg args: Any) = String.format(locale, res.getString(id), *args)

    /** A plural, its form picked by [res] (the strings' language's rules) and filled in [locale]. */
    private fun q(id: Int, n: Int, vararg args: Any) = String.format(locale, res.getQuantityString(id, n), *args)

    /** The language's own digits for every number of a line (a year, a day, a count), never two scripts in one line. */
    private val digits: DecimalStyle = DecimalStyle.of(locale)

    /** years.ts `monthYear`, "June 2022" (the import's own form), in UTC, with the language's digits. */
    override fun monthYear(t: Long): String =
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMMyyyy"), locale).withDecimalStyle(digits)
            .format(Instant.ofEpochMilli(t).atZone(ZoneOffset.UTC))
    override fun span(a: String, b: String) = s(R.string.record_mix_span, a, b)
    override fun early(year: Int) = s(R.string.record_mix_early, year)
    override fun mid(year: Int) = s(R.string.record_mix_mid, year)
    override fun late(year: Int) = s(R.string.record_mix_late, year)
    override fun dot(a: String, b: String) = s(R.string.record_mix_dot, a, b)
    override fun and(a: String, b: String) = s(R.string.record_mix_and, a, b)
    override val comma: String get() = s(R.string.record_mix_comma)
    override val constantsTitle: String get() = s(R.string.record_mix_constants)
    override val constantsSub: String get() = s(R.string.record_mix_constants_sub)
    override val lostTitle: String get() = s(R.string.record_mix_lost)
    override val lostSub: String get() = s(R.string.record_mix_lost_sub)
    override val repeatTitle: String get() = s(R.string.record_mix_repeat)
    override val repeatSub: String get() = s(R.string.record_mix_repeat_sub)
    override val startedTitle: String get() = s(R.string.record_mix_started)
    override val startedSub: String get() = s(R.string.record_mix_started_sub)
    override fun year(year: Int) = s(R.string.record_mix_year, year)
    override fun yearSoFar(year: Int) = s(R.string.record_mix_year_so_far, year)
    override fun artistTitle(name: String) = s(R.string.record_mix_artist, name)
    override fun artistSub(since: Int) = s(R.string.record_mix_artist_sub, since)
    override val weekTitle: String get() = s(R.string.record_mix_week)

    /**
     * A day of the year as the language writes it: "October 28" in English (the web's, whatever the English region), "28.
     * Oktober", "28 октября", "10月28日" (the locale's own month and day, `MMMMd`). Read on a leap year, so 29 February is one.
     */
    private fun monthDay(month0: Int, day: Int): String {
        val pattern = if (locale.language == "en") "MMMM d" else DateFormat.getBestDateTimePattern(locale, "MMMMd")
        return DateTimeFormatter.ofPattern(pattern, locale).withDecimalStyle(digits).format(LocalDate.of(2000, month0 + 1, day))
    }

    override fun weekOneYear(month0: Int, day: Int, year: Int) = s(R.string.record_mix_week_one_year, monthDay(month0, day), year)
    override fun weekYears(month0: Int, day: Int, years: Int) =
        q(R.plurals.record_mix_week_years, years, monthDay(month0, day), years)

    override fun about(minutes: Int): String = when {
        minutes < 60 -> s(R.string.record_mix_about_min, minutes)
        minutes % 60 == 0 -> s(R.string.record_mix_about_hr, minutes / 60)
        else -> s(R.string.record_mix_about_hr_min, minutes / 60, minutes % 60)
    }

    override fun songs(n: Int) = q(R.plurals.record_mix_songs, n, n)
    override fun cardWith(list: String) = s(R.string.record_mix_card_with, list)
    override fun cardShare(percent: Int) = s(R.string.record_mix_card_share, percent)
    override val cardConstants: String get() = s(R.string.record_mix_card_constants)
    override fun cardUpToYears(sub: String, years: Int) = q(R.plurals.record_mix_card_up_to_years, years, sub, years)
    override val cardRepeat: String get() = s(R.string.record_mix_card_repeat)
    override fun cardObsession(title: String, plays: Int, monthYear: String) =
        q(R.plurals.record_mix_card_obsession, plays, title, plays, monthYear)
    override val cardWeek: String get() = s(R.string.record_mix_card_week)
    override val cardYearNow: String get() = s(R.string.record_mix_card_year_now)
    override val cardYear: String get() = s(R.string.record_mix_card_year)
    override fun cardHours(hours: Long) =
        q(R.plurals.record_mix_card_hours, hours.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), RecordCard.count(hours, locale))
    override val cardStarted: String get() = s(R.string.record_mix_card_started)
    override fun cardFromTo(a: String, aYear: Int, b: String, bYear: Int) = s(R.string.record_mix_card_from_to, a, aYear, b, bYear)
    override val cardLost: String get() = s(R.string.record_mix_card_lost)
    override fun number(n: Int): String = String.format(locale, "%d", n)
}

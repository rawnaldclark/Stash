package com.stash.feature.record.imports

import android.content.res.Resources
import android.text.format.DateFormat
import com.stash.feature.record.R
import com.stash.feature.record.card.RecordCard
import com.stash.feature.record.importer.ImportProgress
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The import in words (stash-player `src/lib/ui/spotify/years.ts`), for the sheet, the Home card and the notification. */
internal object ImportText {
    private fun locale(res: Resources): Locale = res.configuration.locales[0] ?: Locale.getDefault()

    /**
     * years.ts `progressOf`'s line: opening, reading (the file being read, of all), saving (plays written, of all), then the
     * playlists and liked songs (their songs matched, of all), then the taste. While a file is read it is counted, so the first one
     * reads "1 of 7" (the web counts files once they are done).
     */
    fun progressLine(res: Resources, p: ImportProgress): String = when (p) {
        ImportProgress.Open -> res.getString(R.string.record_import_opening)
        is ImportProgress.Read ->
            if (p.total > 1) res.getString(R.string.record_import_reading, minOf(p.done + 1, p.total), p.total)
            else res.getString(R.string.record_import_reading_one)
        is ImportProgress.Write ->
            if (p.total > 0) res.getString(R.string.record_import_saving, count(res, p.done.toLong()), count(res, p.total.toLong()))
            else res.getString(R.string.record_import_saving_start)
        is ImportProgress.Library ->
            if (p.total > 0) res.getString(R.string.record_import_library, count(res, p.done.toLong()), count(res, p.total.toLong()))
            else res.getString(R.string.record_import_library_start)
        ImportProgress.Taste -> res.getString(R.string.record_import_taste)
    }

    /** How far along, 0 to 1 (bytes read, plays written, or the library's songs matched); null when there is no measure yet. */
    fun part(p: ImportProgress): Float? = when (p) {
        ImportProgress.Open -> null
        is ImportProgress.Read -> p.part.takeIf { p.total > 0 }
        is ImportProgress.Write -> if (p.total > 0) p.done.toFloat() / p.total else null
        is ImportProgress.Library -> if (p.total > 0) p.done.toFloat() / p.total else null
        ImportProgress.Taste -> null
    }

    /** years.ts `count`: "48,210" in the app's language. */
    fun count(res: Resources, n: Long): String = RecordCard.count(n, locale(res))

    /** years.ts `shortDate`: "Oct 21", or "Oct 21, 2025" in another year, in the phone's calendar. */
    fun shortDate(res: Resources, t: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
        val l = locale(res)
        val day = Instant.ofEpochMilli(t).atZone(zone)
        val sameYear = day.year == Instant.ofEpochMilli(now).atZone(zone).year
        return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(l, if (sameYear) "MMMd" else "yMMMd"), l).format(day)
    }

    /** years.ts `monthYear`: "June 2022", read in UTC as the taste and its eras are. */
    fun monthYear(res: Resources, t: Long): String {
        val l = locale(res)
        return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(l, "MMMMyyyy"), l).format(Instant.ofEpochMilli(t).atZone(ZoneOffset.UTC))
    }

    /** The preview's span: "January 2021 to January 2025", or the one month. */
    fun span(res: Resources, first: Long, last: Long): String? {
        if (first <= 0 || last <= 0) return null
        val a = monthYear(res, first)
        val b = monthYear(res, last)
        return if (a == b) a else res.getString(R.string.record_preview_span, a, b)
    }

    /**
     * years.ts `doneLine`: what this import brought, "Brought in 6,086 plays, 2 playlists and 9 liked songs" (the new listens, the
     * playlists chosen, the songs newly liked; an import of playlists alone says those), or "Your record is up to date".
     */
    fun doneLine(res: Resources, listens: Int, playlists: Int = 0, likes: Int = 0): String {
        val parts = listOfNotNull(
            listens.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.record_done_plays, it, count(res, it.toLong())) },
            playlists.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.record_done_playlists, it, count(res, it.toLong())) },
            likes.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.record_done_likes, it, count(res, it.toLong())) },
        )
        // years.ts `names`: "a", "a and b", "a, b and c"
        val list = when (parts.size) {
            0 -> return res.getString(R.string.record_done_nothing)
            1 -> parts[0]
            2 -> res.getString(R.string.record_done_two, parts[0], parts[1])
            else -> res.getString(R.string.record_done_three, parts[0], parts[1], parts[2])
        }
        return res.getString(R.string.record_done_brought, list)
    }
}

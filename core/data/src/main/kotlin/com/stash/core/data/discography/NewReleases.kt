package com.stash.core.data.discography

import java.time.LocalDate

/**
 * How recent a release has to be for the artist page to call it new: out within the last this
 * many days, today included. The page's Latest releases row shows only such releases, and is
 * absent when the artist has none. Change it here; the row and the Qobuz merge both read it.
 */
const val NEW_RELEASE_DAYS: Long = 60

/**
 * The years a release out within [days] of [today] can carry: this year, and last year too in a
 * year's first weeks. YouTube dates a release by its year alone, so a release from any other year
 * can't be new, and no date is needed for it.
 */
fun newReleaseYears(today: LocalDate, days: Long = NEW_RELEASE_DAYS): IntRange =
    today.minusDays(days).year..today.year

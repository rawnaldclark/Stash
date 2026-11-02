package com.stash.feature.settings.components

import com.stash.core.data.tipjar.DonationGoal
import com.stash.feature.settings.SupporterRow
import java.text.NumberFormat
import java.util.Locale

/**
 * What Settings' Support card shows ([SupportBanner]): the month's goal line when the site gave one, and what the
 * card says about the supporter perk. Pure, built by [supportCardState], so every state is tested without a screen.
 */
data class SupportCardState(
    /** "$168 of $200 this month" and its bar, or null to leave the line out (no number, or not this month's). */
    val goal: GoalLine? = null,
    val perk: SupportPerk = SupportPerk.None,
)

/** The goal line: "[raised] of [target] this month", its bar [fraction] full (0..1). */
data class GoalLine(val raised: String, val target: String, val fraction: Float)

/** What the card says about the supporter perk. */
enum class SupportPerk {
    /** No owner relay in this build: there is no unlimited lossless to promise, so the card doesn't. */
    None,

    /** "Supporters get unlimited Tidal & Qobuz lossless", tapping through to the claim (Lossless sources). */
    Offer,

    /** This phone is a supporter's: the card thanks them and shows their status instead of the ask. */
    Supporter,
}

/** The card for the supporter state [row] (the same one Lossless sources shows) and this month's [goal]. */
internal fun supportCardState(row: SupporterRow, goal: DonationGoal?): SupportCardState = SupportCardState(
    goal = goal?.let(::goalLine),
    perk = when (row) {
        SupporterRow.Hidden -> SupportPerk.None
        is SupporterRow.Offer -> SupportPerk.Offer
        is SupporterRow.Supporter -> SupportPerk.Supporter
    },
)

/** [goal] as its line: whole dollars as the site writes them, and the bar's fill. */
internal fun goalLine(goal: DonationGoal): GoalLine =
    GoalLine(wholeDollars(goal.raisedCents), wholeDollars(goal.goalCents), goalFraction(goal.raisedCents, goal.goalCents))

/**
 * Whole dollars, rounded down and grouped the way the site writes them (web/worker/goal.js `wholeDollars`), so the
 * card never says more came in than did and always matches the front page: 16899 -> "$168", 123456 -> "$1,234".
 */
internal fun wholeDollars(cents: Long): String =
    "$" + NumberFormat.getIntegerInstance(Locale.US).format(maxOf(0L, cents) / 100)

/** The thinnest fill once anything came in, so a started month never reads as empty (the site's bar keeps a 12 px minimum). */
internal const val MIN_GOAL_FILL = 0.04f

/** The bar's fill for [raisedCents] of [goalCents]: 0 with nothing in, at least [MIN_GOAL_FILL] once started, full at the target and past it. */
internal fun goalFraction(raisedCents: Long, goalCents: Long): Float = when {
    goalCents <= 0L || raisedCents <= 0L -> 0f
    else -> (raisedCents.toDouble() / goalCents).toFloat().coerceIn(MIN_GOAL_FILL, 1f)
}

package com.stash.feature.settings.components

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.tipjar.DonationGoal
import com.stash.feature.settings.SupporterRow
import org.junit.Test

/** Settings' Support card: the supporter state and the month's goal as what the card shows ([supportCardState]). */
class SupportCardStateTest {
    private val november = DonationGoal(raisedCents = 16_800, goalCents = 20_000, month = "2026-11")

    @Test fun `someone who could claim the perk sees the ask, the perk and the goal`() {
        assertThat(supportCardState(SupporterRow.Offer(hasToken = false), november))
            .isEqualTo(SupportCardState(GoalLine("$168", "$200", 0.84f), SupportPerk.Offer))
        // A stored code the relays no longer honour is still an offer, not a supporter.
        assertThat(supportCardState(SupporterRow.Offer(hasToken = true), november).perk).isEqualTo(SupportPerk.Offer)
    }

    @Test fun `a supporter is thanked instead of asked, and still sees the month's goal`() {
        val card = supportCardState(SupporterRow.Supporter(hasToken = true), november)
        assertThat(card.perk).isEqualTo(SupportPerk.Supporter)
        assertThat(card.goal).isEqualTo(GoalLine("$168", "$200", 0.84f))
        // Supporter by the relays' word, the code kept on another phone: the same thanks.
        assertThat(supportCardState(SupporterRow.Supporter(hasToken = false), null).perk).isEqualTo(SupportPerk.Supporter)
    }

    @Test fun `a build without an owner relay promises no perk`() {
        assertThat(supportCardState(SupporterRow.Hidden, november).perk).isEqualTo(SupportPerk.None)
    }

    @Test fun `no goal from the site, no goal line`() {
        assertThat(supportCardState(SupporterRow.Offer(false), null)).isEqualTo(SupportCardState(goal = null, perk = SupportPerk.Offer))
        assertThat(supportCardState(SupporterRow.Hidden, null)).isEqualTo(SupportCardState())
    }

    @Test fun `dollars are whole, rounded down and grouped like the site's`() {
        assertThat(wholeDollars(16_899)).isEqualTo("$168")
        assertThat(wholeDollars(20_000)).isEqualTo("$200")
        assertThat(wholeDollars(123_456)).isEqualTo("$1,234")
        assertThat(wholeDollars(99)).isEqualTo("$0")
        assertThat(wholeDollars(0)).isEqualTo("$0")
        assertThat(wholeDollars(-500)).isEqualTo("$0")
    }

    @Test fun `the bar fills by the share raised, shows a sliver once started and stops at full`() {
        assertThat(goalFraction(0, 20_000)).isEqualTo(0f)
        assertThat(goalFraction(16_800, 20_000)).isEqualTo(0.84f)
        assertThat(goalFraction(100, 20_000)).isEqualTo(MIN_GOAL_FILL)
        assertThat(goalFraction(20_000, 20_000)).isEqualTo(1f)
        assertThat(goalFraction(35_000, 20_000)).isEqualTo(1f)
        assertThat(goalFraction(100, 0)).isEqualTo(0f)
        assertThat(goalFraction(-100, 20_000)).isEqualTo(0f)
    }

    @Test fun `a goal past the target reads as more than the target, with a full bar`() {
        val line = goalLine(DonationGoal(25_050, 20_000, "2026-11"))
        assertThat(line).isEqualTo(GoalLine("$250", "$200", 1f))
    }
}

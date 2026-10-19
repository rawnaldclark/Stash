package com.stash.core.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The give-up order for a crowded track row: labels → dots, then the Hi-Res
 * qualifier, then the pill. The title is never squeezed below its fair share
 * while there's decoration left to give up.
 *
 * Production rows never show source names (owner 2026-10-19), so they pass no
 * label width: the first step is skipped and the pill is what gives way. The
 * label cases below keep the pure function's general behaviour.
 *
 * Widths are in dp-like units: the decision is unit-agnostic.
 */
class TrackRowFitTest {

    // Rough widths at 1.0 font scale, from the "Nude" row on a 393 dp Pixel 5.
    private val labelsBoth = 2 * 3 + 38 + 45   // "Spotify" + "YouTube" over dots alone
    private val labelsYouTube = 3 + 45
    private val badgeHiRes = 12 + 58           // "FLAC 24/44"
    private val badgeCompact = 12 + 26         // "FLAC"
    private val gap = 4
    private val min = 80

    private fun fit(
        column: Int,
        labelsExtra: Int = labelsBoth,
        badgeFull: Int = badgeHiRes,
        badgeSmall: Int = badgeCompact,
        title: Int = 300,
    ) = decideTrackRowFit(
        titleColumnWidth = column,
        sourceLabelsExtra = labelsExtra,
        badgeFullWidth = badgeFull,
        badgeCompactWidth = badgeSmall,
        badgeGap = gap,
        titleNaturalWidth = title,
        titleMinWidth = min,
    )

    @Test
    fun `roomy row keeps everything as designed`() {
        assertThat(fit(column = 200, labelsExtra = 0)).isEqualTo(TrackRowFitDefault)
    }

    @Test
    fun `roomy row with label widths passed shows the labels`() {
        assertThat(fit(column = 200)).isEqualTo(TrackRowFit(showSourceLabels = true, badge = BadgeFit.FULL))
    }

    @Test
    fun `rows never show source names - dots only with the full pill when the title has its share`() {
        // Dots only, no label width: 170 - 74 = 96 ≥ 80, so the Hi-Res pill keeps its room.
        assertThat(fit(column = 170, labelsExtra = 0)).isEqualTo(TrackRowFitDefault)
        assertThat(TrackRowFitDefault.showSourceLabels).isFalse()
        assertThat(TrackRowFitDefault.badge).isEqualTo(BadgeFit.FULL)
    }

    @Test
    fun `the Nude row - two labels and a Hi-Res pill - collapses labels to dots first`() {
        // 393 dp: the title column got ~68 with labels, the pill took 70 → "…".
        val result = fit(column = 68, title = 38)  // labels passed; the dots step frees 89
        assertThat(result).isEqualTo(TrackRowFit(showSourceLabels = false, badge = BadgeFit.FULL))
    }

    @Test
    fun `a short title that already fits never costs the row its labels`() {
        // 120 - 70 - 4 = 46 ≥ 38 ("Nude"), so the labels-as-designed step wins.
        assertThat(fit(column = 120, title = 38))
            .isEqualTo(TrackRowFit(showSourceLabels = true, badge = BadgeFit.FULL))
    }

    @Test
    fun `long title gets at least its fair minimum share`() {
        // With labels: 120 - 74 = 46 < 80 → dots: 209 - 74 = 135 ≥ 80.
        assertThat(fit(column = 120)).isEqualTo(
            TrackRowFit(showSourceLabels = false, badge = BadgeFit.FULL),
        )
    }

    @Test
    fun `very narrow row drops the Hi-Res qualifier before hiding the pill`() {
        // Dots: 40 + 89 = 129. Full: 129 - 74 = 55 < 80. Compact: 129 - 42 = 87 ≥ 80.
        assertThat(fit(column = 40)).isEqualTo(
            TrackRowFit(showSourceLabels = false, badge = BadgeFit.COMPACT),
        )
    }

    @Test
    fun `hides the pill only when even the compact one would squeeze the title`() {
        // Dots: 0 + 89 = 89. Compact: 89 - 42 = 47 < 80. Hidden: 89 ≥ 80.
        assertThat(fit(column = 0)).isEqualTo(
            TrackRowFit(showSourceLabels = false, badge = BadgeFit.HIDDEN),
        )
    }

    @Test
    fun `nothing left to give up returns the most compact layout`() {
        assertThat(fit(column = -500)).isEqualTo(
            TrackRowFit(showSourceLabels = false, badge = BadgeFit.HIDDEN),
        )
    }

    @Test
    fun `when a full fair share fits, every title width gets the default (title measuring is skipped)`() {
        // TrackListItem measures the title only when this shortcut fails; it must never change the answer.
        // Checked with the row's real input (no label width).
        for (column in listOf(0, 40, 68, 120, 161, 200, 400)) {
            if (fit(column = column, title = min, labelsExtra = 0) != TrackRowFitDefault) continue
            for (title in listOf(0, 10, 38, 79, 80, 81, 300)) {
                assertThat(fit(column = column, title = title, labelsExtra = 0)).isEqualTo(TrackRowFitDefault)
            }
        }
    }

    @Test
    fun `no pill - only the labels can step aside`() {
        assertThat(fit(column = 60, badgeFull = 0, badgeSmall = 0)).isEqualTo(
            TrackRowFit(showSourceLabels = false, badge = BadgeFit.FULL),
        )
        // Even when it still doesn't fit, a pill-less row never reports a pill change.
        assertThat(fit(column = -200, badgeFull = 0, badgeSmall = 0)).isEqualTo(
            TrackRowFit(showSourceLabels = false, badge = BadgeFit.FULL),
        )
    }

    @Test
    fun `no labels - the dots-only step is skipped and the pill steps aside next`() {
        // Nothing to collapse, so the dots-only step is skipped and compact is next. (Every real source
        // has a label, so this is the pure rule's edge, not a production row.)
        assertThat(fit(column = 130, labelsExtra = 0)).isEqualTo(
            TrackRowFit(showSourceLabels = false, badge = BadgeFit.COMPACT),
        )
    }

    @Test
    fun `no labels and no pill - always the default`() {
        assertThat(fit(column = 10, labelsExtra = 0, badgeFull = 0, badgeSmall = 0))
            .isEqualTo(TrackRowFitDefault)
    }

    @Test
    fun `one YouTube label and a Hi-Res pill keep the label when the title has its share`() {
        // ~393 dp: column with one label ≈ 161; 161 - 74 = 87 ≥ 80. Only reachable when a caller passes label widths.
        assertThat(fit(column = 161, labelsExtra = labelsYouTube))
            .isEqualTo(TrackRowFit(showSourceLabels = true, badge = BadgeFit.FULL))
    }
}

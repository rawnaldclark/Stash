package com.stash.core.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The give-up order for a crowded track row: labels → dots, then the Hi-Res
 * qualifier, then the pill. The title is never squeezed below its fair share
 * while there's decoration left to give up.
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
        assertThat(fit(column = 200)).isEqualTo(TrackRowFitDefault)
    }

    @Test
    fun `the Nude row - two labels and a Hi-Res pill - collapses labels to dots first`() {
        // 393 dp: the title column got ~68 with labels, the pill took 70 → "…".
        val result = fit(column = 68, title = 38)
        assertThat(result).isEqualTo(TrackRowFit(showSourceLabels = false, badge = BadgeFit.FULL))
    }

    @Test
    fun `a short title that already fits never costs the row its labels`() {
        // 120 - 70 - 4 = 46 ≥ 38 ("Nude").
        assertThat(fit(column = 120, title = 38)).isEqualTo(TrackRowFitDefault)
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
        // ~393 dp: column with one label ≈ 161; 161 - 74 = 87 ≥ 80.
        assertThat(fit(column = 161, labelsExtra = labelsYouTube)).isEqualTo(TrackRowFitDefault)
    }
}

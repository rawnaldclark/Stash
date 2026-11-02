package com.stash.core.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertThat
import com.stash.core.ui.theme.StashElevatedSurface
import com.stash.core.ui.theme.StashElevatedSurfaceLight
import com.stash.core.ui.theme.StashTextSecondary
import org.junit.Test

/** A sheet's window bars follow its own surface, on the dark and the light theme. */
@OptIn(ExperimentalMaterial3Api::class)
class SheetBarsTest {

    @Test fun `the dark elevated surface takes dark bars`() {
        assertThat(lightBarsOn(StashElevatedSurface)).isFalse()
        assertThat(sheetBarsFollow(StashElevatedSurface))
            .isEqualTo(ModalBottomSheetProperties(isAppearanceLightStatusBars = false, isAppearanceLightNavigationBars = false))
    }

    @Test fun `the light elevated surface takes light bars`() {
        assertThat(lightBarsOn(StashElevatedSurfaceLight)).isTrue()
        assertThat(sheetBarsFollow(StashElevatedSurfaceLight))
            .isEqualTo(ModalBottomSheetProperties(isAppearanceLightStatusBars = true, isAppearanceLightNavigationBars = true))
    }

    @Test fun `Material's own pick for that sheet, its onSurfaceVariant grey, counts as dark`() {
        // Why the sheets asked for light bars: Material reads a content colour at or under 0.5
        // luminance as dark, and the dark theme's onSurfaceVariant is one.
        assertThat(StashTextSecondary.luminance()).isAtMost(0.5f)
    }
}

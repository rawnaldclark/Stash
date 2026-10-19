package com.stash.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.stash.core.ui.ambient.Ground
import com.stash.core.ui.ambient.brandRoles
import com.stash.core.ui.ambient.rememberReducedMotion
import com.stash.core.ui.components.DiscoverHeroCoverShape
import com.stash.core.ui.components.DiscoverHeroCoverSize
import com.stash.core.ui.components.DiscoverHeroFrame
import com.stash.core.ui.components.DiscoverHeroText
import com.stash.core.ui.components.ShimmerPlaceholder
import com.stash.core.ui.theme.StashTheme

/**
 * Holds the Discover hero's slot while Daily Discover is still being built
 * ([HomeUiState.heroBuilding]), so a fresh install's Home says what is coming
 * instead of showing nothing where the hero will be. The hero's own frame
 * (`DiscoverHeroFrame`) in the brand's colour, without art: a shimmer where
 * the cover will go, then the eyebrow, title and a line on what to expect.
 * The real hero replaces it in place when the first tracks land.
 *
 * Not clickable: there is nothing to open or play yet. TalkBack reads it as
 * one item. The shimmer holds still with animations turned off.
 */
@Composable
internal fun DailyDiscoverBuildingCard(modifier: Modifier = Modifier) {
    val reducedMotion = rememberReducedMotion()
    DiscoverHeroFrame(
        roles = brandRoles(Ground.DARK),
        backdrop = null,
        modifier = modifier.semantics(mergeDescendants = true) {},
    ) { roles ->
        // The cover's slot on the elevated surface (so it shows on the light theme too, where the
        // white shimmer alone would vanish), shimmering unless animations are off.
        Box(
            Modifier
                .size(DiscoverHeroCoverSize)
                .clip(DiscoverHeroCoverShape)
                .background(StashTheme.extendedColors.elevatedSurface),
        ) {
            if (!reducedMotion) ShimmerPlaceholder(modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(14.dp))
        DiscoverHeroText(
            eyebrow = "Updated daily",
            title = "Building your Daily Discover…",
            body = "Fresh finds from what you listen to.",
            roles = roles,
            modifier = Modifier.weight(1f),
        )
    }
}

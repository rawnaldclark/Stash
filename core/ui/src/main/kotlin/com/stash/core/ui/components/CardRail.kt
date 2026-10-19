package com.stash.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The canonical Home horizontal rail: Spacer + [SectionHeader] + a [LazyRow]
 * of cards. Matches the existing discovery-row rhythm (contentPadding 20dp,
 * inter-card gap 12dp) so every rail on Home reads as one system.
 */
@Composable
fun CardRail(
    title: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onActionClick: (() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    Column(modifier) {
        Spacer(Modifier.height(16.dp))
        SectionHeader(title = title, actionText = actionText, onActionClick = onActionClick)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/**
 * The cover (and width) of a Home rail card: the mix and playlist cards, Liked Songs and Recently
 * played, so every card rail on Home steps at one size. Was 140 dp.
 */
val HomeRailCardSize = 120.dp

/** The corners of a Home rail card's cover. */
val HomeRailCoverShape = RoundedCornerShape(10.dp)

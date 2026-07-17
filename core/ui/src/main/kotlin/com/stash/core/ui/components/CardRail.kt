package com.stash.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
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

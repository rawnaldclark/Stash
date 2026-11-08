package com.stash.feature.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/**
 * The first letter of an account's name in the service's colour, in an account card's logo
 * space. Telling two accounts of one service apart is all it does (several Google Drive accounts,
 * several Spotify accounts): a profile photo would need a stored picture for little more.
 */
@Composable
internal fun AccountLetterAvatar(label: String, color: Color, size: Dp = LogoSize) {
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?",
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
        )
    }
}

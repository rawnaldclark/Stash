package com.stash.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.stash.core.ui.R

/**
 * v0.9.13: Heart icon — standard like-button toggle. Tap on an unliked
 * track adds it to the local Stash Liked Songs playlist; tap on a
 * liked track removes it. Stash-only by design — no fan-out to
 * external services on tap. The Spotify auto-save scrobbler runs
 * separately and is one-way (Stash unlike does NOT propagate).
 *
 * The visible icon is rendered at [size] (default 24.dp) but the
 * clickable surface is expanded to the Material3 minimum interactive
 * size (48.dp) for accessibility / touch-target compliance.
 *
 * @param isLiked      True if the track is in the Stash Liked Songs
 *                     playlist. Drives the filled vs outlined heart.
 * @param onTap        Toggles the Stash-liked state.
 * @param unlikedTint  Icon tint when NOT liked. The liked state always
 *                     uses the theme's `error` (red) so it pops
 *                     against any background — this parameter has no
 *                     effect when [isLiked] is true. Pass `null`
 *                     (default) to use `MaterialTheme.colorScheme.onSurface`.
 * @param size         Visible icon size. Default 24.dp matches Material3
 *                     IconButton. The clickable hit area is always at
 *                     least 48.dp regardless of [size].
 */
@Composable
fun LikeButton(
    isLiked: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    unlikedTint: Color? = null,
    size: Dp = 24.dp,
) {
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clickable(
                onClick = onTap,
                role = Role.Button,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            contentDescription = if (isLiked) stringResource(R.string.cd_unlike) else stringResource(R.string.cd_like),
            tint = if (isLiked) {
                MaterialTheme.colorScheme.error
            } else {
                unlikedTint ?: MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.size(size),
        )
    }
}

package com.stash.feature.sync.components

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.stash.core.ui.theme.StashTheme

/**
 * The Sync tab's ambient light: up to two of the user's own covers, blurred
 * into colour behind the top of the screen and faded into the background.
 * Blur needs API 31; below that the covers would show through sharp, so the
 * screen keeps its plain background there.
 */
@Composable
internal fun SyncAmbientBackdrop(arts: List<String>, modifier: Modifier = Modifier) {
    val background = MaterialTheme.colorScheme.background
    Box(modifier.fillMaxWidth().height(520.dp)) {
        if (arts.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Row(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 0.5f }
                    .blur(64.dp, BlurredEdgeTreatment.Unbounded),
            ) {
                arts.take(2).forEach { url ->
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to background.copy(alpha = 0.15f),
                        0.45f to background.copy(alpha = 0.6f),
                        0.95f to background,
                    ),
                ),
        )
    }
}

/** One cover, or a quiet tile where a playlist has none. */
@Composable
internal fun CoverTile(url: String?, modifier: Modifier = Modifier, corner: Dp = 8.dp) {
    val shape = RoundedCornerShape(corner)
    if (url == null) {
        Box(modifier.clip(shape).background(StashTheme.extendedColors.glassBackground))
    } else {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(shape).background(StashTheme.extendedColors.glassBackground),
        )
    }
}

/** Three covers fanned like sleeves pulled from a shelf: the top of a synced Sync tab. */
@Composable
internal fun CoverFan(
    arts: List<String>,
    modifier: Modifier = Modifier,
    /** Tapping cover [i] of [arts]; null leaves the covers as pictures only. */
    onTap: ((Int) -> Unit)? = null,
) {
    if (arts.isEmpty()) return
    // Back to front: smallest and most tilted first, so the last one sits on top.
    val slots = listOf(
        Triple(0.dp, 14.dp, -7f) to 104.dp,
        Triple(70.dp, 4.dp, -2f) to 112.dp,
        Triple(148.dp, 0.dp, 3f) to 120.dp,
    )
    Box(modifier.width(272.dp).height(128.dp)) {
        arts.take(3).forEachIndexed { i, url ->
            val (pos, size) = slots[i + (3 - arts.size.coerceAtMost(3))]
            val (x, y, angle) = pos
            CoverTile(
                url = url,
                corner = 10.dp,
                modifier = Modifier
                    .offset(x, y)
                    .size(size)
                    .rotate(angle)
                    .shadow(20.dp, RoundedCornerShape(10.dp))
                    .then(
                        if (onTap != null) {
                            Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { onTap(i) }
                        } else {
                            Modifier
                        },
                    ),
            )
        }
    }
}

/** Four covers in a square: an account at a glance. */
@Composable
internal fun CoverMosaic(arts: List<String?>, size: Dp, modifier: Modifier = Modifier) {
    val cell = size / 2
    Column(modifier.size(size).clip(RoundedCornerShape(10.dp))) {
        for (row in 0 until 2) {
            Row {
                for (col in 0 until 2) {
                    CoverTile(url = arts.getOrNull(row * 2 + col), corner = 0.dp, modifier = Modifier.size(cell))
                }
            }
        }
    }
}

/** A connected account: its covers, a brand dot, its name and what it holds. Opens its playlists. */
@Composable
internal fun AccountCard(
    name: String,
    brandColor: Color,
    arts: List<String?>,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(StashTheme.extendedColors.glassBackground)
            .clickable(onClick = onClick, role = Role.Button)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CoverMosaic(arts = arts, size = 72.dp)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(brandColor))
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Something that needs the user: songs to fix, downloads that failed. A dot, never an alarm. */
@Composable
internal fun AttentionRow(
    title: String,
    subtitle: String,
    dotColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick, role = Role.Button)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
    }
}

/** A plain row with a hairline above it: Auto-sync, History. */
@Composable
internal fun SyncLinkRow(
    title: String,
    trailing: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(StashTheme.extendedColors.glassBorder))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clickable(onClick = onClick, role = Role.Button)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (trailing != null) {
                Text(
                    text = trailing,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The Spotify mark, drawn: a green disc with its three sound arcs. */
@Composable
internal fun SpotifyMark(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        drawCircle(Color(0xFF1DB954))
        val ink = Color(0xFF06060C)
        // Three arcs, widest on top, each a shallow curve bowing upward.
        listOf(
            Triple(0.26f, 0.39f, 0.072f),
            Triple(0.29f, 0.52f, 0.062f),
            Triple(0.32f, 0.645f, 0.052f),
        ).forEach { (inset, y, stroke) ->
            val path = Path().apply {
                moveTo(s * inset, s * y)
                quadraticTo(s * 0.5f, s * (y - 0.11f), s * (1 - inset), s * (y + 0.035f))
            }
            drawPath(path, ink, style = Stroke(width = s * stroke, cap = StrokeCap.Round))
        }
    }
}

/** The YouTube Music mark, drawn: a red disc, a white ring, a play triangle. */
@Composable
internal fun YouTubeMusicMark(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        drawCircle(Color(0xFFFF0033))
        drawCircle(Color.White, radius = s * 0.26f, style = Stroke(width = s * 0.055f))
        val play = Path().apply {
            moveTo(s * 0.435f, s * 0.39f)
            lineTo(s * 0.435f, s * 0.61f)
            lineTo(s * 0.62f, s * 0.5f)
            close()
        }
        drawPath(play, Color.White)
    }
}

/** A large connect button: the brand's mark, then what tapping does. */
@Composable
internal fun ConnectButton(
    label: String,
    mark: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(StashTheme.extendedColors.glassBackground)
            .border(1.dp, StashTheme.extendedColors.glassBorder, RoundedCornerShape(32.dp))
            .clickable(onClick = onClick, role = Role.Button)
            .padding(start = 16.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        mark()
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A playlist's Save control: a download arrow in a circle, filled once the
 * playlist is kept on the phone. Replaces a switch so "save" reads as what it
 * does. 44dp touch target around a 28dp mark.
 */
@Composable
internal fun SaveCircle(
    saved: Boolean,
    playlistName: String,
    saveLabel: String,
    savedState: String,
    notSavedState: String,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cyan = StashTheme.extendedColors.cyan
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(role = Role.Switch) { onToggle(!saved) }
            .semantics {
                contentDescription = "$saveLabel $playlistName"
                stateDescription = if (saved) savedState else notSavedState
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .then(
                    if (saved) Modifier.background(cyan)
                    else Modifier.border(1.5.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), CircleShape),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.ArrowDownward,
                contentDescription = null,
                tint = if (saved) Color(0xFF06202A) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * A playlist's "In your library" check on its account's page: filled when the
 * playlist shows on Home and in Library, empty once it's been taken out. 44dp
 * touch target around a 24dp mark.
 */
@Composable
internal fun InLibraryCheck(
    inLibrary: Boolean,
    playlistName: String,
    label: String,
    onState: String,
    offState: String,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(role = Role.Checkbox) { onToggle(!inLibrary) }
            .semantics {
                contentDescription = "$label: $playlistName"
                stateDescription = if (inLibrary) onState else offState
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .then(
                    if (inLibrary) Modifier.background(primary)
                    else Modifier.border(1.5.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), CircleShape),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (inLibrary) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** A cover still on its way in: a quiet square and a short line where its name will be. */
@Composable
internal fun PendingCoverTile(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(StashTheme.extendedColors.glassBackground),
        )
        Box(
            Modifier
                .fillMaxWidth(0.55f)
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(StashTheme.extendedColors.glassBackground),
        )
    }
}

/** A playlist that has landed: its cover and name. Tapping opens it, so it plays right away. */
@Composable
internal fun LandedCoverTile(
    name: String,
    artUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.clickable(onClick = onClick, role = Role.Button),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        CoverTile(
            url = artUrl,
            corner = 10.dp,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .shadow(12.dp, RoundedCornerShape(10.dp)),
        )
        Text(
            text = name,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The small "saved" check used on Download mode's list. */
@Composable
internal fun SavedCheck(contentDescription: String, modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.Filled.Check,
        contentDescription = contentDescription,
        tint = StashTheme.extendedColors.success,
        modifier = modifier.size(20.dp),
    )
}

/** Brand dot + label, for the first-sync sheet's "connected" line. */
@Composable
internal fun BrandDotLabel(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun VSpace(height: Dp) = Spacer(Modifier.height(height))

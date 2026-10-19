package com.stash.feature.listening.recap

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.stash.core.ui.ambient.AccentRoles
import com.stash.core.ui.ambient.Ground
import com.stash.core.ui.ambient.PaletteCache
import com.stash.core.ui.ambient.brandRoles
import com.stash.core.ui.ambient.roles
import com.stash.core.ui.components.BlurredCoverBackdrop
import com.stash.core.ui.theme.LocalIsAmoledTheme
import com.stash.core.ui.theme.LocalIsDarkTheme
import com.stash.core.ui.theme.StashTheme

/** The ground this screen is drawn on, for cover colours that hold their contrast on it. */
@Composable
private fun currentGround(): Ground = when {
    !LocalIsDarkTheme.current -> Ground.LIGHT
    LocalIsAmoledTheme.current -> Ground.AMOLED
    else -> Ground.DARK
}

/** [art]'s colour roles on this screen's ground; the brand's until (or unless) the cover is read. */
@Composable
internal fun rememberCoverRoles(art: ArtModel?, ground: Ground = currentGround()): AccentRoles {
    val context = LocalContext.current
    var roles by remember(ground) { mutableStateOf(brandRoles(ground)) }
    LaunchedEffect(art?.key, ground) {
        val palette = art?.let { PaletteCache.load(context, artData(it)) }
        roles = palette?.roles(ground) ?: brandRoles(ground)
    }
    return roles
}

/**
 * The Recap door: a full-width card whose background is the #1 song's cover blurred under a dark
 * scrim, the cover, "Recap" over the title ("Your October so far"), the line ("11 h · #1 Garden")
 * and a play disc in the cover's accent. Home's section and Your listening's banner.
 *
 * @param eyebrow the small line above the title ("Recap"), or null for none.
 */
@Composable
fun RecapDoorCard(
    offer: RecapOffer,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = offer.title,
    eyebrow: String? = "Recap",
) {
    val colors = StashTheme.extendedColors
    val roles = rememberCoverRoles(offer.art, Ground.DARK)
    val accent by animateColorAsState(roles.accent, tween(600), label = "accent")
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = "$title, ${offer.subtitle}. Opens your recap." },
        shape = MaterialTheme.shapes.large,
        color = Color.Transparent,
    ) {
        Box {
            BlurredCoverBackdrop(model = offer.art?.let { artData(it) }, modifier = Modifier.matchParentSize())
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(colors.elevatedSurface),
                    contentAlignment = Alignment.Center,
                ) {
                    val art = offer.art
                    if (art != null) {
                        AsyncImage(model = artData(art), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else {
                        Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(24.dp))
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    if (eyebrow != null) {
                        Text(eyebrow, style = MaterialTheme.typography.labelMedium, color = roles.ink, maxLines = 1)
                    }
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        offer.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = roles.onAccent, modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

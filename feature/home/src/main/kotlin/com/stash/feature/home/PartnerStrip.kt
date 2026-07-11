package com.stash.feature.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stash.core.ui.theme.StashTheme

/**
 * Quiet, collapsible "Powered by ARCOD" credit for Home. Deliberately subordinate
 * to the Supporter pill above it (lower-opacity glass, thinner padding). Collapsed
 * by default; tapping the header expands inline to ARCOD's Ko-fi/Discord links.
 * Links with a blank URL ([arcodPartnerLinks]) are omitted, so the strip degrades
 * gracefully before the real URLs are configured.
 */
@Composable
fun PartnerStrip(modifier: Modifier = Modifier) {
    val extendedColors = StashTheme.extendedColors
    var expanded by remember { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        label = "partner-chevron",
    )
    val uriHandler = LocalUriHandler.current
    val links = remember { arcodPartnerLinks() }

    Surface(
        modifier = modifier,
        // Subordinate to the Supporter pill: ~3% vs the pill's glassBackground.
        color = Color(0x08FFFFFF),
        shape = RoundedCornerShape(13.dp),
        border = BorderStroke(1.dp, extendedColors.glassBorder),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .semantics {
                        role = Role.Button
                        stateDescription = if (expanded) "expanded" else "collapsed"
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(id = com.stash.core.ui.R.drawable.partner_arcod),
                    contentDescription = null,
                    modifier = Modifier
                        .size(18.dp)
                        .clip(RoundedCornerShape(5.dp)),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(com.stash.core.ui.R.string.label_powered_by),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = ArcodPartner.NAME,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    // Decorative: the header Row carries role + state + label.
                    contentDescription = null,
                    modifier = Modifier
                        .size(18.dp)
                        .graphicsLayer(rotationZ = chevronRotation),
                    tint = extendedColors.textTertiary,
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = ArcodPartner.TAGLINE,
                        style = MaterialTheme.typography.bodySmall,
                        color = extendedColors.textTertiary,
                    )
                    if (links.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            links.forEach { link ->
                                PartnerChip(
                                    link = link,
                                    modifier = Modifier.weight(1f),
                                    onClick = { uriHandler.openUri(link.url) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PartnerChip(
    link: PartnerLink,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isKofi = link.kind == PartnerLinkKind.KOFI
    // ko-fi coral / discord blurple — lighter than the canonical 0xFF5865F2 for legibility as a tint on dark glass
    val accent = if (isKofi) Color(0xFFFF7E7B) else Color(0xFF8A93F5)
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = Color(0x0DFFFFFF),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isKofi) {
                Icon(
                    imageVector = Icons.Default.FavoriteBorder,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(14.dp),
                )
            } else {
                Icon(
                    painter = painterResource(id = R.drawable.ic_discord),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(14.dp),
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (isKofi) stringResource(com.stash.core.ui.R.string.partner_support_kofi) else stringResource(com.stash.core.ui.R.string.partner_discord),
                style = MaterialTheme.typography.labelMedium,
                color = accent,
            )
        }
    }
}

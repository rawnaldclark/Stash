package com.stash.feature.search

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.stash.core.common.ArtUrlUpgrader
import com.stash.data.ytmusic.model.ArtistAbout

/**
 * Maps a [com.stash.data.ytmusic.model.SocialLink] `kind` to a display icon.
 * Known platforms share a generic link glyph (material-icons has no brand
 * icons for Instagram/TikTok/etc.); "website" and any unknown kind fall back
 * to the globe. Pure so it can be unit-tested without a Compose harness.
 */
fun socialIconFor(kind: String): ImageVector = when (kind.lowercase()) {
    "instagram", "x", "tiktok", "youtube", "facebook", "soundcloud", "bandcamp" -> Icons.Default.Link
    else -> Icons.Default.Public
}

/**
 * Spotify-style "About" section: artist photo, collapsible bio, and a row of
 * social-link buttons. Rendered under "Fans also like" on the artist page.
 */
@Composable
fun AboutSection(
    about: ArtistAbout,
    avatarUrl: String?,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    var expanded by remember { mutableStateOf(false) }
    // Whether the collapsed bio actually overflows 4 lines — drives whether the
    // "See more" toggle is worth showing (a 1-2 line bio shouldn't get one).
    var bioOverflows by remember { mutableStateOf(false) }
    val photo = about.photoUrl ?: avatarUrl

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        if (photo != null) {
            AsyncImage(
                model = ArtUrlUpgrader.upgrade(photo),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
            Spacer(Modifier.height(12.dp))
        }

        val bio = about.bio
        if (bio != null) {
            Text(
                text = bio,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) bioOverflows = it.hasVisualOverflow },
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize()
                    .clickable { expanded = !expanded },
            )
            if (bioOverflows || expanded) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (expanded) "See less" else "See more",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { expanded = !expanded },
                )
            }
        }

        if (about.socials.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                about.socials.forEach { social ->
                    IconButton(onClick = { runCatching { uriHandler.openUri(social.url) } }) {
                        Icon(
                            imageVector = socialIconFor(social.kind),
                            contentDescription = social.kind,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (bio != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "via Last.fm",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

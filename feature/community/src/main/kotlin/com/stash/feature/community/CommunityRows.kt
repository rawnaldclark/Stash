package com.stash.feature.community

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.stash.core.model.community.CommunityPost
import com.stash.core.model.share.ShareConfig
import com.stash.core.ui.theme.StashTheme

internal fun kindLabel(kind: String): String = when (kind) {
    "mix" -> "Mix"
    "song" -> "Song"
    else -> "Playlist"
}

internal fun songs(n: Int): String = if (n == 1) "1 song" else "$n songs"

/** How long ago [then] was: "just now", "5m", "4h", "3d"; [long] spells it out: "5 minutes ago", "3 days ago". */
internal fun ago(then: Long, now: Long, long: Boolean = false): String {
    val minutes = (now - then).coerceAtLeast(0) / 60_000
    fun of(n: Long, short: String, word: String) = if (long) "$n $word${if (n == 1L) "" else "s"} ago" else "$n$short"
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> of(minutes, "m", "minute")
        minutes < 24 * 60 -> of(minutes / 60, "h", "hour")
        else -> of(minutes / (24 * 60), "d", "day")
    }
}

/**
 * "Playlist · 42 songs · Maya", "Song · Death Plus · Sam". Your own post is tagged YOU, so its age takes the
 * name's place; [withAge] adds the age to everyone else's (See all). [longAge] spells the age out (TalkBack).
 */
internal fun CommunityPost.subtitle(withAge: Boolean, now: Long, longAge: Boolean = false): String {
    val what = if (kind == "song") artist.orEmpty() else songs(count)
    val age = ago(createdAt, now, longAge)
    val who = when {
        mine -> age
        withAge -> "$name · $age"
        else -> name
    }
    return listOf(kindLabel(kind), what, who).filter { it.isNotBlank() }.joinToString(" · ")
}

internal fun CommunityPost.coverUrls(): List<String> = if (kind == "song") listOfNotNull(art) else covers

/**
 * One cover, or a 2×2 mosaic when there are four (spec 2026-09-26 §3). Only cover-host links are ever loaded.
 * The note sits underneath, so it shows while a cover loads or when its link is dead.
 */
@Composable
internal fun CoverArt(urls: List<String>, modifier: Modifier = Modifier, corner: Dp = 6.dp) {
    val safe = urls.filter(ShareConfig::isAllowedCover)
    Box(
        modifier.clip(RoundedCornerShape(corner)).background(StashTheme.extendedColors.glassBackground),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.MusicNote, contentDescription = null, tint = StashTheme.extendedColors.textTertiary)
        when {
            safe.size >= 4 -> Column(Modifier.fillMaxSize()) {
                for (pair in safe.take(4).chunked(2)) Row(Modifier.fillMaxWidth().weight(1f)) {
                    for (url in pair) AsyncImage(url, null, Modifier.weight(1f).fillMaxHeight(), contentScale = ContentScale.Crop)
                }
            }
            safe.isNotEmpty() -> AsyncImage(safe.first(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

/**
 * ▲ score ▼ (spec §3 Votes): your vote lights in purple. Stacked, the two arrows' tap areas meet under the score,
 * so a tap anywhere on it votes instead of opening the post. On your own post the arrows are dimmed and inert.
 */
@Composable
internal fun VoteControl(post: CommunityPost, onVote: (Int) -> Unit, modifier: Modifier = Modifier, vertical: Boolean = true) {
    val up = @Composable {
        VoteArrow(
            Icons.Default.KeyboardArrowUp, "Vote up", lit = post.myVote == 1, enabled = !post.mine,
            iconAt = if (vertical) Alignment.TopCenter else Alignment.Center,
        ) { onVote(1) }
    }
    val score = @Composable {
        val n = post.up - post.down
        Text(
            text = "$n",
            style = MaterialTheme.typography.labelLarge,
            color = if (post.myVote != 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { contentDescription = if (n == 1) "1 vote" else "$n votes" },
        )
    }
    val down = @Composable {
        VoteArrow(
            Icons.Default.KeyboardArrowDown, "Vote down", lit = post.myVote == -1, enabled = !post.mine,
            iconAt = if (vertical) Alignment.BottomCenter else Alignment.Center,
        ) { onVote(-1) }
    }
    if (vertical) {
        // The score sits over the seam. Text takes no taps, so a tap on it reaches the arrow underneath.
        Box(modifier, contentAlignment = Alignment.Center) {
            Column { up(); down() }
            score()
        }
    } else {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) { up(); score(); down() }
    }
}

/** A 40×30 tap area with its arrow at [iconAt]. Disabled (your own post), it's dimmed, silent to TalkBack and lets taps through. */
@Composable
private fun VoteArrow(icon: ImageVector, label: String, lit: Boolean, enabled: Boolean, iconAt: Alignment, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 40.dp, height = 30.dp)
            .clip(RoundedCornerShape(8.dp))
            // Not selectable(enabled = false): a disabled one still swallows the tap, which should open the post.
            .then(if (enabled) Modifier.selectable(selected = lit, role = Role.Button, onClick = onClick) else Modifier.clearAndSetSemantics {}),
        contentAlignment = iconAt,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = when {
                !enabled -> StashTheme.extendedColors.textTertiary.copy(alpha = 0.38f)
                lit -> MaterialTheme.colorScheme.primary
                else -> StashTheme.extendedColors.textTertiary
            },
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * The rank column for a list of [count] posts, one width for every row so the covers line up. A digit is at most
 * 0.65 em in Space Grotesk. It's in em of the rank's own style because Android scales large sp values less than
 * small ones, so a width in sp would stop growing before the 16sp numbers do.
 */
@Composable
internal fun rankWidthFor(count: Int): Dp {
    val em = with(LocalDensity.current) { MaterialTheme.typography.titleMedium.fontSize.toDp() }
    return maxOf(22.dp, em * 0.7f * count.toString().length)
}

/**
 * One ranked post (the Top Albums row, with votes on the right). [rankWidth] comes from [rankWidthFor] for the
 * whole list; [rank] null leaves the number and its column out (Mine). Ages are counted to [now].
 */
@Composable
internal fun PostRow(
    rank: Int?,
    rankWidth: Dp,
    post: CommunityPost,
    withAge: Boolean,
    now: Long,
    onOpen: () -> Unit,
    onVote: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 20.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        if (rank != null) {
            Text(
                text = "$rank",
                style = MaterialTheme.typography.titleMedium,
                color = StashTheme.extendedColors.textTertiary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.width(rankWidth),
            )
        }
        CoverArt(post.coverUrls(), Modifier.size(46.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = post.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (post.mine) YouTag()
            }
            Text(
                text = post.subtitle(withAge, now),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { contentDescription = post.subtitle(withAge, now, longAge = true) },
            )
            if (post.hidden) {
                Text("Hidden by votes", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
        VoteControl(post, onVote)
    }
}

@Composable
private fun YouTag() {
    Text(
        text = "YOU",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

/** What shows instead of rows (spec §3): loading, "Couldn't load Community" with Retry, or [emptyText]. */
@Composable
internal fun ListStatus(state: CommunityViewModel.UiState, emptyText: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
        val posts = state.posts
        when {
            posts == null && state.failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Couldn't load Community",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRetry) { Text("Retry") }
            }
            posts == null -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            posts.isEmpty() -> Text(emptyText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Shows [message] once as a short toast, then clears it. */
@Composable
internal fun MessageToast(message: String?, onShown: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(message) {
        if (message != null) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            onShown()
        }
    }
}

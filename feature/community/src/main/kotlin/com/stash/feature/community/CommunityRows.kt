package com.stash.feature.community

import android.content.res.Resources
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.stash.core.model.community.CommunityPost
import com.stash.core.model.share.ShareConfig
import com.stash.core.ui.components.LocalAppSnackbar
import com.stash.core.ui.theme.StashTheme

/** Between the facts on one line: "Playlist · 42 songs". */
internal const val SEP = " · "

/** The resources Community's words come from, read in composition (and again when the configuration changes). */
@Composable
@ReadOnlyComposable
internal fun words(): Resources {
    LocalConfiguration.current
    return LocalContext.current.resources
}

/** [age] as a list shows it ("3d"), or spelled out ([long]: "3 days ago") for TalkBack and a post's page. */
internal fun Resources.ageText(age: Age, long: Boolean = false): String = when (age) {
    Age.JustNow -> getString(R.string.community_age_just_now)
    is Age.Minutes ->
        if (long) getQuantityString(R.plurals.community_age_minutes, age.n, age.n) else getString(R.string.community_age_minutes_short, age.n)
    is Age.Hours ->
        if (long) getQuantityString(R.plurals.community_age_hours, age.n, age.n) else getString(R.string.community_age_hours_short, age.n)
    is Age.Days ->
        if (long) getQuantityString(R.plurals.community_age_days, age.n, age.n) else getString(R.string.community_age_days_short, age.n)
}

/** What the post is: "Playlist · 42 songs", "Mix · 34 songs", or a song's "Song · Death Plus". */
internal fun Resources.postFacts(post: CommunityPost): String {
    val what = if (post.kind == "song") post.artist.orEmpty() else getQuantityString(R.plurals.community_songs, post.count, post.count)
    return listOf(getString(kindLabel(post.kind)), what).filter { it.isNotBlank() }.joinToString(SEP)
}

/** A playlist's length: "5 min", "1 h", "2 h 11 min"; null when no song says how long it is. */
internal fun Resources.postLength(minutes: Long): String? = when {
    minutes <= 0 -> null
    minutes < 60 -> getString(R.string.community_length_minutes, minutes.toInt())
    minutes % 60 == 0L -> getString(R.string.community_length_hours, (minutes / 60).toInt())
    else -> getString(R.string.community_length_hours_minutes, (minutes / 60).toInt(), (minutes % 60).toInt())
}

/** A post's page: "Playlist · 42 songs · 2 h 11 min", "Mix · 34 songs", or a song's artist (spec 2026-09-26 §3). */
internal fun Resources.postHeadline(post: CommunityPost): String {
    if (post.kind == "song") return post.track?.artist ?: post.artist.orEmpty()
    return listOfNotNull(postFacts(post), postLength(post.lengthMinutes())).joinToString(SEP)
}

/**
 * Who posted it and when: "Maya · 3d". Your own post drops the name (it's tagged YOU, or it's on Your posts), and
 * on Your posts ([ownList]) adds how long it has left: "3d · Leaves in 26 days". [long] spells the age out (TalkBack).
 */
internal fun Resources.posterLine(post: CommunityPost, now: Long, ownList: Boolean = false, long: Boolean = false): String {
    val left = daysLeft(post.expiresAt, now)
    val leaves = when {
        !ownList || left == null -> null
        left == 0 -> getString(R.string.community_leaves_today)
        else -> getQuantityString(R.plurals.community_leaves_in, left, left)
    }
    return listOfNotNull(post.name.takeUnless { post.mine }, ageText(ageOf(post.createdAt, now), long), leaves).joinToString(SEP)
}

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
 * The rank column for a list whose ranks run up to [highest], one width for every row so the covers line up. A digit
 * is at most 0.65 em in Space Grotesk. It's in em of the rank's own style because Android scales large sp values less
 * than small ones, so a width in sp would stop growing before the 16sp numbers do.
 */
@Composable
internal fun rankWidthFor(highest: Int): Dp {
    val em = with(LocalDensity.current) { MaterialTheme.typography.titleMedium.fontSize.toDp() }
    return maxOf(22.dp, em * 0.7f * highest.toString().length)
}

/** A list row's cover: big enough to tell posts apart at a glance (was 46 dp). */
internal val RowCoverSize = 64.dp

/**
 * One post in a list (See all, a post's "More from Community"): the rank when it has one, the cover, then three
 * quiet lines (the title, what it is, who posted it and when) and the vote pill. On Your posts ([ownList]) the
 * third line is its age and how long it has left, and a post hidden by votes says so. Ages are counted to [now].
 */
@Composable
internal fun PostListRow(
    rank: Int?,
    rankWidth: Dp,
    post: CommunityPost,
    now: Long,
    onOpen: () -> Unit,
    onVote: (Int) -> Unit,
    ownList: Boolean = false,
    /** False drops the pill (a list where voting happens elsewhere, e.g. a post's "More from Community"). */
    showVotes: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (rank != null) {
            Text(
                text = "$rank",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.width(rankWidth),
            )
        }
        CoverArt(post.coverUrls(), Modifier.size(RowCoverSize), corner = 8.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = post.title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (post.mine && !ownList) YouTag()
            }
            val res = words()
            Text(
                text = res.postFacts(post),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val spoken = res.posterLine(post, now, ownList, long = true)
            Text(
                text = res.posterLine(post, now, ownList),
                style = MaterialTheme.typography.bodySmall,
                color = StashTheme.extendedColors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { contentDescription = spoken },
            )
            if (post.hidden) {
                Text(stringResource(R.string.community_hidden), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
        if (showVotes) VotePill(post, onVote, compact = true)
    }
}

/** A small tag on your own post among everyone's: in the primary colour on the page, in [color] elsewhere (white on the hero). */
@Composable
internal fun YouTag(color: Color = MaterialTheme.colorScheme.primary) {
    Text(
        text = stringResource(R.string.community_you),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        modifier = Modifier
            .border(1.dp, color, RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

/** "Play it for 30 seconds to vote." while [show], for [CommunitySnackbar]. */
@Composable
internal fun listenFirstNote(show: Boolean): String? = if (show) stringResource(R.string.community_listen_first_note) else null

/**
 * Shows [message] once on the app's snackbar (above the bottom chrome), then clears it. Outside the scaffold
 * (a preview) it falls back to a toast. The snackbar shows from the scaffold's scope, so clearing the message
 * (which restarts this effect) never cuts it short.
 */
@Composable
internal fun CommunitySnackbar(message: String?, onShown: () -> Unit) {
    val snackbar = LocalAppSnackbar.current
    val context = LocalContext.current
    val shown by rememberUpdatedState(onShown)
    LaunchedEffect(message) {
        if (message == null) return@LaunchedEffect
        if (snackbar != null) snackbar.show(message) else Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        shown()
    }
}

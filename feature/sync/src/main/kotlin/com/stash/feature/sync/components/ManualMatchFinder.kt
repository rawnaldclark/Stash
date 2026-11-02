package com.stash.feature.sync.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.stash.core.ui.theme.StashTheme
import com.stash.core.ui.util.formatDuration
import com.stash.feature.sync.ManualMatch
import com.stash.feature.sync.ManualSearch
import com.stash.feature.sync.R
import com.stash.feature.sync.ResyncCandidate

/**
 * "Find it yourself" (#19), open under a song's row on Failed Matches: a search
 * box filled with the song's artist and title, then what YouTube Music and all
 * of YouTube found. Each result previews and picks exactly as the row's
 * automatic match does ([onPreview], [onStopPreview] and the check mark lead to
 * the same calls), so the only new thing is where the candidate came from.
 *
 * @param search           The open search: its words, results and progress.
 * @param afterAutomatic   The row above shows an automatic match, so the label
 *                         reads "Or find it yourself".
 * @param previewingId     The video previewing now, if any.
 * @param previewLoadingId The video whose preview is loading, if any.
 * @param onSearch         Searches for the box's words (the keyboard's Search key).
 * @param onFieldFocused   The box took focus: the screen scrolls the song up so
 *                         the keyboard can't cover the box.
 * @param onPick           Picks a result for the song (the check mark).
 */
@Composable
internal fun ManualMatchFinder(
    search: ManualSearch,
    afterAutomatic: Boolean,
    previewingId: String?,
    previewLoadingId: String?,
    onSearch: (String) -> Unit,
    onFieldFocused: () -> Unit,
    onPreview: (String) -> Unit,
    onStopPreview: () -> Unit,
    onPick: (ResyncCandidate) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text(
            text = stringResource(
                if (afterAutomatic) R.string.failed_matches_find_label_or else R.string.failed_matches_find_label,
            ),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 8.dp),
        )
        FinderField(
            search = search,
            onSearch = onSearch,
            onFocused = onFieldFocused,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(modifier = Modifier.height(4.dp))

        search.results.forEach { match ->
            val videoId = match.candidate.videoId
            ManualMatchRow(
                match = match,
                isPreviewPlaying = previewingId == videoId,
                isPreviewLoading = previewLoadingId == videoId,
                onPreview = { onPreview(videoId) },
                onStopPreview = onStopPreview,
                onPick = { onPick(match.candidate) },
            )
        }

        when {
            search.searchingMusic && search.results.isEmpty() ->
                FinderStatus(text = stringResource(R.string.failed_matches_find_searching), busy = true)
            search.searchingYouTube && !search.searchingMusic ->
                FinderStatus(text = stringResource(R.string.failed_matches_find_searching_youtube), busy = true)
            search.foundNothing && search.query.isNotBlank() ->
                FinderStatus(
                    text = stringResource(
                        if (search.failed) R.string.failed_matches_find_failed else R.string.failed_matches_find_nothing,
                    ),
                    busy = false,
                )
        }
    }
}

/**
 * The search box, drawn like the module's other one (ManagePlaylistsScreen's
 * playlist search). Its text is the screen's own state, so typing never waits
 * on a flow; it starts from the words last searched and is searched on the
 * keyboard's Search key.
 */
@Composable
private fun FinderField(
    search: ManualSearch,
    onSearch: (String) -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by rememberSaveable(search.trackId) { mutableStateOf(search.query) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(StashTheme.extendedColors.glassBackgroundHover)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Box(modifier = Modifier.weight(1f)) {
            if (text.isEmpty()) {
                Text(
                    text = stringResource(R.string.failed_matches_find_placeholder),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        // Down goes the keyboard so the results it covered show.
                        keyboard?.hide()
                        focusManager.clearFocus()
                        onSearch(text)
                    },
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { if (it.isFocused) onFocused() },
            )
        }
        if (text.isNotEmpty()) {
            IconButton(onClick = { text = "" }) {
                Icon(
                    imageVector = Icons.Default.Clear,
                    contentDescription = stringResource(R.string.failed_matches_find_clear),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * One result, laid out as the screen's candidate rows are (48dp art, the
 * "title — artist" line, then a detail line; preview and check mark at the
 * end). The detail line says whether it is a song or a video, and its length.
 */
@Composable
private fun ManualMatchRow(
    match: ManualMatch,
    isPreviewPlaying: Boolean,
    isPreviewLoading: Boolean,
    onPreview: () -> Unit,
    onStopPreview: () -> Unit,
    onPick: () -> Unit,
) {
    val candidate = match.candidate
    val kind = stringResource(
        if (match.isSong) R.string.failed_matches_find_song else R.string.failed_matches_find_video,
    )
    val detail = if (candidate.durationSeconds > 0) {
        stringResource(
            R.string.failed_matches_find_detail,
            kind,
            formatDuration((candidate.durationSeconds * 1_000).toLong()),
        )
    } else {
        kind
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f),
                        ),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (candidate.thumbnailUrl != null) {
                AsyncImage(
                    model = candidate.thumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f),
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = if (candidate.artist.isBlank()) {
                    candidate.title
                } else {
                    "${candidate.title} — ${candidate.artist}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        IconButton(
            onClick = if (isPreviewPlaying) onStopPreview else onPreview,
            modifier = Modifier.size(40.dp),
        ) {
            when {
                isPreviewLoading -> CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
                isPreviewPlaying -> Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = stringResource(R.string.failed_matches_find_stop_preview),
                    tint = MaterialTheme.colorScheme.primary,
                )
                else -> Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.failed_matches_find_preview),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        IconButton(
            onClick = onPick,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = stringResource(R.string.failed_matches_find_use),
                // The screen's approve green, as on the candidate rows.
                tint = Color(0xFF4CAF50),
            )
        }
    }
}

/** A quiet line under the results: still searching, or why there are none. */
@Composable
private fun FinderStatus(text: String, busy: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )
            Spacer(modifier = Modifier.width(10.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

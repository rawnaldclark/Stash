package com.stash.feature.nowplaying

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.stash.core.common.primaryArtist
import com.stash.core.media.listen.ListenTogetherState
import com.stash.core.model.RepeatMode
import com.stash.core.ui.ambient.LocalAlbumChrome
import com.stash.core.ui.components.ListenTogetherRole
import com.stash.core.ui.components.LocalListenTogetherRole
import com.stash.core.ui.components.chromeGlass
import com.stash.core.ui.components.motion.pressScale
import com.stash.feature.nowplaying.listen.ListenTogetherViewModel
import com.stash.feature.nowplaying.listen.MiniSessionFaces
import com.stash.feature.nowplaying.ui.hasNextInQueue
import com.stash.feature.nowplaying.ui.hasPreviousInQueue
import com.stash.feature.nowplaying.ui.rememberSwipeToSkip

/*
 * The glass mini player, a port of the web player's phone mini player (stash-web
 * player/src/lib/ui/MiniPlayer.svelte, shell.css `.mini*`): a 60dp frosted card floating 8dp above
 * the tab bar with 8dp side margins and a 12dp radius; cover 44 (r6) | title / artist | queue 36 |
 * play 40 (filled with the album's accent, its onAccent ink on top) | next 36, 10dp apart; a 2dp
 * progress line in the accent along the top edge. The colours come from the app-wide album colour
 * (LocalAlbumChrome), so they follow the song from any screen, Now Playing open or not, and are the
 * very colours Now Playing's play button wears: opening the player never shifts the shade.
 */

/** shell.css `.mini`: height 60, radius 12, 8 px from the tab bar and the sides. */
private val MiniHeight = 60.dp
private val MiniShape = RoundedCornerShape(12.dp)
val MiniPlayerMargin = 8.dp

/** box-shadow: 0 16px 32px -12px rgb(0 0 0 / .5). */
private val MiniShadow = Shadow(
    radius = 32.dp,
    spread = (-12).dp,
    color = Color.Black.copy(alpha = 0.5f),
    offset = DpOffset(0.dp, 16.dp),
)

/**
 * Compact glass player floating above the tab bar.
 *
 * Shows the cover, title and artist, the queue (hidden in a Listen Together room, as the queue is
 * on Now Playing), play/pause (a spinner while the song resolves or buffers) and skip next (an
 * empty slot for a Listen Together listener). Slides in and out with the current track. Tapping
 * anywhere else opens Now Playing; pressing scales the card to 0.97. Swiping the song (cover, title
 * and artist) sideways skips to the next or previous song, as on Now Playing's art; TalkBack gets
 * Previous as an action on the card (Next is the button).
 *
 * @param onExpand Opens Now Playing.
 * @param onOpenQueue Opens the queue sheet over the current screen.
 * @param modifier Standard Compose [Modifier] for the floating card's slot.
 * @param viewModel The activity-scoped [NowPlayingViewModel] provided by Hilt.
 */
@Composable
fun MiniPlayer(
    onExpand: () -> Unit,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NowPlayingViewModel = hiltViewModel(),
    listen: ListenTogetherViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // Kept as a State and read only inside drawBehind below: the progress line redraws per
    // 250ms tick without recomposing the card (which shows on every screen).
    val position = viewModel.positionMs.collectAsStateWithLifecycle()
    val together by listen.state.collectAsStateWithLifecycle()
    val room = together as? ListenTogetherState.InRoom
    val chrome = LocalAlbumChrome.current

    AnimatedVisibility(
        visible = uiState.hasTrack,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
    ) {
        val press = remember { MutableInteractionSource() }
        val track = uiState.currentTrack
        // Swipe the song sideways to skip, with Now Playing's art threshold (rememberSwipeToSkip).
        // A listener can't skip the room's song; a host's skips go to the room, which decides.
        val isListener = LocalListenTogetherRole.current == ListenTogetherRole.LISTENER
        val repeatAll = uiState.repeatMode == RepeatMode.ALL
        val canNext = room != null || hasNextInQueue(uiState.queue.size, uiState.currentIndex, repeatAll)
        val canPrevious = room != null || hasPreviousInQueue(uiState.queue.size, uiState.currentIndex, repeatAll)
        val swipe = rememberSwipeToSkip(
            trackKey = track?.let { it.id to uiState.currentIndex },
            enabled = track != null && !isListener,
            canNext = canNext,
            canPrevious = canPrevious,
            onNext = viewModel::onSkipNext,
            onPrevious = viewModel::onSwipePrevious,
        )
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(start = MiniPlayerMargin, end = MiniPlayerMargin, bottom = MiniPlayerMargin)
                .pressScale(press)
                .dropShadow(MiniShape, MiniShadow)
                .chromeGlass(MiniShape)
                .clickable(interactionSource = press, indication = ripple(), onClick = onExpand)
                // The swipe's TalkBack equivalent. Next already has its button; Previous has none.
                .semantics {
                    if (!isListener && canPrevious) {
                        customActions = listOf(
                            CustomAccessibilityAction("Previous song") { viewModel.onSwipePrevious(); true },
                        )
                    }
                }
                .height(MiniHeight),
        ) {
            // The 2dp progress line along the top edge (clipped to the card's corners by the glass),
            // drawn in the draw phase so the album colour's crossfade only redraws it.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .drawBehind {
                        val fraction = progressFraction(position.value, uiState.durationMs)
                        drawRect(color = chrome.accent, size = Size(size.width * fraction, size.height))
                    },
            )

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    // end 4 + the next slot's 6dp of spare width = the web's 10dp to the icon.
                    .padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // The song: cover, title and artist. It takes the swipe and moves with it, clipped
                // to its own slot; the controls stay put, so a swipe can't start on play/pause, and
                // a tap here still opens Now Playing (the drag claims only a sideways move past
                // touch slop).
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .then(swipe.gesture)
                        .clipToBounds(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(swipe.content),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val artModel = track?.albumArtPath ?: track?.albumArtUrl
                        val coverShape = RoundedCornerShape(6.dp)
                        if (artModel != null) {
                            // Remembered per cover: a fresh request each recomposition makes Coil treat
                            // it as a new load.
                            val context = LocalContext.current
                            val artRequest = remember(artModel) {
                                ImageRequest.Builder(context).data(artModel).build()
                            }
                            AsyncImage(
                                model = artRequest,
                                contentDescription = "Album art",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(coverShape),
                            )
                        } else {
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .clip(coverShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MusicNote,
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        // Title (600 14) and artist (400 12, --wp-text-2).
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            // primaryArtist() collapses collaboration credits
                            // ("Metro Boomin, Travis Scott" → "Metro Boomin").
                            // remember keeps the split off the hot path: this
                            // composable recomposes on every progress tick.
                            val artist = remember(track?.artist) { track?.artist?.primaryArtist() }
                            Text(
                                text = track?.title ?: "",
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 18.sp),
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = artist ?: "",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                room?.let { MiniSessionFaces(it) }

                // The controls keep the web's visual sizes (36 / 40 / 36, 10dp apart) inside 48dp
                // touch slots set edge to edge: half a slot's spare width on each side makes the
                // same 10dp visual gap, so the targets meet but never overlap.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Up next: the queue sheet over this screen. Hidden in a Listen Together room,
                    // where the queue is the room's (as on Now Playing).
                    if (room == null) {
                        MiniIconButton(onClick = onOpenQueue) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                                contentDescription = "Up next",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }

                    // Play / pause: a 40dp circle in the album's vibrant colour, its ink on top;
                    // a spinner while the track is still resolving/buffering (e.g. a slow
                    // YouTube-fallback resolve). One button to TalkBack: its action, and its state.
                    // It stays ENABLED while buffering and keys on playWhenReady, so a stalled
                    // stream can be paused from any screen (it used to be disabled while loading,
                    // which left no in-app way to stop a stream that was about to start).
                    val playPress = remember { MutableInteractionSource() }
                    val wantsToPlay = uiState.playWhenReady || uiState.isPlaying
                    val loading = uiState.isBuffering && wantsToPlay
                    val playAction = if (wantsToPlay) "Pause" else "Play"
                    val playState = when {
                        loading -> "Loading"
                        uiState.isPlaying -> "Playing"
                        else -> "Paused"
                    }
                    Box(
                        modifier = Modifier
                            .size(MiniTouchTarget)
                            .clickable(
                                interactionSource = playPress,
                                indication = null,
                                role = Role.Button,
                                onClick = viewModel::onPlayPauseClick,
                            )
                            .semantics {
                                contentDescription = playAction
                                stateDescription = playState
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .pressScale(playPress)
                                .clip(CircleShape)
                                .drawBehind { drawRect(chrome.accent) }
                                .indication(playPress, ripple(color = chrome.onAccent)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (loading) {
                                CircularProgressIndicator(
                                    color = chrome.onAccent,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(20.dp),
                                )
                            } else {
                                Icon(
                                    imageVector = if (wantsToPlay) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = chrome.onAccent,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                    }

                    // Skip next. A Listen Together listener can't skip the room's song: the slot stays, empty,
                    // so play/pause doesn't move when the host role changes hands.
                    if (LocalListenTogetherRole.current == ListenTogetherRole.LISTENER) {
                        Spacer(Modifier.size(MiniTouchTarget))
                    } else {
                        MiniIconButton(onClick = viewModel::onSkipNext) {
                            Icon(
                                imageVector = Icons.Default.SkipNext,
                                contentDescription = "Next",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The minimum touch target for every mini player control (Material's 48dp). */
private val MiniTouchTarget = 48.dp

/**
 * shell.css `.mini-ib`: a 36dp round icon button (its ripple), in a 48dp touch slot. The icon's
 * contentDescription names it; the slot is announced as a button.
 */
@Composable
private fun MiniIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(MiniTouchTarget)
            .clickable(
                interactionSource = null,
                indication = ripple(bounded = false, radius = 18.dp),
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) { content() }
}

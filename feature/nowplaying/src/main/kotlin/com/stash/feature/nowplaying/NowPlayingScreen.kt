@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package com.stash.feature.nowplaying

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import com.stash.core.data.backcover.BackCoverAlbum
import com.stash.core.data.prefs.NowPlayingAction
import com.stash.feature.nowplaying.ui.CompactCover
import com.stash.feature.nowplaying.ui.LyricsMenuButton
import com.stash.feature.nowplaying.ui.LyricsModeHeader
import com.stash.feature.nowplaying.ui.LyricsPane
import com.stash.feature.nowplaying.ui.lyricsMenuItemsFor
import com.stash.feature.nowplaying.ui.npIsLight
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.automirrored.filled.Logout
import com.stash.core.media.listen.ListenTogetherState
import com.stash.core.common.primaryArtist
import com.stash.core.media.SleepTimerController
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.stash.core.model.RepeatMode
import com.stash.core.model.isFlac
import com.stash.core.model.isScannedSong
import com.stash.core.ui.components.SaveToPlaylistSheet
import com.stash.core.ui.components.SheetOptionRow
import com.stash.core.ui.theme.LocalIsAmoledTheme
import com.stash.feature.nowplaying.ui.AmbientBackground
import com.stash.feature.nowplaying.ui.GlowingProgressBar
import com.stash.feature.nowplaying.ui.LiveLyricsBar
import com.stash.feature.nowplaying.ui.WithAlbumChrome
import com.stash.core.ui.ambient.LocalAlbumChrome
import com.stash.feature.nowplaying.ui.npInk
import com.stash.feature.nowplaying.ui.QueueBottomSheet
import com.stash.feature.nowplaying.ui.LoadedBackCover
import com.stash.feature.nowplaying.ui.SwipeableArt
import com.stash.feature.nowplaying.ui.rememberLoadedBackCover
import com.stash.feature.nowplaying.ui.hasNextInQueue
import com.stash.feature.nowplaying.ui.hasPreviousInQueue
import com.stash.feature.nowplaying.sheet.HeroArt
import com.stash.feature.nowplaying.sheet.PlayerSheetMath
import com.stash.feature.nowplaying.sheet.PlayerSheetState
import com.stash.feature.nowplaying.sheet.PlayerSheetAnchor
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.toSize

/**
 * Full-screen Now Playing screen with premium visual design.
 *
 * Displays album art with ambient background, playback controls, progress bar,
 * and track information. The album colours are the app-wide ones (LocalAlbumChrome), the same
 * the mini player wears.
 * The ambient ground + inks follow the app theme: near-black with drifting
 * art-derived orbs in dark, the same orbs as a pastel wash over lavender
 * paper in light.
 *
 * Hosted by the player sheet ([sheet]): laid out once at the full screen size, revealed by the
 * sheet's card as it grows from the mini player. Everything here that the sheet's progress moves
 * (the ambient's fade, the controls' fade and rise, the big cover handing over to the travelling
 * one) reads it in a graphicsLayer, so a drag never recomposes this screen.
 *
 * @param onDismiss Callback invoked when the user taps the dismiss (down arrow) button.
 * @param viewModel The [NowPlayingViewModel] provided by Hilt (the activity's: the mini player's).
 * @param sheet The player sheet hosting this screen; null draws it as a plain full screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    onDismiss: () -> Unit,
    onNavigateToArtist: (id: String, name: String, avatarUrl: String?, focusAlbum: String?) -> Unit,
    onNavigateToAlbum: (albumId: String, name: String, artUrl: String?, artistName: String) -> Unit,
    viewModel: NowPlayingViewModel = hiltViewModel(),
    sheet: PlayerSheetState? = null,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val track = uiState.currentTrack
    // The album's back, once its image is in hand: a tap on the art turns the cover over to it, and
    // a swipe there turns through the pages inside.
    val coverArt by viewModel.coverArt.collectAsStateWithLifecycle()
    val backCoverKey by viewModel.backCoverKey.collectAsStateWithLifecycle()
    val backCover = rememberLoadedBackCover(coverArt?.back)
    val insidePages = coverArt?.inside.orEmpty()
    // Settings > Appearance > Cycle album art: the front turns slowly through the back and the
    // pages inside while Now Playing is up, until the art is turned over by hand.
    val cycleAlbumArt by viewModel.cycleAlbumArt.collectAsStateWithLifecycle()
    var cycleIndex by remember(backCoverKey) { mutableStateOf(0) }
    var cycleSnap by remember { mutableStateOf(0) }
    var backUp by remember { mutableStateOf(false) }
    // The page the back opens on: the one the cycle was showing when the art was turned over.
    var packageStart by remember(backCoverKey) { mutableStateOf(0) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val npInSight = lifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
        (sheet == null || sheet.settledAnchor == com.stash.feature.nowplaying.sheet.PlayerSheetAnchor.FULL)
    // A page (or the front) pinched off the art, held up over the whole screen: put back by a new
    // album, or a new song when there is no album to go by.
    val pageZoom = com.stash.feature.nowplaying.ui.rememberPageZoom(track?.let { BackCoverAlbum.of(it)?.key ?: it.id })
    // Page index's Discogs page when its image comes from Discogs (0 is the back), for the credit.
    val discogsCredit: (Int) -> String? = remember(coverArt) {
        { index ->
            val art = coverArt
            art?.discogsPage?.takeIf { if (index == 0) art.backFromDiscogs else art.insideFromDiscogs }
        }
    }
    val backFace: (@Composable (Modifier) -> Unit)? = remember(backCover, insidePages, pageZoom, discogsCredit, packageStart) {
        backCover?.let { loaded ->
            { faceModifier: Modifier ->
                com.stash.feature.nowplaying.ui.AlbumPackage(
                    pageCount = 1 + insidePages.size,
                    modifier = faceModifier,
                    credit = discogsCredit,
                    initialPage = packageStart,
                ) { index ->
                    if (index == 0) {
                        AlbumBackSection(loaded, pageZoom, discogsCredit(0))
                    } else {
                        InsidePageSection(
                            url = insidePages[index - 1],
                            description = "Inside page $index of ${insidePages.size}",
                            zoom = pageZoom,
                            discogsPage = discogsCredit(index),
                        )
                    }
                }
            }
        }
    }
    val resolvingArtist by viewModel.resolvingArtist.collectAsStateWithLifecycle()
    val isDownloadingCurrent by viewModel.isDownloadingCurrent.collectAsStateWithLifecycle()
    val exportingLyricsTrackId by viewModel.exportingLyricsTrackId.collectAsStateWithLifecycle()
    val radioLabel by viewModel.radioSeedLabel.collectAsStateWithLifecycle()
    val radioTuning by viewModel.radioTuning.collectAsStateWithLifecycle()
    // One-shot lock pulse: fires on the tuning→active transition. Also pulses
    // when the screen re-opens with a station already live — reads as "station
    // is active", not a bug. Keys on real state changes, not recomposition.
    var radioLock by remember { mutableStateOf(false) }
    LaunchedEffect(radioTuning, radioLabel) {
        if (!radioTuning && radioLabel != null) {
            radioLock = true
            kotlinx.coroutines.delay(com.stash.feature.nowplaying.ui.LOCK_HOLD_MS)
            radioLock = false
        }
    }
    // Now Playing's own prefs, all from one DataStore: wait for the three together, so a Classic
    // user never sees a frame of Soft glow and a customised row never shows a frame of the
    // default pins. Subscribing to all three before returning keeps the waits from chaining a
    // frame each.
    val ambientAnimationEnabledPref = viewModel.ambientAnimationEnabled.collectAsStateWithLifecycle().value
    val ambientStylePref = viewModel.ambientStyle.collectAsStateWithLifecycle().value
    val pinsPref = viewModel.pinnedActions.collectAsStateWithLifecycle().value
    if (ambientAnimationEnabledPref == null || ambientStylePref == null || pinsPref == null) return
    val ambientAnimationEnabled: Boolean = ambientAnimationEnabledPref
    val ambientStyle: com.stash.core.data.prefs.AmbientStyle = ambientStylePref
    val pins: Set<NowPlayingAction> = pinsPref
    var showQueue by remember { mutableStateOf(false) }
    var showSaveSheet by remember { mutableStateOf(false) }
    var showOptionsSheet by remember { mutableStateOf(false) }
    // The options act on the playing song, so a song change while the sheet is open closes it:
    // otherwise Download / Remove download would land on the next song, not the one it was opened for.
    LaunchedEffect(track?.id) { showOptionsSheet = false }
    var showSleepTimerSheet by remember { mutableStateOf(false) }
    // Signal path (hi-res output): opened by tapping the quality line.
    var showSignalPath by remember { mutableStateOf(false) }
    val signalPathViewModel: com.stash.feature.nowplaying.ui.SignalPathViewModel = hiltViewModel()
    val signalVerdict by signalPathViewModel.verdict.collectAsStateWithLifecycle()
    var showCustomTimerDialog by remember { mutableStateOf(false) }
    val sleepTimerState by viewModel.sleepTimerState.collectAsStateWithLifecycle()
    val sleepTimerSheetState = rememberModalBottomSheetState()
    val optionsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val playbackSpeed by viewModel.playbackSpeed.collectAsStateWithLifecycle()
    var showSpeedSheet by remember { mutableStateOf(false) }
    var showCustomSpeedDialog by remember { mutableStateOf(false) }
    val speedSheetState = rememberModalBottomSheetState()
    // The queue row (if any) whose Save-to-Playlist picker is open.
    var queueSaveTrack by remember { mutableStateOf<com.stash.core.model.Track?>(null) }
    val shareTrack by viewModel.shareTrack.collectAsStateWithLifecycle()
    // Listen Together (spec 2026-09-24 §5).
    val together: com.stash.feature.nowplaying.listen.ListenTogetherViewModel = hiltViewModel()
    val togetherState by together.state.collectAsStateWithLifecycle()
    val room = togetherState as? ListenTogetherState.InRoom
    var showStartTogether by remember { mutableStateOf(false) }
    // Google Cast (spec 2026-10-06 §5). Hidden on phones without Play Services.
    val castViewModel: com.stash.feature.nowplaying.cast.CastViewModel = hiltViewModel()
    val castConnection by castViewModel.connection.collectAsStateWithLifecycle()
    val casting = castConnection is com.stash.core.media.cast.CastConnection.Connected
    var showCastSheet by remember { mutableStateOf(false) }
    // A listener follows the room: no skip or seek; their play/pause is their own (spec §5).
    val isListener = room != null && !room.isHost
    // Where Next / Previous can go, for the art's swipe and its TalkBack actions: the queue's ends,
    // wrapped by repeat-all. In a room the host's skips go to the room, which decides (its Up next,
    // its radio, a restart), so both stay on there; a listener has no art gestures at all.
    val repeatAll = uiState.repeatMode == RepeatMode.ALL
    val canSwipeNext = room != null || hasNextInQueue(uiState.queue.size, uiState.currentIndex, repeatAll)
    val canSwipePrevious = room != null || hasPreviousInQueue(uiState.queue.size, uiState.currentIndex, repeatAll)
    // The Session sheet: people, Up next, suggestions, invite, leave (design 2026-09-25).
    var showSession by remember { mutableStateOf(false) }
    var confirmEndTogether by remember { mutableStateOf(false) }
    var confirmLeaveTogether by remember { mutableStateOf(false) }
    // Haptics at the moments that matter: you're in (start or join), and you became host.
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    // On screen right now (read when an event lands, not in composition): parked under the mini
    // player, Now Playing gives no haptics and starts no navigation.
    val onScreen: () -> Boolean = { sheet == null || sheet.targetAnchor != PlayerSheetAnchor.MINI || sheet.progress > 0f }
    var wasInRoom by remember { mutableStateOf(room != null) }
    LaunchedEffect(room != null) {
        if (room != null && !wasInRoom && onScreen()) haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm)
        wasInRoom = room != null
    }
    LaunchedEffect(Unit) {
        together.events.collect { e ->
            val me = (together.state.value as? ListenTogetherState.InRoom)?.myId
            if (e.kind == com.stash.core.media.listen.SessionEvent.Kind.HOSTING && e.memberId == me && onScreen()) {
                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm)
            }
        }
    }
    // "This song is wrong" dialog — shown when the flag icon is tapped.
    // Decouples the Flag button (which is just "there's a problem") from
    // the action (find a replacement / delete / delete + block).
    var showWrongMatchDialog by remember { mutableStateOf(false) }
    // A delete picked in the "What's wrong" dialog, awaiting confirmation: false = delete,
    // true = delete and block; null = none.
    var pendingWrongMatchDelete by remember { mutableStateOf<Boolean?>(null) }

    // Scroll state is intentionally not keyed by track — on tall screens
    // content doesn't overflow so scroll stays at 0; on narrow screens the
    // user's scroll position aligns with controls and we want it preserved
    // across track changes.

    // The ViewModel's userMessages are toasted once, app-wide, by PlayerMessagesToaster (this
    // screen and the mini player share the ViewModel: two collectors toasted each message twice).
    val toastContext = LocalContext.current

    // Tap-to-artist: the VM resolves the artist name → browseId off the main
    // thread and emits a one-shot nav target; forward it to the host.
    // A lookup that lands after the player closed (or started closing) is dropped: the page
    // under the mini player must not change by itself.
    LaunchedEffect(Unit) {
        viewModel.artistNavEvents.collect { t ->
            if (sheet == null || sheet.targetAnchor != PlayerSheetAnchor.MINI) {
                onNavigateToArtist(t.artistId, t.name, t.avatarUrl, t.focusAlbum)
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.albumNavEvents.collect { a ->
            if (sheet == null || sheet.targetAnchor != PlayerSheetAnchor.MINI) {
                onNavigateToAlbum(a.albumId, a.name, a.artUrl, a.artistName)
            }
        }
    }

    // TalkBack: when the player settles open, focus moves to its Dismiss button, so the reader
    // starts at the top of the player rather than wherever it was on the page behind.
    val dismissFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    if (sheet != null) {
        val a11yContext = LocalContext.current
        LaunchedEffect(sheet) {
            androidx.compose.runtime.snapshotFlow { sheet.settledAnchor }.collect { anchor ->
                val am = a11yContext.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE)
                    as? android.view.accessibility.AccessibilityManager
                if (anchor == PlayerSheetAnchor.FULL && am?.isTouchExplorationEnabled == true) {
                    runCatching { dismissFocus.requestFocus() }
                }
            }
        }
    }

    // Share sheet (fork issue ParaliyzedEvo/Stash#40) — VM-driven with the
    // FULL DB track: the player-state Track is a slim media-session
    // reconstruction whose spotifyUri/youtubeId are null, which is why the
    // link rows never appeared when this read uiState.currentTrack.
    // Shown only for a share asked for here: the ViewModel is shared with the mini player's queue
    // sheet, which shows its own.
    var shareRequested by remember { mutableStateOf(false) }
    if (shareRequested) {
        shareTrack?.let { full ->
            com.stash.core.ui.components.ShareTrackSheet(
                track = full,
                onDismiss = {
                    shareRequested = false
                    viewModel.onShareDismissed()
                },
            )
        }
    }

    // Queue bottom sheet
    if (showQueue) WithAlbumChrome { chrome ->
        QueueBottomSheet(
            queue = uiState.queue,
            currentIndex = uiState.currentIndex,
            accentColor = chrome.ink,
            source = uiState.source,
            isShuffleEnabled = uiState.shuffleEnabled,
            onDismiss = { showQueue = false },
            onTrackClick = { index ->
                viewModel.onSkipToQueueIndex(index)
                showQueue = false
            },
            onRemoveTrack = viewModel::onRemoveFromQueue,
            onMoveTrack = viewModel::onMoveInQueue,
            onPlayNext = viewModel::queuePlayNext,
            onStartRadio = viewModel::startRadioForTrack,
            onSaveToPlaylist = { queueSaveTrack = it },
            // Only when a sheet is coming: a request that never answers must not stay armed.
            onShare = { t -> shareRequested = viewModel.onShareTrack(t) },
            onToggleDownload = viewModel::toggleDownload,
            onRestoreTrack = viewModel::onRestoreToQueue,
        )
    }

    // Save-to-playlist sheet for a queue row's ⋮ (separate from the current-
    // track save sheet so both can target their own track).
    queueSaveTrack?.let { t ->
        SaveToPlaylistSheet(
            playlists = uiState.userPlaylists,
            onSaveToPlaylist = { playlistId ->
                viewModel.saveTrackToPlaylist(t, playlistId)
                queueSaveTrack = null
            },
            onCreatePlaylist = { name ->
                viewModel.createPlaylistAndAddTrack(name, t)
                queueSaveTrack = null
            },
            onDismiss = { queueSaveTrack = null },
        )
    }

    // Lyrics mode — the lyrics take the art's place on the ambient (direction A). Opened by
    // tapping the LiveLyricsBar or the Lyrics action; Dismiss, back or the cover close it.
    val lyricsMode by viewModel.lyricsMode.collectAsStateWithLifecycle()
    // Collected unconditionally (not just while lyrics are up): the bar
    // needs the state, and this subscription is what arms the ViewModel's
    // WhileSubscribed fetch trigger from screen-open onward.
    val lyricsState by viewModel.lyricsViewState.collectAsStateWithLifecycle()
    // Positions tick every 250ms while playing. Both are kept as State objects and handed down
    // as lambdas, read only inside the progress bar and the lyrics, so a tick recomposes those
    // and not this whole screen (the position used to be a uiState field, which re-ran the root,
    // the art and the stack four times a second).
    val lyricsPosition = viewModel.currentPositionMs.collectAsStateWithLifecycle()
    val playerPosition = viewModel.positionMs.collectAsStateWithLifecycle()
    val liveLyricsEnabled by viewModel.liveLyricsBarEnabled.collectAsStateWithLifecycle()
    val lyricsSyncOffsetMs by viewModel.lyricsSyncOffsetMs.collectAsStateWithLifecycle()
    val lyricsOutputOffset by viewModel.lyricsOutputOffset.collectAsStateWithLifecycle()
    val appleLyricsEnabled by viewModel.appleLyricsEnabled.collectAsStateWithLifecycle()
    val upgradingLyricsTrackId by viewModel.upgradingLyricsTrackId.collectAsStateWithLifecycle()
    val reading = lyricsMode && track != null
    if (sheet != null) {
        // Back while reading leaves lyrics first; the sheet's own handler then collapses the player.
        val currentReading by androidx.compose.runtime.rememberUpdatedState(reading)
        androidx.compose.runtime.DisposableEffect(sheet, viewModel) {
            val unregister = sheet.registerBackConsumer {
                if (currentReading) {
                    viewModel.onDismissLyrics()
                    true
                } else {
                    false
                }
            }
            onDispose { unregister() }
        }
    } else {
        androidx.activity.compose.BackHandler(enabled = reading) { viewModel.onDismissLyrics() }
    }

    // What the sheet's progress moves, all read in layers: the controls fade in over the last
    // quarter while rising 24dp; the big cover shows only at FULL (the travelling cover carries it
    // in between) and reports where it sits.
    val controlsLayer = if (sheet == null) Modifier else Modifier.graphicsLayer {
        val p = sheet.progress
        alpha = PlayerSheetMath.controlsAlpha(p)
        translationY = PlayerSheetMath.controlsRiseDp(p) * density
    }
    val screenDensity = LocalDensity.current.density
    fun heroLayer(isCurrentMode: Boolean, cornerDp: Float): Modifier = if (sheet == null) Modifier else Modifier
        .onGloballyPositioned { coords ->
            // Only the view on screen reports (the other may be leaving through the crossfade).
            if (isCurrentMode && coords.isAttached) {
                sheet.heroArt = HeroArt(
                    rect = Rect(coords.positionInRoot(), coords.size.toSize()),
                    cornerPx = cornerDp * screenDensity,
                )
                sheet.heroMeasuredFor = sheet.screenSize
            }
        }
        .graphicsLayer { alpha = PlayerSheetMath.heroArtAlpha(sheet.progress) }

    // The album colours come from LocalAlbumChrome (the app-wide palette the mini player wears
    // too), not from the art this screen loads; both covers share one decode (rememberNpArtRequest).
    val artModel: Any? = track?.albumArtPath ?: track?.albumArtUrl
    // The holder only (no colour read here): each reader takes its colour where it draws, so the
    // 800 ms crossfade on a song change never recomposes the whole screen.
    val chrome = LocalAlbumChrome.current

    // -- Actions: where each shows (pinned row or ⋮ menu) and what it does. Everything the top
    // row reads is remembered on what it depends on, so the 250ms position tick that recomposes
    // this screen leaves the row alone. --
    val postToCommunity = com.stash.core.ui.components.LocalPostToCommunity.current
    val isDownloaded = track?.isDownloaded == true
    // Cast shows (pinned or as a ⋮ row) exactly where its button did: a phone with Play
    // Services, outside a Listen Together room (the two exclude each other, cast spec §3).
    val castSupported = castConnection != com.stash.core.media.cast.CastConnection.Unavailable
    val isScannedSong = track?.isScannedSong == true
    val isCloudSong = track?.source == com.stash.core.model.MusicSource.CLOUD
    val actionCtx = remember(room != null, casting, postToCommunity != null, isDownloaded, castSupported, isScannedSong, isCloudSong) {
        ActionContext(
            inRoom = room != null,
            casting = casting,
            communityAvailable = postToCommunity != null,
            isDownloaded = isDownloaded,
            castSupported = castSupported,
            isScannedSong = isScannedSong,
            isCloudSong = isCloudSong,
        )
    }
    val castConnectingTo = (castConnection as? com.stash.core.media.cast.CastConnection.Connecting)?.deviceName
    val castingTo = (castConnection as? com.stash.core.media.cast.CastConnection.Connected)?.deviceName
    val visualState = remember(radioLabel != null, radioTuning, reading, togetherState, playbackSpeed, isDownloaded, castConnectingTo, castingTo) {
        ActionVisualState(
            radioActive = radioLabel != null,
            radioTuning = radioTuning,
            lyricsOpen = reading,
            together = togetherState,
            speed = playbackSpeed,
            isDownloaded = isDownloaded,
            castConnectingTo = castConnectingTo,
            castingTo = castingTo,
        )
    }
    // The row's width: the window less Now Playing's 24dp margins. Pins that don't fit at 44dp
    // go to the ⋮ menu for now (narrow phones, large display sizes); see fitTopRow.
    val rowWidthDp = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp - 48f
    val rowFit = remember(pins, actionCtx, rowWidthDp) {
        fitTopRow(topRowActions(pins, actionCtx), rowWidthDp)
    }
    val onAction: (NowPlayingAction) -> Unit = remember(radioTuning, radioLabel, togetherState, track, postToCommunity) {
        { action ->
            when (action) {
                // The speaker sheet the Cast button opens: pick a speaker, or control / disconnect.
                NowPlayingAction.CAST -> showCastSheet = true
                // Start a station seeded from this song, or stop the running one; taps while tuning wait.
                NowPlayingAction.RADIO -> when {
                    radioTuning -> Unit
                    radioLabel != null -> viewModel.stopRadio()
                    else -> viewModel.startRadioFromCurrent()
                }
                NowPlayingAction.LYRICS -> viewModel.onToggleLyrics()
                NowPlayingAction.QUEUE -> showQueue = true
                // Start, or the Session sheet (invite, leave, end) once in a room.
                NowPlayingAction.LISTEN_TOGETHER -> when (togetherState) {
                    is ListenTogetherState.InRoom -> showSession = true
                    is ListenTogetherState.Connecting -> Unit
                    ListenTogetherState.Idle -> showStartTogether = true
                }
                NowPlayingAction.SAVE_TO_PLAYLIST -> showSaveSheet = true
                NowPlayingAction.SHARE -> shareRequested = viewModel.onShareCurrent()
                NowPlayingAction.POST_TO_COMMUNITY ->
                    track?.let { t -> postToCommunity?.invoke(com.stash.core.model.community.PostTarget.Song(t)) }
                // Resolves and opens the actual remote album page.
                NowPlayingAction.VIEW_ALBUM -> viewModel.onViewAlbumTapped()
                NowPlayingAction.PLAYBACK_SPEED -> showSpeedSheet = true
                // The ⋮ menu's labelled "Download" / "Remove download" row.
                NowPlayingAction.DOWNLOAD -> viewModel.toggleDownloadForCurrentTrack()
                NowPlayingAction.FLAG_WRONG_MATCH -> showWrongMatchDialog = true
            }
        }
    }
    // A tap on the top row. A pinned Download on a downloaded track never deletes: it only says
    // so, and "Remove download" stays a labelled row in the ⋮ menu (isInertPinnedTap).
    val onPinnedAction: (NowPlayingAction) -> Unit = remember(onAction, actionCtx) {
        { action ->
            if (isInertPinnedTap(action, actionCtx)) {
                android.widget.Toast.makeText(toastContext, "Downloaded", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                onAction(action)
            }
        }
    }

    // Save to playlist bottom sheet
    if (showSaveSheet && track != null) {
        SaveToPlaylistSheet(
            playlists = uiState.userPlaylists,
            onSaveToPlaylist = { playlistId ->
                viewModel.saveTrackToPlaylist(track, playlistId)
            },
            onCreatePlaylist = { name ->
                viewModel.createPlaylistAndAddTrack(name, track)
            },
            onDismiss = { showSaveSheet = false },
        )
    }

    if (showStartTogether) {
        com.stash.feature.nowplaying.listen.ListenTogetherStartDialog(
            name = together.name,
            onNameChange = together::onNameChange,
            onStart = { together.start(); showStartTogether = false; showSession = true },
            onDismiss = { showStartTogether = false },
        )
    }

    if (confirmEndTogether) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmEndTogether = false },
            title = { Text("End the session for everyone?") },
            text = {
                val others = (room?.members?.size ?: 1) - 1
                Text(if (others == 1) "The person listening with you will be dropped." else "The $others people listening with you will be dropped.")
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { together.end(); confirmEndTogether = false },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("End") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmEndTogether = false }) { Text("Cancel") }
            },
        )
    }

    if (confirmLeaveTogether) {
        val nextHost = room?.members?.filter { it.id != room.myId }?.minByOrNull { it.joinedAt }?.name ?: "Someone"
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmLeaveTogether = false },
            title = { Text("Leave the session?") },
            text = { Text("$nextHost will host, and the music keeps playing for everyone else.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { together.leave(); confirmLeaveTogether = false }) { Text("Leave") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmLeaveTogether = false }) { Text("Cancel") }
            },
        )
    }

    if (showSession) {
        com.stash.feature.nowplaying.listen.SessionSheet(
            state = togetherState,
            onDismiss = { showSession = false },
            onAnswer = together::answerSuggestion,
            onMakeHost = together::makeHost,
            onEditQueue = together::editQueue,
            onCopyLink = { com.stash.feature.nowplaying.listen.copyInvite(toastContext, it) },
            onShare = { com.stash.feature.nowplaying.listen.shareInvite(toastContext, it) },
            onLeave = {
                val r = room
                when {
                    r == null -> together.leave()
                    !r.isHost -> { together.leave(); showSession = false }
                    r.members.size <= 1 -> { together.end(); showSession = false } // alone: leaving is ending
                    else -> confirmLeaveTogether = true
                }
            },
            // Alone there's nobody to drop, so no confirmation.
            onEnd = {
                if ((room?.members?.size ?: 1) <= 1) { together.end(); showSession = false } else confirmEndTogether = true
            },
        )
    }

    if (showSpeedSheet) {
        SpeedSheet(
            currentSpeed = playbackSpeed,
            onSelect = { viewModel.onSetPlaybackSpeed(it); showSpeedSheet = false },
            onCustom = { showSpeedSheet = false; showCustomSpeedDialog = true },
            onDismiss = { showSpeedSheet = false },
            sheetState = speedSheetState,
        )
    }

    if (showCustomSpeedDialog) {
        CustomSpeedDialog(
            initial = playbackSpeed,
            onConfirm = { viewModel.onSetPlaybackSpeed(it); showCustomSpeedDialog = false },
            onDismiss = { showCustomSpeedDialog = false },
        )
    }

    // Cast speaker sheet — opened by the cast icon in the top bar.
    if (showCastSheet) {
        com.stash.feature.nowplaying.cast.CastSheet(
            viewModel = castViewModel,
            onDismiss = { showCastSheet = false },
        )
    }

    if (showSignalPath) {
        com.stash.feature.nowplaying.ui.SignalPathSheet(
            onDismiss = { showSignalPath = false },
            onOpenSettings = null,
            service = com.stash.core.media.streaming.StreamService.displayName(track?.streamOrigin),
            viewModel = signalPathViewModel,
        )
    }

    // Sleep timer bottom sheet — opened by the icon next to the track title.
    if (showSleepTimerSheet) {
        SleepTimerSheet(
            currentState = sleepTimerState,
            onSelectMinutes = { minutes ->
                viewModel.onSleepTimerMinutes(minutes)
                showSleepTimerSheet = false
            },
            onSelectEndOfTrack = {
                viewModel.onSleepTimerEndOfTrack()
                showSleepTimerSheet = false
            },
            onSelectCustom = {
                showSleepTimerSheet = false
                showCustomTimerDialog = true
            },
            onCancel = {
                viewModel.onSleepTimerCancel()
                showSleepTimerSheet = false
            },
            onDismiss = { showSleepTimerSheet = false },
            sheetState = sleepTimerSheetState,
        )
    }

    // Custom sleep-timer minutes dialog — opened from the sheet's "Custom" row.
    if (showCustomTimerDialog) {
        CustomSleepTimerDialog(
            onConfirm = { minutes ->
                viewModel.onSleepTimerMinutes(minutes)
                showCustomTimerDialog = false
            },
            onDismiss = { showCustomTimerDialog = false },
        )
    }

    // "This song is wrong" — 3-option dialog triggered by the flag icon.
    // Separated from the icon's direct action so the same entry point
    // covers three very different outcomes: mark for replacement, delete
    // the file, delete + permanently block.
    val pendingBlock = pendingWrongMatchDelete
    if (pendingBlock != null && track != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingWrongMatchDelete = null },
            title = {
                androidx.compose.material3.Text(
                    if (pendingBlock) "Delete and block '${track.title}'?" else "Delete '${track.title}'?",
                )
            },
            text = {
                androidx.compose.material3.Text(
                    if (pendingBlock) {
                        "It's deleted from your device and every playlist, and future syncs never bring it back."
                    } else {
                        "It's deleted from your device and removed from your playlists and Liked Songs."
                    },
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        viewModel.deleteCurrentTrack(alsoBlock = pendingBlock)
                        pendingWrongMatchDelete = null
                    },
                ) {
                    androidx.compose.material3.Text(
                        if (pendingBlock) "Delete & Block" else "Delete",
                        color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingWrongMatchDelete = null }) {
                    androidx.compose.material3.Text("Cancel")
                }
            },
        )
    }
    if (showWrongMatchDialog && track != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showWrongMatchDialog = false },
            title = {
                androidx.compose.material3.Text(
                    text = "What's wrong with this song?",
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                )
            },
            text = {
                androidx.compose.foundation.layout.Column(
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    androidx.compose.material3.Text(
                        text = "Pick what should happen to '${track.title}'.",
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    androidx.compose.foundation.layout.Spacer(
                        modifier = Modifier.height(4.dp),
                    )
                    if (!track.isFlac) {
                        androidx.compose.material3.OutlinedButton(
                            onClick = {
                                viewModel.findInFlacForCurrentTrack()
                                showWrongMatchDialog = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            androidx.compose.material3.Text("Find in FLAC")
                        }
                    }
                    androidx.compose.material3.OutlinedButton(
                        onClick = {
                            viewModel.flagCurrentTrackAsWrongMatch()
                            showWrongMatchDialog = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        androidx.compose.material3.Text("Find a better match")
                    }
                    // Both deletes confirm first (pendingWrongMatchDelete below), as deletes do
                    // elsewhere: "Delete from library" looked exactly like the harmless option
                    // above it, and one mis-tap skipped the song and deleted file and row.
                    androidx.compose.material3.OutlinedButton(
                        onClick = {
                            pendingWrongMatchDelete = false
                            showWrongMatchDialog = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                            contentColor = androidx.compose.material3.MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        androidx.compose.material3.Text("Delete from library")
                    }
                    androidx.compose.material3.Button(
                        onClick = {
                            pendingWrongMatchDelete = true
                            showWrongMatchDialog = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = androidx.compose.material3.MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        androidx.compose.material3.Text("Delete and block forever")
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { showWrongMatchDialog = false },
                ) {
                    androidx.compose.material3.Text("Cancel")
                }
            },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // In the sheet the card draws the ground (it fades in as the card grows).
            .then(if (sheet == null) Modifier.background(MaterialTheme.colorScheme.background) else Modifier),
    ) {
        // Ambient animated background behind everything — dark canvas, the
        // light pastel wash, or a dead-black AMOLED ground, following the
        // resolved app theme. It runs to the screen's bottom edge, under the
        // system navigation bar (the tab bar slides away under the player).
        if (ambientAnimationEnabled) SheetAmbient(sheet) { ambientModifier ->
            when (ambientStyle) {
                com.stash.core.data.prefs.AmbientStyle.SOFT_GLOW -> AmbientBackground(
                    lightMode = MaterialTheme.colorScheme.background.luminance() >= 0.5f,
                    amoledMode = LocalIsAmoledTheme.current,
                    modifier = ambientModifier,
                    entranceAnimated = sheet == null,
                )
                // Settings > Appearance > Ambient style: the original orbs, in the same album colours.
                com.stash.core.data.prefs.AmbientStyle.CLASSIC -> com.stash.feature.nowplaying.ui.ClassicAmbientBackground(
                    lightMode = MaterialTheme.colorScheme.background.luminance() >= 0.5f,
                    amoledMode = LocalIsAmoledTheme.current,
                    modifier = ambientModifier,
                )
            }
        }

        // The raw navigation-bar inset (never consumed by the scaffold here), so the
        // controls sit above the gesture handle or the 3-button bar and do not move
        // while the screen slides away.
        val navBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

        // -- Progress + transport: one piece, shared by both modes. In lyrics mode it glides
        // from under the title to the bottom edge (a shared element, see ModeBounds). --
        val foot: @Composable (Modifier) -> Unit = { footModifier ->
            Column(modifier = footModifier.fillMaxWidth()) {
                // -- Progress bar --
                GlowingProgressBar(
                    progress = { progressFraction(playerPosition.value, uiState.durationMs) },
                    accentColor = { chrome.accent },
                    elapsedMs = { playerPosition.value },
                    totalMs = uiState.durationMs,
                    onSeek = viewModel::onSeekTo,
                    seekable = !isListener,
                    modifier = Modifier.fillMaxWidth(),
                    center = {
                        if (isListener) {
                            com.stash.feature.nowplaying.listen.LivePill(
                                pausedLocally = room.pausedLocally,
                                accent = chrome.accent,
                                ink = npInk(),
                                onRejoin = together::rejoin,
                            )
                        }
                    },
                )

                Spacer(modifier = Modifier.height(StackRhythm.ProgressToControls))

                // -- Playback controls -- the same row for host and listener, so a role change
                // doesn't move anything; a listener's skip slots are simply empty.
                PlaybackControls(
                    // The player's intent, not isPlaying (false while buffering): the button shows
                    // Pause, and pauses, while a stream stalls. A paused buffer shows Play, not a spinner.
                    isPlaying = uiState.playWhenReady || uiState.isPlaying,
                    isBuffering = uiState.isBuffering && (uiState.playWhenReady || uiState.isPlaying),
                    shuffleEnabled = uiState.shuffleEnabled,
                    repeatMode = uiState.repeatMode,
                    onPlayPauseClick = viewModel::onPlayPauseClick,
                    onSkipNext = viewModel::onSkipNext,
                    onSkipPrevious = viewModel::onSkipPrevious,
                    onToggleShuffle = viewModel::onToggleShuffle,
                    onCycleRepeatMode = viewModel::onCycleRepeatMode,
                    // In a session: shuffle and repeat do nothing there, so React takes their place.
                    onReact = if (room != null) together::react else null,
                    canSkip = !isListener,
                )
            }
        }

        Column(modifier = Modifier.fillMaxSize().padding(bottom = navBarInset)) {
            // -- Top row: dismiss, then cast, the pinned actions, ⋮ and (pinned) queue. It stays
            // put while the art and the lyrics swap beneath it. --
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(controlsLayer)
                    .statusBarsPadding()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                WithAlbumChrome { topChrome ->
                    NowPlayingTopBar(
                        fit = rowFit,
                        visualState = visualState,
                        hasTrack = uiState.hasTrack,
                        isDownloading = isDownloadingCurrent,
                        radioLock = radioLock,
                        ink = npInk(),
                        accent = topChrome.accent,
                        // In lyrics mode Dismiss goes back to the art, like back and the cover.
                        dismissDescription = if (reading) "Back to the cover" else "Dismiss",
                        onDismiss = if (reading) viewModel::onDismissLyrics else onDismiss,
                        onMoreClick = { showOptionsSheet = true },
                        onAction = onPinnedAction,
                        dismissModifier = Modifier.focusRequester(dismissFocus),
                        castButton = {
                            com.stash.feature.nowplaying.cast.CastButton(
                                connection = castConnection,
                                tint = npInk(),
                                accentColor = topChrome.accent,
                                onClick = { showCastSheet = true },
                            )
                        },
                    )
                }
                if (room != null) {
                    com.stash.feature.nowplaying.listen.SessionBar(
                        room = room,
                        onClick = { showSession = true },
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            // The art view and the lyrics view swap here. The cover shrinks from the big art
            // into the header (and grows back), the progress + transport glide between their
            // two homes, and the rest crossfades, the lyrics side rising 8dp in a short stagger.
            SharedTransitionLayout(modifier = Modifier.fillMaxWidth().weight(1f)) {
                AnimatedContent(
                    targetState = reading,
                    transitionSpec = {
                        fadeIn(tween(MODE_FADE_IN_MS, delayMillis = MODE_FADE_IN_DELAY_MS))
                            .togetherWith(fadeOut(tween(MODE_FADE_OUT_MS)))
                            .using(SizeTransform(clip = false))
                    },
                    label = "np-mode",
                ) { showLyrics ->
                    val artShared = Modifier.sharedElement(
                        sharedContentState = rememberSharedContentState(ART_SHARED_KEY),
                        animatedVisibilityScope = this@AnimatedContent,
                        boundsTransform = ModeBounds,
                    )
                    val footShared = Modifier.sharedElement(
                        sharedContentState = rememberSharedContentState(FOOT_SHARED_KEY),
                        animatedVisibilityScope = this@AnimatedContent,
                        boundsTransform = ModeBounds,
                    )
                    if (!showLyrics) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // #333 v2 — the slot system. No estimated heights, no thresholds:
                            // the title/progress/controls stack is FIXED and anchors to the
                            // bottom of the column (directly above the lyrics bar when it's
                            // present), while the album art lives in a weight(1f) slot that
                            // absorbs exactly whatever height this phone/scale/bar
                            // combination leaves over. The art sizes to its slot (capped by
                            // width and the 375dp design ceiling) and centers in it, so any
                            // slack splits evenly around the art instead of pooling anywhere.
                            // Every phone renders the same anatomy, scaled — nothing clips,
                            // nothing crowds, nothing pools.
                            NowPlayingBody(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .padding(horizontal = 24.dp),
                                stackModifier = controlsLayer,
                                art = {
                                // -- Album art: NowPlayingBody sizes this slot (a square) --
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .then(heroLayer(isCurrentMode = !reading, cornerDp = PlayerSheetMath.HERO_ART_CORNER_DP)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    // Swipe for the next / previous song, double-tap a side to seek
                                    // 10 s: through the same ViewModel calls as the transport and the
                                    // progress bar, and off for a listener, as those are. A tap turns
                                    // the cover over when the album's back is known.
                                    SwipeableArt(
                                        trackKey = track?.let { it.id to uiState.currentIndex },
                                        gesturesEnabled = track != null && !isListener,
                                        canNext = canSwipeNext,
                                        canPrevious = canSwipePrevious,
                                        canSeek = uiState.durationMs > 0L,
                                        positionMs = { playerPosition.value },
                                        durationMs = uiState.durationMs,
                                        onNext = viewModel::onSkipNext,
                                        onPrevious = viewModel::onSwipePrevious,
                                        onSeekTo = viewModel::onSeekTo,
                                        flipKey = backCoverKey,
                                        back = backFace,
                                        modifier = artShared,
                                        onShowBackChange = { up ->
                                            if (up && !backUp) packageStart = (cycleIndex - 1).coerceAtLeast(0)
                                            backUp = up
                                        },
                                    ) { faceModifier ->
                                        // Turned over by hand: once the front is out of sight (past
                                        // the turn's middle), it is the front again, unfaded.
                                        LaunchedEffect(backUp) {
                                            if (backUp && cycleIndex != 0) {
                                                kotlinx.coroutines.delay(CYCLE_RESET_AFTER_MS)
                                                cycleIndex = 0
                                                cycleSnap++
                                            }
                                        }
                                        val front: @Composable (Modifier) -> Unit = { m ->
                                            AlbumArtSection(
                                                albumArtUrl = track?.albumArtUrl,
                                                albumArtPath = track?.albumArtPath,
                                                zoom = pageZoom,
                                                modifier = m,
                                            )
                                        }
                                        val loaded = backCover
                                        if (cycleAlbumArt && loaded != null) androidx.compose.runtime.key(backCoverKey) {
                                            // Keyed by album: a page still fading out is never drawn
                                            // from the next album's list.
                                            val context = androidx.compose.ui.platform.LocalContext.current
                                            com.stash.feature.nowplaying.ui.CyclingArt(
                                                pageCount = 1 + insidePages.size,
                                                index = cycleIndex,
                                                onIndexChange = { cycleIndex = it },
                                                running = npInSight && !backUp && pageZoom.page == null,
                                                snapKey = cycleSnap,
                                                load = { n ->
                                                    n == 1 || insidePages.getOrNull(n - 2)
                                                        ?.let { com.stash.feature.nowplaying.ui.loadPageImage(context, it) } == true
                                                },
                                                modifier = faceModifier,
                                            ) { n ->
                                                when (n) {
                                                    0 -> front(Modifier)
                                                    1 -> AlbumBackSection(loaded, pageZoom, discogsCredit(0))
                                                    else -> insidePages.getOrNull(n - 2)?.let { url ->
                                                        InsidePageSection(
                                                            url = url,
                                                            description = "Inside page ${n - 1} of ${insidePages.size}",
                                                            zoom = pageZoom,
                                                            discogsPage = discogsCredit(n - 1),
                                                        )
                                                    }
                                                }
                                            }
                                        } else {
                                            front(faceModifier)
                                        }
                                    }
                                    if (room != null) {
                                        // "Maya joined", "Rawn skipped": over the art, so the layout never shifts.
                                        com.stash.feature.nowplaying.listen.SessionEventChip(together.events, room.myId, Modifier.align(Alignment.TopCenter))
                                    }
                                }
                                },
                                stack = {
                                // -- Track info -- (tap the title/artist to open the artist
                                // profile; the trailing chevron signals it's actionable, and
                                // swaps to a spinner while the artist name is being resolved).
                                // The like heart floats at the right edge (relocated out of the
                                // crowded top icon row); symmetric horizontal padding keeps the
                                // title/artist block optically centred under the album art.
                                TrackInfoBlock(
                                    track = track,
                                    resolvingArtist = resolvingArtist,
                                    onTrackInfoTapped = viewModel::onTrackInfoTapped,
                                    sleepTimerState = sleepTimerState,
                                    onSleepTimerClick = { showSleepTimerSheet = true },
                                    isLiked = uiState.currentTrack?.stashLikedAt != null,
                                    onLikeTap = viewModel::onLikeTap,
                                )

                                // Quality line — codec + bit-depth/sample-rate + bitrate, when known.
                                // Sized smaller than the artist/album line; degrades gracefully when
                                // some fields are missing (returns a partial line, not nothing).
                                // When the active MediaItem is sourced from an http(s) URI (Kennyy
                                // stream rather than a local file), a small wifi glyph prefixes
                                // the line so the user knows playback is using their connection.
                                if (track != null) {
                                    val qualityText = trackQualityText(track)
                                    if (qualityText != null) {
                                        Spacer(modifier = Modifier.height(StackRhythm.ArtistToQuality))
                                        // The live verdict ("Bit-perfect", "Hi-Res") rides on the end;
                                        // tapping opens the full signal path, the only place that
                                        // says when Android resampled the song.
                                        val badge = signalVerdict?.let { with(com.stash.feature.nowplaying.ui.SignalPathBadge) { it.badge() } }
                                        QualityLine(
                                            qualityText = if (badge != null) "$qualityText \u00B7 $badge" else qualityText,
                                            isStreaming = uiState.isStreaming,
                                            onClick = { showSignalPath = true },
                                        )
                                    }
                                }

                                if (room != null) com.stash.feature.nowplaying.listen.PickLine(room)

                                Spacer(modifier = Modifier.height(StackRhythm.InfoToProgress))

                                foot(footShared)

                                // "Up next · Title — Artist": a tap opens the queue, or the session
                                // in a room (whose Up next is the room's). Art view only: lyrics
                                // mode keeps the bottom for the lyrics.
                                Spacer(modifier = Modifier.height(StackRhythm.ControlsToUpNext))
                                UpNextLine(
                                    peek = if (room != null) {
                                        roomUpNextPeek(room.queue)
                                    } else {
                                        upNextPeek(uiState.queue, uiState.currentIndex, uiState.repeatMode)
                                    },
                                    inRoom = room != null,
                                    onOpen = { if (room != null) showSession = true else showQueue = true },
                                )
                                },
                            )

                            // Live-lyrics bar — sits exactly where the MiniPlayer is on other
                            // screens (the scaffold hides MiniPlayer on this route), directly
                            // above the nav bar. Zero-height when Hidden, so the content
                            // column keeps the full screen for lyric-less tracks.
                            WithAlbumChrome { barChrome ->
                                LiveLyricsBar(
                                    state = lyricsState,
                                    currentPositionMs = { lyricsPosition.value },
                                    accentColor = barChrome.ink,
                                    liveEnabled = liveLyricsEnabled,
                                    onTap = viewModel::onShowLyrics,
                                    modifier = controlsLayer,
                                    isPlaying = uiState.isPlaying && !uiState.isBuffering,
                                    playbackSpeed = playbackSpeed,
                                )
                            }
                        }
                    } else {
                        // -- Lyrics mode: the web's phone layout. A compact header, the lyrics
                        // filling the middle, progress + transport at the bottom. --
                        Column(modifier = Modifier.fillMaxSize()) {
                            val displayArtist = remember(track) { track?.artist?.primaryArtist().orEmpty() }
                            // The header reads only these settled values (not uiState itself), and
                            // its enter/exit modifiers are remembered, so the 250ms position tick
                            // that recomposes this screen skips the header and its marquees.
                            val headerInk = npInk()
                            val headerLiked = uiState.currentTrack?.stashLikedAt != null
                            val lyricsMenuItems = lyricsMenuItemsFor(
                                    lyricsState,
                                    lyricsSyncOffsetMs,
                                    canSaveToFile = track?.isDownloaded == true,
                                    outputKnown = lyricsOutputOffset != null,
                                    canGetWordSynced = uiState.currentTrackStored == true && appleLyricsEnabled,
                                )
                            val savingLyrics = exportingLyricsTrackId != null
                            val rise = with(LocalDensity.current) { RiseDistance.roundToPx() }
                            val headerTextEnter = remember(this@AnimatedContent, rise) {
                                Modifier.animateEnterExit(enter = riseIn(rise, RISE_STAGGER_MS), exit = fadeOut(tween(MODE_FADE_OUT_MS)))
                            }
                            val headerActionsEnter = remember(this@AnimatedContent, rise) {
                                Modifier.animateEnterExit(enter = riseIn(rise, RISE_STAGGER_MS * 2), exit = fadeOut(tween(MODE_FADE_OUT_MS)))
                            }
                            val paneEnter = remember(this@AnimatedContent, rise) {
                                Modifier.animateEnterExit(enter = riseIn(rise, RISE_STAGGER_MS * 3), exit = fadeOut(tween(MODE_FADE_OUT_MS)))
                            }
                            LyricsModeHeader(
                                cover = {
                                    CompactCover(
                                        artModel = artModel,
                                        onClick = { viewModel.onDismissLyrics() },
                                        modifier = artShared.then(heroLayer(isCurrentMode = reading, cornerDp = CompactCoverCornerDp)),
                                    )
                                },
                                title = track?.title.orEmpty(),
                                artist = displayArtist,
                                ink = headerInk,
                                modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 8.dp),
                                textModifier = headerTextEnter.then(controlsLayer),
                                actionsModifier = headerActionsEnter.then(controlsLayer),
                            ) {
                                // The sleep timer, as on the art view (and in the web's header);
                                // accented, with its live countdown under the moon, while armed.
                                SleepTimerButton(
                                    state = sleepTimerState,
                                    onClick = { showSleepTimerSheet = true },
                                    activeTint = chrome.accent,
                                    idleTint = headerInk.copy(alpha = 0.7f),
                                    buttonSize = 48.dp,
                                    iconSize = 22.dp,
                                )
                                // An All Songs file can't be liked (no library row): no heart.
                                if (uiState.currentTrack?.isScannedSong != true) {
                                    com.stash.core.ui.components.LikeButton(
                                        isLiked = headerLiked,
                                        onTap = { viewModel.onLikeTap() },
                                        unlikedTint = headerInk.copy(alpha = 0.7f),
                                        size = 26.dp,
                                    )
                                }
                                LyricsMenuButton(
                                    items = lyricsMenuItems,
                                    liveEnabled = liveLyricsEnabled,
                                    onLiveToggle = { viewModel.setLiveLyricsBarEnabled(it) },
                                    offsetMs = lyricsSyncOffsetMs,
                                    onOffsetChange = { viewModel.setLyricsSyncOffsetMs(it) },
                                    outputOffset = lyricsOutputOffset,
                                    onOutputOffsetChange = { viewModel.setLyricsOutputOffsetMs(it) },
                                    savingToFile = savingLyrics,
                                    onSaveToFile = { viewModel.exportLyricsForCurrentTrack() },
                                    gettingWordSynced = upgradingLyricsTrackId != null,           // new
                                    onGetWordSynced = { viewModel.getWordSyncedLyricsForCurrentTrack() }, // new
                                    ink = headerInk,
                                )
                            }
                            LyricsPane(
                                state = lyricsState,
                                currentPositionMs = { lyricsPosition.value },
                                isPlaying = uiState.isPlaying && !uiState.isBuffering,
                                playbackSpeed = playbackSpeed,
                                onSeek = { if (!isListener) viewModel.onLyricsLineSeek(it) }, // a listener follows the room
                                onRetry = viewModel::onLyricsRetry,
                                ink = npInk(),
                                accent = { chrome.ink },
                                light = npIsLight(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .padding(top = 4.dp)
                                    .then(paneEnter)
                                    .then(controlsLayer),
                            )
                            foot(Modifier.then(controlsLayer).padding(horizontal = 24.dp).then(footShared))
                            Spacer(modifier = Modifier.height(LyricsFootBottom))
                        }
                    }
                }
            }
        }
        if (room != null) com.stash.feature.nowplaying.listen.FloatingReactions(together.reactions, animate = ambientAnimationEnabled, modifier = Modifier.fillMaxSize().then(controlsLayer))
        com.stash.feature.nowplaying.ui.PageZoomLayer(pageZoom)
    }

    if (showOptionsSheet && track != null) WithAlbumChrome { sheetChrome ->
        NowPlayingOptionsSheet(
            // Pins with no room in the row this frame come back here as ordinary rows.
            menu = menuActions(pins, actionCtx, rowFit.overflow),
            editable = editableActions(pins, actionCtx),
            pins = pins,
            visualState = visualState,
            hiddenReason = { action ->
                hiddenReason(action, actionCtx) ?: OVERFLOW_NOTE.takeIf { action in rowFit.overflow }
            },
            accent = sheetChrome.ink,
            onAction = { action -> onAction(action); showOptionsSheet = false },
            onTogglePin = viewModel::onTogglePin,
            onResetPins = viewModel::onResetPins,
            onDismiss = { showOptionsSheet = false },
            sheetState = optionsSheetState,
        )
    }
}

// ---------------------------------------------------------------------------
// Layout
// ---------------------------------------------------------------------------

/** The fixed steps inside the title-to-controls stack. */
private object StackRhythm {
    val TitleToArtist = 4.dp
    val ArtistToQuality = 6.dp
    val InfoToProgress = 16.dp
    val ProgressToControls = 12.dp
    val ControlsToUpNext = 2.dp
}

/** The art's design ceiling. */
private val ArtMax = 375.dp

/*
 * The three flexible gaps around the art, at their full size and at their floor:
 * top bar -> art, art -> title, controls -> the bottom (the lyrics bar or the edge).
 * They shrink together towards the floor only when keeping them would make the
 * art smaller than ART_COMFORT of its full size (a short phone, a large font).
 */
private val GapTop = 20.dp
private val GapTopMin = 8.dp
private val GapArt = 36.dp
private val GapArtMin = 16.dp
private val GapBottom = 28.dp
private val GapBottomMin = 12.dp
private const val ART_COMFORT = 0.75f

/**
 * Now Playing's vertical rhythm under the top row: a square [art] slot and the
 * [stack] (title, artist, quality, progress, controls). The stack takes its
 * natural height; the art gets what is left after the three gaps, capped by the
 * width (so it lines up with the progress bar and the controls) and [ArtMax].
 * Any height still over (a tall phone) is split evenly above the art and below the
 * controls, so the whole group sits centred with its internal spacing unchanged.
 * Nothing scrolls and nothing overlaps: on a short screen the gaps give way first,
 * then the art.
 */
@Composable
private fun NowPlayingBody(
    modifier: Modifier = Modifier,
    stackModifier: Modifier = Modifier,
    art: @Composable () -> Unit,
    stack: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    androidx.compose.ui.layout.Layout(
        contents = listOf(
            art,
            { Column(modifier = stackModifier, horizontalAlignment = Alignment.CenterHorizontally, content = stack) },
        ),
        modifier = modifier,
    ) { (artM, stackM), constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val loose = constraints.copy(minWidth = w, maxWidth = w, minHeight = 0)
        val stackP = stackM.first().measure(loose)
        val avail = (h - stackP.height).coerceAtLeast(0)

        val full = minOf(w, ArtMax.roundToPx())
        val nominal = GapTop.roundToPx() + GapArt.roundToPx() + GapBottom.roundToPx()
        val floor = GapTopMin.roundToPx() + GapArtMin.roundToPx() + GapBottomMin.roundToPx()
        val shortfall = (full * ART_COMFORT - (avail - nominal)).coerceIn(0f, (nominal - floor).toFloat())
        val k = if (nominal > floor) 1f - shortfall / (nominal - floor) else 1f
        fun gap(max: androidx.compose.ui.unit.Dp, min: androidx.compose.ui.unit.Dp): Int =
            (min.roundToPx() + (max.roundToPx() - min.roundToPx()) * k).toInt()
        val gTop = gap(GapTop, GapTopMin)
        val gArt = gap(GapArt, GapArtMin)
        val gBottom = gap(GapBottom, GapBottomMin)
        val artSide = (avail - gTop - gArt - gBottom).coerceIn(0, full)
        val slack = (avail - gTop - gArt - gBottom - artSide).coerceAtLeast(0)

        val artP = artM.first().measure(androidx.compose.ui.unit.Constraints.fixed(artSide, artSide))
        layout(w, h) {
            val artY = gTop + slack / 2
            artP.place((w - artSide) / 2, artY)
            stackP.place(0, artY + artSide + gArt)
        }
    }
}

// ---------------------------------------------------------------------------
// Private composables
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// Art ↔ lyrics transition
// ---------------------------------------------------------------------------

/** Shared-element keys: the cover (big art ↔ header cover) and the progress + transport. */
private const val ART_SHARED_KEY = "np-art"
private const val FOOT_SHARED_KEY = "np-foot"

/** The views' crossfade: out quickly, in a beat later so the shared elements lead. */
private const val MODE_FADE_OUT_MS = 150
private const val MODE_FADE_IN_MS = 220
private const val MODE_FADE_IN_DELAY_MS = 90

/** The lyrics side's rise (the web's 8px over 320ms), staggered by this much per piece. */
private const val RISE_MS = 320
private const val RISE_STAGGER_MS = 50
private val RiseDistance = 8.dp

/** Space under the transport in lyrics mode, where the live-lyrics bar sits in the art view. */
private val LyricsFootBottom = 20.dp

/**
 * How the shared cover and transport move: a soft spring (the web's 420ms cover spring),
 * settling without a visible bounce.
 */
private val ModeBounds = BoundsTransform { _, _ ->
    spring(dampingRatio = 0.9f, stiffness = 380f, visibilityThreshold = Rect.VisibilityThreshold)
}

/** Fade in while rising [risePx] (the px of [RiseDistance]), after [delayMs]. */
private fun riseIn(risePx: Int, delayMs: Int): EnterTransition =
    fadeIn(tween(RISE_MS, delayMillis = delayMs, easing = FastOutSlowInEasing)) +
        slideInVertically(tween(RISE_MS, delayMillis = delayMs, easing = FastOutSlowInEasing)) { risePx }

/**
 * Title, artist · album, the sleep timer (leading edge) and the like heart (trailing edge),
 * under the big art. Tap the title/artist to open the artist profile; the trailing chevron
 * signals it's actionable, and swaps to a spinner while the artist name is being resolved.
 * Symmetric horizontal padding keeps the title/artist block optically centred under the art.
 */
@Composable
private fun TrackInfoBlock(
    track: com.stash.core.model.Track?,
    resolvingArtist: Boolean,
    onTrackInfoTapped: () -> Unit,
    sleepTimerState: SleepTimerController.State,
    onSleepTimerClick: () -> Unit,
    isLiked: Boolean,
    onLikeTap: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp)
                .then(
                    if (track != null) {
                        Modifier.clickable(enabled = !resolvingArtist) { onTrackInfoTapped() }
                    } else {
                        Modifier
                    },
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // A long title scrolls instead of ending in "…": every
                // character is worth more than an ellipsis here, and the
                // width the format badge used to take beside it now
                // belongs to the title — QualityLine below already says
                // FLAC and the bit depth, so the badge said it twice.
                Text(
                    text = track?.title ?: "Not Playing",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = npInk(),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .marqueeWhileShown(),
                )
                if (track != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    if (resolvingArtist) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = npInk(),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "Open artist",
                            tint = npInk().copy(alpha = 0.6f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(StackRhythm.TitleToArtist))

            // Collapse collaboration credits to the lead act
            // ("Metro Boomin, Travis Scott" → "Metro Boomin"), and
            // remember the split — this Text recomposes with every
            // player-state change.
            val displayArtist = remember(track) { track?.artist?.primaryArtist() }
            Text(
                text = buildString {
                    if (track != null && displayArtist != null) {
                        append(displayArtist)
                        if (track.album.isNotBlank()) {
                            append(" • ")
                            append(track.album)
                        }
                    }
                },
                fontSize = 15.sp,
                color = npInk().copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // Sleep timer — floated to the leading edge, mirroring the
        // Like heart's placement on the trailing edge. Accented
        // while a timer is armed, with the time left under the moon
        // ("23m", "0:45", "End"), so the state is visible at a
        // glance without opening the sheet.
        // The album accent is read in its own scope: the crossfade recomposes the button, not the title.
        if (track != null) WithAlbumChrome { chrome ->
            SleepTimerButton(
                state = sleepTimerState,
                onClick = onSleepTimerClick,
                activeTint = chrome.accent,
                idleTint = npInk().copy(alpha = 0.7f),
                buttonSize = 26.dp,
                iconSize = 20.dp,
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }

        // Like heart — floated to the trailing edge and vertically centred
        // against the title/artist block. The track's other actions live in
        // the top row (pinned) or the ⋮ menu. None for an All Songs file (no library row).
        if (track != null && !track.isScannedSong) {
            com.stash.core.ui.components.LikeButton(
                isLiked = isLiked,
                onTap = onLikeTap,
                unlikedTint = npInk().copy(alpha = 0.7f),
                size = 26.dp,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

/** Album art with a glow in the album accent behind it ([ArtGlow]; Coil 3 [AsyncImage]). */
@Composable
private fun AlbumArtSection(
    albumArtUrl: String?,
    albumArtPath: String?,
    zoom: com.stash.feature.nowplaying.ui.PageZoom,
    modifier: Modifier = Modifier,
) {
    val artModel = albumArtPath ?: albumArtUrl
    // An image that cannot load (deleted upstream, a 404, a network failure) must
    // read as "no art" — a quiet tile in the surface tone, same shape as the art —
    // not as a black hole in the middle of the screen. Coil draws nothing at all
    // for an error unless told otherwise, and never retries a failed URL.
    val missingArtColor = MaterialTheme.colorScheme.surfaceVariant
    val missingArt = remember(missingArtColor) { ColorPainter(missingArtColor) }
    // Shared with lyrics mode's compact cover: one fixed decode (see rememberNpArtRequest).
    val artRequest = com.stash.feature.nowplaying.ui.rememberNpArtRequest(artModel)

    // Pinched, the front lifts off to the whole screen like the pages inside.
    val lifted = remember(artRequest) {
        artModel?.let { com.stash.feature.nowplaying.ui.LiftedPage("front:$it", artRequest, "Album art") }
    }
    com.stash.feature.nowplaying.ui.LiftablePage(
        zoom = zoom,
        page = lifted,
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        ArtGlow()

        AsyncImage(
            model = artRequest,
            contentDescription = "Album art",
            contentScale = ContentScale.Crop,
            error = missingArt,
            fallback = missingArt,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(20.dp)),
        )
    }
}

/**
 * The glow in the album accent behind a face of the art, filling its box. The colour is read in
 * the layer's draw block, so the album colour's crossfade redraws the shadow and never recomposes.
 */
@Composable
private fun ArtGlow() {
    val chrome = LocalAlbumChrome.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(10.dp)
            // Modifier.shadow(40.dp, RoundedCornerShape(20.dp), ...) spelled out, so the
            // shadow colours are read at draw time.
            .graphicsLayer {
                shadowElevation = 40.dp.toPx()
                shape = ArtGlowShape
                clip = false
                val glow = chrome.accent.copy(alpha = 0.25f)
                ambientShadowColor = glow
                spotShadowColor = glow
            },
    )
}

/**
 * The album's back cover, the art's other face: at its own shape (an LP's back is square, a CD's
 * tray card wider than tall), centred in the art's square, with the front's glow and corners.
 */
@Composable
private fun AlbumBackSection(
    back: LoadedBackCover,
    zoom: com.stash.feature.nowplaying.ui.PageZoom,
    discogsPage: String?,
    modifier: Modifier = Modifier,
) {
    val lifted = remember(back, discogsPage) {
        com.stash.feature.nowplaying.ui.LiftedPage(back.request.data.toString(), back.request, "Back cover", discogsPage)
    }
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // A sliver of a scan (a spine, a panorama of a gatefold) would read as broken: past these
        // shapes it is cropped to them instead.
        com.stash.feature.nowplaying.ui.LiftablePage(
            zoom = zoom,
            page = lifted,
            modifier = Modifier.aspectRatio(back.aspectRatio.coerceIn(BACK_MIN_ASPECT, BACK_MAX_ASPECT)),
        ) {
            ArtGlow()
            AsyncImage(
                model = back.request,
                contentDescription = "Back cover",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(20.dp)),
            )
        }
    }
}

/**
 * One page inside the album (a booklet page, a gatefold panel, the inner sleeve), drawn like the
 * back: at its own shape once loaded, centred, with the glow and corners. A quiet tile in the
 * art's place while it loads or if it can't.
 */
@Composable
private fun InsidePageSection(
    url: String,
    description: String,
    zoom: com.stash.feature.nowplaying.ui.PageZoom,
    discogsPage: String?,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val request = remember(url) { com.stash.feature.nowplaying.ui.backCoverRequest(context, url) }
    val lifted = remember(request, description, discogsPage) {
        com.stash.feature.nowplaying.ui.LiftedPage(url, request, description, discogsPage)
    }
    var aspect by remember(url) { mutableStateOf(1f) }
    // A failed load (archive.org's bad moments) is tried again: a new try redraws the image anew.
    var attempt by remember(url) { mutableStateOf(1) }
    var failedAttempt by remember(url) { mutableStateOf(0) }
    LaunchedEffect(url, failedAttempt) {
        if (failedAttempt == 0 || failedAttempt >= com.stash.feature.nowplaying.ui.IMAGE_TRIES) return@LaunchedEffect
        kotlinx.coroutines.delay(com.stash.feature.nowplaying.ui.IMAGE_RETRY_MS * failedAttempt)
        attempt = failedAttempt + 1
    }
    val tile = MaterialTheme.colorScheme.surfaceVariant
    val tilePainter = remember(tile) { ColorPainter(tile) }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        com.stash.feature.nowplaying.ui.LiftablePage(
            zoom = zoom,
            page = lifted,
            modifier = Modifier.aspectRatio(aspect.coerceIn(BACK_MIN_ASPECT, BACK_MAX_ASPECT)),
        ) {
            ArtGlow()
            androidx.compose.runtime.key(attempt) {
                AsyncImage(
                    model = request,
                    contentDescription = description,
                    contentScale = ContentScale.Crop,
                    placeholder = tilePainter,
                    error = tilePainter,
                    onSuccess = { state ->
                        val size = state.painter.intrinsicSize
                        if (size.width > 0f && size.height > 0f) aspect = size.width / size.height
                    },
                    onError = { failedAttempt = attempt },
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(20.dp)),
                )
            }
        }
    }
}

/** A cycled page turned over by hand becomes the front after this: once the turn hides it. */
private const val CYCLE_RESET_AFTER_MS = 300L

/** The narrowest and widest a back cover is drawn (width over height). */
private const val BACK_MIN_ASPECT = 0.6f
private const val BACK_MAX_ASPECT = 1.7f

/** The album art glow's shape (the art's own corners). */
private val ArtGlowShape = RoundedCornerShape(20.dp)

/**
 * Playback controls row: shuffle, previous, play/pause, next, repeat. Wears the album colours
 * from [LocalAlbumChrome], the same accent and ink the mini player's play button wears, so the
 * button keeps its shade when the player opens.
 */
@Composable
private fun PlaybackControls(
    isPlaying: Boolean,
    isBuffering: Boolean,
    shuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    onPlayPauseClick: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeatMode: () -> Unit,
    onReact: ((String) -> Unit)? = null,
    /** False for a Listen Together listener: the room skips, so the slots stay empty rather than dead. */
    canSkip: Boolean = true,
) {
    val chrome = LocalAlbumChrome.current
    // Read here, so a crossfade recomposes this row only. The play button's fill is read at draw.
    val accentColor = chrome.accent
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Shuffle; in a session, an empty slot that keeps Play centred.
        if (onReact != null) {
            Spacer(Modifier.size(48.dp))
        } else IconButton(onClick = onToggleShuffle) {
            Icon(
                imageVector = Icons.Default.Shuffle,
                contentDescription = "Shuffle",
                tint = if (shuffleEnabled) accentColor else npInk().copy(alpha = 0.6f),
                modifier = Modifier.size(24.dp),
            )
        }

        // Previous
        if (!canSkip) Spacer(Modifier.size(48.dp)) else IconButton(onClick = onSkipPrevious) {
            Icon(
                imageVector = Icons.Default.SkipPrevious,
                contentDescription = "Previous",
                tint = npInk(),
                modifier = Modifier.size(36.dp),
            )
        }

        // Play / Pause — large accent circle. While the track is still
        // resolving/buffering, show a spinner in place of the icon so it
        // doesn't look frozen — but the button STAYS enabled: a slow or hung
        // stream resolve must never lock the user out of pausing (Media3's
        // COMMAND_PLAY_PAUSE is valid during STATE_BUFFERING).
        IconButton(
            onClick = onPlayPauseClick,
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                // A solid accent, exactly the mini player's play button (it used to fade to 70 %
                // over the ambient, which read as a darker shade when the player opened).
                .drawBehind { drawRect(chrome.accent) },
        ) {
            // On the accent circle, not the ambient ground: the accent's own ink (white,
            // or near-black on a pale accent), the mini player's play button's ink too.
            if (isBuffering) {
                CircularProgressIndicator(
                    color = chrome.onAccent,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(28.dp),
                )
            } else {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = chrome.onAccent,
                    modifier = Modifier.size(32.dp),
                )
            }
        }

        // Next
        if (!canSkip) Spacer(Modifier.size(48.dp)) else IconButton(onClick = onSkipNext) {
            Icon(
                imageVector = Icons.Default.SkipNext,
                contentDescription = "Next",
                tint = npInk(),
                modifier = Modifier.size(36.dp),
            )
        }

        // Repeat; in a session, React.
        if (onReact != null) {
            com.stash.feature.nowplaying.listen.ReactionButton(onReact = onReact)
        } else IconButton(onClick = onCycleRepeatMode) {
            Icon(
                imageVector = when (repeatMode) {
                    RepeatMode.ONE -> Icons.Default.RepeatOne
                    else -> Icons.Default.Repeat
                },
                contentDescription = "Repeat",
                tint = when (repeatMode) {
                    RepeatMode.OFF -> npInk().copy(alpha = 0.6f)
                    else -> accentColor
                },
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * Renders the codec/bitrate quality line beneath the artist · album row.
 * When [isStreaming] is `true` a small wifi glyph is prefixed so the
 * user can tell at a glance that playback is coming from the network
 * rather than a local file. The icon picks up
 * [MaterialTheme.colorScheme.primary] so it stands out against the
 * white-on-ambient quality text without clashing with the album-art
 * palette.
 *
 * Centered as a Row so the prefix-icon variant stays visually balanced
 * with the icon-less variant — the original `Text(textAlign = Center)`
 * call is preserved when there is nothing to prefix.
 */
@Composable
private fun QualityLine(
    qualityText: String,
    isStreaming: Boolean,
    onClick: (() -> Unit)? = null,
) {
    val tap = if (onClick != null) {
        Modifier.clickable(onClickLabel = "Show signal path", onClick = onClick)
    } else {
        Modifier
    }
    if (isStreaming) {
        Row(
            modifier = Modifier.fillMaxWidth().then(tap),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Wifi,
                contentDescription = "Streaming",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(12.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = qualityText,
                style = MaterialTheme.typography.bodySmall,
                color = npInk().copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    } else {
        Text(
            text = qualityText,
            style = MaterialTheme.typography.bodySmall,
            color = npInk().copy(alpha = 0.5f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().then(tap),
        )
    }
}

/**
 * Sleep-timer picker, opened from the icon beside the track title. Shows
 * the live countdown (to the second) or the end-of-track status (if armed), then the preset
 * durations, a Custom entry, End of Track, and — only while a timer is
 * running — a Cancel row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepTimerSheet(
    currentState: SleepTimerController.State,
    onSelectMinutes: (Int) -> Unit,
    onSelectEndOfTrack: () -> Unit,
    onSelectCustom: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    sheetState: androidx.compose.material3.SheetState,
) {
    val extendedColors = com.stash.core.ui.theme.StashTheme.extendedColors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = extendedColors.elevatedSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp),
        ) {
            Text(
                text = "Sleep Timer",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier
                    .padding(bottom = 20.dp)
                    .align(Alignment.CenterHorizontally),
            )

            when (currentState) {
                is SleepTimerController.State.Countdown -> {
                    // Live, to the second, while the sheet is open (it used to be worked out once,
                    // when the sheet opened, and sat still).
                    val remainingMs = rememberSleepTimerRemainingMs(currentState, everySecond = true) ?: 0L
                    val spoken = "Music pauses in ${sleepTimerSpoken(remainingMs, withSeconds = true)}"
                    Text(
                        text = "Music pauses in ${sleepTimerClock(remainingMs)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(bottom = 12.dp)
                            // "22 minutes 41 seconds", not a "22:41" read as a time of day.
                            .semantics { contentDescription = spoken },
                    )
                }
                SleepTimerController.State.EndOfTrack -> Text(
                    text = "End of track: music pauses when this song ends",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                SleepTimerController.State.Off -> Unit
            }

            listOf(15, 30, 45, 60, 120, 180).forEach { minutes ->
                SheetOptionRow(
                    icon = Icons.Default.Timer,
                    label = "$minutes min",
                    onClick = { onSelectMinutes(minutes) },
                )
                Spacer(Modifier.height(8.dp))
            }
            SheetOptionRow(icon = Icons.Default.Edit, label = "Custom", onClick = onSelectCustom)
            Spacer(Modifier.height(8.dp))
            SheetOptionRow(icon = Icons.Default.Bedtime, label = "End of Track", onClick = onSelectEndOfTrack)

            if (currentState != SleepTimerController.State.Off) {
                Spacer(Modifier.height(8.dp))
                SheetOptionRow(
                    icon = Icons.Default.Close,
                    label = "Cancel timer",
                    onClick = onCancel,
                )
            }
        }
    }
}

/** Plain numeric-minutes entry, reached via the sheet's "Custom" row. */
@Composable
private fun CustomSleepTimerDialog(
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var minutesText by remember { mutableStateOf("") }
    val parsedMinutes = minutesText.toIntOrNull()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom sleep timer") },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = minutesText,
                onValueChange = { if (it.all(Char::isDigit)) minutesText = it },
                label = { Text("Minutes") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                ),
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { parsedMinutes?.let(onConfirm) },
                enabled = parsedMinutes != null && parsedMinutes > 0,
            ) {
                Text("Start")
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeedSheet(
    currentSpeed: Float,
    onSelect: (Float) -> Unit,
    onCustom: () -> Unit,
    onDismiss: () -> Unit,
    sheetState: androidx.compose.material3.SheetState,
) {
    val extendedColors = com.stash.core.ui.theme.StashTheme.extendedColors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = extendedColors.elevatedSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp),
        ) {
            Text(
                text = "Playback speed",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier
                    .padding(bottom = 4.dp)
                    .align(Alignment.CenterHorizontally),
            )
            Text(
                text = speedLabel(currentSpeed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(bottom = 16.dp)
                    .align(Alignment.CenterHorizontally),
            )

            // Four to a row when every label fits, else two: one line per chip at any font size.
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val labelStyle = MaterialTheme.typography.labelMedium
                val measurer = androidx.compose.ui.text.rememberTextMeasurer()
                val density = androidx.compose.ui.platform.LocalDensity.current
                val widestLabelPx = remember(labelStyle, density) {
                    SpeedPresets.maxOf { measurer.measure(speedLabel(it), labelStyle, maxLines = 1).size.width }
                }
                val columns = with(density) {
                    speedPresetColumns(
                        rowWidthPx = constraints.maxWidth,
                        widestChipPx = widestLabelPx + SpeedChipLabelInset.roundToPx(),
                        gapPx = 8.dp.roundToPx(),
                    )
                }
                Column {
                    SpeedPresets.chunked(columns).forEach { rowSpeeds ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            rowSpeeds.forEach { s ->
                                FilterChip(
                                    selected = currentSpeed == s,
                                    onClick = { onSelect(s) },
                                    label = {
                                        Text(
                                            text = speedLabel(s),
                                            style = labelStyle,
                                            modifier = Modifier.fillMaxWidth(),
                                            textAlign = TextAlign.Center,
                                            maxLines = 1,
                                            softWrap = false,
                                        )
                                    },
                                    colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    ),
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            SheetOptionRow(icon = Icons.Default.Edit, label = "Custom", onClick = onCustom)
        }
    }
}

/**
 * A FilterChip's label sits inside 8 dp of chip padding and 8 dp of label padding on each side
 * (Material 3's chip layout); the column choice above measures the label plus this.
 */
private val SpeedChipLabelInset = 32.dp

/** Free-form speed entry, reached via the sheet's "Custom" row. */
@Composable
private fun CustomSpeedDialog(
    initial: Float,
    onConfirm: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(if (initial == 1f) "" else formatSpeed(initial).removeSuffix("x")) }
    val parsed = parseCustomSpeed(text)
    val valid = parsed != null
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom speed") },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { new -> customSpeedInput(new)?.let { text = it } },
                label = { Text("Speed (0.1 – 4.0)") },
                suffix = { Text("x") },
                singleLine = true,
                isError = text.isNotEmpty() && !valid,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onConfirm) }, enabled = valid) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@androidx.compose.ui.tooling.preview.Preview(
    name = "QualityLine — streaming",
    showBackground = true,
    backgroundColor = 0xFF101012,
)
@Composable
private fun PreviewQualityLineStreaming() {
    com.stash.core.ui.theme.StashTheme {
        QualityLine(
            qualityText = "OPUS \u00B7 160 kbps",
            isStreaming = true,
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(
    name = "QualityLine — local",
    showBackground = true,
    backgroundColor = 0xFF101012,
)
@Composable
private fun PreviewQualityLineLocal() {
    com.stash.core.ui.theme.StashTheme {
        QualityLine(
            qualityText = "FLAC \u00B7 24-bit/96.0 kHz \u00B7 4233 kbps",
            isStreaming = false,
        )
    }
}

/** Lyrics mode's header cover radius (CompactCover's clip), for the travelling cover to land on. */
private const val CompactCoverCornerDp = 8f

/**
 * The ambient, under the player sheet: composed only while the sheet is above MINI (parked at
 * MINI its orbit animations would tick for nothing), fading in over 10..70 % of the opening. Its
 * own recompose scope, so composing it never recomposes the screen. Without a sheet: always there.
 */
@Composable
private fun SheetAmbient(sheet: PlayerSheetState?, content: @Composable (Modifier) -> Unit) {
    if (sheet == null) {
        content(Modifier.fillMaxSize())
        return
    }
    val shown by remember(sheet) { androidx.compose.runtime.derivedStateOf { sheet.progress > 0f } }
    if (!shown) return
    content(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = PlayerSheetMath.ambientAlpha(sheet.progress) },
    )
}

/**
 * The title's endless marquee, only while the player sheet shows Now Playing: parked under the
 * mini player (composed but not placed) the marquee's frame loop kept running with nothing on
 * screen. It starts again from the title's beginning on each opening.
 */
@Composable
private fun Modifier.marqueeWhileShown(): Modifier =
    if (com.stash.feature.nowplaying.ui.LocalNowPlayingShown.current) basicMarquee(
        iterations = Int.MAX_VALUE,
        // Compose's default 1.2 s pause between loops plus 2 s (owner, 2026-10-22).
        repeatDelayMillis = 3_200,
    ) else this

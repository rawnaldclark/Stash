package com.stash.feature.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.stash.core.model.RepeatMode
import com.stash.core.model.isFlac
import com.stash.core.ui.components.SaveToPlaylistSheet
import com.stash.core.ui.theme.LocalIsAmoledTheme
import com.stash.feature.nowplaying.ui.AmbientBackground
import com.stash.feature.nowplaying.ui.GlowingProgressBar
import com.stash.feature.nowplaying.ui.LiveLyricsBar
import com.stash.feature.nowplaying.ui.LyricsBottomSheet
import com.stash.feature.nowplaying.ui.QueueBottomSheet

/** Light-ground ink for the pastel-wash Now Playing (the app's plum-black). */
private val NpInkLight = Color(0xFF241C36)

/**
 * Foreground ink for the Now Playing surface: white on the dark ambient,
 * plum-black on the light pastel wash. Reads the *resolved* theme background
 * (not the system setting) so manual/AMOLED overrides pick the right ink.
 */
@Composable
private fun npInk(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color.White else NpInkLight

/**
 * Album palettes can be too pale to hold contrast on the light wash — darken
 * toward ink until they do. Dark theme passes the raw palette through.
 */
@Composable
private fun npAccent(raw: Color): Color =
    if (MaterialTheme.colorScheme.background.luminance() >= 0.5f && raw.luminance() > 0.55f) {
        lerp(raw, NpInkLight, 0.35f)
    } else {
        raw
    }

/**
 * Full-screen Now Playing screen with premium visual design.
 *
 * Displays album art with ambient background, playback controls, progress bar,
 * and track information. Colors are extracted from album art via Palette API.
 * The ambient ground + inks follow the app theme: near-black with drifting
 * art-derived orbs in dark, the same orbs as a pastel wash over lavender
 * paper in light.
 *
 * @param onDismiss Callback invoked when the user taps the dismiss (down arrow) button.
 * @param viewModel The [NowPlayingViewModel] provided by Hilt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    onDismiss: () -> Unit,
    onNavigateToArtist: (id: String, name: String, avatarUrl: String?, focusAlbum: String?) -> Unit,
    viewModel: NowPlayingViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val track = uiState.currentTrack
    val resolvingArtist by viewModel.resolvingArtist.collectAsStateWithLifecycle()
    val isDownloadingCurrent by viewModel.isDownloadingCurrent.collectAsStateWithLifecycle()
    val exportingLyricsTrackId by viewModel.exportingLyricsTrackId.collectAsStateWithLifecycle()
    val radioLabel by viewModel.radioSeedLabel.collectAsStateWithLifecycle()
    val ambientAnimationEnabled = viewModel.ambientAnimationEnabled.collectAsStateWithLifecycle().value ?: return
    var showQueue by remember { mutableStateOf(false) }
    var showSaveSheet by remember { mutableStateOf(false) }
    val shareTrack by viewModel.shareTrack.collectAsStateWithLifecycle()
    // "This song is wrong" dialog — shown when the flag icon is tapped.
    // Decouples the Flag button (which is just "there's a problem") from
    // the action (find a replacement / delete / delete + block).
    var showWrongMatchDialog by remember { mutableStateOf(false) }

    // Scroll state is intentionally not keyed by track — on tall screens
    // content doesn't overflow so scroll stays at 0; on narrow screens the
    // user's scroll position aligns with controls and we want it preserved
    // across track changes.
    val scrollState = rememberScrollState()

    // One-shot Toast confirmation for the "wrong match" flag action. Toast
    // instead of Snackbar so we don't have to restructure the screen into
    // a Scaffold — the full-screen ambient background would fight with
    // Material's Snackbar surface anyway.
    val toastContext = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.userMessages.collect { msg ->
            android.widget.Toast.makeText(toastContext, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    // Tap-to-artist: the VM resolves the artist name → browseId off the main
    // thread and emits a one-shot nav target; forward it to the host.
    LaunchedEffect(Unit) {
        viewModel.artistNavEvents.collect { t ->
            onNavigateToArtist(t.artistId, t.name, t.avatarUrl, t.focusAlbum)
        }
    }

    // Share sheet (fork issue ParaliyzedEvo/Stash#40) — VM-driven with the
    // FULL DB track: the player-state Track is a slim media-session
    // reconstruction whose spotifyUri/youtubeId are null, which is why the
    // link rows never appeared when this read uiState.currentTrack.
    shareTrack?.let { full ->
        com.stash.core.ui.components.ShareTrackSheet(
            title = full.title,
            artist = full.artist,
            spotifyUri = full.spotifyUri,
            youtubeId = full.youtubeId,
            onDismiss = viewModel::onShareDismissed,
        )
    }

    // Queue bottom sheet
    if (showQueue) {
        QueueBottomSheet(
            queue = uiState.queue,
            currentIndex = uiState.currentIndex,
            accentColor = npAccent(uiState.vibrantColor),
            onDismiss = { showQueue = false },
            onTrackClick = { index ->
                viewModel.onSkipToQueueIndex(index)
                showQueue = false
            },
            onRemoveTrack = viewModel::onRemoveFromQueue,
            onMoveTrack = viewModel::onMoveInQueue,
        )
    }

    // Lyrics bottom sheet — opened by tapping the LiveLyricsBar pinned at
    // the screen's bottom edge (`onShowLyrics`); the bar and the sheet share
    // the one subscription collected just below.
    val showLyrics by viewModel.lyricsSheetOpen.collectAsStateWithLifecycle()
    // Collected unconditionally (not just while the sheet is open): the bar
    // needs the state, and this subscription is what arms the ViewModel's
    // WhileSubscribed fetch trigger from screen-open onward. The screen
    // already recomposes every 250ms from uiState position ticks, so the
    // extra position collect adds no new recomposition pressure.
    val lyricsState by viewModel.lyricsViewState.collectAsStateWithLifecycle()
    val lyricsPositionMs by viewModel.currentPositionMs.collectAsStateWithLifecycle()
    val liveLyricsEnabled by viewModel.liveLyricsBarEnabled.collectAsStateWithLifecycle()
    if (showLyrics) {
        LyricsBottomSheet(
            state = lyricsState,
            currentPositionMs = lyricsPositionMs,
            liveLyricsEnabled = liveLyricsEnabled,
            onLiveLyricsToggle = viewModel::setLiveLyricsBarEnabled,
            onSeek = viewModel::onLyricsLineSeek,
            canSaveToFile = track?.isDownloaded == true,
            savingToFile = exportingLyricsTrackId != null,
            onSaveToFile = viewModel::exportLyricsForCurrentTrack,
            onRetry = viewModel::onLyricsRetry,
            onDismiss = viewModel::onDismissLyrics,
        )
    }

    // Save to playlist bottom sheet
    if (showSaveSheet && track != null) {
        SaveToPlaylistSheet(
            playlists = uiState.userPlaylists,
            onSaveToPlaylist = { playlistId ->
                viewModel.saveTrackToPlaylist(track.id, playlistId)
            },
            onCreatePlaylist = { name ->
                viewModel.createPlaylistAndAddTrack(name, track.id)
            },
            onDismiss = { showSaveSheet = false },
        )
    }

    // "This song is wrong" — 3-option dialog triggered by the flag icon.
    // Separated from the icon's direct action so the same entry point
    // covers three very different outcomes: mark for replacement, delete
    // the file, delete + permanently block.
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
                    androidx.compose.material3.OutlinedButton(
                        onClick = {
                            viewModel.deleteCurrentTrack(alsoBlock = false)
                            showWrongMatchDialog = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        androidx.compose.material3.Text("Delete from library")
                    }
                    androidx.compose.material3.Button(
                        onClick = {
                            viewModel.deleteCurrentTrack(alsoBlock = true)
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

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Ambient animated background behind everything — dark canvas, the
        // light pastel wash, or a dead-black AMOLED ground, following the
        // resolved app theme.
        if (ambientAnimationEnabled) AmbientBackground(
            dominantColor = uiState.dominantColor,
            vibrantColor = uiState.vibrantColor,
            mutedColor = uiState.mutedColor,
            lightMode = MaterialTheme.colorScheme.background.luminance() >= 0.5f,
            amoledMode = LocalIsAmoledTheme.current,
            modifier = Modifier.fillMaxSize(),
        )

        Column(modifier = Modifier.fillMaxSize()) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .statusBarsPadding(),
            ) {
                // #333: the fixed chrome around the art (top bar, title block,
                // progress, controls, spacers) needs ~460dp at full spacing.
                // Screens that fall short — large display/font scale, shorter
                // phones, the lyrics bar eating a row — used to push the
                // PLAY CONTROLS below the fold. The art absorbs the shortfall
                // instead (280dp design size down to 180dp), and compact mode
                // halves the decorative spacers. verticalScroll stays as the
                // net for anything below 180dp of slack.
                val artSize = (this.maxHeight - 460.dp).coerceIn(180.dp, 280.dp)
                // Spacers stay at FULL size while the art alone can absorb
                // the shortfall — art + spacers then fill the column exactly,
                // with no dead band. Compact (halved spacers) kicks in only
                // when the art is genuinely squeezed toward its floor. A
                // threshold at the design size was a 1dp cliff that dropped
                // the Pixel itself into compact mode: title crowding the art
                // and ~200dp of dead space pooling above the lyrics bar.
                val compact = artSize <= 200.dp
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // -- Top bar: dismiss, radio, flag, download, save, queue --
                TopBar(
                    onDismiss = onDismiss,
                    onFlagWrongMatch = { showWrongMatchDialog = true },
                    onSaveClick = { showSaveSheet = true },
                    onQueueClick = { showQueue = true },
                    hasTrack = uiState.hasTrack,
                    queueSize = uiState.queueSize,
                    onDownloadTap = viewModel::toggleDownloadForCurrentTrack,
                    isDownloaded = uiState.currentTrack?.isDownloaded == true,
                    isDownloading = isDownloadingCurrent,
                    // Radio toggle: start a station seeded from this song, or stop
                    // the active one. Lives in the TopBar icon row (no vertical
                    // footprint); accented while a station is running.
                    radioActive = radioLabel != null,
                    onStartRadio = viewModel::startRadioFromCurrent,
                    onStopRadio = viewModel::stopRadio,
                    accentColor = npAccent(uiState.vibrantColor),
                )

                Spacer(modifier = Modifier.height(if (compact) 12.dp else 24.dp))

                // -- Album art --
                AlbumArtSection(
                    albumArtUrl = track?.albumArtUrl,
                    albumArtPath = track?.albumArtPath,
                    accentColor = npAccent(uiState.vibrantColor),
                    artSize = artSize,
                    onBitmapLoaded = viewModel::onAlbumArtLoaded,
                )

                Spacer(modifier = Modifier.height(if (compact) 16.dp else 32.dp))

                // -- Track info -- (tap the title/artist to open the artist
                // profile; the trailing chevron signals it's actionable, and
                // swaps to a spinner while the artist name is being resolved).
                // The like heart floats at the right edge (relocated out of the
                // crowded top icon row); symmetric horizontal padding keeps the
                // title/artist block optically centred under the album art.
                Box(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 40.dp)
                        .then(
                            if (track != null) {
                                Modifier.clickable(enabled = !resolvingArtist) {
                                    viewModel.onTrackInfoTapped()
                                }
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
                        Text(
                            text = track?.title ?: "Not Playing",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = npInk(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (track != null) {
                            Spacer(modifier = Modifier.width(8.dp))
                            com.stash.core.ui.components.FlacBadge(
                                fileFormat = track.fileFormat,
                                bitsPerSample = track.bitsPerSample,
                                sampleRateHz = track.sampleRateHz,
                                size = 18.dp,
                                tint = npInk(),
                            )
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

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = buildString {
                            if (track != null) {
                                append(track.artist)
                                if (track.album.isNotBlank()) {
                                    append(" \u2022 ")
                                    append(track.album)
                                }
                            }
                        },
                        fontSize = 14.sp,
                        color = npInk().copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                    // Share — on the track itself (leading edge), mirroring the
                    // heart. Loads the FULL DB row before opening the sheet so
                    // the Spotify/YouTube link rows actually have identities.
                    if (track != null) {
                        IconButton(
                            onClick = viewModel::onShareCurrent,
                            modifier = Modifier.align(Alignment.CenterStart),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share song",
                                tint = npInk().copy(alpha = 0.7f),
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    // Like heart — relocated from the top icon row to a cleaner
                    // primary spot, floated to the trailing edge and vertically
                    // centred against the title/artist block.
                    if (track != null) {
                        com.stash.core.ui.components.LikeButton(
                            isLiked = uiState.currentTrack?.stashLikedAt != null,
                            onTap = viewModel::onLikeTap,
                            unlikedTint = npInk().copy(alpha = 0.7f),
                            size = 26.dp,
                            modifier = Modifier.align(Alignment.CenterEnd),
                        )
                    }
                }

                // Quality line — codec + bit-depth/sample-rate + bitrate, when known.
                // Sized smaller than the artist/album line; degrades gracefully when
                // some fields are missing (returns a partial line, not nothing).
                // When the active MediaItem is sourced from an http(s) URI (Kennyy
                // stream rather than a local file), a small wifi glyph prefixes
                // the line so the user knows playback is using their connection.
                if (track != null) {
                    val qualityText = trackQualityText(track)
                    if (qualityText != null) {
                        Spacer(modifier = Modifier.height(2.dp))
                        QualityLine(
                            qualityText = qualityText,
                            isStreaming = uiState.isStreaming,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(if (compact) 16.dp else 28.dp))

                // -- Progress bar --
                GlowingProgressBar(
                    progress = uiState.progressFraction,
                    accentColor = npAccent(uiState.vibrantColor),
                    elapsedMs = uiState.currentPositionMs,
                    totalMs = uiState.durationMs,
                    onSeek = viewModel::onSeekTo,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(if (compact) 12.dp else 20.dp))

                // -- Playback controls --
                PlaybackControls(
                    isPlaying = uiState.isPlaying,
                    isBuffering = uiState.isBuffering,
                    shuffleEnabled = uiState.shuffleEnabled,
                    repeatMode = uiState.repeatMode,
                    accentColor = npAccent(uiState.vibrantColor),
                    onPlayPauseClick = viewModel::onPlayPauseClick,
                    onSkipNext = viewModel::onSkipNext,
                    onSkipPrevious = viewModel::onSkipPrevious,
                    onToggleShuffle = viewModel::onToggleShuffle,
                    onCycleRepeatMode = viewModel::onCycleRepeatMode,
                )

                Spacer(modifier = Modifier.height(if (compact) 24.dp else 48.dp))
            }
            }

            // Live-lyrics bar — sits exactly where the MiniPlayer is on other
            // screens (the scaffold hides MiniPlayer on this route), directly
            // above the nav bar. Zero-height when Hidden, so the content
            // column keeps the full screen for lyric-less tracks.
            LiveLyricsBar(
                state = lyricsState,
                currentPositionMs = lyricsPositionMs,
                accentColor = npAccent(uiState.vibrantColor),
                liveEnabled = liveLyricsEnabled,
                onTap = viewModel::onShowLyrics,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Private composables
// ---------------------------------------------------------------------------

/**
 * Top bar with dismiss button, "NOW PLAYING" label, save-to-playlist button,
 * and queue button.
 *
 * @param onDismiss    Callback when the down-arrow is tapped.
 * @param onSaveClick  Callback when the save/bookmark icon is tapped.
 * @param onQueueClick Callback when the queue icon is tapped.
 * @param hasTrack     Whether a track is currently loaded (save button is hidden otherwise).
 * @param queueSize    Number of tracks in the queue, shown as a badge hint.
 */
@Composable
private fun TopBar(
    onDismiss: () -> Unit,
    onFlagWrongMatch: () -> Unit,
    onSaveClick: () -> Unit,
    onQueueClick: () -> Unit,
    hasTrack: Boolean,
    queueSize: Int,
    onDownloadTap: () -> Unit,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    radioActive: Boolean,
    onStartRadio: () -> Unit,
    onStopRadio: () -> Unit,
    accentColor: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Dismiss",
                tint = npInk(),
                modifier = Modifier.size(28.dp),
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Radio toggle — start a station from the current song, or stop the
        // running one. Accent tint signals an active station.
        if (hasTrack) {
            IconButton(onClick = { if (radioActive) onStopRadio() else onStartRadio() }) {
                Icon(
                    imageVector = Icons.Default.Radio,
                    contentDescription = if (radioActive) "Stop radio" else "Start radio",
                    tint = if (radioActive) accentColor else npInk(),
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        // Flag as wrong match — only shown when a track is loaded. Lives
        // here (not in the Playlist Detail row menu) because Now Playing
        // is where the user actually realises "this isn't the right song"
        // — their ears are the ground truth.
        if (hasTrack) {
            IconButton(onClick = onFlagWrongMatch) {
                Icon(
                    imageVector = Icons.Default.Flag,
                    contentDescription = "Flag as wrong match",
                    tint = npInk(),
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        // Download / Remove-download toggle — single button that flips
        // based on the current track's on-disk state. Streaming-mode
        // users use this to grab the song they're listening to right now
        // without leaving Now Playing. While a download is in flight a
        // spinner replaces the icon so it isn't a silent background job.
        if (hasTrack) {
            if (isDownloading) {
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.5.dp,
                        color = npInk(),
                    )
                }
            } else {
                IconButton(onClick = onDownloadTap) {
                    Icon(
                        imageVector = if (isDownloaded) Icons.Default.DownloadDone else Icons.Default.Download,
                        contentDescription = if (isDownloaded) "Remove download" else "Download",
                        tint = npInk(),
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }

        // Save to playlist — only shown when a track is loaded.
        if (hasTrack) {
            IconButton(onClick = onSaveClick) {
                Icon(
                    imageVector = Icons.Default.BookmarkBorder,
                    contentDescription = "Save to Playlist",
                    tint = npInk(),
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        IconButton(onClick = onQueueClick) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                contentDescription = "Queue ($queueSize tracks)",
                tint = npInk(),
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * Album art with a colored glow shadow behind it.
 *
 * Uses Coil 3 [AsyncImage] to load the art. When the image is loaded
 * successfully, the bitmap is forwarded to [onBitmapLoaded] for palette
 * extraction.
 */
@Composable
private fun AlbumArtSection(
    albumArtUrl: String?,
    albumArtPath: String?,
    accentColor: Color,
    artSize: androidx.compose.ui.unit.Dp = 280.dp,
    onBitmapLoaded: (android.graphics.Bitmap?) -> Unit,
) {
    val context = LocalContext.current
    val artModel = albumArtPath ?: albumArtUrl
    // remember: an inline ImageRequest.Builder is a new object every
    // recomposition, which makes Coil re-evaluate the request each time this
    // recomposes (and this screen recomposes on every 250ms position tick).
    val artRequest = remember(context, artModel) {
        ImageRequest.Builder(context)
            .data(artModel)
            .allowHardware(false) // Required for Palette bitmap extraction.
            .build()
    }

    Box(contentAlignment = Alignment.Center) {
        // Glow behind the artwork.
        Box(
            modifier = Modifier
                .size(artSize - 20.dp)
                .shadow(
                    elevation = 40.dp,
                    shape = RoundedCornerShape(20.dp),
                    ambientColor = accentColor.copy(alpha = 0.25f),
                    spotColor = accentColor.copy(alpha = 0.25f),
                ),
        )

        AsyncImage(
            model = artRequest,
            contentDescription = "Album art",
            contentScale = ContentScale.Crop,
            onState = { state ->
                if (state is AsyncImagePainter.State.Success) {
                    try {
                        val bitmap = state.result.image.toBitmap()
                        onBitmapLoaded(bitmap)
                    } catch (_: Exception) {
                        // Bitmap extraction failed; palette will use defaults.
                        onBitmapLoaded(null)
                    }
                }
            },
            modifier = Modifier
                .size(artSize)
                .clip(RoundedCornerShape(20.dp)),
        )
    }
}

/**
 * Playback controls row: shuffle, previous, play/pause, next, repeat.
 */
@Composable
private fun PlaybackControls(
    isPlaying: Boolean,
    isBuffering: Boolean,
    shuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    accentColor: Color,
    onPlayPauseClick: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeatMode: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Shuffle
        IconButton(onClick = onToggleShuffle) {
            Icon(
                imageVector = Icons.Default.Shuffle,
                contentDescription = "Shuffle",
                tint = if (shuffleEnabled) accentColor else npInk().copy(alpha = 0.6f),
                modifier = Modifier.size(24.dp),
            )
        }

        // Previous
        IconButton(onClick = onSkipPrevious) {
            Icon(
                imageVector = Icons.Default.SkipPrevious,
                contentDescription = "Previous",
                tint = npInk(),
                modifier = Modifier.size(36.dp),
            )
        }

        // Play / Pause — large gradient circle. While the track is still
        // resolving/buffering, show a spinner in place of the icon so it
        // doesn't look frozen — but the button STAYS enabled: a slow or hung
        // stream resolve must never lock the user out of pausing (Media3's
        // COMMAND_PLAY_PAUSE is valid during STATE_BUFFERING).
        IconButton(
            onClick = onPlayPauseClick,
            modifier = Modifier
                .size(64.dp)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(accentColor, accentColor.copy(alpha = 0.7f)),
                    ),
                    shape = CircleShape,
                ),
        ) {
            // On the accent-gradient circle, not the ambient ground — stays
            // white in both themes (the accent is contrast-adjusted instead).
            if (isBuffering) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(28.dp),
                )
            } else {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp),
                )
            }
        }

        // Next
        IconButton(onClick = onSkipNext) {
            Icon(
                imageVector = Icons.Default.SkipNext,
                contentDescription = "Next",
                tint = npInk(),
                modifier = Modifier.size(36.dp),
            )
        }

        // Repeat
        IconButton(onClick = onCycleRepeatMode) {
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
 * Formats a one-line quality summary for the Now Playing screen.
 *
 * Examples:
 *   - All four fields known:  `FLAC · 24-bit/96.0 kHz · 4233 kbps`
 *   - Codec + bitrate only:    `OPUS · 160 kbps`
 *   - Codec only:              `FLAC` (data not yet backfilled)
 *
 * Returns null only when the codec is blank — in that case the caller
 * should render no line at all.
 */
private fun trackQualityText(track: com.stash.core.model.Track): String? {
    // v0.9.13 fix: tracks downloaded before format-tracking was wired (pre-v0.9.11)
    // default to file_format = "opus" regardless of the actual codec — so a FLAC
    // file would render "OPUS · 4233 kbps", which is the source of "every track says
    // Opus" complaints. The Library Health backfill writes correct values from disk
    // but only when the user opens that screen. Cheap interim correction: if the
    // track has a downloaded filePath, prefer the file extension as canonical.
    val extension = track.filePath
        ?.takeIf { it.isNotBlank() }
        ?.substringAfterLast('.', missingDelimiterValue = "")
        ?.lowercase()
    val codec = when (extension) {
        "flac", "alac", "wav", "ape", "tta", "wv", "aiff" -> extension!!.uppercase()
        "opus", "m4a", "mp3", "ogg", "aac" -> extension!!.uppercase()
        else -> track.fileFormat.takeIf { it.isNotBlank() }?.uppercase() ?: return null
    }
    val bitDepth = track.bitsPerSample
    val sampleRateKHz = track.sampleRateHz?.let { it / 1000.0 }
    val bitrate = track.qualityKbps.takeIf { it > 0 }
    return buildList {
        add(codec)
        if (bitDepth != null && sampleRateKHz != null) {
            add("${bitDepth}-bit/${"%.1f".format(sampleRateKHz)} kHz")
        }
        if (bitrate != null) add("$bitrate kbps")
        // Flag the YouTube fallback so the user can tell when a track is
        // playing from yt-dlp/InnerTube extraction rather than Qobuz. The
        // codec ("AAC") alone doesn't convey this — Qobuz also serves AAC
        // at MP3_320 tier. Only the streamOrigin field distinguishes the
        // two. We don't badge "via Kennyy" / "via squid" because those
        // are the expected primary sources; only the lossy fallback
        // deserves a callout.
        if (track.streamOrigin == "youtube") add("via YT")
    }.joinToString(" · ")
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
) {
    if (isStreaming) {
        Row(
            modifier = Modifier.fillMaxWidth(),
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
            modifier = Modifier.fillMaxWidth(),
        )
    }
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

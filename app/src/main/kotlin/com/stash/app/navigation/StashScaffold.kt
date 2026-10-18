package com.stash.app.navigation

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.safeDrawing
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import com.stash.core.ui.components.LocalChromeBlurEnabled
import com.stash.data.ytmusic.model.AlbumSource
import com.stash.feature.community.CommunityPostingHost
import com.stash.feature.community.rememberCommunityPosting
import com.stash.feature.nowplaying.PlayerMessagesToaster
import com.stash.feature.nowplaying.sheet.CollapseOnNavigation
import com.stash.feature.nowplaying.sheet.LocalPlayerSheet
import com.stash.feature.nowplaying.sheet.PlayerSheetDeepLink
import com.stash.feature.nowplaying.sheet.PlayerSheetAnchor
import com.stash.feature.nowplaying.sheet.PlayerSheetBackHandler
import com.stash.feature.nowplaying.sheet.PlayerSheetMath
import com.stash.feature.nowplaying.sheet.PlayerSheetOverlay
import com.stash.feature.nowplaying.sheet.rememberPlayerSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.stash.core.data.prefs.StartTab
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.stash.app.RequestNotificationPermissionOnce
import com.stash.data.download.lossless.quota.LosslessQuotaNotice
import com.stash.data.download.lossless.quota.LosslessQuotaText
import com.stash.data.download.lossless.squid.CaptchaExpiredNotifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import com.stash.feature.nowplaying.MiniPlayer
import com.stash.feature.nowplaying.PlayerQueueSheet
import com.stash.app.ambient.AlbumColorsViewModel
import com.stash.core.ui.ambient.LocalAlbumChrome
import com.stash.core.ui.ambient.ProvideAlbumChrome
import com.stash.core.ui.components.LocalBottomChromeInset
import com.stash.core.ui.components.LocalChromeHazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/**
 * Root scaffold for the Stash app.
 *
 * Hosts the [StashNavHost], the bottom navigation bar, and the glass [MiniPlayer]
 * floating just above it. Every screen draws edge to edge under that bottom chrome so
 * the glass can blur it (Haze: the nav host is the `hazeSource`, the mini player and tab
 * bar the effects); screens pad their scrolling content by [LocalBottomChromeInset].
 * Also publishes the app-wide album colour ([LocalAlbumChrome]), hosts the queue
 * sheet the mini player opens, and shows the daily lossless allowance's heads-ups
 * ([losslessNotices]) as a snackbar above the bottom chrome on any screen.
 *
 * The player is one sheet over all of it ([PlayerSheetOverlay], outside the NavHost): the mini
 * player is its handle, and dragging it (or tapping it) grows it into Now Playing with the finger,
 * while the page behind scales back under a scrim and the tab bar slides away. The bottom chrome is
 * always laid out (only moved by layers), so opening the player never re-lays out the page.
 */
@Composable
fun StashScaffold(
    pendingDeepLink: String? = null,
    onDeepLinkConsumed: () -> Unit = {},
    startTab: StartTab = StartTab.HOME,
    losslessNotices: Flow<LosslessQuotaNotice> = emptyFlow(),
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // The player: mini player <-> Now Playing as one finger-driven sheet.
    val playerSheet = rememberPlayerSheetState()
    // At rest at MINI: the scaffold's own dialogs and cards may show. Recomposes only on the change.
    val playerClosed by remember(playerSheet) {
        derivedStateOf { playerSheet.settledAnchor == PlayerSheetAnchor.MINI && playerSheet.targetAnchor == PlayerSheetAnchor.MINI }
    }
    // Open and resting at FULL: the app below is covered, for TalkBack too.
    val playerCovering by remember(playerSheet) {
        derivedStateOf { playerSheet.settledAnchor != PlayerSheetAnchor.MINI && playerSheet.targetAnchor != PlayerSheetAnchor.MINI }
    }
    // The page under an open player is held at STARTED (it pauses, as it did under the old Now
    // Playing destination) and resumes as the player closes.
    val pageLifecycleOwner = rememberCoveredPageLifecycleOwner(covered = playerCovering)

    // The glass blurs only while the player is still (a page moving under it costs a blur a frame).
    val chromeBlurEnabled: () -> Boolean = remember(playerSheet) { { PlayerSheetMath.blurEnabled(playerSheet.progress) } }

    // Anywhere the app navigates while the player is open (an artist from Now Playing, a link,
    // a tab switch from the snackbar), the player steps aside (owner decision 1); see
    // CollapseOnNavigation for the restored-graph rule.
    DisposableEffect(navController, playerSheet) {
        val collapser = CollapseOnNavigation(playerSheet)
        val listener = NavController.OnDestinationChangedListener { _, _, _ -> collapser.onDestinationChanged() }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }

    // Stash Community's posting sheets and LocalPostToCommunity, over the NavHost AND the player
    // (Now Playing's "Post to Community" lives outside the NavHost now).
    val communityPosting = rememberCommunityPosting()

    // Whether a detail screen is currently in multi-select mode. Detail screens
    // signal this via `onSelectionModeChanged`; while it is true we hide the
    // whole bottom chrome (mini-player AND nav bar) so the screen's own bottom
    // selection action bar owns the bottom edge instead of stacking on / being
    // crowded by it (premium multi-select pattern, avoids mis-taps).
    var selectionActive by remember { mutableStateOf(false) }

    // The queue sheet opened from the mini player's Up next button, over any screen.
    var showQueue by rememberSaveable { mutableStateOf(false) }

    // One album colour for the whole app (wordmark, selected tab, mini player), computed
    // at the player level so it follows the song whether or not Now Playing is open.
    val albumColorsViewModel: AlbumColorsViewModel = hiltViewModel()
    val albumColors by albumColorsViewModel.colors.collectAsStateWithLifecycle()

    // The backdrop the bottom chrome frosts: the nav host, drawn under the bars.
    val hazeState = rememberHazeState()

    // Safeguard: a selection-capable screen normally clears its selection on
    // every exit path (✕ / Back / last-deselect), which fires
    // `onSelectionModeChanged(false)` before it leaves composition. Resetting on
    // route change as well guarantees the mini-player can never stay hidden if a
    // screen leaves the stack without that signal landing.
    LaunchedEffect(currentRoute) { selectionActive = false }

    // Android 13+ runtime permission for notifications. One-shot per install.
    RequestNotificationPermissionOnce()

    // The lossless allowance's once-a-day heads-ups (50 left, limit reached), with the
    // supporter nudge; "Unlock" opens Audio & Quality, where the Ko-fi claim is. Collected
    // while the app is visible: one raised in the background waits in its buffer.
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    // Canonical bottom-nav tab switch (save/restore semantics + reselect-pop).
    // Local fun rather than inline in StashNavigationBar's onNavigate so screens
    // that trigger a tab switch themselves (Home's Liked card → Library) route
    // through the exact same logic via StashNavHost's onNavigateToTab.
    fun navigateToTab(dest: TopLevelDestination) {
        val destRoute = dest.route::class.qualifiedName
        // The "current tab" is the top-most top-level route in
        // the live back stack (the start tab, Home unless Open Stash
        // on says otherwise, is always at the bottom; the active tab
        // sits above it, with detail screens above that).
        val topLevelRoutes =
            TopLevelDestination.entries.map { it.route::class.qualifiedName }.toSet()
        val currentTabRoute = navController.currentBackStack.value
            .lastOrNull { it.destination.route in topLevelRoutes }
            ?.destination?.route

        if (currentTabRoute == destRoute && currentRoute != destRoute) {
            // Reselecting the tab we're already deep inside. The
            // save/restore path below would popUpTo(start) SAVE
            // this exact stack keyed by the tab, then restoreState
            // bring it right back — a byte-identical no-op that
            // reads as a dead tab button (only Back escaped). Pop
            // back to the tab's own entry instead; this also drops
            // any Now Playing pushed above it.
            navController.popBackStack(dest.route, inclusive = false)
        } else {
            // Genuine tab switch (the player, if open, collapses: see the destination listener).
            navController.navigate(dest.route) {
                // Save each tab's back stack + state when leaving
                // it, and restore it when returning — so tabbing to
                // Settings and back to Search lands on your results,
                // not the landing screen (canonical bottom-nav).
                popUpTo(navController.graph.findStartDestination().id) {
                    inclusive = false
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    // Process notification deep-link extras handed in from MainActivity.
    // Only one target right now (the captcha verifier); easy to extend
    // when more deep-link surfaces show up.
    LaunchedEffect(pendingDeepLink) {
        when (pendingDeepLink) {
            // The media notification (A2): open the player over whatever screen is showing.
            PlayerSheetDeepLink.TARGET -> {
                PlayerSheetDeepLink.handle(pendingDeepLink, playerSheet)
                onDeepLinkConsumed()
            }
            CaptchaExpiredNotifier.DEEP_LINK_TARGET -> {
                // Push Settings onto the back stack BEFORE the captcha screen.
                // SquidWtfCaptchaRoute reaches into the SettingsViewModel via
                // `navController.getBackStackEntry(SettingsRoute)` to share the
                // ViewModel's cookie-write callback — that throws
                // IllegalArgumentException ("No destination with route
                // SettingsRoute in BackStack") if Settings isn't already on the
                // stack. Cold-start from a notification has no stack history,
                // so we synthesize the parent here. When the user closes the
                // captcha screen they land on Settings — natural UX.
                navController.navigate(SettingsRoute) {
                    launchSingleTop = true
                }
                navController.navigate(SquidWtfCaptchaRoute) {
                    launchSingleTop = true
                }
                onDeepLinkConsumed()
            }
            null -> Unit
            else -> {
                if (pendingDeepLink.startsWith(com.stash.app.MainActivity.DEEP_LINK_SHARED_MIX_PREFIX)) {
                    val id = pendingDeepLink.removePrefix(com.stash.app.MainActivity.DEEP_LINK_SHARED_MIX_PREFIX)
                    navController.navigate(SharedMixRoute(id))
                } else if (pendingDeepLink.startsWith(com.stash.app.MainActivity.DEEP_LINK_SHARED_TRACK_PREFIX)) {
                    val link = pendingDeepLink.removePrefix(com.stash.app.MainActivity.DEEP_LINK_SHARED_TRACK_PREFIX)
                    navController.navigate(SharedTrackRoute(link))
                } else if (pendingDeepLink.startsWith(com.stash.app.MainActivity.DEEP_LINK_LISTEN_PREFIX)) {
                    val code = pendingDeepLink.removePrefix(com.stash.app.MainActivity.DEEP_LINK_LISTEN_PREFIX)
                    navController.navigate(ListenJoinRoute(code))
                } else if (pendingDeepLink.startsWith(com.stash.app.MainActivity.DEEP_LINK_WEB_LINK_PREFIX)) {
                    val link = pendingDeepLink.removePrefix(com.stash.app.MainActivity.DEEP_LINK_WEB_LINK_PREFIX)
                    navController.navigate(WebLinkRoute(link))
                }
                onDeepLinkConsumed() // unknown targets are cleared too, so we don't loop
            }
        }
    }

    LaunchedEffect(lifecycleOwner, losslessNotices) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            losslessNotices.collect { n ->
                val result = snackbarHostState.showSnackbar(
                    message = LosslessQuotaText.notice(n, LosslessQuotaText.resetTime(context, n.resetsAtMs)),
                    actionLabel = LosslessQuotaText.NOTICE_ACTION,
                    withDismissAction = true,
                    duration = SnackbarDuration.Long,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    // Audio & Quality reads its view model from Settings' entry: Settings first.
                    navigateToTab(TopLevelDestination.SETTINGS)
                    navController.navigate(SettingsAudioQualityRoute) { launchSingleTop = true }
                }
            }
        }
    }

    CommunityPostingHost(
        communityPosting,
        onSeeMyPosts = {
            // From See all itself, replace it rather than stacking a second one; from anywhere else, just open it.
            val onSeeAll = navController.currentBackStackEntry?.destination?.hasRoute<CommunityRoute>() == true
            navController.navigate(CommunityRoute(mine = true)) { if (onSeeAll) popUpTo<CommunityRoute> { inclusive = true } }
        },
    ) {
    ProvideAlbumChrome(albumColors) {
    CompositionLocalProvider(
        LocalChromeHazeState provides hazeState,
        LocalPlayerSheet provides playerSheet,
        LocalChromeBlurEnabled provides chromeBlurEnabled,
    ) {
    // The bottom chrome's height (Scaffold's bottom padding), for the snackbar's place.
    var bottomChromeHeight by remember { mutableStateOf(0.dp) }
    Box(Modifier.fillMaxSize()) {
    Scaffold(
        // Under an open player the app is covered: hidden from TalkBack too.
        modifier = if (playerCovering) Modifier.clearAndSetSemantics { } else Modifier,
        containerColor = MaterialTheme.colorScheme.background,
        // Use Scaffold's default safe-drawing insets so screens automatically
        // avoid the status bar (top) and gesture / 3-button nav (bottom).
        // The previous `WindowInsets(0.dp)` override was leaking content under
        // the system status bar — Pixel 6 Pro and similar devices on Android
        // 15+ where edge-to-edge is enforced. Reported via Twitter
        // (https://x.com/tekno_deha1/status/...).
        bottomBar = {
            // While a screen is selecting, render no bottom chrome at all — the
            // screen's own selection action bar (which handles its own nav insets)
            // takes the bottom edge. This drops innerPadding.bottom to 0 so the
            // content extends full-height behind that action bar.
            if (!selectionActive) {
                val qobuzStatusViewModel: QobuzStatusViewModel = androidx.hilt.navigation.compose.hiltViewModel()
                val qobuzStatus by qobuzStatusViewModel.bannerStatus.collectAsStateWithLifecycle()

                // No bottom navigation-bar padding here: the tab bar's glass runs down under
                // the system bar and pads its own items (StashNavigationBar), so content scrolling
                // under it is frosted all the way to the screen's edge. The cards above it keep
                // clear of a side system bar (3-button navigation in landscape).
                // Always laid out, open player or not: the player covers this chrome rather than
                // removing it, so the page's inset never changes as the player opens and closes.
                val cardInsets = Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal))
                // Under the player's full card none of it is drawn.
                Column(Modifier.graphicsLayer { alpha = if (playerSheet.progress >= 1f) 0f else 1f }) {
                    Box(cardInsets) {
                        com.stash.core.ui.components.QobuzDiscoveryBanner(status = qobuzStatus)
                    }
                    Column(cardInsets) {
                        // "Continue from Chrome on Windows" (link-sync spec §2.5): above the mini player.
                        // Always laid out (so the mini player never jumps when the player closes); it
                        // fades with the mini player's content and takes no touches unless the player
                        // is closed.
                        Box(
                            Modifier
                                .graphicsLayer { alpha = PlayerSheetMath.miniContentAlpha(playerSheet.progress) }
                                .pointerInput(playerSheet) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                            if (!playerSheet.isAtRest(PlayerSheetAnchor.MINI)) {
                                                event.changes.forEach { it.consume() }
                                            }
                                        }
                                    }
                                },
                        ) {
                            com.stash.feature.nowplaying.handoff.HandoffCard()
                        }
                        // "Chrome on Windows sent you 3 playlists and 40 likes." (link-sync spec §2.3): a
                        // dialog, shown once per send, never over the open player.
                        if (playerClosed) com.stash.feature.settings.weblink.InboxPrompt()
                        MiniPlayer(
                            onOpenQueue = { showQueue = true },
                            onExpand = playerSheet::expand,
                            sheet = playerSheet,
                        )
                    }

                    // The tab bar slides away as the player opens (gone by 60 %) and comes back as
                    // it closes: a layer move with the finger, never a re-layout. It fades out faster
                    // than it slides (gone by 12 %), so it never shows through the system's 3-button
                    // bar on its way down.
                    StashNavigationBar(
                        currentRoute = currentRoute,
                        onNavigate = ::navigateToTab,
                        modifier = Modifier.graphicsLayer {
                            val p = playerSheet.progress
                            translationY = size.height * PlayerSheetMath.tabBarHide(p)
                            alpha = PlayerSheetMath.tabBarAlpha(p)
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        // Every screen takes the whole height down to the screen's edge: the glass mini
        // player and tab bar float over it and blur it. Screens pad their scrolling content
        // by LocalBottomChromeInset (the bottom chrome's height, system bar included) so the
        // last row scrolls clear; a screen that doesn't scroll is padded by its destination
        // in StashNavHost. The status bar inset stays.
        // While a screen is selecting there's no glass to draw under: it keeps the system
        // bar inset as padding, so its own selection bar sits above the system bar as before.
        val layoutDirection = LocalLayoutDirection.current
        val bottomChrome = innerPadding.calculateBottomPadding()
        SideEffect { bottomChromeHeight = bottomChrome }
        val contentPadding = PaddingValues(
            start = innerPadding.calculateStartPadding(layoutDirection),
            top = innerPadding.calculateTopPadding(),
            end = innerPadding.calculateEndPadding(layoutDirection),
            bottom = if (selectionActive) bottomChrome else 0.dp,
        )
        CompositionLocalProvider(
            LocalBottomChromeInset provides if (selectionActive) 0.dp else bottomChrome,
            // The NavHost takes its host lifecycle from here and caps every page at it.
            LocalLifecycleOwner provides pageLifecycleOwner,
        ) {
        StashNavHost(
            navController = navController,
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                // The bottom chrome owns the system bar inset, so screens' own Scaffolds don't
                // pad it again.
                .consumeWindowInsets(innerPadding)
                // The page steps back (5 %) as the player opens; the glass blurs what's
                // inside this layer, the unscaled page, and is off while the player moves.
                .graphicsLayer {
                    val p = playerSheet.progress
                    val scale = PlayerSheetMath.pageScale(p)
                    scaleX = scale
                    scaleY = scale
                    // Under the player's full card the page isn't drawn at all.
                    alpha = if (p >= 1f) 0f else 1f
                }
                .hazeSource(hazeState),
            onSelectionModeChanged = { selectionActive = it },
            onNavigateToTab = ::navigateToTab,
            startDestination = when (startTab) {
                StartTab.HOME -> HomeRoute
                StartTab.LIBRARY -> LibraryRoute
                StartTab.SEARCH -> SearchRoute
            },
            posting = communityPosting,
        )
        }
    }

    // The player sheet over everything: scrim, the card growing into Now Playing, the travelling cover.
    PlayerSheetOverlay(
        state = playerSheet,
        // From the player to an artist or album: the player collapses and the page opens behind
        // it; Back from there returns to where you were before the player.
        onNavigateToArtist = { id, name, avatar, focusAlbum ->
            playerSheet.collapse()
            navController.navigate(SearchArtistRoute(id, name, avatar, focusAlbum))
        },
        onNavigateToAlbum = { albumId, name, artUrl, artistName ->
            playerSheet.collapse()
            navController.navigate(
                SearchAlbumRoute(
                    browseId = albumId,
                    title = name,
                    artist = artistName,
                    thumbnailUrl = artUrl,
                    year = null,
                    source = AlbumSource.YOUTUBE,
                ),
            )
        },
    )

    // Above the player too: a heads-up shows over Now Playing, above the system bar there, and
    // above the bottom chrome otherwise.
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .then(
                if (playerClosed) {
                    Modifier.padding(bottom = bottomChromeHeight)
                } else {
                    Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                },
            ),
    )

    // Back over an open player: lyrics first, then collapse (registered after the NavHost's).
    PlayerSheetBackHandler(playerSheet)
    }

    // The player's confirmations ("Playing next", "Saved to …"), once, wherever they come from.
    PlayerMessagesToaster()

    // Up next from the mini player: the same queue sheet Now Playing opens, over this screen.
    if (showQueue) {
        PlayerQueueSheet(onDismiss = { showQueue = false })
    }
    }
    }
    }
}


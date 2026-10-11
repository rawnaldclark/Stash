package com.stash.app.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.lerp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.data.prefs.StartTab
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.stash.app.RequestNotificationPermissionOnce
import com.stash.core.ui.theme.StashTheme
import com.stash.data.download.lossless.squid.CaptchaExpiredNotifier
import com.stash.feature.nowplaying.MiniPlayer
import com.stash.feature.nowplaying.PlayerQueueSheet
import com.stash.app.ambient.AlbumColorsViewModel
import com.stash.core.ui.ambient.LocalAlbumChrome
import com.stash.core.ui.ambient.ProvideAlbumChrome
import com.stash.core.ui.ambient.TAB_COLOUR_MS
import com.stash.core.ui.ambient.rememberReducedMotion
import com.stash.core.ui.components.LocalBottomChromeInset
import com.stash.core.ui.components.LocalChromeHazeState
import com.stash.core.ui.components.chromeGlass
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/**
 * Root scaffold for the Stash app.
 *
 * Hosts the [StashNavHost], the bottom navigation bar, and the glass [MiniPlayer]
 * floating just above it. Every screen draws edge to edge under that bottom chrome so
 * the glass can blur it (Haze: the nav host is the `hazeSource`, the mini player and tab
 * bar the effects); screens pad their scrolling content by [LocalBottomChromeInset].
 * Also publishes the app-wide album colour ([LocalAlbumChrome]) and hosts the queue
 * sheet the mini player opens.
 */
@Composable
fun StashScaffold(
    pendingDeepLink: String? = null,
    onDeepLinkConsumed: () -> Unit = {},
    startTab: StartTab = StartTab.HOME,
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val onNowPlaying = currentRoute == NowPlayingRoute::class.qualifiedName

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

    // Canonical bottom-nav tab switch (save/restore semantics + reselect-pop).
    // Local fun rather than inline in StashBottomBar's onNavigate so screens
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
            // Genuine tab switch. Now Playing is transient
            // full-screen chrome — remove it (wherever it sits)
            // before the switch so restoreState can never
            // resurrect it into a tab's saved stack.
            navController.popBackStack(NowPlayingRoute, inclusive = true)
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

    ProvideAlbumChrome(albumColors) {
    CompositionLocalProvider(LocalChromeHazeState provides hazeState) {
    Scaffold(
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
                // the system bar and pads its own items (StashBottomBar), so content scrolling
                // under it is frosted all the way to the screen's edge. The cards above it keep
                // clear of a side system bar (3-button navigation in landscape).
                val cardInsets = Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal))
                Column {
                    AnimatedVisibility(
                        visible = !onNowPlaying,
                        modifier = cardInsets,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        com.stash.core.ui.components.QobuzDiscoveryBanner(status = qobuzStatus)
                    }
                    // On Now Playing the LiveLyricsBar (rendered inside the
                    // screen itself) takes the MiniPlayer's spot — the full
                    // player already shows all transport controls, so the
                    // duplicate mini transport hides on this route only.
                    AnimatedVisibility(
                        visible = currentRoute != NowPlayingRoute::class.qualifiedName,
                        modifier = cardInsets,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                      Column {
                        // "Continue from Chrome on Windows" (link-sync spec §2.5): above the mini player, never on Now Playing.
                        com.stash.feature.nowplaying.handoff.HandoffCard()
                        // "Chrome on Windows sent you 3 playlists and 40 likes." (link-sync spec §2.3): a dialog, shown once per send.
                        com.stash.feature.settings.weblink.InboxPrompt()
                        MiniPlayer(
                            onOpenQueue = { showQueue = true },
                            onExpand = {
                                // Return to an existing Now Playing entry instead
                                // of pushing a duplicate. launchSingleTop only
                                // guards a CONSECUTIVE dup — via NP → artist
                                // profile → mini player, each expand used to
                                // stack another NP entry, and every buried
                                // entry's ViewModel keeps its 4Hz player combine
                                // running forever (observed: 6 live VMs in a
                                // heap dump). popBackStack returns false when NP
                                // isn't in the stack — normal push in that case.
                                if (!navController.popBackStack(NowPlayingRoute, inclusive = false)) {
                                    navController.navigate(NowPlayingRoute) {
                                        launchSingleTop = true
                                    }
                                }
                            },
                        )
                      }
                    }

                    // Now Playing is a full-screen player: the tab bar slides away while it
                    // is open and comes back as it closes (down chevron, Back, a tab switch).
                    AnimatedVisibility(
                        visible = !onNowPlaying,
                        enter = slideInVertically(tween(NAV_BAR_SLIDE_MS)) { it } + fadeIn(tween(NAV_BAR_SLIDE_MS)),
                        exit = slideOutVertically(tween(NAV_BAR_SLIDE_MS)) { it } + fadeOut(tween(NAV_BAR_SLIDE_MS)),
                    ) {
                        StashBottomBar(
                            currentRoute = currentRoute,
                            onNavigate = ::navigateToTab,
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        // Every screen takes the whole height down to the screen's edge: the glass mini
        // player and tab bar float over it and blur it. Screens pad their scrolling content
        // by LocalBottomChromeInset (the bottom chrome's height, system bar included) so the
        // last row scrolls clear; a screen that doesn't scroll is padded by its destination
        // in StashNavHost. Now Playing's ambient runs under the (gesture or 3-button) system
        // bar and the screen pads its own controls above it. The status bar inset stays.
        // While a screen is selecting there's no glass to draw under: it keeps the system
        // bar inset as padding, so its own selection bar sits above the system bar as before.
        val layoutDirection = LocalLayoutDirection.current
        val bottomChrome = innerPadding.calculateBottomPadding()
        val contentPadding = PaddingValues(
            start = innerPadding.calculateStartPadding(layoutDirection),
            top = innerPadding.calculateTopPadding(),
            end = innerPadding.calculateEndPadding(layoutDirection),
            bottom = if (selectionActive) bottomChrome else 0.dp,
        )
        CompositionLocalProvider(LocalBottomChromeInset provides if (selectionActive) 0.dp else bottomChrome) {
        StashNavHost(
            navController = navController,
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                // Off Now Playing the bottom chrome owns the system bar inset, so screens'
                // own Scaffolds don't pad it again; Now Playing still reads it.
                .consumeWindowInsets(if (onNowPlaying) contentPadding else innerPadding)
                .hazeSource(hazeState),
            onSelectionModeChanged = { selectionActive = it },
            onNavigateToTab = ::navigateToTab,
            startDestination = when (startTab) {
                StartTab.HOME -> HomeRoute
                StartTab.LIBRARY -> LibraryRoute
                StartTab.SEARCH -> SearchRoute
            },
        )
        }
    }

    // Up next from the mini player: the same queue sheet Now Playing opens, over this screen.
    if (showQueue) {
        PlayerQueueSheet(onDismiss = { showQueue = false })
    }
    }
    }
}

/** The tab bar's slide on and off Now Playing. */
private const val NAV_BAR_SLIDE_MS = 350 // = StashNavHost SLIDE_DURATION_MS, so bar and player move as one

@Composable
private fun StashBottomBar(
    currentRoute: String?,
    onNavigate: (TopLevelDestination) -> Unit,
) {
    val extendedColors = StashTheme.extendedColors
    // The web's 1px top line on the tab bar (rgba(255,255,255,.06) / the light glass border).
    val hairline = extendedColors.glassBorder
    val chrome = LocalAlbumChrome.current
    val reducedMotion = rememberReducedMotion()

    NavigationBar(
        // The same frosted glass as the mini player (a denser tint: the tab labels sit on it),
        // running down under the system bar; the top hairline marks its edge.
        modifier = Modifier
            .chromeGlass(ring = false, tintAlphaScale = NAV_GLASS_TINT_SCALE)
            .drawBehind {
                drawLine(
                    color = hairline,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx(),
                )
            },
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        windowInsets = WindowInsets.navigationBars,
    ) {
        TopLevelDestination.entries.forEach { dest ->
            val isSelected = currentRoute == dest.route::class.qualifiedName
            // Selected: the song's text ink while a song plays, the theme's neutral selected
            // colour otherwise (LocalAlbumChrome crossfades that over 800 ms); the change of
            // tab itself takes the web's 180 ms. Unselected tabs stay neutral. Only the
            // selection is animated here, so the album crossfade isn't chased by a second tween.
            val selectedness by animateFloatAsState(
                targetValue = if (isSelected) 1f else 0f,
                animationSpec = tween(if (reducedMotion) 0 else TAB_COLOUR_MS),
                label = "tabSelected",
            )
            val ink = lerp(extendedColors.textTertiary, chrome.selectedTab, selectedness)

            NavigationBarItem(
                selected = isSelected,
                onClick = { onNavigate(dest) },
                icon = {
                    Icon(
                        imageVector = if (isSelected) dest.selectedIcon else dest.unselectedIcon,
                        contentDescription = dest.label,
                        tint = ink,
                    )
                },
                label = {
                    Text(text = dest.label, style = MaterialTheme.typography.labelSmall, color = ink)
                },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = chrome.selectedTab.copy(alpha = 0.12f),
                ),
            )
        }
    }
}

/** The tab bar's glass is denser than the mini player's (70 % → 84 % on dark). */
private const val NAV_GLASS_TINT_SCALE = 1.2f

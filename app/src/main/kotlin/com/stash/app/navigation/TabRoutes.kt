package com.stash.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavController

/*
 * Which tab is on screen, and how shortcuts reach a tab. The back stack is the single source:
 * the start tab sits at the bottom, the active tab above it, and that tab's detail screens (and a
 * Now Playing) above that. The tab bar and the scaffold's tab switch both read it through
 * [tabRouteOf], so they can't disagree.
 */

/** Every tab's route name, as the back stack names them. */
private val TabRouteNames: Set<String> =
    TopLevelDestination.entries.mapNotNull { it.route::class.qualifiedName }.toSet()

/**
 * The route of the tab whose stack is on screen, given a back stack's route names bottom to top:
 * the top-most tab route. Null for a stack with no tab in it (only while the graph is still empty).
 */
fun tabRouteOf(routes: List<String?>): String? = routes.lastOrNull { it in TabRouteNames }

/**
 * [tabRouteOf] this controller's live back stack. Unlike the current route, it stays on the tab
 * through its detail screens, and it is right when a tab switch restores a stack sitting on a
 * detail screen.
 */
@Composable
fun NavController.currentTabRoute(): String? {
    val backStack by currentBackStack.collectAsState()
    return tabRouteOf(backStack.map { it.destination.route })
}

/**
 * Opens the Settings tab from a shortcut elsewhere (Home's gear, Sync's Reconnect, the empty
 * Library's Accounts), then [subScreen] on top of it if given.
 *
 * It goes through the real tab switch ([onNavigateToTab]), so Settings and its sub-screens land
 * in Settings' own back stack, not inside the tab the shortcut was in, where the tab bar would
 * light Settings while that tab later restored to a Settings page. Settings' hub goes under
 * [subScreen] as its screens read their view model from the hub's entry, and a restored Settings
 * page is popped first so the shortcut always lands where it says.
 */
fun NavController.openSettingsTab(
    onNavigateToTab: (TopLevelDestination) -> Unit,
    subScreen: Any? = null,
) {
    onNavigateToTab(TopLevelDestination.SETTINGS)
    popBackStack(SettingsRoute, inclusive = false)
    if (subScreen != null) navigate(subScreen) { launchSingleTop = true }
}

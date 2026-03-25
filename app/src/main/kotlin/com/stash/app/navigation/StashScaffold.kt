package com.stash.app.navigation
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.stash.core.model.PlayerState
import com.stash.core.model.Track
import com.stash.core.ui.components.MiniPlayerBar
import com.stash.core.ui.theme.StashTheme

@Composable
fun StashScaffold() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    var stubPlayerState by remember { mutableStateOf(PlayerState(currentTrack = Track(title = "Welcome to Stash", artist = "Set up sync to get started"))) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0.dp),
        bottomBar = {
            Column {
                MiniPlayerBar(
                    playerState = stubPlayerState,
                    onPlayPauseClick = { stubPlayerState = stubPlayerState.copy(isPlaying = !stubPlayerState.isPlaying) },
                    onSkipNextClick = {},
                    onBarClick = {},
                )
                StashBottomBar(currentRoute = currentRoute, onNavigate = { dest ->
                    navController.navigate(dest.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                })
            }
        },
    ) { innerPadding ->
        StashNavHost(navController = navController, modifier = Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding))
    }
}

@Composable
private fun StashBottomBar(currentRoute: String?, onNavigate: (TopLevelDestination) -> Unit) {
    val extendedColors = StashTheme.extendedColors
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface, tonalElevation = 0.dp) {
        TopLevelDestination.entries.forEach { dest ->
            val isSelected = currentRoute == dest.route::class.qualifiedName
            NavigationBarItem(
                selected = isSelected,
                onClick = { onNavigate(dest) },
                icon = { Icon(if (isSelected) dest.selectedIcon else dest.unselectedIcon, contentDescription = dest.label) },
                label = { Text(dest.label, style = MaterialTheme.typography.labelSmall) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = extendedColors.textTertiary,
                    unselectedTextColor = extendedColors.textTertiary,
                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                ),
            )
        }
    }
}

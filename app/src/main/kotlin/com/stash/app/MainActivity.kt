package com.stash.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.stash.app.navigation.StashScaffold
import com.stash.core.data.prefs.ThemePreference
import com.stash.core.model.ThemeMode
import com.stash.core.ui.theme.StashTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var themePreference: ThemePreference

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Capture the flow into a local so the lambda below doesn't touch `this`
        // during composition setup.
        val themeModeFlow: Flow<ThemeMode> = themePreference.themeMode

        setContent {
            // Collect the persisted preference. Default to SYSTEM until DataStore
            // emits so the initial frame doesn't flash the wrong theme.
            val themeMode by themeModeFlow.collectAsState(initial = ThemeMode.SYSTEM)
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> systemDark
            }

            StashTheme(darkTheme = darkTheme) {
                StashScaffold()
            }
        }
    }
}

package com.stash.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.IntentCompat
import com.stash.app.navigation.StashScaffold
import com.stash.core.data.prefs.ThemePreference
import com.stash.core.model.ThemeMode
import com.stash.core.ui.theme.StashTheme
import com.stash.data.download.files.LocalImportCoordinator
import com.stash.data.download.lossless.squid.CaptchaExpiredNotifier
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var themePreference: ThemePreference

    @Inject
    lateinit var localImportCoordinator: LocalImportCoordinator

    /**
     * Pending deep-link target read from the launch / new-intent extras.
     * Compose observes this via [StashScaffold]'s `pendingDeepLink`
     * parameter, navigates once, then calls back to clear it via
     * [clearPendingDeepLink].
     */
    private val pendingDeepLink = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val themeModeFlow: Flow<ThemeMode> = themePreference.themeMode

        setContent {
            val themeMode by themeModeFlow.collectAsState(initial = ThemeMode.SYSTEM)
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> systemDark
            }

            StashTheme(darkTheme = darkTheme) {
                StashScaffold(
                    pendingDeepLink = pendingDeepLink.value,
                    onDeepLinkConsumed = { pendingDeepLink.value = null },
                )
            }
        }

        // Handle the initial intent (share target cold-start path,
        // notification deep-link cold-start path, etc).
        handleShareIntent(intent)
        handleDeepLinkIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
        handleDeepLinkIntent(intent)
    }

    /**
     * Reads [CaptchaExpiredNotifier.INTENT_EXTRA_NAV_TARGET] from the
     * launch intent and stashes it for Compose to consume on the next
     * frame. No-op when the extra is absent (the normal launcher case).
     */
    private fun handleDeepLinkIntent(intent: Intent?) {
        if (intent == null) return
        val target = intent.getStringExtra(CaptchaExpiredNotifier.INTENT_EXTRA_NAV_TARGET)
            ?: return
        pendingDeepLink.value = target
        // Clear the extra so a config change doesn't re-trigger the
        // navigate. setIntent() above already replaces the activity's
        // intent reference; this just guards against the same Intent
        // instance being processed twice.
        intent.removeExtra(CaptchaExpiredNotifier.INTENT_EXTRA_NAV_TARGET)
    }

    /**
     * Extracts audio URIs from a share-sheet intent and hands them to the
     * [LocalImportCoordinator]. Silently ignores non-share intents so the
     * normal launcher flow is untouched.
     */
    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let { listOf(it) } ?: emptyList()
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                IntentCompat.getParcelableArrayListExtra(
                    intent,
                    Intent.EXTRA_STREAM,
                    Uri::class.java,
                ).orEmpty().toList()
            }
            else -> emptyList()
        }
        if (uris.isNotEmpty()) {
            localImportCoordinator.start(uris)
        }
    }
}

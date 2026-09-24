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
import com.stash.core.model.share.ShareLinks
import com.stash.core.ui.theme.StashTheme
import com.stash.data.download.files.LocalImportCoordinator
import com.stash.data.download.lossless.squid.CaptchaExpiredNotifier
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        /** [pendingDeepLink] prefix for a shared-track link; the raw link follows. */
        const val DEEP_LINK_SHARED_TRACK_PREFIX = "shared_track:"

        /** [pendingDeepLink] prefix for a shared-mix link; the share id follows. */
        const val DEEP_LINK_SHARED_MIX_PREFIX = "shared_mix:"

        /** [pendingDeepLink] prefix for a Listen Together invite; the room code follows. */
        const val DEEP_LINK_LISTEN_PREFIX = "listen:"
    }

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
            val amoledDark by themePreference.amoledDark.collectAsState(initial = false)
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> systemDark
            }

            StashTheme(darkTheme = darkTheme, amoled = amoledDark) {
                StashScaffold(
                    pendingDeepLink = pendingDeepLink.value,
                    onDeepLinkConsumed = { pendingDeepLink.value = null },
                )
            }
        }

        // Handle the initial intent (share target cold-start path,
        // notification deep-link cold-start path, etc). Not on a restore
        // (process death / config change) or a Recents relaunch: those
        // replay the original intent, and the nav back stack is already restored.
        val relaunch = savedInstanceState != null ||
            (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        if (!relaunch) {
            handleShareIntent(intent)
            handleDeepLinkIntent(intent)
        }
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

        // Shared mix / track links (https App Links, and legacy stash://track). Spec §6.
        if (intent.action == Intent.ACTION_VIEW) {
            val handled = when (val parsed = ShareLinks.parse(intent.data?.toString())) {
                is ShareLinks.Parsed.Mix -> {
                    pendingDeepLink.value = DEEP_LINK_SHARED_MIX_PREFIX + parsed.shareId
                    true
                }
                is ShareLinks.Parsed.Track -> {
                    pendingDeepLink.value = DEEP_LINK_SHARED_TRACK_PREFIX + ShareLinks.trackUrl(parsed.track) // bounded, normalised
                    true
                }
                is ShareLinks.Parsed.Room -> {
                    pendingDeepLink.value = DEEP_LINK_LISTEN_PREFIX + parsed.code
                    true
                }
                null -> false
            }
            if (handled) { intent.data = null; return } // don't reprocess on config change
        }

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

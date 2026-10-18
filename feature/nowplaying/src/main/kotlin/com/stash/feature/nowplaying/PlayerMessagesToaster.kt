package com.stash.feature.nowplaying

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Toasts the player's one-shot confirmations ([NowPlayingViewModel.userMessages]: "Playing next",
 * "Saved to …", "Downloading…") once, app-wide. The mini player, its queue sheet and Now Playing all
 * share the activity's ViewModel; each used to collect the messages itself, so with Now Playing in
 * the player sheet every message would have been toasted twice. Hosted once by the app scaffold.
 */
@Composable
fun PlayerMessagesToaster(viewModel: NowPlayingViewModel = hiltViewModel()) {
    val context = LocalContext.current
    LaunchedEffect(viewModel, context) {
        viewModel.userMessages.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }
}

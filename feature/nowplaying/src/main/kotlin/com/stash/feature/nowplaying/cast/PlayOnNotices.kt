package com.stash.feature.nowplaying.cast

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.stash.core.media.cast.BrowserNotice
import com.stash.feature.nowplaying.NowPlayingViewModel
import com.stash.feature.nowplaying.R

/**
 * What happened to a browser session, on the app's snackbar, once (Remote v2 §3): a browser that closed or a connection that
 * stayed down ("Chrome on Windows closed. Your music is back on this phone." with Play), a browser playing its own music, one
 * that never answered, and songs only this phone has that were stepped over. Hosted once by the app scaffold.
 */
@Composable
fun PlayOnNotices(
    snackbarHostState: SnackbarHostState,
    cast: CastViewModel = hiltViewModel(),
    player: NowPlayingViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    LaunchedEffect(cast, context) {
        cast.notices.collect { notice ->
            val res = context.resources
            val message = when (notice) {
                is BrowserNotice.Closed -> res.getString(R.string.play_on_notice_closed, notice.name)
                is BrowserNotice.Lost -> res.getString(R.string.play_on_notice_lost, notice.name)
                is BrowserNotice.OwnMusic -> res.getString(R.string.play_on_notice_own_music, notice.name)
                is BrowserNotice.NoAnswer -> res.getString(R.string.play_on_notice_no_answer, notice.name)
                is BrowserNotice.Unlinked -> res.getString(R.string.play_on_notice_unlinked, notice.name)
                is BrowserNotice.Skipped -> res.getQuantityString(R.plurals.play_on_notice_skipped, notice.count, notice.count)
            }
            // Back here paused: Play carries on where the browser was (a browser going away never starts the loudspeaker).
            val offersPlay = notice is BrowserNotice.Closed || notice is BrowserNotice.Lost || notice is BrowserNotice.Unlinked
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = if (offersPlay) res.getString(R.string.play_on_notice_play) else null,
                withDismissAction = !offersPlay,
                duration = if (offersPlay) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed && !player.uiState.value.isPlaying) player.onPlayPauseClick()
        }
    }
}

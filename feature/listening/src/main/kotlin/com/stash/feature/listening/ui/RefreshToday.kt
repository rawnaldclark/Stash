package com.stash.feature.listening.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.ZonedDateTime

/**
 * Keeps a listening screen's "today" current: [onRefresh] runs when the screen comes back (it
 * may have been away past midnight) and at every midnight while it stays open, so "Today",
 * streaks and rolling ranges move on without leaving the screen. [key] is the screen's ViewModel.
 */
@Composable
internal fun RefreshTodayEffect(key: Any, onRefresh: () -> Unit) {
    val refresh by rememberUpdatedState(onRefresh)
    LifecycleResumeEffect(key) {
        refresh()
        onPauseOrDispose {}
    }
    LaunchedEffect(key) {
        while (true) {
            val now = ZonedDateTime.now()
            val midnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
            // A second past midnight, so "today" has certainly changed when it's read.
            delay(Duration.between(now, midnight).toMillis() + 1_000)
            refresh()
        }
    }
}

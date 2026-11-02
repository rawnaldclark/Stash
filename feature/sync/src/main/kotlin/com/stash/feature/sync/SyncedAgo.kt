package com.stash.feature.sync

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private const val MINUTE_MS = 60_000L

/**
 * The "synced 3 minutes ago" words on the Sync tab, for a sync that finished at [finishedAt]
 * (null: no sync yet, which reads ""). Worked out where they're drawn, and again each minute
 * while the tab is on screen. They used to be written once, when the sync finished, and the
 * tab kept them, so it said "1 minute ago" hours later (2026-11-02).
 *
 * The clock runs only while the tab is at least STARTED: another tab or the app in the
 * background stops it, and coming back reads the time afresh.
 */
@Composable
internal fun rememberSyncedAgo(finishedAt: Long?): String {
    val justNow = stringResource(R.string.sync_time_just_now)
    val now by remember(finishedAt) { syncedAgoClock(finishedAt) }
        .collectAsStateWithLifecycle(initialValue = System.currentTimeMillis())
    return syncedAgoText(finishedAt, now, justNow)
}

/**
 * The words for a sync that finished at [finishedAt], read at [now]: [justNow] under a minute
 * (or while the phone's clock is behind [finishedAt]), otherwise Android's own words in the
 * phone's language ("1 minute ago", "2 hours ago", "Yesterday"). "" when there's no sync.
 */
internal fun syncedAgoText(finishedAt: Long?, now: Long, justNow: String): String = when {
    finishedAt == null -> ""
    now - finishedAt < MINUTE_MS -> justNow
    else -> DateUtils.getRelativeTimeSpanString(finishedAt, now, DateUtils.MINUTE_IN_MILLIS).toString()
}

/**
 * The clock the words are read against: the time now, then again at each whole minute after
 * [finishedAt], when they can next change (opened 40 s after: "just now", then "1 minute ago"
 * at 1:00, "2 minutes ago" at 2:00). With no sync there's nothing to tick: one reading, then
 * it ends. [now] is the wall clock; tests pass their own.
 */
internal fun syncedAgoClock(finishedAt: Long?, now: () -> Long = System::currentTimeMillis): Flow<Long> = flow {
    while (true) {
        val t = now()
        emit(t)
        if (finishedAt == null) return@flow
        delay(MINUTE_MS - (t - finishedAt).mod(MINUTE_MS))
    }
}

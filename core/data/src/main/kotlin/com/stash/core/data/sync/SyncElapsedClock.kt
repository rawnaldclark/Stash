package com.stash.core.data.sync

import android.os.SystemClock
import javax.inject.Inject

/**
 * The sync workers' clock for their own time limits: [SystemClock.elapsedRealtime], which never
 * jumps with the wall clock. Open so a test can move time on.
 */
open class SyncElapsedClock @Inject constructor() {
    /** Milliseconds since the phone started. */
    open fun nowMs(): Long = SystemClock.elapsedRealtime()
}

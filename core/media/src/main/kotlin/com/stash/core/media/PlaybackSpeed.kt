package com.stash.core.media

/**
 * How long [mediaMs] of a song takes to play at [speed], on the wall clock: at 2x the last 6 s of a
 * song are over in 3 s. Anything that races the end of a track with a real-time timer or ramp (the
 * crossfade, the sleep timer's fade) converts first. A speed that isn't a positive number counts as 1x.
 */
internal fun mediaToWallMs(mediaMs: Long, speed: Float): Long =
    if (speed > 0f && speed.isFinite()) (mediaMs / speed.toDouble()).toLong() else mediaMs

/** The reverse of [mediaToWallMs]: how much of a song [wallMs] of playing covers at [speed]. */
internal fun wallToMediaMs(wallMs: Long, speed: Float): Long =
    if (speed > 0f && speed.isFinite()) (wallMs * speed.toDouble()).toLong() else wallMs

package com.stash.core.media.listen

/**
 * Turns ping/pong samples into the offset between this phone's clock and the room's (spec §4).
 * For each reply: `rtt = now − c`, `offset = r − (c + rtt / 2)`. The sample with the smallest
 * round trip in the last five minutes wins. Room time is `now + offset`. (YumaPlayer had the
 * sign of this backwards.) Not thread-safe; the session calls it on the main thread.
 */
class ClockSync(private val windowMs: Long = 5 * 60_000L) {
    private class Sample(val atMs: Long, val rttMs: Long, val offsetMs: Long)
    private val samples = ArrayDeque<Sample>()

    fun onPong(clientSentMs: Long, roomMs: Long, nowMs: Long) {
        val rtt = nowMs - clientSentMs
        if (rtt < 0) return
        samples.addLast(Sample(nowMs, rtt, roomMs - (clientSentMs + rtt / 2)))
        while (samples.isNotEmpty() && nowMs - samples.first().atMs > windowMs) samples.removeFirst()
    }

    val offsetMs: Long? get() = samples.minByOrNull { it.rttMs }?.offsetMs

    fun roomNow(nowMs: Long): Long? = offsetMs?.let { nowMs + it }

    fun reset() = samples.clear()
}

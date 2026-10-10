package com.stash.core.model.weblink

/**
 * A hybrid logical clock stamp of sync-v1 (stash-player `docs/sync-v1.md` "Clocks"): `[wallMs, counter, deviceId]` on the wire,
 * compared as a tuple. [wall] is server-corrected time ([ClockOffset]), so a device with a wrong clock can't win every conflict.
 * Device ids compare by UTF-16 code unit, as JavaScript compares strings (they are ASCII).
 */
data class Hlc(val wall: Long, val counter: Int, val device: String) : Comparable<Hlc> {
    override fun compareTo(other: Hlc): Int = compareValuesBy(this, other, Hlc::wall, Hlc::counter, Hlc::device)

    companion object {
        /** How far past the server's own stamp (`serverAt` of the row or slot that carried it) a stamp may be. */
        const val MAX_FUTURE_MS = 10L * 60 * 1000

        /**
         * A stamp from the future: its wall is more than 10 minutes after the `serverAt` of the row that carried it. The op is
         * skipped, not clamped: every reader sees the same `serverAt`, so all of them skip it alike.
         */
        fun fromTheFuture(at: Hlc, serverAt: Long): Boolean = at.wall > serverAt + MAX_FUTURE_MS

        /** A local event: stamped after [last] (this device's previous stamp, or null) at corrected time [wall]. */
        fun tick(last: Hlc?, wall: Long, device: String): Hlc =
            if (last == null || wall > last.wall) Hlc(wall, 0, device) else Hlc(last.wall, last.counter + 1, device)

        /** A remote stamp seen: this device's clock moves past it, so its next edit orders after what it has seen. */
        fun recv(last: Hlc?, remote: Hlc, wall: Long, device: String): Hlc {
            val w = maxOf(wall, last?.wall ?: Long.MIN_VALUE, remote.wall)
            val c = when {
                last != null && w == last.wall && w == remote.wall -> maxOf(last.counter, remote.counter) + 1
                last != null && w == last.wall -> last.counter + 1
                w == remote.wall -> remote.counter + 1
                else -> 0
            }
            return Hlc(w, c, device)
        }
    }
}

/** `serverTime − localTime`, from the newest exchanges with the sync server; `wall = local + offset`. */
object ClockOffset {
    /** How many of the newest samples count. */
    const val SAMPLES = 5

    /** One exchange: local clock when the request went out and when the answer came back, and the answer's `serverTime`. */
    data class Sample(val sent: Long, val received: Long, val serverTime: Long)

    /**
     * For each of the newest 5 samples, `serverTime − floor((sent + received) / 2)`; the offset is the lower median of those
     * (sorted ascending, element `(n − 1) / 2`). No samples: 0.
     */
    fun of(samples: List<Sample>): Long {
        val xs = samples.takeLast(SAMPLES).map { it.serverTime - Math.floorDiv(it.sent + it.received, 2L) }.sorted()
        return if (xs.isEmpty()) 0 else xs[(xs.size - 1) / 2]
    }
}

package com.stash.feature.listening

import com.stash.core.data.db.dao.HistoryRow
import com.stash.core.data.mapper.toDomain
import com.stash.core.model.Track

/**
 * What a tap in "Your listening" plays, decided without touching the player so it can be tested.
 *
 * The connection rule is Library's (feature/library LibraryPlayback.kt): online every song plays,
 * streaming what isn't downloaded; offline only songs with a file on the phone, and a tap on one that
 * needs a connection says so instead of quietly playing something else.
 */
internal sealed interface PlayPlan {
    /** Hand [tracks] to the player starting at [startIndex]. */
    data class Play(val tracks: List<Track>, val startIndex: Int) : PlayPlan

    /** Nothing can play now; tell the user [message]. */
    data class Blocked(val message: String) : PlayPlan
}

/** A tap on one song that needs a connection, made offline (the same words as Library). */
internal const val SONG_NEEDS_CONNECTION = "This song isn't downloaded. Connect to play it."

/** "Play this day" offline when none of the day's songs is downloaded. */
internal const val DAY_NEEDS_CONNECTION = "None of this day's songs are downloaded. Connect to play them."

/** The song has a file on the phone, so it plays with no connection. */
internal fun Track.isOnPhone(): Boolean = filePath != null

/**
 * [queue] playing from [startIndex] given the connection. [tapped] = the user picked that song
 * (offline, a song that needs a connection is refused rather than swapped for another);
 * otherwise the start is just the top of a list ("Play this day") and offline starts at its first
 * downloaded song. Offline the songs that need a connection are left out of the queue: a queue
 * that mixes them in stops after a few failures.
 */
internal fun planPlay(
    queue: List<Track>,
    startIndex: Int,
    online: Boolean,
    tapped: Boolean,
    noneMessage: String = DAY_NEEDS_CONNECTION,
): PlayPlan {
    if (queue.isEmpty()) return PlayPlan.Blocked(noneMessage)
    val start = startIndex.coerceIn(0, queue.lastIndex)
    if (online) return PlayPlan.Play(queue, start)
    if (tapped && !queue[start].isOnPhone()) return PlayPlan.Blocked(SONG_NEEDS_CONNECTION)
    // Positions, not ids: a day can hold the same song twice, and the tapped one is a position.
    val kept = queue.indices.filter { queue[it].isOnPhone() }
    if (kept.isEmpty()) return PlayPlan.Blocked(noneMessage)
    val newStart = if (tapped) kept.indexOf(start) else 0
    return PlayPlan.Play(kept.map { queue[it] }, newStart.coerceAtLeast(0))
}

/** A day's listens as a queue: [tracks] in the order they were heard, [startIndex] the chosen one. */
internal data class DayQueue(val tracks: List<Track>, val startIndex: Int)

/**
 * [listens] (one day, oldest first) as the queue that replays the day: in the order the songs
 * were heard, a song heard several times in a row (repeat-one) once. [startEventId] = the listen
 * the user tapped: the queue starts there and plays on through what came after it that day; null
 * starts at the day's first song. If that listen is gone (removed since the list was drawn), the
 * queue starts at the listen nearest [startedAt], the tapped row's start time.
 */
internal fun dayQueue(listens: List<HistoryRow>, startEventId: Long?, startedAt: Long? = null): DayQueue {
    val tracks = ArrayList<Track>(listens.size)
    var start: Int? = null
    var nearest = 0
    var nearestGap = Long.MAX_VALUE
    var lastTrackId: Long? = null
    for (row in listens) {
        if (row.track.id != lastTrackId) {
            tracks += row.track.toDomain()
            lastTrackId = row.track.id
        }
        if (row.event.id == startEventId) start = tracks.lastIndex
        if (startedAt != null) {
            val gap = kotlin.math.abs(row.event.startedAt - startedAt)
            if (gap < nearestGap) {
                nearestGap = gap
                nearest = tracks.lastIndex
            }
        }
    }
    return DayQueue(tracks, start ?: if (startEventId != null && startedAt != null) nearest else 0)
}

/**
 * A tap in History as a queue: the list as it reads on screen ([listed], newest first), playing
 * from the tapped row ([tappedIndex]) down through the earlier listens, the way a tap in any list
 * plays on to the songs below it. [before] rows above the tap stay queued so Previous works, at
 * most [after] below it. A song heard several times in a row (repeat-one) is queued once.
 */
internal fun listQueue(listed: List<HistoryRow>, tappedIndex: Int, before: Int = 50, after: Int = 500): DayQueue {
    if (listed.isEmpty()) return DayQueue(emptyList(), 0)
    val tapped = tappedIndex.coerceIn(0, listed.lastIndex)
    val from = (tapped - before).coerceAtLeast(0)
    val to = minOf(listed.lastIndex, tapped + after)
    val tracks = ArrayList<Track>(to - from + 1)
    var start = 0
    var lastTrackId: Long? = null
    for (i in from..to) {
        val row = listed[i]
        if (row.track.id != lastTrackId) {
            tracks += row.track.toDomain()
            lastTrackId = row.track.id
        }
        if (i == tapped) start = tracks.lastIndex
    }
    return DayQueue(tracks, start)
}

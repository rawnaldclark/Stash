package com.stash.feature.record.archive

import com.stash.core.model.weblink.SongKey

/**
 * One play as Your record keeps it: stash-player `src/lib/spotify/plays.ts` `ArchivePlay`, which the fork reads as it is
 * (stash-on-record `src/stash/embed.ts`). Short names on purpose: a record can hold a few hundred thousand of them, and every
 * row crosses the bridge as JSON.
 */
data class ArchivePlay(
    /** `textKey|startedAt` ([playId]). */
    val id: String,
    val t: String,
    val a: String,
    val al: String? = null,
    /** The Spotify track id (22 characters), when an export named one. */
    val sid: String? = null,
    /** Start, epoch ms UTC. */
    val s: Long,
    /** Time played, ms. */
    val ms: Long,
    /** [RecordFlags] bits. */
    val f: Int,
)

/** The archive's flag bits (plays.ts `F_*`): the same numbers on the web, in the fork and here. */
object RecordFlags {
    const val CHOSEN = 1
    const val DONE = 2
    const val SKIPPED = 4
    const val SHUFFLE = 8

    /** From Spotify's Account data (minute precision, no reasons). */
    const val ACCOUNT = 16

    /** The export said how it started and ended (Extended history): [CHOSEN] and [DONE] mean something. */
    const val REASONS = 32

    /** A Last.fm scrobble: when it started, never how long it played. */
    const val LASTFM = 64

    /** Its `ms` is the song's length or a guess ([GUESS_MS]), not time played. */
    const val ESTIMATED = 128

    /** A play made in Stash on the phone: the record's Stash source. */
    const val STASH = 256
}

/** How long a play lasted when nothing knows (plays.ts `LASTFM_GUESS_MS`): about three and a half minutes. */
const val GUESS_MS = 210_000L

/**
 * A play's id (plays.ts `playId`): the song's folded words and when it started. The phone and the web compute it the same way,
 * so one play is never counted twice. [SongKey.textKey] is the port of the web's `textKey` (the shared identity vectors).
 */
fun playId(title: String, artist: String, startedAt: Long): String = "${SongKey.textKey(title, artist)}|$startedAt"

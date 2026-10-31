package com.stash.feature.record.lastfm

import android.content.res.Resources
import com.stash.feature.record.R
import com.stash.feature.record.imports.ImportText

/** What the Last.fm part says when nothing runs: its line, and what its main button does. */
internal data class LastfmIdle(val line: String, val action: LastfmAction)

/** The Last.fm part's main button. */
internal enum class LastfmAction { CONNECT, BRING, CARRY_ON, NEWER }

/**
 * Last.fm in Your record, in words (stash-player `src/lib/ui/spotify/lastfm.ts` and LastfmPart.svelte's `idle`): the lines the
 * sheet, Settings and the notification say while the scrobbles come in.
 */
internal object LastfmText {
    /** "1 scrobble", "48,210 scrobbles". */
    fun scrobbles(res: Resources, n: Int): String = res.getQuantityString(R.plurals.record_lastfm_scrobbles, n, ImportText.count(res, n.toLong()))

    /** Honest progress: "Page 3 of 241 · 600 scrobbles", or the first ask before Last.fm has said how many pages there are. */
    fun progressLine(res: Resources, p: LastfmProgress?): String =
        if (p == null) {
            res.getString(R.string.record_lastfm_asking)
        } else {
            res.getString(
                R.string.record_lastfm_progress,
                ImportText.count(res, p.page.toLong()),
                ImportText.count(res, p.pages.toLong()),
                scrobbles(res, p.added),
            )
        }

    /**
     * lastfm.ts `doneLine`: what a finished run brought. [total] (the run read the whole history, or carried on with it): the whole
     * import, with this run's part beside it when it carried on from an earlier one ("30,060 scrobbles in, 7,660 of them this
     * time"). Without (a top-up): what was newer. [missing]: pages still skipped, said after it.
     */
    fun doneLine(res: Resources, added: Int, total: Int? = null, missing: Boolean = false): String {
        val line = when {
            total == 0 -> res.getString(R.string.record_lastfm_done_none_yet)
            total != null && added > 0 && added < total ->
                res.getString(R.string.record_lastfm_done_part, scrobbles(res, total), ImportText.count(res, added.toLong()))
            total != null -> res.getString(R.string.record_lastfm_done, scrobbles(res, total))
            added > 0 -> res.getString(R.string.record_lastfm_done, scrobbles(res, added))
            else -> res.getString(R.string.record_lastfm_done_none)
        }
        // Pages still skipped as holes: said in plain words, as the web says it (lastfm.ts `MISSING`)
        return if (missing) "$line ${res.getString(R.string.record_lastfm_done_missing)}" else line
    }

    /**
     * LastfmPart.svelte's `idle`: not connected (with what came in before, from whom), connected with nothing in for this name,
     * stopped part way, or all of it in. Another name's import starts over, so it doesn't count for this one.
     */
    fun idle(res: Resources, connected: String?, mark: LastfmMark?, now: Long = System.currentTimeMillis()): LastfmIdle {
        if (connected == null) {
            return if (mark != null) {
                LastfmIdle(res.getString(R.string.record_lastfm_idle_had, scrobbles(res, mark.added), mark.user), LastfmAction.CONNECT)
            } else {
                LastfmIdle(res.getString(R.string.record_lastfm_idle_connect), LastfmAction.CONNECT)
            }
        }
        // Another account than the import's: it starts over for this one, and every earlier account's scrobbles stay
        val mine = mark?.takeIf { it.user == connected }
            ?: return LastfmIdle(
                res.getString(if (mark != null) R.string.record_lastfm_idle_bring_beside else R.string.record_lastfm_idle_bring, connected),
                LastfmAction.BRING,
            )
        if (!mine.done) {
            return LastfmIdle(
                res.getString(R.string.record_lastfm_idle_stopped, ImportText.count(res, (mine.next - 1).toLong()), ImportText.count(res, mine.pages.toLong())),
                LastfmAction.CARRY_ON,
            )
        }
        return LastfmIdle(
            res.getString(R.string.record_lastfm_idle_done, scrobbles(res, mine.added), connected, ImportText.shortDate(res, mine.at * 1000, now)),
            LastfmAction.NEWER,
        )
    }

    /** lastfm.ts `LASTFM_ERRORS`: each way it can stop short, a title and a line. */
    fun failure(reason: LastfmFailure): Pair<Int, Int> = when (reason) {
        LastfmFailure.RECONNECT -> R.string.record_lastfm_reconnect_title to R.string.record_lastfm_reconnect_body
        LastfmFailure.BUSY -> R.string.record_lastfm_busy_title to R.string.record_lastfm_busy_body
        LastfmFailure.UNAVAILABLE -> R.string.record_lastfm_unavailable_title to R.string.record_lastfm_unavailable_body
        LastfmFailure.OTHER -> R.string.record_lastfm_other_title to R.string.record_lastfm_other_body
    }
}

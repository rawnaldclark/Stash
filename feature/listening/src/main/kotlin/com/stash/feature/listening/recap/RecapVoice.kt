package com.stash.feature.listening.recap

import androidx.compose.runtime.staticCompositionLocalOf
import com.stash.core.data.lastfm.recap.LastFmRecapException
import com.stash.feature.listening.recap.lastfm.LastFmRecapProgress
import java.util.Locale

/** Whose listening a Recap story tells: Stash's own history, or the user's Last.fm scrobbles. */
enum class RecapSource(val routeValue: String) {
    STASH("stash"),
    LASTFM("lastfm");

    companion object {
        /** The route's source argument; anything else (or none) is Stash's own recap. */
        fun of(value: String?): RecapSource = entries.firstOrNull { it.routeValue == value } ?: STASH
    }
}

/**
 * The words that change with a recap's source, so the same cards and share images say whose
 * listening it is. A Last.fm recap is labelled Last.fm everywhere (owner rule): its title ("Your
 * October on Last.fm"), its intro ("You scrobbled"), a "Scrobbles from Last.fm" credit on the
 * intro and summary, and every share image's footer ("Stash · Last.fm · October 2026"). It has no
 * end actions: its songs may not be in the library.
 */
data class RecapVoice(val source: RecapSource) {
    private val lastFm: Boolean get() = source == RecapSource.LASTFM

    /** "Your October" → "Your October on Last.fm". */
    fun title(base: String): String = if (lastFm) "$base on Last.fm" else base

    /** The intro card's lead-in before the big number ("You pressed play / 142 / times."). */
    val introLead: String get() = if (lastFm) "You scrobbled" else "You pressed play"

    /** The intro card's whole sentence, for TalkBack. */
    fun introSentence(count: Int): String = "$introLead $count times."

    /** The data credit on the intro and summary cards, or null for Stash's own listening. */
    val credit: String? get() = if (lastFm) LastFmRecapVoice.CREDIT else null

    /** The share images' footer. */
    fun footer(longName: String): String = if (lastFm) "Stash · Last.fm · $longName" else "Stash · $longName"

    /** The share image's file-name prefix. */
    val filePrefix: String get() = if (lastFm) "stash lastfm" else "stash"

    /** "Play your top songs" and "Save as playlist" under the summary (library songs only). */
    val endActions: Boolean get() = !lastFm

    companion object {
        val Stash = RecapVoice(RecapSource.STASH)
        val LastFm = RecapVoice(RecapSource.LASTFM)

        fun of(routeValue: String?): RecapVoice = RecapVoice(RecapSource.of(routeValue))
    }
}

/** The story's voice for the cards drawn under it (on screen and in share images). */
internal val LocalRecapVoice = staticCompositionLocalOf { RecapVoice.Stash }

/** The Last.fm recap's loading and empty-state lines (calm, never an error dump). */
internal object LastFmRecapVoice {
    const val CREDIT = "Scrobbles from Last.fm"
    const val READING = "Reading your scrobbles from Last.fm…"
    const val FINISHING = "Putting your story together…"
    const val NOT_CONNECTED = "Connect Last.fm in Settings to see this recap."
    const val NOT_ENOUGH = "Not enough scrobbles here for a recap yet. A recap needs ${RecapAvailability.MIN_PLAYS}."

    fun progressLine(progress: LastFmRecapProgress): String = when (progress) {
        is LastFmRecapProgress.Reading -> when {
            progress.read <= 0 -> READING
            progress.expected > 0 -> "Reading your scrobbles… %,d of %,d".format(Locale.US, minOf(progress.read, progress.expected), progress.expected)
            else -> "Reading your scrobbles… %,d".format(Locale.US, progress.read)
        }
        LastFmRecapProgress.Finishing -> FINISHING
    }

    fun failure(reason: LastFmRecapException.Reason?): String = when (reason) {
        LastFmRecapException.Reason.PRIVATE ->
            "This Last.fm profile keeps its listening private, so Stash can't read it. You can change that in Last.fm's privacy settings."
        LastFmRecapException.Reason.NOT_FOUND -> "Last.fm doesn't know this profile any more."
        LastFmRecapException.Reason.OFFLINE -> "Couldn't reach Last.fm. Check your connection and try again."
        LastFmRecapException.Reason.BUSY -> "Last.fm is busy right now. Try again in a minute."
        LastFmRecapException.Reason.FAILED, null -> "Couldn't read your Last.fm history. Try again."
    }

    fun canRetry(reason: LastFmRecapException.Reason): Boolean =
        reason != LastFmRecapException.Reason.PRIVATE && reason != LastFmRecapException.Reason.NOT_FOUND
}

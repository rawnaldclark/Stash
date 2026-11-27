package com.stash.feature.nowplaying.session

import androidx.annotation.StringRes
import com.stash.feature.nowplaying.R

/**
 * The room's life (Sessions step 4), as plain rules: what a reaction is drawn as, what a phrase id reads as, and the
 * feed under the crowd.
 */

/**
 * A reaction's text face ([com.stash.core.model.listen.RoomProtocol.FACES]) as its frames: one for most, two for a
 * big one, which steps between them as it climbs, drawn larger. The last frame is the face at rest, as the tray shows
 * it. Null for an id this app doesn't know (a newer app's, or an emoji, which a session never passes on): nothing is
 * shown.
 */
internal fun faceFrames(id: String): List<String>? = FACE_FRAMES[id]

/** The face at rest, or null for an id this app doesn't know. */
internal fun faceText(id: String): String? = FACE_FRAMES[id]?.last()

/** A big one: it plays over everyone's screen, larger, stepping between its frames. */
internal fun isBigFace(id: String): Boolean = (FACE_FRAMES[id]?.size ?: 0) > 1

/** Its name, as the tray shows it under the face: "/shrug". Also what a screen reader says, without the slash. */
internal fun faceName(id: String): String = "/" + id.removePrefix(FACE_PREFIX)

private const val FACE_PREFIX = "k:"

/** Raw strings where a face has a backslash, so it is written as it shows. */
private val FACE_FRAMES: Map<String, List<String>> = mapOf(
    "k:shrug" to listOf("""¯\_(ツ)_/¯"""),
    "k:lenny" to listOf("( ͡° ͜ʖ ͡°)"),
    "k:yay" to listOf("""\(^o^)/"""),
    "k:love" to listOf("(♥‿♥)"),
    "k:cool" to listOf("(⌐■_■)"),
    "k:look" to listOf("ಠ_ಠ"),
    "k:wow" to listOf("(⊙_⊙)"),
    "k:cry" to listOf("(╥﹏╥)"),
    "k:wave" to listOf("( ﾟ◡ﾟ)/"),
    "k:notes" to listOf("♪♫♪"),
    "k:heart" to listOf("<3"),
    "k:bear" to listOf("ʕ•ᴥ•ʔ"),
    "k:tableflip" to listOf("(°□°) ┬─┬", "(╯°□°)╯︵ ┻━┻"),
    "k:unflip" to listOf("┻━┻ ( º _ º)", "┬─┬ノ( º _ ºノ)"),
    "k:dance" to listOf("┏(･o･)┛♪", "♪┗(･o･)┓"),
    "k:rockon" to listOf("""\m/ (>_<) \m/""", """\m/ (^o^) \m/"""),
    "k:sparkle" to listOf("(ﾉ◕ヮ◕)ﾉ", "(ﾉ◕ヮ◕)ﾉ*:･ﾟ✧"),
)

/** A phrase id's words, or null for one this app doesn't know. */
@StringRes
internal fun phraseRes(id: String): Int? = when (id) {
    "hi" -> R.string.phrase_hi
    "who" -> R.string.phrase_who
    "tune" -> R.string.phrase_tune
    "again" -> R.string.phrase_again
    "louder" -> R.string.phrase_louder
    "more" -> R.string.phrase_more
    "chills" -> R.string.phrase_chills
    "thanks" -> R.string.phrase_thanks
    "morning" -> R.string.phrase_morning
    "night" -> R.string.phrase_night
    else -> null
}

/** One line of the feed. */
internal sealed interface FeedLine {
    /** "Ben tuned in". */
    data class Joined(val name: String?) : FeedLine

    /** "Ben · Tune!", or "Tune! ×4" once others said the same within [Feed.FOLD_MS] ([count] over 1). */
    data class Said(val name: String?, val phrase: String, val count: Int = 1) : FeedLine

    /** "Redbone is a crowd favourite". */
    data class Favourite(val title: String) : FeedLine

    /** "Ben is back": a regular arriving. */
    data class Back(val name: String?) : FeedLine

    /** "Lee is off the decks": two turns missed in a row. */
    data class OffDecks(val name: String?) : FeedLine

    /** "Last song": the host is ending after this one. */
    data object LastSong : FeedLine
}

/**
 * The feed under the crowd: the last [SHOWN] lines, newest last, kept only while the session is on screen's side of
 * memory (nothing is stored). The same phrase said again within [FOLD_MS] folds into its line instead of adding one.
 * Immutable: each change returns the next feed.
 */
internal data class Feed(val lines: List<Entry> = emptyList()) {
    data class Entry(val id: Long, val line: FeedLine, val at: Long)

    fun add(line: FeedLine, now: Long): Feed {
        if (line is FeedLine.Said) {
            val i = lines.indexOfLast { (it.line as? FeedLine.Said)?.phrase == line.phrase && now - it.at < FOLD_MS }
            if (i >= 0) {
                val old = lines[i]
                val folded = (old.line as FeedLine.Said).let { it.copy(count = it.count + 1) }
                // It moves to the end, as the newest thing said, and keeps its id so the screen doesn't flash it.
                return Feed(lines.filterIndexed { j, _ -> j != i } + old.copy(line = folded, at = now))
            }
        }
        val id = (lines.maxOfOrNull { it.id } ?: 0L) + 1
        return Feed((lines + Entry(id, line, now)).takeLast(SHOWN))
    }

    companion object {
        const val SHOWN = 4
        const val FOLD_MS = 10_000L
    }
}

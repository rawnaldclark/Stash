package com.stash.feature.nowplaying.session

import androidx.annotation.StringRes
import com.stash.feature.nowplaying.R

/**
 * The room's life (Sessions step 4), as plain rules: what an animated reaction is drawn as, what a phrase id reads
 * as, and the feed under the crowd.
 */

/**
 * What a reaction is drawn with: one of the six emoji as itself, an animated one ([com.stash.core.model.listen.RoomProtocol.ANIMS])
 * as its glyph, drawn larger and moving. Null for an id this app doesn't know (a newer app's): nothing is shown.
 */
internal fun reactionGlyph(emoji: String): String? = if (emoji.startsWith(ANIM_PREFIX)) ANIM_GLYPHS[emoji] else emoji

internal fun isAnimated(emoji: String): Boolean = emoji.startsWith(ANIM_PREFIX)

private const val ANIM_PREFIX = "a:"

private val ANIM_GLYPHS = mapOf(
    "a:applause" to "👏", // clapping hands
    "a:fire" to "🔥",
    "a:hearts" to "💕", // two hearts
    "a:dancing" to "🕺", // man dancing
    "a:mindblown" to "🤯",
    "a:lighter" to "🕯️", // candle: a lighter held up
    "a:headphones" to "🎧",
    "a:rewind" to "⏪",
    "a:laughing" to "😂",
    "a:bassdrop" to "🔊", // speaker, loud
    "a:wave" to "👋",
    "a:clap" to "🙌", // raised hands: clapping along
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

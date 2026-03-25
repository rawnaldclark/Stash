package com.stash.core.media

import androidx.media3.common.Player
import com.stash.core.model.RepeatMode

/**
 * Maps the app-level [RepeatMode] to the Media3 [Player] repeat-mode constant.
 */
fun RepeatMode.toMedia3(): Int = when (this) {
    RepeatMode.OFF -> Player.REPEAT_MODE_OFF
    RepeatMode.ALL -> Player.REPEAT_MODE_ALL
    RepeatMode.ONE -> Player.REPEAT_MODE_ONE
}

/**
 * Maps a Media3 [Player] repeat-mode constant back to the app-level [RepeatMode].
 */
fun Int.toRepeatMode(): RepeatMode = when (this) {
    Player.REPEAT_MODE_ALL -> RepeatMode.ALL
    Player.REPEAT_MODE_ONE -> RepeatMode.ONE
    else -> RepeatMode.OFF
}

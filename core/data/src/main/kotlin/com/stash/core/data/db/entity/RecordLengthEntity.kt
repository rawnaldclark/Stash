package com.stash.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * What Your record's lengths pass remembers about one song it couldn't time (stash-player `src/lib/spotify/lengths.ts`
 * `LengthsMark`, its `none`, `failed` and `failures`): Last.fm had no length for it ([kind] [NONE]), or its ask failed, [kind]
 * being how many times in a row (1 or more: its wait doubles with each, a day to 16), and when ([at], epoch s). A song is in one
 * of the two at most.
 *
 * [key] is the record's song key (`textKey(keyTitle(title), artist)`), never a row's id: the pass groups a song's scrobbles under
 * it. Kept beside `record_plays` rather than in the preferences with the rest of the pass's mark, since a long history leaves
 * thousands of songs Last.fm can't time. Removing the scrobbles empties it.
 */
@Entity(tableName = "record_lengths")
data class RecordLengthEntity(
    @PrimaryKey @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "kind") val kind: Int,
    @ColumnInfo(name = "at") val at: Long,
) {
    companion object {
        /** Last.fm had no length for the song. */
        const val NONE = 0

        /** Its ask failed once (more failures in a row count up from here). */
        const val FAILED = 1
    }
}

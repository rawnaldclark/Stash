package com.stash.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * What Your record's lengths pass remembers about one song it couldn't time (stash-player `src/lib/spotify/lengths.ts`
 * `LengthsMark`, its `none` and `failed`): Last.fm had no length for it ([NONE]), or its ask stayed busy after every try
 * ([FAILED]), and when ([at], epoch s). A song is in one of the two at most; the pass asks it again after 30 days or a day.
 *
 * [key] is the record's song key (`textKey(keyTitle(title), artist)`), never a row's id: the pass groups a song's scrobbles under
 * it. Kept beside `record_plays` rather than in the preferences with the rest of the pass's mark, since a long history leaves
 * thousands of songs Last.fm can't time, and each is saved the moment its answer comes. Removing the scrobbles empties it.
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

        /** Its ask failed after every try. */
        const val FAILED = 1
    }
}

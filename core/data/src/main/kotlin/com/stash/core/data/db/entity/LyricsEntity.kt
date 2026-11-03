package com.stash.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * v0.9.36: per-track lyrics row. One lyrics row per track; the primary
 * key is the track's INTEGER id with FK cascade so deleting a track
 * cleans up the lyrics row automatically.
 *
 * `plainText` is the un-timed lyrics (always present when the row
 * exists, unless [instrumental] = true). `syncedLrc` is the optional
 * LRC-format timestamped block (when the source returns one). Both
 * may be null only in the [instrumental] = true case where we
 * explicitly know the track has no lyrics.
 *
 * Sentinel semantics for "tried and got nothing" live on the
 * `tracks.lyrics_fetched_at` column rather than on this table — a
 * tried-and-failed result is represented by stamping 0L on
 * `tracks.lyrics_fetched_at` and NOT writing a lyrics row at all.
 */
@Entity(
    tableName = "lyrics",
    foreignKeys = [ForeignKey(
        entity = TrackEntity::class,
        parentColumns = ["id"],
        childColumns = ["track_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("track_id")],
)
data class LyricsEntity(
    @PrimaryKey @ColumnInfo("track_id")       val trackId: Long,
    @ColumnInfo("plain_text")                  val plainText: String?,
    @ColumnInfo("synced_lrc")                  val syncedLrc: String?,
    @ColumnInfo("instrumental")                val instrumental: Boolean,
    @ColumnInfo("language")                    val language: String?,
    @ColumnInfo("source")                      val source: String,
    @ColumnInfo("source_lyrics_id")            val sourceLyricsId: String?,
    @ColumnInfo("fetched_at")                  val fetchedAt: Long,
    @ColumnInfo("ttml")                        val ttml: String? = null,
    @ColumnInfo("ttml_checked_at")             val ttmlCheckedAt: Long? = null,
    /** Signed ms; positive delays lyrics, negative shows them earlier. */
    @ColumnInfo("sync_offset_ms")              val syncOffsetMs: Long = 0L,
    @ColumnInfo("credit_uploader_name")        val creditUploaderName: String? = null,
    @ColumnInfo("credit_uploader_url")         val creditUploaderUrl: String? = null,
    @ColumnInfo("credit_maker_name")           val creditMakerName: String? = null,
    @ColumnInfo("credit_maker_url")            val creditMakerUrl: String? = null,
)

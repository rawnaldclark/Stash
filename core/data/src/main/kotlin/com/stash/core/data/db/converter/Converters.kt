package com.stash.core.data.db.converter

import androidx.room.TypeConverter
import com.stash.core.model.DownloadFailureType
import com.stash.core.model.DownloadStatus
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import com.stash.core.model.SyncState
import com.stash.core.model.SyncTrigger
import java.time.Instant

/**
 * Room [TypeConverter] collection for the Stash database.
 *
 * Converts between domain types and their Room-storable representations:
 * - [Instant] <-> epoch-millis [Long]
 * - All enums <-> [String] (using `name` / `valueOf`)
 */
internal class Converters {

    // ── Instant ──────────────────────────────────────────────────────────

    @TypeConverter
    fun instantToLong(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun longToInstant(value: Long?): Instant? = value?.let { Instant.ofEpochMilli(it) }

    // ── MusicSource ──────────────────────────────────────────────────────

    @TypeConverter
    fun musicSourceToString(value: MusicSource?): String? = value?.name

    @TypeConverter
    fun stringToMusicSource(value: String?): MusicSource? =
        // A value this build doesn't know (a backup from a newer Stash) reads as LOCAL: a song
        // that only plays what it has, never one sent off to be matched or resolved online.
        value?.let { v -> MusicSource.entries.firstOrNull { it.name == v } ?: MusicSource.LOCAL }

    // ── PlaylistType ─────────────────────────────────────────────────────

    @TypeConverter
    fun playlistTypeToString(value: PlaylistType?): String? = value?.name

    @TypeConverter
    fun stringToPlaylistType(value: String?): PlaylistType? =
        value?.let { PlaylistType.valueOf(it) }

    // ── SyncState ────────────────────────────────────────────────────────

    @TypeConverter
    fun syncStateToString(value: SyncState?): String? = value?.name

    @TypeConverter
    fun stringToSyncState(value: String?): SyncState? =
        value?.let { SyncState.valueOf(it) }

    // ── SyncTrigger ──────────────────────────────────────────────────────

    @TypeConverter
    fun syncTriggerToString(value: SyncTrigger?): String? = value?.name

    @TypeConverter
    fun stringToSyncTrigger(value: String?): SyncTrigger? =
        value?.let { SyncTrigger.valueOf(it) }

    // ── DownloadStatus ───────────────────────────────────────────────────

    @TypeConverter
    fun downloadStatusToString(value: DownloadStatus?): String? = value?.name

    @TypeConverter
    fun stringToDownloadStatus(value: String?): DownloadStatus? =
        value?.let { DownloadStatus.valueOf(it) }

    // ── DownloadFailureType ──────────────────────────────────────────────

    @TypeConverter
    fun downloadFailureTypeToString(value: DownloadFailureType?): String? = value?.name

    /**
     * Lenient: a name this build doesn't know (a type a newer build added, read after a
     * downgrade) reads as UNKNOWN instead of throwing on the first queue read, which took the
     * Downloads screen and every download worker down with it.
     */
    @TypeConverter
    fun stringToDownloadFailureType(value: String?): DownloadFailureType? =
        value?.let { name -> DownloadFailureType.entries.firstOrNull { it.name == name } ?: DownloadFailureType.UNKNOWN }
}

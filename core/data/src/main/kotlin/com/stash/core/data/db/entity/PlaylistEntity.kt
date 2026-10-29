package com.stash.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import java.time.Instant

/**
 * Room entity representing a playlist synced from a music service.
 */
@Entity(
    tableName = "playlists",
    indices = [
        Index(value = ["source_id"], unique = true),
    ],
)
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String,

    val source: MusicSource,

    @ColumnInfo(name = "source_id")
    val sourceId: String = "",

    val type: PlaylistType = PlaylistType.CUSTOM,

    @ColumnInfo(name = "mix_number")
    val mixNumber: Int? = null,

    @ColumnInfo(name = "last_synced")
    val lastSynced: Instant? = null,

    @ColumnInfo(name = "track_count")
    val trackCount: Int = 0,

    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,

    @ColumnInfo(name = "art_url")
    val artUrl: String? = null,

    /**
     * The mirror stamp of a Spotify library playlist a sync mirrored completely
     * ([com.stash.core.data.sync.workers.SpotifyMirrorStamp]): Spotify's revision
     * plus what that mirror left here. While it still matches, a sync skips
     * reading the playlist. Null means "read it in full next time": never
     * mirrored, mirrored incompletely, or forgotten (Undo, a block or unblock,
     * a backup restore, or a playlist a backup merge adds). Only the sync's
     * diff writes a value.
     */
    @ColumnInfo(name = "snapshot_id")
    val snapshotId: String? = null,

    /** Whether this playlist participates in sync/download. Kotlin default is
     *  `false` — users must explicitly opt-in via the Spotify Sync
     *  Preferences on the Sync tab. Prevents surprise downloads of every
     *  playlist on the user's Spotify account on the first sync. The SQL
     *  `defaultValue` stays `"1"` to preserve the existing schema and avoid
     *  a Room migration; Room-generated INSERTs always supply the Kotlin
     *  value so the SQL default is effectively unused in practice. */
    @ColumnInfo(name = "sync_enabled", defaultValue = "1")
    val syncEnabled: Boolean = false,

    /**
     * Timestamp of when this playlist was first added to the user's
     * library — stable across syncs, unlike [lastSynced] which resets
     * every sync run. Drives the Library tab's "Recently added" sort
     * order so user-added playlists stay where the user added them in
     * the list instead of reshuffling after each sync.
     *
     * Default = `Instant.now()` for Kotlin-side inserts; migration
     * v12→v13 backfills existing rows from `last_synced` if set, else
     * the migration's wall clock.
     */
    @ColumnInfo(name = "date_added", defaultValue = "0")
    val dateAdded: Instant = Instant.now(),

    /**
     * When true, this playlist is suppressed from the Home rails (but still
     * appears in Library). Lets surfaced/synced mixes be hidden from Home
     * without disabling sync or deleting them. Default `false` — nothing is
     * hidden until the user opts in.
     */
    @ColumnInfo(name = "hide_from_home", defaultValue = "0")
    val hideFromHome: Boolean = false,

    /** User-pinned playlists sort first in the Library Playlists grid. */
    @ColumnInfo(name = "pinned", defaultValue = "0")
    val pinned: Boolean = false,

    /**
     * Epoch-millis when the user put this playlist on the Home "Your
     * playlists" rail; NULL = not on Home (the default). Doubles as the
     * rail's stable sort key (first pinned renders first). Independent of
     * [pinned] (Library grid sort) and [hideFromHome] (mixes-only flow).
     */
    @ColumnInfo(name = "pinned_to_home_at")
    val pinnedToHomeAt: Long? = null,

    /**
     * The playlist page's Download switch (#474): keep this playlist on the
     * phone in either mode. Separate from [syncEnabled], which in Stream-only
     * mode means "keep synced and on Home" and so can't double as "download".
     * Every sync queues whatever a kept playlist gained; turning it off stops
     * new downloads and deletes nothing.
     */
    @ColumnInfo(name = "keep_offline", defaultValue = "0")
    val keepOffline: Boolean = false,

    /**
     * The user took this playlist out of their library from its account's
     * playlist page (the Sync tab overhaul, 2026-10-29). Hidden means inactive
     * ([isActive] false), the state every surface already leaves out; this flag
     * is what tells sync not to bring it back, and the playlist page to keep
     * listing it so it can be turned back on. Separate from [syncEnabled]
     * (Download mode: downloads) and [keepOffline] (on the phone).
     */
    @ColumnInfo(name = "user_hidden", defaultValue = "0")
    val userHidden: Boolean = false,

    /**
     * A mix that stacks: each sync adds the songs it brings and removes none, so it grows
     * into a library of its own. Off = the mix mirrors what the service gives it this time.
     * Only the mixes [com.stash.core.data.sync.MixStacking] names can stack; the switch is
     * per mix since 2026-10-29 (it was one Refresh/Accumulate setting per account).
     */
    @ColumnInfo(name = "stack_songs", defaultValue = "0")
    val stackSongs: Boolean = false,
)

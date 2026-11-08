package com.stash.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.stash.core.auth.spotify.SpotifyProfile

/**
 * v79: one EXTRA Spotify account (plan 2026-11-04 §2.1). The main account has no row: it keeps
 * its one `spotify_token` sign-in, and its playlists carry no account tag (NULL).
 *
 * An extra is read-only: Stash reads its playlists, Liked Songs, saved albums and (when switched
 * on) mixes, and never writes to it. Its sign-in lives in the encrypted token store under this
 * row's [id] (`spotify_extra_<id>`), never in the database, so a restored backup brings the
 * account, its name and switches back without its login (it shows "Sign in again"), the rule
 * every other login follows.
 *
 * Rows it owns carry its [userId] in `playlists.spotify_account` and
 * `remote_playlist_snapshots.spotify_account`, not this row's id: removing and re-adding the same
 * account re-adopts them, Download switches included.
 */
@Entity(
    tableName = "spotify_accounts",
    indices = [Index(value = ["user_id"], unique = true)],
)
data class SpotifyAccountEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /**
     * The account's Spotify user id, normalised by `SpotifyProfile.accountKeyOf` (trimmed, lower
     * case, no `spotify:user:` prefix): one account is one row, as the unique index compares case
     * exactly. Add and look up through [com.stash.core.data.db.dao.SpotifyAccountDao.addOrFind] /
     * [com.stash.core.data.db.dao.SpotifyAccountDao.findByAccount], which normalise; a row built
     * with an id in any other shape is refused (see `init`). Never shown, never logged.
     */
    @ColumnInfo(name = "user_id")
    val userId: String,
    /** The name the listener gave it (pre-filled with the Spotify name), shown on its cards. */
    val label: String,
    /** When it was added, epoch millis; the accounts' display order. */
    @ColumnInfo(name = "added_at")
    val addedAt: Long,
    /** Sync its playlists. */
    @ColumnInfo(name = "sync_playlists", defaultValue = "1")
    val syncPlaylists: Boolean = true,
    /** Sync its Liked Songs, as its own Liked row (`spotify_liked_songs:<userId>`). */
    @ColumnInfo(name = "sync_liked", defaultValue = "1")
    val syncLiked: Boolean = true,
    /** Sync its saved albums. */
    @ColumnInfo(name = "sync_albums", defaultValue = "1")
    val syncAlbums: Boolean = true,
    /** Sync its mixes (Daily Mix, Discover Weekly…). Off by default: Home would get two unlabelled "Daily Mix 1"s. */
    @ColumnInfo(name = "sync_mixes", defaultValue = "0")
    val syncMixes: Boolean = false,
    /**
     * When its sign-in was found to no longer work (epoch millis); null while it works. Its sync
     * is skipped and its rows are left exactly as they are until it signs in again.
     */
    @ColumnInfo(name = "signed_out_at")
    val signedOutAt: Long? = null,
) {
    init {
        // Every row is built here, so an id in another shape never reaches the table. A row Room
        // reads back passed this check when it was written.
        require(SpotifyProfile.isAccountKey(userId)) {
            "spotify_accounts.user_id must be normalised (SpotifyProfile.accountKeyOf)"
        }
    }
}

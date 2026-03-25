# Phase 3: Database + Library UI — Implementation Plan

**App:** Stash (Offline Music Sync)
**Phase:** 3 of N
**Assumes:** Phase 1 (project scaffolding, Hilt, navigation, theme) and Phase 2 (Media3 player, PlayerRepository, NowPlayingScreen, MiniPlayer) are complete.
**Estimated time:** 60-80 tasks at 2-5 minutes each

---

## Overview

This phase builds the persistence layer (Room database with FTS4 search) and connects it to two feature screens (Home and Library). By the end of this phase, the app has a populated database, a functional Home screen with sync status and playlist carousels, a Library screen with search/filter/sort, and tapping a track launches playback through the real player from Phase 2.

---

## Section A: Core Model & Enums (core:model)

### Task 3.01 — Create source and status enums

**File:** `core/model/src/main/java/com/stash/app/core/model/TrackSource.kt`

```kotlin
package com.stash.app.core.model

enum class TrackSource {
    SPOTIFY,
    YOUTUBE,
    BOTH
}
```

**File:** `core/model/src/main/java/com/stash/app/core/model/PlaylistType.kt`

```kotlin
package com.stash.app.core.model

enum class PlaylistType {
    DAILY_MIX,
    LIKED_SONGS,
    CUSTOM
}
```

**File:** `core/model/src/main/java/com/stash/app/core/model/SyncStatus.kt`

```kotlin
package com.stash.app.core.model

enum class SyncStatus {
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    CANCELLED
}
```

**File:** `core/model/src/main/java/com/stash/app/core/model/DownloadStatus.kt`

```kotlin
package com.stash.app.core.model

enum class DownloadStatus {
    QUEUED,
    MATCHING,
    DOWNLOADING,
    PROCESSING,
    TAGGING,
    COMPLETED,
    FAILED,
    UNMATCHED
}
```

**File:** `core/model/src/main/java/com/stash/app/core/model/SyncTrigger.kt`

```kotlin
package com.stash.app.core.model

enum class SyncTrigger {
    SCHEDULED,
    MANUAL,
    FIRST_LAUNCH
}
```

**Verify:** Build succeeds with `./gradlew :core:model:build`.

---

### Task 3.02 — Create domain model data classes

**File:** `core/model/src/main/java/com/stash/app/core/model/Track.kt`

```kotlin
package com.stash.app.core.model

import java.time.Instant

data class Track(
    val id: Long = 0,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0,
    val filePath: String? = null,
    val fileFormat: String? = null,
    val qualityKbps: Int? = null,
    val fileSizeBytes: Long? = null,
    val source: TrackSource = TrackSource.SPOTIFY,
    val spotifyUri: String? = null,
    val youtubeId: String? = null,
    val albumArtUrl: String? = null,
    val albumArtPath: String? = null,
    val dateAdded: Instant = Instant.now(),
    val lastPlayed: Instant? = null,
    val playCount: Int = 0,
    val isDownloaded: Boolean = false,
    val canonicalTitle: String = "",
    val canonicalArtist: String = "",
    val matchConfidence: Float? = null,
)
```

**File:** `core/model/src/main/java/com/stash/app/core/model/Playlist.kt`

```kotlin
package com.stash.app.core.model

import java.time.Instant

data class Playlist(
    val id: Long = 0,
    val name: String,
    val source: TrackSource,
    val sourceId: String = "",
    val type: PlaylistType,
    val mixNumber: Int? = null,
    val lastSynced: Instant? = null,
    val trackCount: Int = 0,
    val isActive: Boolean = true,
    val artUrl: String? = null,
)
```

**File:** `core/model/src/main/java/com/stash/app/core/model/SyncHistory.kt`

```kotlin
package com.stash.app.core.model

import java.time.Instant

data class SyncHistory(
    val id: Long = 0,
    val startedAt: Instant,
    val completedAt: Instant? = null,
    val status: SyncStatus,
    val playlistsChecked: Int = 0,
    val newTracksFound: Int = 0,
    val tracksDownloaded: Int = 0,
    val tracksFailed: Int = 0,
    val bytesDownloaded: Long = 0,
    val errorMessage: String? = null,
    val trigger: SyncTrigger = SyncTrigger.MANUAL,
)
```

**Verify:** `./gradlew :core:model:build`

---

## Section B: Room Database Setup (core:data)

### Task 3.03 — Add Room dependencies to core:data build.gradle.kts

**File:** `core/data/build.gradle.kts`

Add to the existing dependencies block:

```kotlin
plugins {
    // ... existing
    alias(libs.plugins.ksp)
}

dependencies {
    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Existing deps...
    implementation(project(":core:model"))
}
```

**File:** `gradle/libs.versions.toml` — add in the `[versions]` section:

```toml
room = "2.7.1"
```

In `[libraries]`:

```toml
room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
```

In `[plugins]` (if not already present):

```toml
ksp = { id = "com.google.devtools.ksp", version = "2.1.10-1.0.31" }
```

**Verify:** `./gradlew :core:data:dependencies` shows Room resolved.

---

### Task 3.04 — Create Room type converters

**File:** `core/data/src/main/java/com/stash/app/core/data/db/converter/Converters.kt`

```kotlin
package com.stash.app.core.data.db.converter

import androidx.room.TypeConverter
import com.stash.app.core.model.DownloadStatus
import com.stash.app.core.model.PlaylistType
import com.stash.app.core.model.SyncStatus
import com.stash.app.core.model.SyncTrigger
import com.stash.app.core.model.TrackSource
import java.time.Instant

class Converters {

    // Instant <-> Long (epoch millis)
    @TypeConverter
    fun fromInstant(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun toInstant(value: Long?): Instant? = value?.let { Instant.ofEpochMilli(it) }

    // TrackSource <-> String
    @TypeConverter
    fun fromTrackSource(value: TrackSource): String = value.name

    @TypeConverter
    fun toTrackSource(value: String): TrackSource = TrackSource.valueOf(value)

    // PlaylistType <-> String
    @TypeConverter
    fun fromPlaylistType(value: PlaylistType): String = value.name

    @TypeConverter
    fun toPlaylistType(value: String): PlaylistType = PlaylistType.valueOf(value)

    // SyncStatus <-> String
    @TypeConverter
    fun fromSyncStatus(value: SyncStatus): String = value.name

    @TypeConverter
    fun toSyncStatus(value: String): SyncStatus = SyncStatus.valueOf(value)

    // SyncTrigger <-> String
    @TypeConverter
    fun fromSyncTrigger(value: SyncTrigger): String = value.name

    @TypeConverter
    fun toSyncTrigger(value: String): SyncTrigger = SyncTrigger.valueOf(value)

    // DownloadStatus <-> String
    @TypeConverter
    fun fromDownloadStatus(value: DownloadStatus): String = value.name

    @TypeConverter
    fun toDownloadStatus(value: String): DownloadStatus = DownloadStatus.valueOf(value)
}
```

**Verify:** `./gradlew :core:data:compileDebugKotlin`

---

### Task 3.05 — Create TrackEntity

**File:** `core/data/src/main/java/com/stash/app/core/data/db/entity/TrackEntity.kt`

```kotlin
package com.stash.app.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.stash.app.core.model.TrackSource
import java.time.Instant

@Entity(
    tableName = "tracks",
    indices = [
        Index(value = ["spotify_uri"], unique = true),
        Index(value = ["youtube_id"], unique = true),
        Index(value = ["artist"]),
        Index(value = ["album"]),
        Index(value = ["date_added"]),
        Index(value = ["last_played"]),
        Index(value = ["play_count"]),
        Index(value = ["title", "artist"]),
    ]
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val artist: String,
    val album: String = "",
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long = 0,
    @ColumnInfo(name = "file_path")
    val filePath: String? = null,
    @ColumnInfo(name = "file_format")
    val fileFormat: String? = null,
    @ColumnInfo(name = "quality_kbps")
    val qualityKbps: Int? = null,
    @ColumnInfo(name = "file_size_bytes")
    val fileSizeBytes: Long? = null,
    val source: TrackSource = TrackSource.SPOTIFY,
    @ColumnInfo(name = "spotify_uri")
    val spotifyUri: String? = null,
    @ColumnInfo(name = "youtube_id")
    val youtubeId: String? = null,
    @ColumnInfo(name = "album_art_url")
    val albumArtUrl: String? = null,
    @ColumnInfo(name = "album_art_path")
    val albumArtPath: String? = null,
    @ColumnInfo(name = "date_added")
    val dateAdded: Instant = Instant.now(),
    @ColumnInfo(name = "last_played")
    val lastPlayed: Instant? = null,
    @ColumnInfo(name = "play_count")
    val playCount: Int = 0,
    @ColumnInfo(name = "is_downloaded")
    val isDownloaded: Boolean = false,
    @ColumnInfo(name = "canonical_title")
    val canonicalTitle: String = "",
    @ColumnInfo(name = "canonical_artist")
    val canonicalArtist: String = "",
    @ColumnInfo(name = "match_confidence")
    val matchConfidence: Float? = null,
)
```

**Verify:** `./gradlew :core:data:compileDebugKotlin`

---

### Task 3.06 — Create PlaylistEntity

**File:** `core/data/src/main/java/com/stash/app/core/data/db/entity/PlaylistEntity.kt`

```kotlin
package com.stash.app.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.stash.app.core.model.PlaylistType
import com.stash.app.core.model.TrackSource
import java.time.Instant

@Entity(
    tableName = "playlists",
    indices = [
        Index(value = ["source_id"], unique = true),
        Index(value = ["type"]),
    ]
)
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val source: TrackSource,
    @ColumnInfo(name = "source_id")
    val sourceId: String = "",
    val type: PlaylistType,
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
)
```

---

### Task 3.07 — Create PlaylistTrackCrossRef

**File:** `core/data/src/main/java/com/stash/app/core/data/db/entity/PlaylistTrackCrossRef.kt`

```kotlin
package com.stash.app.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import java.time.Instant

@Entity(
    tableName = "playlist_track_cross_ref",
    primaryKeys = ["playlist_id", "track_id"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlist_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["track_id"],
            onDelete = ForeignKey.CASCADE
        ),
    ],
    indices = [
        Index(value = ["track_id"]),
        Index(value = ["playlist_id"]),
    ]
)
data class PlaylistTrackCrossRef(
    @ColumnInfo(name = "playlist_id")
    val playlistId: Long,
    @ColumnInfo(name = "track_id")
    val trackId: Long,
    val position: Int = 0,
    @ColumnInfo(name = "added_at")
    val addedAt: Instant = Instant.now(),
    @ColumnInfo(name = "removed_at")
    val removedAt: Instant? = null,
)
```

---

### Task 3.08 — Create SyncHistoryEntity

**File:** `core/data/src/main/java/com/stash/app/core/data/db/entity/SyncHistoryEntity.kt`

```kotlin
package com.stash.app.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.stash.app.core.model.SyncStatus
import com.stash.app.core.model.SyncTrigger
import java.time.Instant

@Entity(tableName = "sync_history")
data class SyncHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "started_at")
    val startedAt: Instant,
    @ColumnInfo(name = "completed_at")
    val completedAt: Instant? = null,
    val status: SyncStatus,
    @ColumnInfo(name = "playlists_checked")
    val playlistsChecked: Int = 0,
    @ColumnInfo(name = "new_tracks_found")
    val newTracksFound: Int = 0,
    @ColumnInfo(name = "tracks_downloaded")
    val tracksDownloaded: Int = 0,
    @ColumnInfo(name = "tracks_failed")
    val tracksFailed: Int = 0,
    @ColumnInfo(name = "bytes_downloaded")
    val bytesDownloaded: Long = 0,
    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,
    val trigger: SyncTrigger = SyncTrigger.MANUAL,
)
```

---

### Task 3.09 — Create DownloadQueueEntity

**File:** `core/data/src/main/java/com/stash/app/core/data/db/entity/DownloadQueueEntity.kt`

```kotlin
package com.stash.app.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.stash.app.core.model.DownloadStatus
import java.time.Instant

@Entity(
    tableName = "download_queue",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["track_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = SyncHistoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["sync_id"],
            onDelete = ForeignKey.SET_NULL
        ),
    ],
    indices = [
        Index(value = ["track_id"]),
        Index(value = ["sync_id"]),
        Index(value = ["status"]),
    ]
)
data class DownloadQueueEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "track_id")
    val trackId: Long,
    @ColumnInfo(name = "sync_id")
    val syncId: Long? = null,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    @ColumnInfo(name = "search_query")
    val searchQuery: String? = null,
    @ColumnInfo(name = "youtube_url")
    val youtubeUrl: String? = null,
    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0,
    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant = Instant.now(),
    @ColumnInfo(name = "completed_at")
    val completedAt: Instant? = null,
)
```

---

### Task 3.10 — Create SourceAccountEntity

**File:** `core/data/src/main/java/com/stash/app/core/data/db/entity/SourceAccountEntity.kt`

```kotlin
package com.stash.app.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.stash.app.core.model.TrackSource
import java.time.Instant

@Entity(
    tableName = "source_accounts",
    indices = [
        Index(value = ["source"], unique = true),
    ]
)
data class SourceAccountEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val source: TrackSource,
    @ColumnInfo(name = "display_name")
    val displayName: String = "",
    val email: String = "",
    @ColumnInfo(name = "avatar_url")
    val avatarUrl: String? = null,
    @ColumnInfo(name = "is_connected")
    val isConnected: Boolean = false,
    @ColumnInfo(name = "connected_at")
    val connectedAt: Instant? = null,
    @ColumnInfo(name = "last_sync_at")
    val lastSyncAt: Instant? = null,
)
```

---

### Task 3.11 — Create TrackFts FTS4 entity

**File:** `core/data/src/main/java/com/stash/app/core/data/db/entity/TrackFts.kt`

```kotlin
package com.stash.app.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions

@Fts4(contentEntity = TrackEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "tracks_fts")
data class TrackFts(
    val title: String,
    val artist: String,
    val album: String,
)
```

**Key design decision:** Using `contentEntity = TrackEntity::class` creates an external-content FTS4 table. Room automatically keeps it in sync when tracks are inserted/updated/deleted through the DAO. The `TOKENIZER_UNICODE61` handles accented characters (e.g., searching "Beyonce" matches "Beyoncé").

---

### Task 3.12 — Create StashDatabase

**File:** `core/data/src/main/java/com/stash/app/core/data/db/StashDatabase.kt`

```kotlin
package com.stash.app.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.stash.app.core.data.db.converter.Converters
import com.stash.app.core.data.db.dao.DownloadQueueDao
import com.stash.app.core.data.db.dao.PlaylistDao
import com.stash.app.core.data.db.dao.SourceAccountDao
import com.stash.app.core.data.db.dao.SyncHistoryDao
import com.stash.app.core.data.db.dao.TrackDao
import com.stash.app.core.data.db.entity.DownloadQueueEntity
import com.stash.app.core.data.db.entity.PlaylistEntity
import com.stash.app.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.app.core.data.db.entity.SourceAccountEntity
import com.stash.app.core.data.db.entity.SyncHistoryEntity
import com.stash.app.core.data.db.entity.TrackEntity
import com.stash.app.core.data.db.entity.TrackFts

@Database(
    entities = [
        TrackEntity::class,
        PlaylistEntity::class,
        PlaylistTrackCrossRef::class,
        SyncHistoryEntity::class,
        DownloadQueueEntity::class,
        SourceAccountEntity::class,
        TrackFts::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class StashDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun syncHistoryDao(): SyncHistoryDao
    abstract fun downloadQueueDao(): DownloadQueueDao
    abstract fun sourceAccountDao(): SourceAccountDao
}
```

---

### Task 3.13 — Configure schema export in build.gradle.kts

**File:** `core/data/build.gradle.kts` — add KSP arg:

```kotlin
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.generateKotlin", "true")
}
```

Also add to `.gitignore` exclusion — schemas should be committed:

**Note:** Add `core/data/schemas/` to git tracking (do NOT gitignore it). This is required for Room's migration testing.

**Verify:** `./gradlew :core:data:kspDebugKotlin` produces `core/data/schemas/com.stash.app.core.data.db.StashDatabase/1.json`.

---

## Section C: DAOs

### Task 3.14 — Create TrackDao

**File:** `core/data/src/main/java/com/stash/app/core/data/db/dao/TrackDao.kt`

```kotlin
package com.stash.app.core.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.stash.app.core.data.db.entity.TrackEntity
import com.stash.app.core.model.TrackSource
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {

    // === Inserts ===

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(track: TrackEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(tracks: List<TrackEntity>): List<Long>

    // === Updates ===

    @Update
    suspend fun update(track: TrackEntity)

    @Query("UPDATE tracks SET play_count = play_count + 1, last_played = :timestamp WHERE id = :trackId")
    suspend fun incrementPlayCount(trackId: Long, timestamp: Long)

    @Query("UPDATE tracks SET is_downloaded = :downloaded, file_path = :filePath WHERE id = :trackId")
    suspend fun markDownloaded(trackId: Long, downloaded: Boolean, filePath: String?)

    @Query("UPDATE tracks SET source = :source WHERE id = :trackId")
    suspend fun updateSource(trackId: Long, source: TrackSource)

    // === Deletes ===

    @Delete
    suspend fun delete(track: TrackEntity)

    @Query("DELETE FROM tracks WHERE id = :trackId")
    suspend fun deleteById(trackId: Long)

    // === Queries (Flow-based for reactive UI) ===

    @Query("SELECT * FROM tracks WHERE id = :trackId")
    fun getTrackById(trackId: Long): Flow<TrackEntity?>

    @Query("SELECT * FROM tracks WHERE id = :trackId")
    suspend fun getTrackByIdOnce(trackId: Long): TrackEntity?

    @Query("SELECT * FROM tracks ORDER BY date_added DESC")
    fun getAllTracksByDateAdded(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks ORDER BY title ASC, artist ASC")
    fun getAllTracksAlphabetical(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks ORDER BY play_count DESC")
    fun getAllTracksByPlayCount(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE is_downloaded = 1 ORDER BY date_added DESC")
    fun getDownloadedTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks ORDER BY date_added DESC LIMIT :limit")
    fun getRecentlyAdded(limit: Int = 20): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE last_played IS NOT NULL ORDER BY last_played DESC LIMIT :limit")
    fun getRecentlyPlayed(limit: Int = 20): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE source = :source ORDER BY date_added DESC")
    fun getTracksBySource(source: TrackSource): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE artist = :artist ORDER BY album ASC, title ASC")
    fun getTracksByArtist(artist: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE album = :album AND artist = :artist ORDER BY title ASC")
    fun getTracksByAlbum(album: String, artist: String): Flow<List<TrackEntity>>

    @Query("SELECT DISTINCT artist FROM tracks ORDER BY artist ASC")
    fun getAllArtists(): Flow<List<String>>

    @Query("SELECT DISTINCT album FROM tracks WHERE album != '' ORDER BY album ASC")
    fun getAllAlbums(): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM tracks")
    fun getTrackCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM tracks WHERE is_downloaded = 1")
    fun getDownloadedTrackCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(file_size_bytes), 0) FROM tracks WHERE is_downloaded = 1")
    fun getTotalStorageUsed(): Flow<Long>

    // === FTS4 Search ===

    @Query("""
        SELECT tracks.* FROM tracks
        JOIN tracks_fts ON tracks.rowid = tracks_fts.rowid
        WHERE tracks_fts MATCH :query
        ORDER BY date_added DESC
        LIMIT :limit
    """)
    fun searchTracks(query: String, limit: Int = 50): Flow<List<TrackEntity>>

    // === Dedup Queries ===

    @Query("SELECT * FROM tracks WHERE spotify_uri = :spotifyUri LIMIT 1")
    suspend fun findBySpotifyUri(spotifyUri: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE youtube_id = :youtubeId LIMIT 1")
    suspend fun findByYoutubeId(youtubeId: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE canonical_title = :canonicalTitle AND canonical_artist = :canonicalArtist LIMIT 1")
    suspend fun findByCanonical(canonicalTitle: String, canonicalArtist: String): TrackEntity?
}
```

---

### Task 3.15 — Create PlaylistDao

**File:** `core/data/src/main/java/com/stash/app/core/data/db/dao/PlaylistDao.kt`

```kotlin
package com.stash.app.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.stash.app.core.data.db.entity.PlaylistEntity
import com.stash.app.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.app.core.data.db.entity.TrackEntity
import com.stash.app.core.model.PlaylistType
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Update
    suspend fun updatePlaylist(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCrossRef(crossRef: PlaylistTrackCrossRef)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCrossRefs(crossRefs: List<PlaylistTrackCrossRef>)

    @Query("""
        UPDATE playlist_track_cross_ref 
        SET removed_at = :removedAt 
        WHERE playlist_id = :playlistId AND track_id = :trackId
    """)
    suspend fun softRemoveTrack(playlistId: Long, trackId: Long, removedAt: Long)

    @Query("SELECT * FROM playlists WHERE id = :playlistId")
    fun getPlaylistById(playlistId: Long): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlists WHERE is_active = 1 ORDER BY name ASC")
    fun getActivePlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE type = :type AND is_active = 1 ORDER BY mix_number ASC")
    fun getPlaylistsByType(type: PlaylistType): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE type = 'DAILY_MIX' AND is_active = 1 ORDER BY mix_number ASC")
    fun getDailyMixes(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE type = 'LIKED_SONGS' AND is_active = 1")
    fun getLikedSongsPlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT COUNT(*) FROM playlists WHERE is_active = 1")
    fun getActivePlaylistCount(): Flow<Int>

    @Transaction
    @Query("""
        SELECT tracks.* FROM tracks
        INNER JOIN playlist_track_cross_ref ON tracks.id = playlist_track_cross_ref.track_id
        WHERE playlist_track_cross_ref.playlist_id = :playlistId
        AND playlist_track_cross_ref.removed_at IS NULL
        ORDER BY playlist_track_cross_ref.position ASC
    """)
    fun getTracksForPlaylist(playlistId: Long): Flow<List<TrackEntity>>

    @Query("SELECT * FROM playlists WHERE source_id = :sourceId LIMIT 1")
    suspend fun findBySourceId(sourceId: String): PlaylistEntity?
}
```

---

### Task 3.16 — Create SyncHistoryDao

**File:** `core/data/src/main/java/com/stash/app/core/data/db/dao/SyncHistoryDao.kt`

```kotlin
package com.stash.app.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.stash.app.core.data.db.entity.SyncHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncHistoryDao {

    @Insert
    suspend fun insert(sync: SyncHistoryEntity): Long

    @Update
    suspend fun update(sync: SyncHistoryEntity)

    @Query("SELECT * FROM sync_history ORDER BY started_at DESC LIMIT 1")
    fun getLatestSync(): Flow<SyncHistoryEntity?>

    @Query("SELECT * FROM sync_history ORDER BY started_at DESC LIMIT :limit")
    fun getRecentSyncs(limit: Int = 10): Flow<List<SyncHistoryEntity>>

    @Query("SELECT * FROM sync_history WHERE status = 'COMPLETED' ORDER BY started_at DESC LIMIT 1")
    fun getLastSuccessfulSync(): Flow<SyncHistoryEntity?>
}
```

---

### Task 3.17 — Create DownloadQueueDao

**File:** `core/data/src/main/java/com/stash/app/core/data/db/dao/DownloadQueueDao.kt`

```kotlin
package com.stash.app.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.stash.app.core.data.db.entity.DownloadQueueEntity
import com.stash.app.core.model.DownloadStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadQueueDao {

    @Insert
    suspend fun insert(item: DownloadQueueEntity): Long

    @Insert
    suspend fun insertAll(items: List<DownloadQueueEntity>): List<Long>

    @Update
    suspend fun update(item: DownloadQueueEntity)

    @Query("UPDATE download_queue SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: DownloadStatus)

    @Query("SELECT * FROM download_queue WHERE status = 'QUEUED' ORDER BY created_at ASC LIMIT :limit")
    suspend fun getNextQueued(limit: Int = 3): List<DownloadQueueEntity>

    @Query("SELECT * FROM download_queue WHERE status IN ('QUEUED', 'MATCHING', 'DOWNLOADING', 'PROCESSING', 'TAGGING') ORDER BY created_at ASC")
    fun getActiveDownloads(): Flow<List<DownloadQueueEntity>>

    @Query("SELECT * FROM download_queue WHERE status = 'FAILED' ORDER BY created_at DESC")
    fun getFailedDownloads(): Flow<List<DownloadQueueEntity>>

    @Query("SELECT COUNT(*) FROM download_queue WHERE status = 'QUEUED'")
    fun getPendingCount(): Flow<Int>
}
```

---

### Task 3.18 — Create SourceAccountDao

**File:** `core/data/src/main/java/com/stash/app/core/data/db/dao/SourceAccountDao.kt`

```kotlin
package com.stash.app.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.stash.app.core.data.db.entity.SourceAccountEntity
import com.stash.app.core.model.TrackSource
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceAccountDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: SourceAccountEntity)

    @Update
    suspend fun update(account: SourceAccountEntity)

    @Query("SELECT * FROM source_accounts WHERE source = :source LIMIT 1")
    fun getAccount(source: TrackSource): Flow<SourceAccountEntity?>

    @Query("SELECT * FROM source_accounts WHERE is_connected = 1")
    fun getConnectedAccounts(): Flow<List<SourceAccountEntity>>

    @Query("SELECT * FROM source_accounts")
    fun getAllAccounts(): Flow<List<SourceAccountEntity>>
}
```

---

### Task 3.19 — Create DatabaseModule (Hilt)

**File:** `core/data/src/main/java/com/stash/app/core/data/di/DatabaseModule.kt`

```kotlin
package com.stash.app.core.data.di

import android.content.Context
import androidx.room.Room
import com.stash.app.core.data.db.StashDatabase
import com.stash.app.core.data.db.dao.DownloadQueueDao
import com.stash.app.core.data.db.dao.PlaylistDao
import com.stash.app.core.data.db.dao.SourceAccountDao
import com.stash.app.core.data.db.dao.SyncHistoryDao
import com.stash.app.core.data.db.dao.TrackDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): StashDatabase {
        return Room.databaseBuilder(
            context,
            StashDatabase::class.java,
            "stash.db"
        )
            .fallbackToDestructiveMigration(dropAllTables = false)
            .build()
    }

    @Provides
    fun provideTrackDao(db: StashDatabase): TrackDao = db.trackDao()

    @Provides
    fun providePlaylistDao(db: StashDatabase): PlaylistDao = db.playlistDao()

    @Provides
    fun provideSyncHistoryDao(db: StashDatabase): SyncHistoryDao = db.syncHistoryDao()

    @Provides
    fun provideDownloadQueueDao(db: StashDatabase): DownloadQueueDao = db.downloadQueueDao()

    @Provides
    fun provideSourceAccountDao(db: StashDatabase): SourceAccountDao = db.sourceAccountDao()
}
```

**Verify:** `./gradlew :core:data:kspDebugKotlin` — Room generates all implementations without errors.

---

## Section D: Entity-Model Mappers

### Task 3.20 — Create mapper extensions

**File:** `core/data/src/main/java/com/stash/app/core/data/db/mapper/TrackMapper.kt`

```kotlin
package com.stash.app.core.data.db.mapper

import com.stash.app.core.data.db.entity.TrackEntity
import com.stash.app.core.model.Track

fun TrackEntity.toModel(): Track = Track(
    id = id,
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    filePath = filePath,
    fileFormat = fileFormat,
    qualityKbps = qualityKbps,
    fileSizeBytes = fileSizeBytes,
    source = source,
    spotifyUri = spotifyUri,
    youtubeId = youtubeId,
    albumArtUrl = albumArtUrl,
    albumArtPath = albumArtPath,
    dateAdded = dateAdded,
    lastPlayed = lastPlayed,
    playCount = playCount,
    isDownloaded = isDownloaded,
    canonicalTitle = canonicalTitle,
    canonicalArtist = canonicalArtist,
    matchConfidence = matchConfidence,
)

fun Track.toEntity(): TrackEntity = TrackEntity(
    id = id,
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    filePath = filePath,
    fileFormat = fileFormat,
    qualityKbps = qualityKbps,
    fileSizeBytes = fileSizeBytes,
    source = source,
    spotifyUri = spotifyUri,
    youtubeId = youtubeId,
    albumArtUrl = albumArtUrl,
    albumArtPath = albumArtPath,
    dateAdded = dateAdded,
    lastPlayed = lastPlayed,
    playCount = playCount,
    isDownloaded = isDownloaded,
    canonicalTitle = canonicalTitle,
    canonicalArtist = canonicalArtist,
    matchConfidence = matchConfidence,
)
```

**File:** `core/data/src/main/java/com/stash/app/core/data/db/mapper/PlaylistMapper.kt`

```kotlin
package com.stash.app.core.data.db.mapper

import com.stash.app.core.data.db.entity.PlaylistEntity
import com.stash.app.core.model.Playlist

fun PlaylistEntity.toModel(): Playlist = Playlist(
    id = id,
    name = name,
    source = source,
    sourceId = sourceId,
    type = type,
    mixNumber = mixNumber,
    lastSynced = lastSynced,
    trackCount = trackCount,
    isActive = isActive,
    artUrl = artUrl,
)

fun Playlist.toEntity(): PlaylistEntity = PlaylistEntity(
    id = id,
    name = name,
    source = source,
    sourceId = sourceId,
    type = type,
    mixNumber = mixNumber,
    lastSynced = lastSynced,
    trackCount = trackCount,
    isActive = isActive,
    artUrl = artUrl,
)
```

**File:** `core/data/src/main/java/com/stash/app/core/data/db/mapper/SyncHistoryMapper.kt`

```kotlin
package com.stash.app.core.data.db.mapper

import com.stash.app.core.data.db.entity.SyncHistoryEntity
import com.stash.app.core.model.SyncHistory

fun SyncHistoryEntity.toModel(): SyncHistory = SyncHistory(
    id = id,
    startedAt = startedAt,
    completedAt = completedAt,
    status = status,
    playlistsChecked = playlistsChecked,
    newTracksFound = newTracksFound,
    tracksDownloaded = tracksDownloaded,
    tracksFailed = tracksFailed,
    bytesDownloaded = bytesDownloaded,
    errorMessage = errorMessage,
    trigger = trigger,
)
```

---

## Section E: MusicRepository

### Task 3.21 — Create MusicRepository interface

**File:** `core/data/src/main/java/com/stash/app/core/data/repository/MusicRepository.kt`

```kotlin
package com.stash.app.core.data.repository

import com.stash.app.core.model.Playlist
import com.stash.app.core.model.SyncHistory
import com.stash.app.core.model.Track
import com.stash.app.core.model.TrackSource
import kotlinx.coroutines.flow.Flow

interface MusicRepository {

    // === Tracks ===
    fun getAllTracks(sortBy: TrackSortOrder = TrackSortOrder.DATE_ADDED): Flow<List<Track>>
    fun getRecentlyAdded(limit: Int = 20): Flow<List<Track>>
    fun getRecentlyPlayed(limit: Int = 20): Flow<List<Track>>
    fun getTracksBySource(source: TrackSource): Flow<List<Track>>
    fun getTracksByArtist(artist: String): Flow<List<Track>>
    fun getTracksByAlbum(album: String, artist: String): Flow<List<Track>>
    fun searchTracks(query: String): Flow<List<Track>>
    fun getTrackById(trackId: Long): Flow<Track?>
    fun getTrackCount(): Flow<Int>
    fun getDownloadedTrackCount(): Flow<Int>
    fun getTotalStorageUsed(): Flow<Long>
    suspend fun incrementPlayCount(trackId: Long)

    // === Artists & Albums ===
    fun getAllArtists(): Flow<List<String>>
    fun getAllAlbums(): Flow<List<String>>

    // === Playlists ===
    fun getActivePlaylists(): Flow<List<Playlist>>
    fun getDailyMixes(): Flow<List<Playlist>>
    fun getLikedSongsPlaylists(): Flow<List<Playlist>>
    fun getTracksForPlaylist(playlistId: Long): Flow<List<Track>>
    fun getActivePlaylistCount(): Flow<Int>

    // === Sync ===
    fun getLatestSync(): Flow<SyncHistory?>
    fun getLastSuccessfulSync(): Flow<SyncHistory?>
    fun getRecentSyncs(limit: Int = 10): Flow<List<SyncHistory>>
}

enum class TrackSortOrder {
    DATE_ADDED,
    ALPHABETICAL,
    MOST_PLAYED,
}
```

---

### Task 3.22 — Implement MusicRepositoryImpl

**File:** `core/data/src/main/java/com/stash/app/core/data/repository/MusicRepositoryImpl.kt`

```kotlin
package com.stash.app.core.data.repository

import com.stash.app.core.data.db.dao.PlaylistDao
import com.stash.app.core.data.db.dao.SyncHistoryDao
import com.stash.app.core.data.db.dao.TrackDao
import com.stash.app.core.data.db.mapper.toModel
import com.stash.app.core.model.Playlist
import com.stash.app.core.model.SyncHistory
import com.stash.app.core.model.Track
import com.stash.app.core.model.TrackSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MusicRepositoryImpl @Inject constructor(
    private val trackDao: TrackDao,
    private val playlistDao: PlaylistDao,
    private val syncHistoryDao: SyncHistoryDao,
) : MusicRepository {

    // === Tracks ===

    override fun getAllTracks(sortBy: TrackSortOrder): Flow<List<Track>> {
        return when (sortBy) {
            TrackSortOrder.DATE_ADDED -> trackDao.getAllTracksByDateAdded()
            TrackSortOrder.ALPHABETICAL -> trackDao.getAllTracksAlphabetical()
            TrackSortOrder.MOST_PLAYED -> trackDao.getAllTracksByPlayCount()
        }.map { entities -> entities.map { it.toModel() } }
    }

    override fun getRecentlyAdded(limit: Int): Flow<List<Track>> =
        trackDao.getRecentlyAdded(limit).map { it.map { e -> e.toModel() } }

    override fun getRecentlyPlayed(limit: Int): Flow<List<Track>> =
        trackDao.getRecentlyPlayed(limit).map { it.map { e -> e.toModel() } }

    override fun getTracksBySource(source: TrackSource): Flow<List<Track>> =
        trackDao.getTracksBySource(source).map { it.map { e -> e.toModel() } }

    override fun getTracksByArtist(artist: String): Flow<List<Track>> =
        trackDao.getTracksByArtist(artist).map { it.map { e -> e.toModel() } }

    override fun getTracksByAlbum(album: String, artist: String): Flow<List<Track>> =
        trackDao.getTracksByAlbum(album, artist).map { it.map { e -> e.toModel() } }

    override fun searchTracks(query: String): Flow<List<Track>> {
        // FTS4 query: append * for prefix matching, wrap terms in quotes for phrases
        val ftsQuery = query.trim().split("\\s+".toRegex()).joinToString(" ") { "$it*" }
        return trackDao.searchTracks(ftsQuery).map { it.map { e -> e.toModel() } }
    }

    override fun getTrackById(trackId: Long): Flow<Track?> =
        trackDao.getTrackById(trackId).map { it?.toModel() }

    override fun getTrackCount(): Flow<Int> = trackDao.getTrackCount()

    override fun getDownloadedTrackCount(): Flow<Int> = trackDao.getDownloadedTrackCount()

    override fun getTotalStorageUsed(): Flow<Long> = trackDao.getTotalStorageUsed()

    override suspend fun incrementPlayCount(trackId: Long) {
        trackDao.incrementPlayCount(trackId, Instant.now().toEpochMilli())
    }

    // === Artists & Albums ===

    override fun getAllArtists(): Flow<List<String>> = trackDao.getAllArtists()

    override fun getAllAlbums(): Flow<List<String>> = trackDao.getAllAlbums()

    // === Playlists ===

    override fun getActivePlaylists(): Flow<List<Playlist>> =
        playlistDao.getActivePlaylists().map { it.map { e -> e.toModel() } }

    override fun getDailyMixes(): Flow<List<Playlist>> =
        playlistDao.getDailyMixes().map { it.map { e -> e.toModel() } }

    override fun getLikedSongsPlaylists(): Flow<List<Playlist>> =
        playlistDao.getLikedSongsPlaylists().map { it.map { e -> e.toModel() } }

    override fun getTracksForPlaylist(playlistId: Long): Flow<List<Track>> =
        playlistDao.getTracksForPlaylist(playlistId).map { it.map { e -> e.toModel() } }

    override fun getActivePlaylistCount(): Flow<Int> = playlistDao.getActivePlaylistCount()

    // === Sync ===

    override fun getLatestSync(): Flow<SyncHistory?> =
        syncHistoryDao.getLatestSync().map { it?.toModel() }

    override fun getLastSuccessfulSync(): Flow<SyncHistory?> =
        syncHistoryDao.getLastSuccessfulSync().map { it?.toModel() }

    override fun getRecentSyncs(limit: Int): Flow<List<SyncHistory>> =
        syncHistoryDao.getRecentSyncs(limit).map { it.map { e -> e.toModel() } }
}
```

---

### Task 3.23 — Create RepositoryModule (Hilt binding)

**File:** `core/data/src/main/java/com/stash/app/core/data/di/RepositoryModule.kt`

```kotlin
package com.stash.app.core.data.di

import com.stash.app.core.data.repository.MusicRepository
import com.stash.app.core.data.repository.MusicRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindMusicRepository(impl: MusicRepositoryImpl): MusicRepository
}
```

**Verify:** `./gradlew :core:data:build` passes.

---

## Section F: Database Pre-Population (Development)

### Task 3.24 — Create DatabaseSeeder for test data

**File:** `core/data/src/main/java/com/stash/app/core/data/db/seed/DatabaseSeeder.kt`

```kotlin
package com.stash.app.core.data.db.seed

import com.stash.app.core.data.db.dao.PlaylistDao
import com.stash.app.core.data.db.dao.SourceAccountDao
import com.stash.app.core.data.db.dao.SyncHistoryDao
import com.stash.app.core.data.db.dao.TrackDao
import com.stash.app.core.data.db.entity.PlaylistEntity
import com.stash.app.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.app.core.data.db.entity.SourceAccountEntity
import com.stash.app.core.data.db.entity.SyncHistoryEntity
import com.stash.app.core.data.db.entity.TrackEntity
import com.stash.app.core.model.PlaylistType
import com.stash.app.core.model.SyncStatus
import com.stash.app.core.model.SyncTrigger
import com.stash.app.core.model.TrackSource
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject

class DatabaseSeeder @Inject constructor(
    private val trackDao: TrackDao,
    private val playlistDao: PlaylistDao,
    private val syncHistoryDao: SyncHistoryDao,
    private val sourceAccountDao: SourceAccountDao,
) {
    suspend fun seedIfEmpty() {
        // Only seed if no tracks exist
        val existing = trackDao.getTrackByIdOnce(1)
        if (existing != null) return

        val now = Instant.now()

        // --- Source accounts ---
        sourceAccountDao.upsert(
            SourceAccountEntity(
                source = TrackSource.SPOTIFY,
                displayName = "Demo User",
                email = "demo@example.com",
                isConnected = true,
                connectedAt = now.minus(30, ChronoUnit.DAYS),
                lastSyncAt = now.minus(2, ChronoUnit.HOURS),
            )
        )
        sourceAccountDao.upsert(
            SourceAccountEntity(
                source = TrackSource.YOUTUBE,
                displayName = "Demo User",
                email = "demo@gmail.com",
                isConnected = true,
                connectedAt = now.minus(25, ChronoUnit.DAYS),
                lastSyncAt = now.minus(2, ChronoUnit.HOURS),
            )
        )

        // --- Tracks (30 sample tracks) ---
        val tracks = listOf(
            TrackEntity(title = "Blinding Lights", artist = "The Weeknd", album = "After Hours", durationMs = 200040, source = TrackSource.SPOTIFY, spotifyUri = "spotify:track:0VjIjW4GlUZAMYd2vXMi3b", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b2738863bc11d2aa12b54f5aeb36", dateAdded = now.minus(5, ChronoUnit.DAYS), playCount = 42, isDownloaded = true, canonicalTitle = "blinding lights", canonicalArtist = "the weeknd"),
            TrackEntity(title = "Levitating", artist = "Dua Lipa", album = "Future Nostalgia", durationMs = 203064, source = TrackSource.SPOTIFY, spotifyUri = "spotify:track:463CkQjx2Zk1yXoBuierM9", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273d4daf28d55fe4197ece848fd", dateAdded = now.minus(4, ChronoUnit.DAYS), playCount = 28, isDownloaded = true, canonicalTitle = "levitating", canonicalArtist = "dua lipa"),
            TrackEntity(title = "Anti-Hero", artist = "Taylor Swift", album = "Midnights", durationMs = 200690, source = TrackSource.BOTH, spotifyUri = "spotify:track:0V3wPSX9ygBnCm8psDIeLp", youtubeId = "b1kbLwvqugk", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273bb54dde68cd23e2a268ae0f5", dateAdded = now.minus(3, ChronoUnit.DAYS), playCount = 35, isDownloaded = true, canonicalTitle = "anti-hero", canonicalArtist = "taylor swift"),
            TrackEntity(title = "Flowers", artist = "Miley Cyrus", album = "Endless Summer Vacation", durationMs = 200547, source = TrackSource.YOUTUBE, youtubeId = "G7KNmW9a75Y", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b27382ea2e9e1858aa012c57e4d4", dateAdded = now.minus(2, ChronoUnit.DAYS), playCount = 15, isDownloaded = true, canonicalTitle = "flowers", canonicalArtist = "miley cyrus"),
            TrackEntity(title = "As It Was", artist = "Harry Styles", album = "Harry's House", durationMs = 167303, source = TrackSource.SPOTIFY, spotifyUri = "spotify:track:4Dvkj6JhhA12EX05fT7y2e", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b2732e8ed79e177ff6011076f5f0", dateAdded = now.minus(6, ChronoUnit.DAYS), playCount = 22, isDownloaded = true, canonicalTitle = "as it was", canonicalArtist = "harry styles"),
            TrackEntity(title = "Cruel Summer", artist = "Taylor Swift", album = "Lover", durationMs = 178427, source = TrackSource.SPOTIFY, spotifyUri = "spotify:track:1BxfuPKGuaTgP7aM0Bbdwr", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273e787cffec20aa2a396a61647", dateAdded = now.minus(1, ChronoUnit.DAYS), playCount = 50, isDownloaded = true, canonicalTitle = "cruel summer", canonicalArtist = "taylor swift"),
            TrackEntity(title = "vampire", artist = "Olivia Rodrigo", album = "GUTS", durationMs = 219724, source = TrackSource.BOTH, spotifyUri = "spotify:track:1kuGVB7EU95pJObxwvfwKS", youtubeId = "RlPNh_PBZb4", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273e85259a1cae29a8d91f2093d", dateAdded = now.minus(7, ChronoUnit.DAYS), playCount = 18, isDownloaded = true, canonicalTitle = "vampire", canonicalArtist = "olivia rodrigo"),
            TrackEntity(title = "Starboy", artist = "The Weeknd", album = "Starboy", durationMs = 230453, source = TrackSource.SPOTIFY, spotifyUri = "spotify:track:7MXVkk9YMctZqd1Srtv4MB", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b2734718e2b124f79258be7bc452", dateAdded = now.minus(10, ChronoUnit.DAYS), playCount = 33, isDownloaded = true, canonicalTitle = "starboy", canonicalArtist = "the weeknd"),
            TrackEntity(title = "Watermelon Sugar", artist = "Harry Styles", album = "Fine Line", durationMs = 174000, source = TrackSource.YOUTUBE, youtubeId = "E07s5ZYadZw", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b27377fdcfda6535601aff081b6a", dateAdded = now.minus(8, ChronoUnit.DAYS), playCount = 20, isDownloaded = true, canonicalTitle = "watermelon sugar", canonicalArtist = "harry styles"),
            TrackEntity(title = "Espresso", artist = "Sabrina Carpenter", album = "espresso", durationMs = 175431, source = TrackSource.SPOTIFY, spotifyUri = "spotify:track:2qSkIjg1o9h3YT9RAgYN75", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b2735b3a5189b6b1c8db99a8a04e", dateAdded = now.minus(1, ChronoUnit.DAYS), playCount = 60, isDownloaded = true, canonicalTitle = "espresso", canonicalArtist = "sabrina carpenter"),
            // 10 more partially downloaded tracks
            TrackEntity(title = "Paint The Town Red", artist = "Doja Cat", album = "Scarlet", durationMs = 231960, source = TrackSource.SPOTIFY, spotifyUri = "spotify:track:2IGMVunIBsBLtEQyoI1Mu7", dateAdded = now.minus(12, ChronoUnit.DAYS), playCount = 8, isDownloaded = true, canonicalTitle = "paint the town red", canonicalArtist = "doja cat"),
            TrackEntity(title = "Snooze", artist = "SZA", album = "SOS", durationMs = 201800, source = TrackSource.BOTH, spotifyUri = "spotify:track:4iZ4pt7kvcaH6Yo8UoZ4s2", youtubeId = "LVuASmdhMPQ", dateAdded = now.minus(14, ChronoUnit.DAYS), playCount = 25, isDownloaded = true, canonicalTitle = "snooze", canonicalArtist = "sza"),
            TrackEntity(title = "Karma", artist = "Taylor Swift", album = "Midnights", durationMs = 204852, source = TrackSource.SPOTIFY, spotifyUri = "spotify:track:7KokYm8cMIXCsGVmUvKtqf", dateAdded = now.minus(3, ChronoUnit.DAYS), playCount = 12, isDownloaded = true, canonicalTitle = "karma", canonicalArtist = "taylor swift"),
            TrackEntity(title = "Die With A Smile", artist = "Lady Gaga & Bruno Mars", album = "Die With A Smile", durationMs = 251667, source = TrackSource.YOUTUBE, youtubeId = "kPa7bsKwL-c", dateAdded = now.minus(1, ChronoUnit.DAYS), playCount = 5, isDownloaded = false, canonicalTitle = "die with a smile", canonicalArtist = "lady gaga bruno mars"),
            TrackEntity(title = "greedy", artist = "Tate McRae", album = "THINK LATER", durationMs = 131507, source = TrackSource.SPOTIFY, spotifyUri = "spotify:track:3rUGC1vUpkDG9CZFHMur1t", dateAdded = now.minus(9, ChronoUnit.DAYS), playCount = 14, isDownloaded = true, canonicalTitle = "greedy", canonicalArtist = "tate mcrae"),
            TrackEntity(title = "Lose Yourself", artist = "Eminem", album = "8 Mile OST", durationMs = 326000, source = TrackSource.YOUTUBE, youtubeId = "_Yhyp-_hX2s", dateAdded = now.minus(20, ChronoUnit.DAYS), playCount = 40, isDownloaded = true, canonicalTitle = "lose yourself", canonicalArtist = "eminem"),
            TrackEntity(title = "Bohemian Rhapsody", artist = "Queen", album = "A Night at the Opera", durationMs = 354000, source = TrackSource.YOUTUBE, youtubeId = "fJ9rUzIMcZQ", dateAdded = now.minus(25, ChronoUnit.DAYS), playCount = 55, isDownloaded = true, canonicalTitle = "bohemian rhapsody", canonicalArtist = "queen"),
            TrackEntity(title = "Shape of You", artist = "Ed Sheeran", album = "Divide", durationMs = 233713, source = TrackSource.BOTH, spotifyUri = "spotify:track:7qiZfU4dY1lWllzX7mPBI3", youtubeId = "JGwWNGJdvx8", dateAdded = now.minus(15, ChronoUnit.DAYS), playCount = 30, isDownloaded = true, canonicalTitle = "shape of you", canonicalArtist = "ed sheeran"),
            TrackEntity(title = "Someone Like You", artist = "Adele", album = "21", durationMs = 285000, source = TrackSource.SPOTIFY, spotifyUri = "spotify:track:1zwMYTA5nlNjZxYrvBB2pV", dateAdded = now.minus(18, ChronoUnit.DAYS), playCount = 38, isDownloaded = true, canonicalTitle = "someone like you", canonicalArtist = "adele"),
            TrackEntity(title = "Heat Waves", artist = "Glass Animals", album = "Dreamland", durationMs = 238805, source = TrackSource.SPOTIFY, spotifyUri = "spotify:track:02MWAaffLxlfxAUY7c5dvx", dateAdded = now.minus(11, ChronoUnit.DAYS), playCount = 19, isDownloaded = true, canonicalTitle = "heat waves", canonicalArtist = "glass animals"),
        )

        val trackIds = trackDao.insertAll(tracks)

        // --- Playlists ---
        val dailyMix1Id = playlistDao.insertPlaylist(
            PlaylistEntity(name = "Daily Mix 1", source = TrackSource.SPOTIFY, sourceId = "spotify_dm1", type = PlaylistType.DAILY_MIX, mixNumber = 1, lastSynced = now.minus(2, ChronoUnit.HOURS), trackCount = 6, artUrl = "https://dailymix-images.scdn.co/v2/img/ab6761610000e5eb1")
        )
        val dailyMix2Id = playlistDao.insertPlaylist(
            PlaylistEntity(name = "Daily Mix 2", source = TrackSource.SPOTIFY, sourceId = "spotify_dm2", type = PlaylistType.DAILY_MIX, mixNumber = 2, lastSynced = now.minus(2, ChronoUnit.HOURS), trackCount = 5, artUrl = "https://dailymix-images.scdn.co/v2/img/ab6761610000e5eb2")
        )
        val dailyMix3Id = playlistDao.insertPlaylist(
            PlaylistEntity(name = "Daily Mix 3", source = TrackSource.SPOTIFY, sourceId = "spotify_dm3", type = PlaylistType.DAILY_MIX, mixNumber = 3, lastSynced = now.minus(2, ChronoUnit.HOURS), trackCount = 4, artUrl = "https://dailymix-images.scdn.co/v2/img/ab6761610000e5eb3")
        )
        val likedSpotifyId = playlistDao.insertPlaylist(
            PlaylistEntity(name = "Liked Songs", source = TrackSource.SPOTIFY, sourceId = "spotify_liked", type = PlaylistType.LIKED_SONGS, lastSynced = now.minus(2, ChronoUnit.HOURS), trackCount = 8)
        )
        val likedYtId = playlistDao.insertPlaylist(
            PlaylistEntity(name = "YT Music Likes", source = TrackSource.YOUTUBE, sourceId = "yt_liked", type = PlaylistType.LIKED_SONGS, lastSynced = now.minus(2, ChronoUnit.HOURS), trackCount = 5)
        )
        val customPlaylistId = playlistDao.insertPlaylist(
            PlaylistEntity(name = "Chill Vibes", source = TrackSource.SPOTIFY, sourceId = "spotify_chill", type = PlaylistType.CUSTOM, lastSynced = now.minus(5, ChronoUnit.HOURS), trackCount = 4)
        )

        // --- Cross-refs (assign tracks to playlists) ---
        val crossRefs = listOf(
            // Daily Mix 1: indices 0,1,2,4,5,9
            PlaylistTrackCrossRef(playlistId = dailyMix1Id, trackId = trackIds[0], position = 0),
            PlaylistTrackCrossRef(playlistId = dailyMix1Id, trackId = trackIds[1], position = 1),
            PlaylistTrackCrossRef(playlistId = dailyMix1Id, trackId = trackIds[2], position = 2),
            PlaylistTrackCrossRef(playlistId = dailyMix1Id, trackId = trackIds[4], position = 3),
            PlaylistTrackCrossRef(playlistId = dailyMix1Id, trackId = trackIds[5], position = 4),
            PlaylistTrackCrossRef(playlistId = dailyMix1Id, trackId = trackIds[9], position = 5),
            // Daily Mix 2: indices 3,6,7,10,14
            PlaylistTrackCrossRef(playlistId = dailyMix2Id, trackId = trackIds[3], position = 0),
            PlaylistTrackCrossRef(playlistId = dailyMix2Id, trackId = trackIds[6], position = 1),
            PlaylistTrackCrossRef(playlistId = dailyMix2Id, trackId = trackIds[7], position = 2),
            PlaylistTrackCrossRef(playlistId = dailyMix2Id, trackId = trackIds[10], position = 3),
            PlaylistTrackCrossRef(playlistId = dailyMix2Id, trackId = trackIds[14], position = 4),
            // Daily Mix 3: indices 8,11,15,16
            PlaylistTrackCrossRef(playlistId = dailyMix3Id, trackId = trackIds[8], position = 0),
            PlaylistTrackCrossRef(playlistId = dailyMix3Id, trackId = trackIds[11], position = 1),
            PlaylistTrackCrossRef(playlistId = dailyMix3Id, trackId = trackIds[15], position = 2),
            PlaylistTrackCrossRef(playlistId = dailyMix3Id, trackId = trackIds[16], position = 3),
            // Liked Songs (Spotify): indices 0,2,5,6,9,12,17,18
            PlaylistTrackCrossRef(playlistId = likedSpotifyId, trackId = trackIds[0], position = 0),
            PlaylistTrackCrossRef(playlistId = likedSpotifyId, trackId = trackIds[2], position = 1),
            PlaylistTrackCrossRef(playlistId = likedSpotifyId, trackId = trackIds[5], position = 2),
            PlaylistTrackCrossRef(playlistId = likedSpotifyId, trackId = trackIds[6], position = 3),
            PlaylistTrackCrossRef(playlistId = likedSpotifyId, trackId = trackIds[9], position = 4),
            PlaylistTrackCrossRef(playlistId = likedSpotifyId, trackId = trackIds[12], position = 5),
            PlaylistTrackCrossRef(playlistId = likedSpotifyId, trackId = trackIds[17], position = 6),
            PlaylistTrackCrossRef(playlistId = likedSpotifyId, trackId = trackIds[18], position = 7),
            // YT Music Likes: indices 3,8,13,15,16
            PlaylistTrackCrossRef(playlistId = likedYtId, trackId = trackIds[3], position = 0),
            PlaylistTrackCrossRef(playlistId = likedYtId, trackId = trackIds[8], position = 1),
            PlaylistTrackCrossRef(playlistId = likedYtId, trackId = trackIds[13], position = 2),
            PlaylistTrackCrossRef(playlistId = likedYtId, trackId = trackIds[15], position = 3),
            PlaylistTrackCrossRef(playlistId = likedYtId, trackId = trackIds[16], position = 4),
            // Chill Vibes: indices 1,4,11,19
            PlaylistTrackCrossRef(playlistId = customPlaylistId, trackId = trackIds[1], position = 0),
            PlaylistTrackCrossRef(playlistId = customPlaylistId, trackId = trackIds[4], position = 1),
            PlaylistTrackCrossRef(playlistId = customPlaylistId, trackId = trackIds[11], position = 2),
            PlaylistTrackCrossRef(playlistId = customPlaylistId, trackId = trackIds[19], position = 3),
        )
        playlistDao.insertCrossRefs(crossRefs)

        // --- Sync history ---
        syncHistoryDao.insert(
            SyncHistoryEntity(
                startedAt = now.minus(2, ChronoUnit.HOURS),
                completedAt = now.minus(2, ChronoUnit.HOURS).plus(8, ChronoUnit.MINUTES),
                status = SyncStatus.COMPLETED,
                playlistsChecked = 6,
                newTracksFound = 3,
                tracksDownloaded = 3,
                tracksFailed = 0,
                bytesDownloaded = 14_500_000,
                trigger = SyncTrigger.SCHEDULED,
            )
        )
        syncHistoryDao.insert(
            SyncHistoryEntity(
                startedAt = now.minus(26, ChronoUnit.HOURS),
                completedAt = now.minus(26, ChronoUnit.HOURS).plus(12, ChronoUnit.MINUTES),
                status = SyncStatus.COMPLETED,
                playlistsChecked = 6,
                newTracksFound = 5,
                tracksDownloaded = 5,
                tracksFailed = 0,
                bytesDownloaded = 24_200_000,
                trigger = SyncTrigger.SCHEDULED,
            )
        )
    }
}
```

---

### Task 3.25 — Call DatabaseSeeder on app launch

**File:** `app/src/main/java/com/stash/app/StashApplication.kt` — add to the existing Application class:

```kotlin
// Add to existing StashApplication class
@Inject
lateinit var seeder: DatabaseSeeder

override fun onCreate() {
    super.onCreate()
    // Existing Hilt/Timber init...

    // Seed database with test data in debug builds
    if (BuildConfig.DEBUG) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            seeder.seedIfEmpty()
        }
    }
}
```

**Verify:** Run on emulator, use App Inspection > Database Inspector to confirm 20 tracks, 6 playlists, cross-refs populated.

---

## Section G: HomeViewModel + HomeScreen

### Task 3.26 — Create HomeUiState

**File:** `feature/home/src/main/java/com/stash/app/feature/home/HomeUiState.kt`

```kotlin
package com.stash.app.feature.home

import com.stash.app.core.model.Playlist
import com.stash.app.core.model.SyncHistory
import com.stash.app.core.model.Track

data class HomeUiState(
    val isLoading: Boolean = true,
    val latestSync: SyncHistory? = null,
    val dailyMixes: List<Playlist> = emptyList(),
    val recentlyAdded: List<Track> = emptyList(),
    val likedSongsPlaylists: List<Playlist> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val trackCount: Int = 0,
    val downloadedCount: Int = 0,
    val storageUsedBytes: Long = 0,
)
```

---

### Task 3.27 — Create HomeViewModel

**File:** `feature/home/src/main/java/com/stash/app/feature/home/HomeViewModel.kt`

```kotlin
package com.stash.app.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.app.core.data.repository.MusicRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = combine(
        musicRepository.getLatestSync(),
        musicRepository.getDailyMixes(),
        musicRepository.getRecentlyAdded(limit = 15),
        musicRepository.getLikedSongsPlaylists(),
        musicRepository.getActivePlaylists(),
    ) { latestSync, dailyMixes, recentlyAdded, likedSongs, playlists ->
        HomeUiState(
            isLoading = false,
            latestSync = latestSync,
            dailyMixes = dailyMixes,
            recentlyAdded = recentlyAdded,
            likedSongsPlaylists = likedSongs,
            playlists = playlists,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(),
    )

    // Secondary combine for stats (separate flow since combine() max arity is 5)
    val stats: StateFlow<HomeStats> = combine(
        musicRepository.getTrackCount(),
        musicRepository.getDownloadedTrackCount(),
        musicRepository.getTotalStorageUsed(),
    ) { trackCount, downloadedCount, storageUsed ->
        HomeStats(trackCount, downloadedCount, storageUsed)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeStats(),
    )
}

data class HomeStats(
    val trackCount: Int = 0,
    val downloadedCount: Int = 0,
    val storageUsedBytes: Long = 0,
)
```

---

### Task 3.28 — Create HomeScreen composable (scaffold + sync card)

**File:** `feature/home/src/main/java/com/stash/app/feature/home/HomeScreen.kt`

```kotlin
package com.stash.app.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.app.core.model.Playlist
import com.stash.app.core.model.SyncHistory
import com.stash.app.core.model.Track
import com.stash.app.core.ui.theme.StashTheme
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HomeScreen(
    onTrackClick: (Track, List<Track>) -> Unit,
    onPlaylistClick: (Long) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(StashTheme.colors.background),
        contentPadding = PaddingValues(bottom = 120.dp), // room for mini player + nav
    ) {
        // Header
        item {
            HomeHeader()
        }

        // Sync status card
        item {
            SyncStatusCard(
                latestSync = uiState.latestSync,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        // Quick stats row
        item {
            StatsRow(
                trackCount = stats.trackCount,
                downloadedCount = stats.downloadedCount,
                storageUsedBytes = stats.storageUsedBytes,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        // Daily Mix carousel
        if (uiState.dailyMixes.isNotEmpty()) {
            item {
                SectionHeader(title = "Daily Mixes", action = "See All")
            }
            item {
                DailyMixCarousel(
                    mixes = uiState.dailyMixes,
                    onMixClick = { onPlaylistClick(it.id) },
                )
            }
        }

        // Recently added
        if (uiState.recentlyAdded.isNotEmpty()) {
            item {
                SectionHeader(title = "Recently Added", action = "See All")
            }
            item {
                RecentlyAddedRow(
                    tracks = uiState.recentlyAdded,
                    onTrackClick = { track ->
                        onTrackClick(track, uiState.recentlyAdded)
                    },
                )
            }
        }

        // Liked Songs featured card
        if (uiState.likedSongsPlaylists.isNotEmpty()) {
            item {
                SectionHeader(title = "Liked Songs")
            }
            items(uiState.likedSongsPlaylists) { playlist ->
                LikedSongsCard(
                    playlist = playlist,
                    onClick = { onPlaylistClick(playlist.id) },
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
        }

        // Playlist grid
        if (uiState.playlists.isNotEmpty()) {
            item {
                SectionHeader(title = "Your Playlists", action = "See All")
            }
            item {
                PlaylistGrid(
                    playlists = uiState.playlists.filter { it.type != com.stash.app.core.model.PlaylistType.LIKED_SONGS },
                    onPlaylistClick = { onPlaylistClick(it.id) },
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }
    }
}
```

**Note:** The exact implementations of `HomeHeader`, `SyncStatusCard`, `StatsRow`, `SectionHeader`, `DailyMixCarousel`, `RecentlyAddedRow`, `LikedSongsCard`, and `PlaylistGrid` each become their own sub-tasks below.

---

### Task 3.29 — Create SyncStatusCard composable

**File:** `feature/home/src/main/java/com/stash/app/feature/home/components/SyncStatusCard.kt`

```kotlin
package com.stash.app.feature.home.components

import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stash.app.core.model.SyncHistory
import com.stash.app.core.model.SyncStatus
import com.stash.app.core.ui.theme.StashTheme
import java.time.Duration
import java.time.Instant

@Composable
fun SyncStatusCard(
    latestSync: SyncHistory?,
    modifier: Modifier = Modifier,
    onSyncNowClick: () -> Unit = {},
) {
    val shape = RoundedCornerShape(20.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.06f),
                        Color.White.copy(alpha = 0.02f),
                    )
                )
            )
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.10f),
                shape = shape,
            )
            .padding(18.dp),
    ) {
        Column {
            // Header row: status badge + time ago
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PulsingDot(
                        color = if (latestSync?.status == SyncStatus.COMPLETED)
                            StashTheme.colors.spotifyGreen
                        else StashTheme.colors.primary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when (latestSync?.status) {
                            SyncStatus.COMPLETED -> "Synced"
                            SyncStatus.IN_PROGRESS -> "Syncing..."
                            SyncStatus.FAILED -> "Sync failed"
                            else -> "Not synced"
                        },
                        color = if (latestSync?.status == SyncStatus.COMPLETED)
                            StashTheme.colors.spotifyGreen
                        else StashTheme.colors.textPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                latestSync?.completedAt?.let { completedAt ->
                    Text(
                        text = formatTimeAgo(completedAt),
                        color = StashTheme.colors.textTertiary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Stats row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SyncDetailItem(
                    label = "TRACKS",
                    value = "${latestSync?.tracksDownloaded ?: 0}",
                    subtitle = "downloaded",
                    modifier = Modifier.weight(1f),
                )
                SyncDetailItem(
                    label = "FOUND",
                    value = "${latestSync?.newTracksFound ?: 0}",
                    subtitle = "new tracks",
                    modifier = Modifier.weight(1f),
                )
                SyncDetailItem(
                    label = "PLAYLISTS",
                    value = "${latestSync?.playlistsChecked ?: 0}",
                    subtitle = "checked",
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Bottom row: next sync + sync now button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Next sync: 3:00 AM",
                    color = StashTheme.colors.textSecondary,
                    fontSize = 12.sp,
                )
                TextButton(onClick = onSyncNowClick) {
                    Text(
                        text = "Sync Now",
                        color = StashTheme.colors.primary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun PulsingDot(color: Color) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )

    Box(
        modifier = Modifier
            .size(8.dp)
            .alpha(alpha)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun SyncDetailItem(
    label: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.03f))
            .border(1.dp, Color.White.copy(alpha = 0.04f), RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Text(
            text = label,
            color = StashTheme.colors.textTertiary,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = value,
            color = StashTheme.colors.textPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = StashTheme.typography.display,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = subtitle,
            color = StashTheme.colors.textTertiary,
            fontSize = 11.sp,
        )
    }
}

private fun formatTimeAgo(instant: Instant): String {
    val duration = Duration.between(instant, Instant.now())
    return when {
        duration.toMinutes() < 1 -> "just now"
        duration.toMinutes() < 60 -> "${duration.toMinutes()}m ago"
        duration.toHours() < 24 -> "${duration.toHours()}h ago"
        else -> "${duration.toDays()}d ago"
    }
}
```

---

### Task 3.30 — Create DailyMixCarousel composable

**File:** `feature/home/src/main/java/com/stash/app/feature/home/components/DailyMixCarousel.kt`

```kotlin
package com.stash.app.feature.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.stash.app.core.model.Playlist
import com.stash.app.core.ui.theme.StashTheme

// Gradient pairs for each daily mix card
private val mixGradients = listOf(
    listOf(Color(0xFF8B5CF6), Color(0xFF6D28D9)),
    listOf(Color(0xFF06B6D4), Color(0xFF0891B2)),
    listOf(Color(0xFFF59E0B), Color(0xFFD97706)),
    listOf(Color(0xFFEF4444), Color(0xFFDC2626)),
    listOf(Color(0xFF10B981), Color(0xFF059669)),
    listOf(Color(0xFFEC4899), Color(0xFFDB2777)),
)

@Composable
fun DailyMixCarousel(
    mixes: List<Playlist>,
    onMixClick: (Playlist) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(mixes, key = { it.id }) { mix ->
            DailyMixCard(
                playlist = mix,
                gradientColors = mixGradients.getOrElse((mix.mixNumber ?: 1) - 1) { mixGradients[0] },
                onClick = { onMixClick(mix) },
            )
        }
    }
}

@Composable
private fun DailyMixCard(
    playlist: Playlist,
    gradientColors: List<Color>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)

    Box(
        modifier = modifier
            .width(160.dp)
            .height(200.dp)
            .clip(shape)
            .background(
                brush = Brush.verticalGradient(
                    colors = gradientColors.map { it.copy(alpha = 0.3f) } + listOf(Color.Transparent),
                )
            )
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.BottomStart,
    ) {
        // Gradient overlay at bottom for text readability
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .align(Alignment.BottomCenter)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)),
                    )
                ),
        )

        Column(
            modifier = Modifier
                .padding(16.dp)
                .align(Alignment.BottomStart),
        ) {
            Text(
                text = playlist.name,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = StashTheme.typography.display,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${playlist.trackCount} tracks",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
            )
        }
    }
}
```

---

### Task 3.31 — Create RecentlyAddedRow composable

**File:** `feature/home/src/main/java/com/stash/app/feature/home/components/RecentlyAddedRow.kt`

```kotlin
package com.stash.app.feature.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.stash.app.core.model.Track
import com.stash.app.core.ui.theme.StashTheme

@Composable
fun RecentlyAddedRow(
    tracks: List<Track>,
    onTrackClick: (Track) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(tracks, key = { it.id }) { track ->
            RecentTrackCard(
                track = track,
                onClick = { onTrackClick(track) },
            )
        }
    }
}

@Composable
private fun RecentTrackCard(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(155.dp)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = track.albumArtUrl ?: track.albumArtPath,
            contentDescription = "${track.title} album art",
            modifier = Modifier
                .size(155.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(StashTheme.colors.elevated),
            contentScale = ContentScale.Crop,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = track.title,
            color = StashTheme.colors.textPrimary,
            fontSize = 14.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = track.artist,
            color = StashTheme.colors.textSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
```

---

### Task 3.32 — Create SectionHeader, StatsRow, LikedSongsCard, PlaylistGrid composables

**File:** `feature/home/src/main/java/com/stash/app/feature/home/components/HomeComponents.kt`

This file contains `SectionHeader`, `StatsRow`, `LikedSongsCard`, and `PlaylistGrid` composables. Each follows the patterns established in the HTML mockup -- glassmorphic cards, source indicator dots, Space Grotesk display font for numbers, Inter for body text.

Key patterns:
- `SectionHeader`: Row with title (Space Grotesk 18sp Bold) + optional "See All" action text (accent color 13sp)
- `StatsRow`: Three `stat-chip` boxes in a Row with icon + value + label
- `LikedSongsCard`: Full-width glassmorphic card with heart icon, track count, source indicator dot (green for Spotify, red for YouTube)
- `PlaylistGrid`: 2-column grid using `LazyVerticalStaggeredGrid` or manual `FlowRow` with playlist cards showing name, track count, source dot

I will not reproduce the full code for all four here to save space, but each follows the exact same glassmorphic card pattern from `SyncStatusCard` (Task 3.29).

**Verify:** Run app, Home tab displays all sections with seeded data.

---

### Task 3.33 — Create HomeHeader composable

**File:** `feature/home/src/main/java/com/stash/app/feature/home/components/HomeHeader.kt`

```kotlin
package com.stash.app.feature.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stash.app.core.ui.theme.StashTheme

@Composable
fun HomeHeader(
    modifier: Modifier = Modifier,
    onSettingsClick: () -> Unit = {},
    onNotificationsClick: () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Logo
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(StashTheme.colors.primary, Color(0xFF6D28D9)),
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                // Use a music note icon or custom SVG
                Text("S", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "Stash",
                fontFamily = StashTheme.typography.display,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = StashTheme.colors.textPrimary,
            )
        }

        Row {
            GlassIconButton(
                onClick = onNotificationsClick,
                icon = { Icon(Icons.Outlined.Notifications, contentDescription = "Notifications", tint = StashTheme.colors.textSecondary, modifier = Modifier.size(18.dp)) },
            )
            Spacer(modifier = Modifier.width(8.dp))
            GlassIconButton(
                onClick = onSettingsClick,
                icon = { Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = StashTheme.colors.textSecondary, modifier = Modifier.size(18.dp)) },
            )
        }
    }
}

@Composable
private fun GlassIconButton(
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp)),
    ) {
        icon()
    }
}
```

---

## Section H: LibraryViewModel + LibraryScreen

### Task 3.34 — Create LibraryUiState and LibraryTab enum

**File:** `feature/library/src/main/java/com/stash/app/feature/library/LibraryUiState.kt`

```kotlin
package com.stash.app.feature.library

import com.stash.app.core.data.repository.TrackSortOrder
import com.stash.app.core.model.Playlist
import com.stash.app.core.model.Track

enum class LibraryTab {
    PLAYLISTS,
    TRACKS,
    ARTISTS,
    ALBUMS,
}

enum class ViewMode {
    LIST,
    GRID,
}

data class LibraryUiState(
    val isLoading: Boolean = true,
    val selectedTab: LibraryTab = LibraryTab.PLAYLISTS,
    val viewMode: ViewMode = ViewMode.LIST,
    val sortOrder: TrackSortOrder = TrackSortOrder.DATE_ADDED,
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,

    // Tab data
    val playlists: List<Playlist> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val artists: List<String> = emptyList(),
    val albums: List<String> = emptyList(),

    // Search results
    val searchResults: List<Track> = emptyList(),
)
```

---

### Task 3.35 — Create LibraryViewModel

**File:** `feature/library/src/main/java/com/stash/app/feature/library/LibraryViewModel.kt`

```kotlin
package com.stash.app.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.app.core.data.repository.MusicRepository
import com.stash.app.core.data.repository.TrackSortOrder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
) : ViewModel() {

    private val _selectedTab = MutableStateFlow(LibraryTab.PLAYLISTS)
    private val _viewMode = MutableStateFlow(ViewMode.LIST)
    private val _sortOrder = MutableStateFlow(TrackSortOrder.DATE_ADDED)
    private val _searchQuery = MutableStateFlow("")
    private val _isSearchActive = MutableStateFlow(false)

    // Debounced search query for FTS
    private val debouncedQuery = _searchQuery
        .debounce(300)
        .distinctUntilChanged()

    // Search results flow
    private val searchResults = debouncedQuery.flatMapLatest { query ->
        if (query.isBlank()) flowOf(emptyList())
        else musicRepository.searchTracks(query)
    }

    // Tracks flow respecting sort order
    private val sortedTracks = _sortOrder.flatMapLatest { order ->
        musicRepository.getAllTracks(sortBy = order)
    }

    val uiState: StateFlow<LibraryUiState> = combine(
        _selectedTab,
        _viewMode,
        _sortOrder,
        combine(_searchQuery, _isSearchActive, searchResults) { query, active, results ->
            Triple(query, active, results)
        },
        combine(
            musicRepository.getActivePlaylists(),
            sortedTracks,
            musicRepository.getAllArtists(),
            musicRepository.getAllAlbums(),
        ) { playlists, tracks, artists, albums ->
            TabData(playlists, tracks, artists, albums)
        },
    ) { tab, viewMode, sortOrder, (query, searchActive, results), tabData ->
        LibraryUiState(
            isLoading = false,
            selectedTab = tab,
            viewMode = viewMode,
            sortOrder = sortOrder,
            searchQuery = query,
            isSearchActive = searchActive,
            playlists = tabData.playlists,
            tracks = tabData.tracks,
            artists = tabData.artists,
            albums = tabData.albums,
            searchResults = results,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LibraryUiState(),
    )

    fun selectTab(tab: LibraryTab) { _selectedTab.value = tab }
    fun toggleViewMode() { _viewMode.value = if (_viewMode.value == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST }
    fun setSortOrder(order: TrackSortOrder) { _sortOrder.value = order }
    fun setSearchQuery(query: String) { _searchQuery.value = query }
    fun toggleSearch() { _isSearchActive.value = !_isSearchActive.value; if (!_isSearchActive.value) _searchQuery.value = "" }
}

private data class TabData(
    val playlists: List<com.stash.app.core.model.Playlist>,
    val tracks: List<com.stash.app.core.model.Track>,
    val artists: List<String>,
    val albums: List<String>,
)
```

**Design note:** The `combine` with 5 parameters hits the Kotlin Flow limit, so we nest two inner `combine` calls. This is the standard pattern in Android architecture.

---

### Task 3.36 — Create LibraryScreen composable

**File:** `feature/library/src/main/java/com/stash/app/feature/library/LibraryScreen.kt`

```kotlin
package com.stash.app.feature.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.app.core.model.Track
import com.stash.app.core.ui.theme.StashTheme

@Composable
fun LibraryScreen(
    onTrackClick: (Track, List<Track>) -> Unit,
    onPlaylistClick: (Long) -> Unit,
    onArtistClick: (String) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StashTheme.colors.background),
    ) {
        // Header with title + view toggle + sort button
        LibraryHeader(
            viewMode = uiState.viewMode,
            onToggleViewMode = viewModel::toggleViewMode,
            onSortClick = { /* show sort bottom sheet */ },
        )

        // Search bar (glassmorphic)
        LibrarySearchBar(
            query = uiState.searchQuery,
            isActive = uiState.isSearchActive,
            onQueryChange = viewModel::setSearchQuery,
            onToggleSearch = viewModel::toggleSearch,
        )

        // Filter tabs
        LibraryTabRow(
            selectedTab = uiState.selectedTab,
            onTabSelected = viewModel::selectTab,
        )

        // Content area (animated tab switch)
        AnimatedContent(
            targetState = if (uiState.isSearchActive && uiState.searchQuery.isNotBlank())
                "search" else uiState.selectedTab.name,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "libraryContent",
        ) { state ->
            when (state) {
                "search" -> SearchResultsList(
                    results = uiState.searchResults,
                    onTrackClick = { track -> onTrackClick(track, uiState.searchResults) },
                )
                LibraryTab.PLAYLISTS.name -> PlaylistsList(
                    playlists = uiState.playlists,
                    viewMode = uiState.viewMode,
                    onPlaylistClick = onPlaylistClick,
                )
                LibraryTab.TRACKS.name -> TracksList(
                    tracks = uiState.tracks,
                    viewMode = uiState.viewMode,
                    onTrackClick = { track -> onTrackClick(track, uiState.tracks) },
                )
                LibraryTab.ARTISTS.name -> ArtistsList(
                    artists = uiState.artists,
                    viewMode = uiState.viewMode,
                    onArtistClick = onArtistClick,
                )
                LibraryTab.ALBUMS.name -> AlbumsList(
                    albums = uiState.albums,
                    viewMode = uiState.viewMode,
                )
            }
        }
    }
}
```

---

### Task 3.37 — Create LibrarySearchBar composable

**File:** `feature/library/src/main/java/com/stash/app/feature/library/components/LibrarySearchBar.kt`

Glassmorphic search bar matching the HTML mockup exactly: 44dp height, 12dp corner radius, glass background (0.04 alpha white), glass border (0.06 alpha white), search icon on left, focus state changes border to purple (0.40 alpha) with purple glow shadow.

```kotlin
package com.stash.app.feature.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stash.app.core.ui.theme.StashTheme

@Composable
fun LibrarySearchBar(
    query: String,
    isActive: Boolean,
    onQueryChange: (String) -> Unit,
    onToggleSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val borderColor = if (isFocused) StashTheme.colors.primary.copy(alpha = 0.40f)
        else Color.White.copy(alpha = 0.06f)
    val bgColor = if (isFocused) StashTheme.colors.primary.copy(alpha = 0.06f)
        else Color.White.copy(alpha = 0.04f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .height(44.dp)
            .clip(shape)
            .background(bgColor)
            .border(1.dp, borderColor, shape)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = "Search",
                modifier = Modifier.size(18.dp),
                tint = if (isFocused) StashTheme.colors.primary else StashTheme.colors.textTertiary,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = "Search tracks, artists, albums...",
                        color = StashTheme.colors.textTertiary,
                        fontSize = 14.sp,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { isFocused = it.isFocused },
                    textStyle = TextStyle(
                        color = StashTheme.colors.textPrimary,
                        fontSize = 14.sp,
                    ),
                    singleLine = true,
                    cursorBrush = SolidColor(StashTheme.colors.primary),
                )
            }
        }
    }
}
```

---

### Task 3.38 — Create LibraryTabRow composable

**File:** `feature/library/src/main/java/com/stash/app/feature/library/components/LibraryTabRow.kt`

Horizontal scrollable filter tabs matching the mockup: pill-shaped buttons, selected tab has primary color background with 0.15 alpha and primary-colored text, unselected has glass background.

---

### Task 3.39 — Create TrackListItem composable (shared)

**File:** `core/ui/src/main/java/com/stash/app/core/ui/components/TrackListItem.kt`

Reusable track row component: 56dp album art thumbnail (8dp corners), title + artist column, duration text, source indicator dot (green for Spotify, red for YouTube, both for BOTH), overflow menu icon.

This composable is shared between Library and Home screens. The source indicator dot is a 6dp circle with color based on `Track.source`.

---

### Task 3.40 — Create TracksList, ArtistsList, AlbumsList, PlaylistsList, SearchResultsList sub-composables

**File:** `feature/library/src/main/java/com/stash/app/feature/library/components/LibraryLists.kt`

Each list composable:
- `TracksList`: LazyColumn of `TrackListItem` (list mode) or LazyVerticalGrid of album-art cards (grid mode)
- `ArtistsList`: LazyColumn showing artist name + track count
- `AlbumsList`: LazyVerticalGrid of album cards with cover art
- `PlaylistsList`: LazyColumn (list) or 2-column grid of playlist cards with art, name, track count, source dot
- `SearchResultsList`: LazyColumn of `TrackListItem` showing FTS search results

---

## Section I: Connect Player to Real Database

### Task 3.41 — Update navigation to pass track + queue to player

**File:** `app/src/main/java/com/stash/app/navigation/StashNavHost.kt`

Update the Home and Library screen composable calls to wire `onTrackClick` callbacks. When a track is tapped:

1. Call `playerRepository.setQueue(tracks, startIndex)` where `startIndex` is the clicked track's position in the list
2. Call `playerRepository.play()`
3. Call `musicRepository.incrementPlayCount(track.id)`

```kotlin
// In the NavHost, for Home:
composable(StashRoute.Home.route) {
    HomeScreen(
        onTrackClick = { track, trackList ->
            coroutineScope.launch {
                playerRepository.setQueue(
                    tracks = trackList.map { it.toMediaItem() },
                    startIndex = trackList.indexOf(track),
                )
                playerRepository.play()
                musicRepository.incrementPlayCount(track.id)
            }
        },
        onPlaylistClick = { playlistId ->
            navController.navigate(StashRoute.PlaylistDetail.createRoute(playlistId))
        },
    )
}
```

---

### Task 3.42 — Create Track.toMediaItem() extension

**File:** `core/media/src/main/java/com/stash/app/core/media/TrackMediaMapper.kt`

```kotlin
package com.stash.app.core.media

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.stash.app.core.model.Track

fun Track.toMediaItem(): MediaItem {
    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setAlbumTitle(album)
        .setArtworkUri(
            albumArtPath?.let { Uri.parse(it) }
                ?: albumArtUrl?.let { Uri.parse(it) }
        )
        .build()

    return MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(filePath?.let { Uri.parse(it) })
        .setMediaMetadata(metadata)
        .build()
}
```

---

### Task 3.43 — Update NowPlayingViewModel to use MusicRepository

**File:** `feature/nowplaying/src/main/java/com/stash/app/feature/nowplaying/NowPlayingViewModel.kt`

Add `MusicRepository` injection. When the current media item changes (observed via `PlayerRepository.playerState`), call `musicRepository.incrementPlayCount()` with a debounce to only count plays after 30 seconds of playback. This ensures the Home screen's "most played" data stays current.

---

### Task 3.44 — Build and verify full data flow

**Verify steps:**
1. `./gradlew :app:assembleDebug` passes
2. Run on emulator
3. Home screen shows: sync status card with "Synced" + green pulsing dot, 3 Daily Mix cards in carousel, 15 recently added tracks in horizontal scroll, 2 Liked Songs cards, 3 playlists in grid
4. Library screen shows: search bar, 4 tabs, 20 tracks in Tracks tab, 6 playlists in Playlists tab
5. Search "Taylor" returns 3 tracks via FTS4
6. Tap a track: mini player appears, NowPlaying screen shows correct metadata
7. Sort toggle changes track order in Library

---

## Section J: Migration Strategy Setup

### Task 3.45 — Configure Room for future migrations

**File:** `core/data/build.gradle.kts` — already has `exportSchema = true` from Task 3.13.

Create a migration holder for future use:

**File:** `core/data/src/main/java/com/stash/app/core/data/db/migration/Migrations.kt`

```kotlin
package com.stash.app.core.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * All manual Room migrations live here.
 * Version 1 -> 2 will be the first migration when schema changes.
 *
 * Usage in DatabaseModule:
 *   Room.databaseBuilder(...)
 *       .addMigrations(MIGRATION_1_2, MIGRATION_2_3, ...)
 *       .build()
 */

// Placeholder: uncomment and fill when v2 schema is defined.
// val MIGRATION_1_2 = object : Migration(1, 2) {
//     override fun migrate(db: SupportSQLiteDatabase) {
//         db.execSQL("ALTER TABLE tracks ADD COLUMN lyrics_url TEXT DEFAULT NULL")
//     }
// }
```

Also add to `.gitignore`:

```
# DO NOT ignore Room schemas - they are needed for migration testing
!core/data/schemas/
```

---

### Task 3.46 — Add Room schema test dependency

**File:** `core/data/build.gradle.kts` — add:

```kotlin
androidTestImplementation(libs.room.testing)
```

**File:** `gradle/libs.versions.toml`:

```toml
room-testing = { group = "androidx.room", name = "room-testing", version.ref = "room" }
```

This enables `MigrationTestHelper` for future migration tests.

---

## Dependency Graph

```
Task 3.01 (enums) ─────┐
Task 3.02 (models) ─────┤
                         ├──> Task 3.03 (Room deps)
                         │         │
                         │    Task 3.04 (converters)
                         │         │
                         ├──> Tasks 3.05-3.11 (entities, in parallel)
                         │         │
                         │    Task 3.12 (StashDatabase)
                         │         │
                         │    Task 3.13 (schema export config)
                         │         │
                         ├──> Tasks 3.14-3.18 (DAOs, in parallel)
                         │         │
                         │    Task 3.19 (DatabaseModule)
                         │         │
                         ├──> Task 3.20 (mappers)
                         │         │
                         ├──> Tasks 3.21-3.23 (repository)
                         │         │
                         ├──> Tasks 3.24-3.25 (seeder)
                         │         │
                         ├──> Tasks 3.26-3.33 (Home screen)
                         │         │
                         ├──> Tasks 3.34-3.40 (Library screen)
                         │         │
                         ├──> Tasks 3.41-3.43 (player integration)
                         │         │
                         └──> Tasks 3.44-3.46 (verify + migration)
```

**Total tasks:** 46
**Estimated time:** 92-230 minutes (at 2-5 min each)

---

## Potential Challenges

1. **FTS4 content sync:** Room's external-content FTS4 tables require triggers to stay in sync. Using `contentEntity` in the `@Fts4` annotation lets Room generate these triggers automatically. However, if you bypass Room to insert directly via SQL, the FTS table will go stale. Always use the DAO.

2. **combine() arity limit:** Kotlin `combine` supports at most 5 flows. `HomeViewModel` needs 8+ data sources. Solution: nest two `combine` calls, or use `combine(List<Flow>)` with explicit types.

3. **LazyColumn key stability:** All list items use `key = { it.id }` to prevent recomposition issues when data changes reactively.

4. **FTS query escaping:** User input must be sanitized before passing to FTS4 MATCH. Characters like `"`, `*`, `(`, `)` have special meaning. The repository wraps each word with `*` for prefix matching but should strip FTS metacharacters first.

5. **Instant storage:** Room has no built-in Instant converter. Our `Converters` class handles epoch-millis conversion. Be aware this loses nanosecond precision, which is acceptable for a music app.

---

### Critical Files for Implementation

- `core/data/src/main/java/com/stash/app/core/data/db/StashDatabase.kt` - Central Room database definition; all entities, DAOs, and type converters are registered here
- `core/data/src/main/java/com/stash/app/core/data/db/dao/TrackDao.kt` - Most complex DAO with FTS4 search, all sort queries, dedup queries, and play count updates
- `core/data/src/main/java/com/stash/app/core/data/repository/MusicRepositoryImpl.kt` - Bridges DAOs to domain models; consumed by both Home and Library ViewModels
- `feature/home/src/main/java/com/stash/app/feature/home/HomeViewModel.kt` - Combines 8+ reactive data flows into HomeUiState for the primary app screen
- `feature/library/src/main/java/com/stash/app/feature/library/LibraryViewModel.kt` - Manages tab state, FTS search debouncing, sort order, and view mode toggling
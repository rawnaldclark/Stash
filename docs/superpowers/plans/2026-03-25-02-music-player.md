# Phase 2: Music Player Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build a complete music playback system with premium Now Playing UI, background playback, and media controls.

**Architecture:** Media3 MediaSessionService + ExoPlayer for audio, MediaController bridge to UI via PlayerRepository, Compose UI with dynamic theming.

**Tech Stack:** Media3 ExoPlayer 1.9.2, Media3 Session 1.9.2, Palette KTX, Coil 3, Jetpack Compose Animation

**Assumes Phase 1 complete:** Multi-module Gradle project with `:app`, `:core:ui`, `:core:model`, `:core:data`, `:core:media`, `:core:common`, `:feature:nowplaying` modules. Hilt setup, Room database, Navigation Compose scaffold with bottom nav, and Stash dark theme all in place.

---

### Task 1: Add Media3 Dependencies to :core:media Module

**Files:**
- Modify: `core/media/build.gradle.kts`
- Modify: `gradle/libs.versions.toml`

- [ ] **Step 1: Add Media3 version catalog entries**

In `gradle/libs.versions.toml`, add these entries to the `[versions]` and `[libraries]` sections:

```toml
# In [versions]
media3 = "1.9.2"

# In [libraries]
androidx-media3-exoplayer = { group = "androidx.media3", name = "media3-exoplayer", version.ref = "media3" }
androidx-media3-session = { group = "androidx.media3", name = "media3-session", version.ref = "media3" }
androidx-media3-common = { group = "androidx.media3", name = "media3-common", version.ref = "media3" }
androidx-palette-ktx = { group = "androidx.palette", name = "palette-ktx", version = "1.0.0" }
```

- [ ] **Step 2: Configure :core:media build.gradle.kts**

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.stash.core.media"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:data"))

    // Media3
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.common)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
```

- [ ] **Step 3: Add palette-ktx to :feature:nowplaying build.gradle.kts**

Ensure `:feature:nowplaying` depends on `:core:media` and palette:

```kotlin
dependencies {
    implementation(project(":core:media"))
    implementation(project(":core:ui"))
    implementation(project(":core:model"))
    implementation(libs.androidx.palette.ktx)
    implementation(libs.coil.compose)
    // ... other existing deps
}
```

- [ ] **Step 4: Sync Gradle and verify build**

Run `./gradlew :core:media:assembleDebug` and confirm zero errors.

---

### Task 2: Create StashPlaybackService with ExoPlayer

**Files:**
- Create: `core/media/src/main/java/com/stash/core/media/service/StashPlaybackService.kt`
- Test: `core/media/src/test/java/com/stash/core/media/service/StashPlaybackServiceTest.kt`

- [ ] **Step 1: Create the MediaSessionService**

```kotlin
package com.stash.core.media.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class StashPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null

    companion object {
        const val CUSTOM_COMMAND_TOGGLE_SHUFFLE = "com.stash.TOGGLE_SHUFFLE"
        const val CUSTOM_COMMAND_CYCLE_REPEAT = "com.stash.CYCLE_REPEAT"
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        val sessionActivityPendingIntent = packageManager
            ?.getLaunchIntentForPackage(packageName)
            ?.let { sessionIntent ->
                PendingIntent.getActivity(
                    this,
                    0,
                    sessionIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            }

        val customCallback = StashMediaSessionCallback()

        mediaSession = MediaSession.Builder(this, player!!)
            .setCallback(customCallback)
            .apply {
                sessionActivityPendingIntent?.let { setSessionActivity(it) }
            }
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player ?: run {
            stopSelf()
            return
        }
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        player = null
        super.onDestroy()
    }

    private inner class StashMediaSessionCallback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(CUSTOM_COMMAND_TOGGLE_SHUFFLE, Bundle.EMPTY))
                .add(SessionCommand(CUSTOM_COMMAND_CYCLE_REPEAT, Bundle.EMPTY))
                .build()

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CUSTOM_COMMAND_TOGGLE_SHUFFLE -> {
                    val player = session.player
                    player.shuffleModeEnabled = !player.shuffleModeEnabled
                }
                CUSTOM_COMMAND_CYCLE_REPEAT -> {
                    val player = session.player
                    player.repeatMode = when (player.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF
                        else -> Player.REPEAT_MODE_OFF
                    }
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> {
            val resolvedItems = mediaItems.map { item ->
                item.buildUpon()
                    .setUri(item.requestMetadata.mediaUri)
                    .build()
            }
            return Futures.immediateFuture(resolvedItems)
        }
    }
}
```

- [ ] **Step 2: Create basic unit test**

```kotlin
package com.stash.core.media.service

import org.junit.Assert.assertEquals
import org.junit.Test
import androidx.media3.common.Player

class StashPlaybackServiceTest {

    @Test
    fun `repeat mode cycles correctly OFF to ALL to ONE to OFF`() {
        val modes = listOf(
            Player.REPEAT_MODE_OFF,
            Player.REPEAT_MODE_ALL,
            Player.REPEAT_MODE_ONE,
            Player.REPEAT_MODE_OFF
        )

        var current = Player.REPEAT_MODE_OFF
        for (i in 1 until modes.size) {
            current = when (current) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF
                else -> Player.REPEAT_MODE_OFF
            }
            assertEquals(modes[i], current)
        }
    }
}
```

---

### Task 3: Create PlayerRepository Interface and MediaController-Based Implementation

**Files:**
- Create: `core/media/src/main/java/com/stash/core/media/PlayerState.kt`
- Create: `core/media/src/main/java/com/stash/core/media/PlayerRepository.kt`
- Create: `core/media/src/main/java/com/stash/core/media/PlayerRepositoryImpl.kt`
- Test: `core/media/src/test/java/com/stash/core/media/PlayerRepositoryImplTest.kt`

- [ ] **Step 1: Create PlayerState data class**

```kotlin
package com.stash.core.media

import com.stash.core.model.Track

data class PlayerState(
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val shuffleModeEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val duration: Long = 0L,
    val currentIndex: Int = 0,
    val queueSize: Int = 0,
    val isBuffering: Boolean = false,
)

enum class RepeatMode {
    OFF, ALL, ONE;

    companion object {
        fun fromMedia3(value: Int): RepeatMode = when (value) {
            androidx.media3.common.Player.REPEAT_MODE_ALL -> ALL
            androidx.media3.common.Player.REPEAT_MODE_ONE -> ONE
            else -> OFF
        }

        fun toMedia3(mode: RepeatMode): Int = when (mode) {
            OFF -> androidx.media3.common.Player.REPEAT_MODE_OFF
            ALL -> androidx.media3.common.Player.REPEAT_MODE_ALL
            ONE -> androidx.media3.common.Player.REPEAT_MODE_ONE
        }
    }
}
```

- [ ] **Step 2: Create PlayerRepository interface**

```kotlin
package com.stash.core.media

import com.stash.core.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface PlayerRepository {
    val playerState: StateFlow<PlayerState>
    val currentPosition: Flow<Long>

    suspend fun play()
    suspend fun pause()
    suspend fun togglePlayPause()
    suspend fun skipToNext()
    suspend fun skipToPrevious()
    suspend fun seekTo(positionMs: Long)
    suspend fun setQueue(tracks: List<Track>, startIndex: Int = 0)
    suspend fun addToQueue(track: Track)
    suspend fun removeFromQueue(index: Int)
    suspend fun toggleShuffle()
    suspend fun cycleRepeatMode()
    suspend fun release()
}
```

- [ ] **Step 3: Create PlayerRepositoryImpl with MediaController bridge**

```kotlin
package com.stash.core.media

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.stash.core.media.service.StashPlaybackService
import com.stash.core.model.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlayerRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playbackStateStore: PlaybackStateStore,
) : PlayerRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var mediaController: MediaController? = null
    private var currentQueue: List<Track> = emptyList()

    private val _playerState = MutableStateFlow(PlayerState())
    override val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    override val currentPosition: Flow<Long> = flow {
        while (true) {
            val controller = mediaController
            if (controller != null && controller.isConnected) {
                emit(controller.currentPosition)
            }
            delay(200L)
        }
    }

    init {
        scope.launch {
            connectToService()
        }
    }

    private suspend fun connectToService() {
        val sessionToken = SessionToken(
            context,
            ComponentName(context, StashPlaybackService::class.java)
        )
        mediaController = MediaController.Builder(context, sessionToken)
            .buildAsync()
            .await()

        mediaController?.addListener(playerListener)
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateState()
            if (!isPlaying) {
                scope.launch {
                    savePlaybackPosition()
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            updateState()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateState()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            updateState()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            updateState()
        }
    }

    private fun updateState() {
        val controller = mediaController ?: return
        val currentMediaItem = controller.currentMediaItem
        val currentTrack = currentMediaItem?.let { mediaItem ->
            currentQueue.getOrNull(controller.currentMediaItemIndex)
        }

        _playerState.value = PlayerState(
            currentTrack = currentTrack,
            isPlaying = controller.isPlaying,
            shuffleModeEnabled = controller.shuffleModeEnabled,
            repeatMode = RepeatMode.fromMedia3(controller.repeatMode),
            duration = controller.duration.coerceAtLeast(0L),
            currentIndex = controller.currentMediaItemIndex,
            queueSize = controller.mediaItemCount,
            isBuffering = controller.playbackState == Player.STATE_BUFFERING,
        )
    }

    override suspend fun play() {
        mediaController?.play()
    }

    override suspend fun pause() {
        mediaController?.pause()
        savePlaybackPosition()
    }

    override suspend fun togglePlayPause() {
        val controller = mediaController ?: return
        if (controller.isPlaying) pause() else play()
    }

    override suspend fun skipToNext() {
        mediaController?.seekToNext()
    }

    override suspend fun skipToPrevious() {
        val controller = mediaController ?: return
        if (controller.currentPosition > 3000L) {
            controller.seekTo(0)
        } else {
            controller.seekToPrevious()
        }
    }

    override suspend fun seekTo(positionMs: Long) {
        mediaController?.seekTo(positionMs)
    }

    override suspend fun setQueue(tracks: List<Track>, startIndex: Int) {
        val controller = mediaController ?: return
        currentQueue = tracks

        val mediaItems = tracks.map { it.toMediaItem() }
        controller.setMediaItems(mediaItems, startIndex, /* startPositionMs= */ 0L)
        controller.prepare()
        controller.play()
    }

    override suspend fun addToQueue(track: Track) {
        val controller = mediaController ?: return
        currentQueue = currentQueue + track
        controller.addMediaItem(track.toMediaItem())
    }

    override suspend fun removeFromQueue(index: Int) {
        val controller = mediaController ?: return
        if (index in 0 until controller.mediaItemCount) {
            currentQueue = currentQueue.toMutableList().apply { removeAt(index) }
            controller.removeMediaItem(index)
        }
    }

    override suspend fun toggleShuffle() {
        val controller = mediaController ?: return
        controller.shuffleModeEnabled = !controller.shuffleModeEnabled
    }

    override suspend fun cycleRepeatMode() {
        val controller = mediaController ?: return
        controller.repeatMode = when (controller.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF
            else -> Player.REPEAT_MODE_OFF
        }
    }

    override suspend fun release() {
        savePlaybackPosition()
        mediaController?.removeListener(playerListener)
        mediaController?.release()
        mediaController = null
    }

    private suspend fun savePlaybackPosition() {
        val controller = mediaController ?: return
        val track = currentQueue.getOrNull(controller.currentMediaItemIndex) ?: return
        playbackStateStore.savePosition(
            trackId = track.id,
            positionMs = controller.currentPosition,
            queueIndex = controller.currentMediaItemIndex,
        )
    }

    private fun Track.toMediaItem(): MediaItem {
        return MediaItem.Builder()
            .setMediaId(id.toString())
            .setUri(Uri.parse(filePath))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setArtworkUri(albumArtPath?.let { Uri.parse(it) })
                    .build()
            )
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setMediaUri(Uri.parse(filePath))
                    .build()
            )
            .build()
    }
}
```

- [ ] **Step 4: Create Hilt module for DI binding**

Create `core/media/src/main/java/com/stash/core/media/di/MediaModule.kt`:

```kotlin
package com.stash.core.media.di

import com.stash.core.media.PlayerRepository
import com.stash.core.media.PlayerRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class MediaModule {

    @Binds
    @Singleton
    abstract fun bindPlayerRepository(
        impl: PlayerRepositoryImpl
    ): PlayerRepository
}
```

- [ ] **Step 5: Create unit test for PlayerState and RepeatMode**

```kotlin
package com.stash.core.media

import org.junit.Assert.assertEquals
import org.junit.Test
import androidx.media3.common.Player

class PlayerStateTest {

    @Test
    fun `RepeatMode fromMedia3 maps correctly`() {
        assertEquals(RepeatMode.OFF, RepeatMode.fromMedia3(Player.REPEAT_MODE_OFF))
        assertEquals(RepeatMode.ALL, RepeatMode.fromMedia3(Player.REPEAT_MODE_ALL))
        assertEquals(RepeatMode.ONE, RepeatMode.fromMedia3(Player.REPEAT_MODE_ONE))
    }

    @Test
    fun `RepeatMode toMedia3 maps correctly`() {
        assertEquals(Player.REPEAT_MODE_OFF, RepeatMode.toMedia3(RepeatMode.OFF))
        assertEquals(Player.REPEAT_MODE_ALL, RepeatMode.toMedia3(RepeatMode.ALL))
        assertEquals(Player.REPEAT_MODE_ONE, RepeatMode.toMedia3(RepeatMode.ONE))
    }

    @Test
    fun `default PlayerState has sensible defaults`() {
        val state = PlayerState()
        assertEquals(null, state.currentTrack)
        assertEquals(false, state.isPlaying)
        assertEquals(RepeatMode.OFF, state.repeatMode)
        assertEquals(false, state.shuffleModeEnabled)
        assertEquals(0L, state.duration)
    }
}
```

---

### Task 4: Create QueueManager with Shuffle/Repeat Logic

**Files:**
- Create: `core/media/src/main/java/com/stash/core/media/queue/QueueManager.kt`
- Test: `core/media/src/test/java/com/stash/core/media/queue/QueueManagerTest.kt`

- [ ] **Step 1: Create QueueManager**

```kotlin
package com.stash.core.media.queue

import com.stash.core.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class QueueState(
    val originalQueue: List<Track> = emptyList(),
    val shuffledQueue: List<Track> = emptyList(),
    val isShuffled: Boolean = false,
    val currentIndex: Int = 0,
) {
    val activeQueue: List<Track>
        get() = if (isShuffled) shuffledQueue else originalQueue

    val currentTrack: Track?
        get() = activeQueue.getOrNull(currentIndex)

    val hasNext: Boolean
        get() = currentIndex < activeQueue.lastIndex

    val hasPrevious: Boolean
        get() = currentIndex > 0

    val size: Int
        get() = activeQueue.size
}

@Singleton
class QueueManager @Inject constructor() {

    private val _queueState = MutableStateFlow(QueueState())
    val queueState: StateFlow<QueueState> = _queueState.asStateFlow()

    fun setQueue(tracks: List<Track>, startIndex: Int = 0) {
        _queueState.value = QueueState(
            originalQueue = tracks,
            shuffledQueue = emptyList(),
            isShuffled = false,
            currentIndex = startIndex,
        )
    }

    fun toggleShuffle() {
        val current = _queueState.value
        if (current.isShuffled) {
            // Turning shuffle OFF: restore original order, find current track's original index
            val currentTrack = current.currentTrack
            val originalIndex = if (currentTrack != null) {
                current.originalQueue.indexOf(currentTrack).coerceAtLeast(0)
            } else {
                0
            }
            _queueState.value = current.copy(
                isShuffled = false,
                shuffledQueue = emptyList(),
                currentIndex = originalIndex,
            )
        } else {
            // Turning shuffle ON: create shuffled list with current track at position 0
            val currentTrack = current.currentTrack
            val remaining = current.originalQueue.filterNot { it.id == currentTrack?.id }.shuffled()
            val shuffled = if (currentTrack != null) {
                listOf(currentTrack) + remaining
            } else {
                current.originalQueue.shuffled()
            }
            _queueState.value = current.copy(
                isShuffled = true,
                shuffledQueue = shuffled,
                currentIndex = 0,
            )
        }
    }

    fun skipToNext(): Track? {
        val current = _queueState.value
        if (!current.hasNext) return null
        val newIndex = current.currentIndex + 1
        _queueState.value = current.copy(currentIndex = newIndex)
        return _queueState.value.currentTrack
    }

    fun skipToPrevious(): Track? {
        val current = _queueState.value
        if (!current.hasPrevious) return null
        val newIndex = current.currentIndex - 1
        _queueState.value = current.copy(currentIndex = newIndex)
        return _queueState.value.currentTrack
    }

    fun skipToIndex(index: Int): Track? {
        val current = _queueState.value
        if (index !in current.activeQueue.indices) return null
        _queueState.value = current.copy(currentIndex = index)
        return _queueState.value.currentTrack
    }

    fun addToQueue(track: Track) {
        val current = _queueState.value
        _queueState.value = current.copy(
            originalQueue = current.originalQueue + track,
            shuffledQueue = if (current.isShuffled) current.shuffledQueue + track else emptyList(),
        )
    }

    fun removeFromQueue(index: Int) {
        val current = _queueState.value
        val queue = current.activeQueue.toMutableList()
        if (index !in queue.indices) return

        queue.removeAt(index)
        val adjustedIndex = when {
            index < current.currentIndex -> current.currentIndex - 1
            index == current.currentIndex -> current.currentIndex.coerceAtMost(queue.lastIndex.coerceAtLeast(0))
            else -> current.currentIndex
        }

        if (current.isShuffled) {
            _queueState.value = current.copy(
                shuffledQueue = queue,
                currentIndex = adjustedIndex,
            )
        } else {
            _queueState.value = current.copy(
                originalQueue = queue,
                currentIndex = adjustedIndex,
            )
        }
    }

    fun moveInQueue(fromIndex: Int, toIndex: Int) {
        val current = _queueState.value
        val queue = current.activeQueue.toMutableList()
        if (fromIndex !in queue.indices || toIndex !in queue.indices) return

        val item = queue.removeAt(fromIndex)
        queue.add(toIndex, item)

        val newCurrentIndex = when (current.currentIndex) {
            fromIndex -> toIndex
            in (minOf(fromIndex, toIndex)..maxOf(fromIndex, toIndex)) -> {
                if (fromIndex < toIndex) current.currentIndex - 1
                else current.currentIndex + 1
            }
            else -> current.currentIndex
        }

        if (current.isShuffled) {
            _queueState.value = current.copy(shuffledQueue = queue, currentIndex = newCurrentIndex)
        } else {
            _queueState.value = current.copy(originalQueue = queue, currentIndex = newCurrentIndex)
        }
    }

    fun clear() {
        _queueState.value = QueueState()
    }
}
```

- [ ] **Step 2: Create comprehensive QueueManager tests**

```kotlin
package com.stash.core.media.queue

import com.stash.core.model.Track
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class QueueManagerTest {

    private lateinit var queueManager: QueueManager

    private fun track(id: Long, title: String = "Track $id") = Track(
        id = id,
        title = title,
        artist = "Artist",
        album = "Album",
        durationMs = 180_000L,
        filePath = "/music/track_$id.opus",
        fileFormat = "opus",
        source = "SPOTIFY",
    )

    private val testTracks = listOf(track(1), track(2), track(3), track(4), track(5))

    @Before
    fun setup() {
        queueManager = QueueManager()
    }

    @Test
    fun `setQueue initializes with correct state`() {
        queueManager.setQueue(testTracks, startIndex = 2)
        val state = queueManager.queueState.value

        assertEquals(5, state.size)
        assertEquals(2, state.currentIndex)
        assertEquals(track(3), state.currentTrack)
        assertFalse(state.isShuffled)
    }

    @Test
    fun `skipToNext advances index`() {
        queueManager.setQueue(testTracks, startIndex = 0)
        val next = queueManager.skipToNext()

        assertEquals(track(2), next)
        assertEquals(1, queueManager.queueState.value.currentIndex)
    }

    @Test
    fun `skipToNext returns null at end of queue`() {
        queueManager.setQueue(testTracks, startIndex = 4)
        val next = queueManager.skipToNext()

        assertNull(next)
        assertEquals(4, queueManager.queueState.value.currentIndex)
    }

    @Test
    fun `skipToPrevious decrements index`() {
        queueManager.setQueue(testTracks, startIndex = 3)
        val prev = queueManager.skipToPrevious()

        assertEquals(track(3), prev)
        assertEquals(2, queueManager.queueState.value.currentIndex)
    }

    @Test
    fun `toggleShuffle preserves current track at position 0`() {
        queueManager.setQueue(testTracks, startIndex = 2)
        queueManager.toggleShuffle()

        val state = queueManager.queueState.value
        assertTrue(state.isShuffled)
        assertEquals(0, state.currentIndex)
        assertEquals(track(3), state.currentTrack)
        assertEquals(5, state.shuffledQueue.size)
        assertEquals(track(3), state.shuffledQueue[0])
    }

    @Test
    fun `toggleShuffle off restores original order and finds current track`() {
        queueManager.setQueue(testTracks, startIndex = 2)
        queueManager.toggleShuffle() // ON
        queueManager.toggleShuffle() // OFF

        val state = queueManager.queueState.value
        assertFalse(state.isShuffled)
        assertEquals(track(3), state.currentTrack)
        assertEquals(2, state.currentIndex)
    }

    @Test
    fun `addToQueue appends track`() {
        queueManager.setQueue(testTracks)
        val newTrack = track(6, "New Track")
        queueManager.addToQueue(newTrack)

        assertEquals(6, queueManager.queueState.value.size)
        assertEquals(newTrack, queueManager.queueState.value.activeQueue.last())
    }

    @Test
    fun `removeFromQueue adjusts current index when removing before current`() {
        queueManager.setQueue(testTracks, startIndex = 3)
        queueManager.removeFromQueue(1)

        assertEquals(2, queueManager.queueState.value.currentIndex)
        assertEquals(4, queueManager.queueState.value.size)
    }

    @Test
    fun `clear resets to empty state`() {
        queueManager.setQueue(testTracks, startIndex = 2)
        queueManager.clear()

        val state = queueManager.queueState.value
        assertEquals(0, state.size)
        assertNull(state.currentTrack)
    }
}
```

---

### Task 5: Create PlaybackStateStore for Resume

**Files:**
- Create: `core/media/src/main/java/com/stash/core/media/PlaybackStateStore.kt`
- Test: `core/media/src/test/java/com/stash/core/media/PlaybackStateStoreTest.kt`

- [ ] **Step 1: Create PlaybackStateStore with DataStore**

```kotlin
package com.stash.core.media

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.playbackDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "playback_state"
)

data class SavedPlaybackState(
    val trackId: Long,
    val positionMs: Long,
    val queueIndex: Int,
)

@Singleton
class PlaybackStateStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val TRACK_ID = longPreferencesKey("last_track_id")
        val POSITION_MS = longPreferencesKey("last_position_ms")
        val QUEUE_INDEX = intPreferencesKey("last_queue_index")
    }

    suspend fun savePosition(trackId: Long, positionMs: Long, queueIndex: Int) {
        context.playbackDataStore.edit { prefs ->
            prefs[Keys.TRACK_ID] = trackId
            prefs[Keys.POSITION_MS] = positionMs
            prefs[Keys.QUEUE_INDEX] = queueIndex
        }
    }

    suspend fun getLastPlaybackState(): SavedPlaybackState? {
        val prefs = context.playbackDataStore.data.first()
        val trackId = prefs[Keys.TRACK_ID] ?: return null
        val positionMs = prefs[Keys.POSITION_MS] ?: 0L
        val queueIndex = prefs[Keys.QUEUE_INDEX] ?: 0
        return SavedPlaybackState(
            trackId = trackId,
            positionMs = positionMs,
            queueIndex = queueIndex,
        )
    }

    suspend fun clear() {
        context.playbackDataStore.edit { it.clear() }
    }
}
```

- [ ] **Step 2: Create test**

```kotlin
package com.stash.core.media

import org.junit.Assert.*
import org.junit.Test

class PlaybackStateStoreTest {

    @Test
    fun `SavedPlaybackState holds correct values`() {
        val state = SavedPlaybackState(
            trackId = 42L,
            positionMs = 120_000L,
            queueIndex = 3,
        )
        assertEquals(42L, state.trackId)
        assertEquals(120_000L, state.positionMs)
        assertEquals(3, state.queueIndex)
    }
}
```

---

### Task 6: Register Service in AndroidManifest, Permissions

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Add required permissions to the app manifest**

Add these permissions inside `<manifest>` before `<application>`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
```

- [ ] **Step 2: Register StashPlaybackService inside `<application>`**

```xml
<service
    android:name="com.stash.core.media.service.StashPlaybackService"
    android:exported="true"
    android:foregroundServiceType="mediaPlayback">
    <intent-filter>
        <action android:name="androidx.media3.session.MediaSessionService" />
    </intent-filter>
</service>
```

- [ ] **Step 3: Verify build succeeds**

Run `./gradlew :app:assembleDebug` and confirm the manifest merges correctly with no duplicate permission errors.

---

### Task 7: Create NowPlayingViewModel

**Files:**
- Create: `feature/nowplaying/src/main/java/com/stash/feature/nowplaying/NowPlayingViewModel.kt`
- Create: `feature/nowplaying/src/main/java/com/stash/feature/nowplaying/NowPlayingUiState.kt`
- Test: `feature/nowplaying/src/test/java/com/stash/feature/nowplaying/NowPlayingViewModelTest.kt`

- [ ] **Step 1: Create NowPlayingUiState**

```kotlin
package com.stash.feature.nowplaying

import androidx.compose.ui.graphics.Color
import com.stash.core.media.RepeatMode
import com.stash.core.model.Track

data class NowPlayingUiState(
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val currentPosition: Long = 0L,
    val duration: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val isBuffering: Boolean = false,
    val queueSize: Int = 0,
    val currentIndex: Int = 0,
    val dominantColor: Color = Color(0xFF8B5CF6),
    val vibrantColor: Color = Color(0xFF06B6D4),
    val mutedColor: Color = Color(0xFF1A1A2E),
)

val NowPlayingUiState.progressFraction: Float
    get() = if (duration > 0) (currentPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f

val NowPlayingUiState.hasTrack: Boolean
    get() = currentTrack != null
```

- [ ] **Step 2: Create NowPlayingViewModel**

```kotlin
package com.stash.feature.nowplaying

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.palette.graphics.Palette
import com.stash.core.media.PlayerRepository
import com.stash.core.media.PlayerState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class NowPlayingViewModel @Inject constructor(
    private val playerRepository: PlayerRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NowPlayingUiState())
    val uiState: StateFlow<NowPlayingUiState> = _uiState.asStateFlow()

    init {
        observePlayerState()
        observePosition()
    }

    private fun observePlayerState() {
        viewModelScope.launch {
            playerRepository.playerState.collect { playerState: PlayerState ->
                _uiState.update { current ->
                    current.copy(
                        currentTrack = playerState.currentTrack,
                        isPlaying = playerState.isPlaying,
                        duration = playerState.duration,
                        shuffleEnabled = playerState.shuffleModeEnabled,
                        repeatMode = playerState.repeatMode,
                        isBuffering = playerState.isBuffering,
                        queueSize = playerState.queueSize,
                        currentIndex = playerState.currentIndex,
                    )
                }
            }
        }
    }

    private fun observePosition() {
        viewModelScope.launch {
            playerRepository.currentPosition.collect { position ->
                _uiState.update { it.copy(currentPosition = position) }
            }
        }
    }

    fun onPlayPauseClick() {
        viewModelScope.launch { playerRepository.togglePlayPause() }
    }

    fun onSkipNext() {
        viewModelScope.launch { playerRepository.skipToNext() }
    }

    fun onSkipPrevious() {
        viewModelScope.launch { playerRepository.skipToPrevious() }
    }

    fun onSeekTo(positionMs: Long) {
        viewModelScope.launch { playerRepository.seekTo(positionMs) }
    }

    fun onToggleShuffle() {
        viewModelScope.launch { playerRepository.toggleShuffle() }
    }

    fun onCycleRepeatMode() {
        viewModelScope.launch { playerRepository.cycleRepeatMode() }
    }

    fun onAlbumArtLoaded(bitmap: Bitmap?) {
        if (bitmap == null) return
        viewModelScope.launch {
            val palette = withContext(Dispatchers.Default) {
                Palette.from(bitmap)
                    .resizeBitmapArea(128 * 128)
                    .generate()
            }
            val darkMuted = palette.darkMutedSwatch?.rgb?.let { Color(it) }
            val vibrant = palette.vibrantSwatch?.rgb?.let { Color(it) }
            val muted = palette.mutedSwatch?.rgb?.let { Color(it) }

            _uiState.update { current ->
                current.copy(
                    dominantColor = darkMuted ?: current.dominantColor,
                    vibrantColor = vibrant ?: current.vibrantColor,
                    mutedColor = muted ?: current.mutedColor,
                )
            }
        }
    }
}
```

- [ ] **Step 3: Create ViewModel test**

```kotlin
package com.stash.feature.nowplaying

import com.stash.core.media.PlayerRepository
import com.stash.core.media.PlayerState
import com.stash.core.media.RepeatMode
import com.stash.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakePlayerRepository: FakePlayerRepository
    private lateinit var viewModel: NowPlayingViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakePlayerRepository = FakePlayerRepository()
        viewModel = NowPlayingViewModel(fakePlayerRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state has no track`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        assertNull(viewModel.uiState.value.currentTrack)
        assertFalse(viewModel.uiState.value.isPlaying)
    }

    @Test
    fun `state updates when player state changes`() = runTest {
        val track = Track(
            id = 1L,
            title = "Test",
            artist = "Artist",
            album = "Album",
            durationMs = 180_000L,
            filePath = "/test.opus",
            fileFormat = "opus",
            source = "SPOTIFY",
        )
        fakePlayerRepository.emitState(
            PlayerState(currentTrack = track, isPlaying = true, duration = 180_000L)
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(track, viewModel.uiState.value.currentTrack)
        assertTrue(viewModel.uiState.value.isPlaying)
        assertEquals(180_000L, viewModel.uiState.value.duration)
    }

    @Test
    fun `onPlayPauseClick delegates to repository`() = runTest {
        viewModel.onPlayPauseClick()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(fakePlayerRepository.togglePlayPauseCalled)
    }
}

private class FakePlayerRepository : PlayerRepository {
    private val _playerState = MutableStateFlow(PlayerState())
    override val playerState: StateFlow<PlayerState> = _playerState
    override val currentPosition: Flow<Long> = flowOf(0L)

    var togglePlayPauseCalled = false

    fun emitState(state: PlayerState) {
        _playerState.value = state
    }

    override suspend fun play() {}
    override suspend fun pause() {}
    override suspend fun togglePlayPause() { togglePlayPauseCalled = true }
    override suspend fun skipToNext() {}
    override suspend fun skipToPrevious() {}
    override suspend fun seekTo(positionMs: Long) {}
    override suspend fun setQueue(tracks: List<Track>, startIndex: Int) {}
    override suspend fun addToQueue(track: Track) {}
    override suspend fun removeFromQueue(index: Int) {}
    override suspend fun toggleShuffle() {}
    override suspend fun cycleRepeatMode() {}
    override suspend fun release() {}
}
```

---

### Task 8: Create AmbientBackground Composable (Animated Gradients)

**Files:**
- Create: `feature/nowplaying/src/main/java/com/stash/feature/nowplaying/ui/AmbientBackground.kt`

- [ ] **Step 1: Create AmbientBackground with triple radial gradient and drift animation**

```kotlin
package com.stash.feature.nowplaying.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun AmbientBackground(
    dominantColor: Color,
    vibrantColor: Color,
    mutedColor: Color,
    modifier: Modifier = Modifier,
) {
    val animatedDominant by animateColorAsState(
        targetValue = dominantColor,
        animationSpec = tween(durationMillis = 800),
        label = "dominantColor",
    )
    val animatedVibrant by animateColorAsState(
        targetValue = vibrantColor,
        animationSpec = tween(durationMillis = 800),
        label = "vibrantColor",
    )
    val animatedMuted by animateColorAsState(
        targetValue = mutedColor,
        animationSpec = tween(durationMillis = 800),
        label = "mutedColor",
    )

    val infiniteTransition = rememberInfiniteTransition(label = "ambientDrift")

    val angle1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 12_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "angle1",
    )

    val angle2 by infiniteTransition.animateFloat(
        initialValue = 120f,
        targetValue = 480f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 16_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "angle2",
    )

    val angle3 by infiniteTransition.animateFloat(
        initialValue = 240f,
        targetValue = 600f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 20_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "angle3",
    )

    val baseBackground = Color(0xFF06060C)

    Canvas(modifier = modifier.fillMaxSize()) {
        // Base dark fill
        drawRect(color = baseBackground)

        val w = size.width
        val h = size.height
        val radius = w * 0.7f

        // Radial gradient 1 - dominant, drifts around top-left area
        val rad1 = Math.toRadians(angle1.toDouble())
        val center1 = Offset(
            x = w * 0.3f + (w * 0.15f * cos(rad1)).toFloat(),
            y = h * 0.25f + (h * 0.1f * sin(rad1)).toFloat(),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    animatedDominant.copy(alpha = 0.35f),
                    animatedDominant.copy(alpha = 0.08f),
                    Color.Transparent,
                ),
                center = center1,
                radius = radius,
            ),
            radius = radius,
            center = center1,
        )

        // Radial gradient 2 - vibrant, drifts around center-right area
        val rad2 = Math.toRadians(angle2.toDouble())
        val center2 = Offset(
            x = w * 0.7f + (w * 0.12f * cos(rad2)).toFloat(),
            y = h * 0.5f + (h * 0.08f * sin(rad2)).toFloat(),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    animatedVibrant.copy(alpha = 0.25f),
                    animatedVibrant.copy(alpha = 0.05f),
                    Color.Transparent,
                ),
                center = center2,
                radius = radius,
            ),
            radius = radius,
            center = center2,
        )

        // Radial gradient 3 - muted, drifts around bottom area
        val rad3 = Math.toRadians(angle3.toDouble())
        val center3 = Offset(
            x = w * 0.5f + (w * 0.1f * cos(rad3)).toFloat(),
            y = h * 0.75f + (h * 0.08f * sin(rad3)).toFloat(),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    animatedMuted.copy(alpha = 0.20f),
                    animatedMuted.copy(alpha = 0.04f),
                    Color.Transparent,
                ),
                center = center3,
                radius = radius,
            ),
            radius = radius,
            center = center3,
        )
    }
}
```

---

### Task 9: Create Custom GlowingProgressBar Composable

**Files:**
- Create: `feature/nowplaying/src/main/java/com/stash/feature/nowplaying/ui/GlowingProgressBar.kt`

- [ ] **Step 1: Create the custom progress bar with gradient fill, glow, and drag-to-seek**

```kotlin
package com.stash.feature.nowplaying.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
fun GlowingProgressBar(
    progress: Float,
    accentColor: Color,
    elapsedMs: Long,
    totalMs: Long,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }

    val displayProgress = if (isDragging) dragProgress else progress
    val animatedProgress by animateFloatAsState(
        targetValue = displayProgress,
        animationSpec = tween(durationMillis = if (isDragging) 0 else 100),
        label = "progress",
    )

    val trackHeight = if (isDragging) 6.dp else 4.dp
    val thumbRadius = if (isDragging) 8.dp else 0.dp

    val gradientBrush = Brush.horizontalGradient(
        colors = listOf(
            accentColor.copy(alpha = 0.7f),
            accentColor,
        )
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                        onSeek(fraction)
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            dragProgress = (offset.x / size.width).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            onSeek(dragProgress)
                            isDragging = false
                        },
                        onDragCancel = {
                            isDragging = false
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            dragProgress = (dragProgress + dragAmount / size.width).coerceIn(0f, 1f)
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(trackHeight)
            ) {
                val trackY = size.height / 2
                val barHeight = size.height

                // Background track
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.08f),
                    topLeft = Offset(0f, trackY - barHeight / 2),
                    size = Size(size.width, barHeight),
                    cornerRadius = CornerRadius(barHeight / 2),
                )

                // Filled track with gradient
                val filledWidth = size.width * animatedProgress
                if (filledWidth > 0f) {
                    drawRoundRect(
                        brush = gradientBrush,
                        topLeft = Offset(0f, trackY - barHeight / 2),
                        size = Size(filledWidth, barHeight),
                        cornerRadius = CornerRadius(barHeight / 2),
                    )
                }

                // Glow at playhead
                if (filledWidth > 0f) {
                    drawCircle(
                        color = accentColor.copy(alpha = 0.4f),
                        radius = 12.dp.toPx(),
                        center = Offset(filledWidth, trackY),
                    )
                }

                // Thumb (visible when dragging)
                if (isDragging) {
                    drawCircle(
                        color = Color.White,
                        radius = 8.dp.toPx(),
                        center = Offset(filledWidth, trackY),
                    )
                    drawCircle(
                        color = accentColor,
                        radius = 5.dp.toPx(),
                        center = Offset(filledWidth, trackY),
                    )
                }
            }
        }

        // Time labels
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatDuration(if (isDragging) (dragProgress * totalMs).toLong() else elapsedMs),
                style = TextStyle(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.55f),
                    letterSpacing = 0.02.sp,
                ),
            )
            Text(
                text = "-${formatDuration(totalMs - (if (isDragging) (dragProgress * totalMs).toLong() else elapsedMs))}",
                style = TextStyle(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.35f),
                    letterSpacing = 0.02.sp,
                ),
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}
```

---

### Task 10: Create NowPlayingScreen (Full Layout with Gestures)

**Files:**
- Create: `feature/nowplaying/src/main/java/com/stash/feature/nowplaying/NowPlayingScreen.kt`

- [ ] **Step 1: Create the full NowPlayingScreen composable**

```kotlin
package com.stash.feature.nowplaying

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import com.stash.core.media.RepeatMode
import com.stash.feature.nowplaying.ui.AmbientBackground
import com.stash.feature.nowplaying.ui.GlowingProgressBar
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NowPlayingScreen(
    onDismiss: () -> Unit,
    viewModel: NowPlayingViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val track = uiState.currentTrack ?: return

    Box(modifier = Modifier.fillMaxSize()) {
        // Layer 0: Ambient animated gradient background
        AmbientBackground(
            dominantColor = uiState.dominantColor,
            vibrantColor = uiState.vibrantColor,
            mutedColor = uiState.mutedColor,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top bar: collapse button + menu
            Spacer(modifier = Modifier.height(8.dp))
            TopBar(onDismiss = onDismiss)

            Spacer(modifier = Modifier.height(24.dp))

            // Album art with glow and horizontal pager for swipe skip
            AlbumArtSection(
                artworkPath = track.albumArtPath,
                accentColor = uiState.vibrantColor,
                onBitmapReady = viewModel::onAlbumArtLoaded,
                modifier = Modifier.weight(1f, fill = false),
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Track info
            TrackInfoSection(
                title = track.title,
                artist = track.artist,
                album = track.album ?: "",
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Progress bar
            GlowingProgressBar(
                progress = uiState.progressFraction,
                accentColor = uiState.vibrantColor,
                elapsedMs = uiState.currentPosition,
                totalMs = uiState.duration,
                onSeek = { fraction ->
                    viewModel.onSeekTo((fraction * uiState.duration).toLong())
                },
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Playback controls
            PlaybackControls(
                isPlaying = uiState.isPlaying,
                shuffleEnabled = uiState.shuffleEnabled,
                repeatMode = uiState.repeatMode,
                accentColor = uiState.vibrantColor,
                onPlayPause = viewModel::onPlayPauseClick,
                onSkipNext = viewModel::onSkipNext,
                onSkipPrevious = viewModel::onSkipPrevious,
                onToggleShuffle = viewModel::onToggleShuffle,
                onCycleRepeat = viewModel::onCycleRepeatMode,
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun TopBar(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Dismiss",
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(28.dp),
            )
        }
        Text(
            text = "NOW PLAYING",
            style = TextStyle(
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.5f),
                letterSpacing = 2.sp,
            ),
        )
        IconButton(onClick = { /* TODO: overflow menu */ }) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "More options",
                tint = Color.White.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun AlbumArtSection(
    artworkPath: String?,
    accentColor: Color,
    onBitmapReady: (Bitmap?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val glowColor by animateColorAsState(
        targetValue = accentColor.copy(alpha = 0.5f),
        animationSpec = tween(800),
        label = "glowColor",
    )

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        // Glow effect behind album art
        Box(
            modifier = Modifier
                .size(260.dp)
                .graphicsLayer {
                    shadowElevation = 0f
                }
                .drawBehind {
                    drawCircle(
                        color = glowColor,
                        radius = size.width * 0.6f,
                    )
                }
        )

        // Album art
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(artworkPath)
                .crossfade(400)
                .allowHardware(false) // Required for Palette extraction
                .build(),
            contentDescription = "Album artwork",
            contentScale = ContentScale.Crop,
            onState = { state ->
                if (state is AsyncImagePainter.State.Success) {
                    val bitmap = state.result.image
                        .asDrawable(context.resources)
                        .toBitmap()
                    onBitmapReady(bitmap)
                }
            },
            modifier = Modifier
                .size(280.dp)
                .clip(RoundedCornerShape(20.dp))
                .shadow(
                    elevation = 24.dp,
                    shape = RoundedCornerShape(20.dp),
                    ambientColor = accentColor.copy(alpha = 0.3f),
                    spotColor = accentColor.copy(alpha = 0.3f),
                ),
        )
    }
}

@Composable
private fun TrackInfoSection(
    title: String,
    artist: String,
    album: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = TextStyle(
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.95f),
                letterSpacing = (-0.02).sp,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = if (album.isNotEmpty()) "$artist • $album" else artist,
            style = TextStyle(
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                color = Color.White.copy(alpha = 0.55f),
                letterSpacing = 0.01.sp,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PlaybackControls(
    isPlaying: Boolean,
    shuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    accentColor: Color,
    onPlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
) {
    val playScale by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "playScale",
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Shuffle
        IconButton(onClick = onToggleShuffle) {
            Icon(
                imageVector = Icons.Default.Shuffle,
                contentDescription = "Shuffle",
                tint = if (shuffleEnabled) accentColor else Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(24.dp),
            )
        }

        // Previous
        IconButton(onClick = onSkipPrevious, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = Icons.Default.SkipPrevious,
                contentDescription = "Previous",
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(32.dp),
            )
        }

        // Play/Pause - large center button
        Box(
            modifier = Modifier
                .size(64.dp)
                .graphicsLayer { scaleX = playScale; scaleY = playScale }
                .clip(CircleShape)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(accentColor, accentColor.copy(alpha = 0.7f))
                    )
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onPlayPause,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = Color.White,
                modifier = Modifier.size(32.dp),
            )
        }

        // Next
        IconButton(onClick = onSkipNext, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = Icons.Default.SkipNext,
                contentDescription = "Next",
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(32.dp),
            )
        }

        // Repeat
        IconButton(onClick = onCycleRepeat) {
            Icon(
                imageVector = when (repeatMode) {
                    RepeatMode.ONE -> Icons.Default.RepeatOne
                    else -> Icons.Default.Repeat
                },
                contentDescription = "Repeat",
                tint = when (repeatMode) {
                    RepeatMode.OFF -> Color.White.copy(alpha = 0.5f)
                    else -> accentColor
                },
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
```

---

### Task 11: Create MiniPlayer Composable

**Files:**
- Create: `feature/nowplaying/src/main/java/com/stash/feature/nowplaying/MiniPlayer.kt`

- [ ] **Step 1: Create MiniPlayer with track info, progress, and play/pause**

```kotlin
package com.stash.feature.nowplaying

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage

@Composable
fun MiniPlayer(
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NowPlayingViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    AnimatedVisibility(
        visible = uiState.hasTrack,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
        modifier = modifier,
    ) {
        val track = uiState.currentTrack ?: return@AnimatedVisibility

        val surfaceColor by animateColorAsState(
            targetValue = uiState.dominantColor.copy(alpha = 0.15f),
            animationSpec = tween(800),
            label = "miniPlayerBg",
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onExpand),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = Color(0xFF0D0D18),
            tonalElevation = 4.dp,
        ) {
            Column {
                // Thin progress indicator at top
                LinearProgressIndicator(
                    progress = { uiState.progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = uiState.vibrantColor,
                    trackColor = Color.White.copy(alpha = 0.06f),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(surfaceColor)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Album art thumbnail
                    AsyncImage(
                        model = track.albumArtPath,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    // Track info
                    Column(
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = track.title,
                            style = TextStyle(
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White.copy(alpha = 0.95f),
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = track.artist,
                            style = TextStyle(
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Normal,
                                color = Color.White.copy(alpha = 0.55f),
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    // Play/Pause
                    IconButton(
                        onClick = { viewModel.onPlayPauseClick() },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (uiState.isPlaying) "Pause" else "Play",
                            tint = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.size(24.dp),
                        )
                    }

                    // Skip Next
                    IconButton(
                        onClick = { viewModel.onSkipNext() },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Next",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}
```

---

### Task 12: Wire MiniPlayer into Scaffold (Above Bottom Nav)

**Files:**
- Modify: `app/src/main/java/com/stash/app/ui/StashApp.kt` (or equivalent main scaffold file)
- Modify: `app/src/main/java/com/stash/app/navigation/StashNavHost.kt`

- [ ] **Step 1: Add MiniPlayer to the main Scaffold layout**

In the main `StashApp.kt` composable (or wherever the root Scaffold lives), add the MiniPlayer between the content area and bottom navigation. The Scaffold's `bottomBar` slot should contain both MiniPlayer and BottomNavigationBar stacked in a Column:

```kotlin
// In StashApp.kt - modify the Scaffold bottomBar
@Composable
fun StashApp(
    navController: NavHostController = rememberNavController(),
) {
    Scaffold(
        containerColor = Color(0xFF06060C),
        bottomBar = {
            Column {
                MiniPlayer(
                    onExpand = {
                        navController.navigate("now_playing")
                    },
                )
                StashBottomNavigationBar(navController = navController)
            }
        },
    ) { innerPadding ->
        StashNavHost(
            navController = navController,
            modifier = Modifier.padding(innerPadding),
        )
    }
}
```

- [ ] **Step 2: Add NowPlaying route to NavHost**

In `StashNavHost.kt`, add the Now Playing screen as a full-screen dialog/overlay route:

```kotlin
// In StashNavHost.kt
composable(
    route = "now_playing",
    enterTransition = {
        slideInVertically(
            initialOffsetY = { it },
            animationSpec = tween(400)
        )
    },
    exitTransition = {
        slideOutVertically(
            targetOffsetY = { it },
            animationSpec = tween(300)
        )
    },
) {
    NowPlayingScreen(
        onDismiss = { navController.popBackStack() },
    )
}
```

- [ ] **Step 3: Verify MiniPlayer appears on all screens and tapping it navigates to NowPlayingScreen**

Build and run. When a track is playing, the MiniPlayer should be visible above the bottom nav on all screens. Tapping it should slide up the NowPlayingScreen. Swiping down or tapping the down arrow should dismiss it.

---

### Task 13: Add Album Art Color Extraction (Palette)

**Files:**
- Create: `core/media/src/main/java/com/stash/core/media/palette/ColorExtractor.kt`
- Test: `core/media/src/test/java/com/stash/core/media/palette/ColorExtractorTest.kt`

- [ ] **Step 1: Create ColorExtractor utility**

```kotlin
package com.stash.core.media.palette

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class AlbumColors(
    val dominant: Color = DefaultColors.DOMINANT,
    val vibrant: Color = DefaultColors.VIBRANT,
    val muted: Color = DefaultColors.MUTED,
) {
    object DefaultColors {
        val DOMINANT = Color(0xFF8B5CF6) // Purple accent from design tokens
        val VIBRANT = Color(0xFF06B6D4) // Cyan accent from design tokens
        val MUTED = Color(0xFF1A1A2E)   // Elevated surface
    }
}

@Singleton
class ColorExtractor @Inject constructor() {

    private val cache = LinkedHashMap<String, AlbumColors>(
        /* initialCapacity= */ 16,
        /* loadFactor= */ 0.75f,
        /* accessOrder= */ true,
    ).apply {
        // LRU eviction at 50 entries
    }

    private val maxCacheSize = 50

    suspend fun extractColors(bitmap: Bitmap, cacheKey: String? = null): AlbumColors {
        // Check cache first
        cacheKey?.let { key ->
            synchronized(cache) {
                cache[key]?.let { return it }
            }
        }

        return withContext(Dispatchers.Default) {
            val palette = Palette.from(bitmap)
                .resizeBitmapArea(128 * 128) // 128x128 for speed
                .generate()

            val colors = AlbumColors(
                dominant = palette.darkMutedSwatch?.rgb
                    ?.let { Color(it) }
                    ?: AlbumColors.DefaultColors.DOMINANT,
                vibrant = palette.vibrantSwatch?.rgb
                    ?.let { Color(it) }
                    ?: palette.lightVibrantSwatch?.rgb
                        ?.let { Color(it) }
                    ?: AlbumColors.DefaultColors.VIBRANT,
                muted = palette.mutedSwatch?.rgb
                    ?.let { Color(it) }
                    ?: palette.darkVibrantSwatch?.rgb
                        ?.let { Color(it) }
                    ?: AlbumColors.DefaultColors.MUTED,
            )

            // Cache result
            cacheKey?.let { key ->
                synchronized(cache) {
                    cache[key] = colors
                    // Evict oldest if over limit
                    while (cache.size > maxCacheSize) {
                        val oldest = cache.entries.iterator().next()
                        cache.remove(oldest.key)
                    }
                }
            }

            colors
        }
    }

    fun clearCache() {
        synchronized(cache) {
            cache.clear()
        }
    }
}
```

- [ ] **Step 2: Create test for default colors**

```kotlin
package com.stash.core.media.palette

import org.junit.Assert.*
import org.junit.Test

class ColorExtractorTest {

    @Test
    fun `AlbumColors default values are set correctly`() {
        val colors = AlbumColors()
        assertEquals(AlbumColors.DefaultColors.DOMINANT, colors.dominant)
        assertEquals(AlbumColors.DefaultColors.VIBRANT, colors.vibrant)
        assertEquals(AlbumColors.DefaultColors.MUTED, colors.muted)
    }

    @Test
    fun `ColorExtractor clearCache does not throw`() {
        val extractor = ColorExtractor()
        extractor.clearCache() // Should not throw
    }
}
```

---

### Task 14: Test Playback with Bundled Test Audio File

**Files:**
- Create: `app/src/main/res/raw/test_track.opus` (copy a short royalty-free audio file)
- Create: `feature/nowplaying/src/main/java/com/stash/feature/nowplaying/TestPlaybackHelper.kt`

- [ ] **Step 1: Add a test audio file to raw resources**

Download or copy a short (10-30 second) royalty-free Opus audio file and place it at:
```
app/src/main/res/raw/test_track.opus
```

If Opus is problematic in raw resources, use MP3:
```
app/src/main/res/raw/test_track.mp3
```

- [ ] **Step 2: Create TestPlaybackHelper for development testing**

```kotlin
package com.stash.feature.nowplaying

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.stash.core.media.PlayerRepository
import com.stash.core.model.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Development-only helper to test playback with a bundled audio file.
 * Remove or gate behind BuildConfig.DEBUG before release.
 */
@Singleton
class TestPlaybackHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playerRepository: PlayerRepository,
) {
    suspend fun playTestTrack() {
        val testUri = Uri.Builder()
            .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
            .authority(context.packageName)
            .appendPath("raw")
            .appendPath("test_track")
            .build()
            .toString()

        val testTrack = Track(
            id = -1L,
            title = "Test Track",
            artist = "Stash Test",
            album = "Test Album",
            durationMs = 30_000L,
            filePath = testUri,
            fileFormat = "opus",
            source = "LOCAL",
            albumArtPath = null,
            albumArtUrl = null,
        )

        playerRepository.setQueue(listOf(testTrack), startIndex = 0)
    }

    suspend fun playMultipleTestTracks() {
        val testUri = Uri.Builder()
            .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
            .authority(context.packageName)
            .appendPath("raw")
            .appendPath("test_track")
            .build()
            .toString()

        val testTracks = (1..5).map { i ->
            Track(
                id = -i.toLong(),
                title = "Test Track $i",
                artist = "Stash Test Artist",
                album = "Test Album",
                durationMs = 30_000L,
                filePath = testUri,
                fileFormat = "opus",
                source = "LOCAL",
                albumArtPath = null,
                albumArtUrl = null,
            )
        }

        playerRepository.setQueue(testTracks, startIndex = 0)
    }
}
```

- [ ] **Step 3: Add a temporary test button to HomeScreen (debug only)**

In the HomeScreen composable (or any convenient screen), add a temporary button gated behind `BuildConfig.DEBUG`:

```kotlin
// Temporary addition to HomeScreen.kt for testing - REMOVE before release
if (BuildConfig.DEBUG) {
    val testHelper = // inject via ViewModel or remember
    Button(
        onClick = { scope.launch { testHelper.playTestTrack() } },
        modifier = Modifier.padding(16.dp),
    ) {
        Text("Test Playback")
    }
}
```

- [ ] **Step 4: Run the app and verify**

Expected behavior:
1. Tap "Test Playback" button
2. Audio plays from the bundled file
3. MiniPlayer appears above bottom nav showing "Test Track" / "Stash Test"
4. Tap MiniPlayer to expand to full NowPlayingScreen
5. Play/pause, progress bar, and skip controls all function
6. Media notification appears in the notification shade with controls
7. Audio continues when app is backgrounded
8. Pulling down the notification shade shows media controls

---

### Task 15: Commit

**Files:**
- All files created/modified in Tasks 1-14

- [ ] **Step 1: Stage all new and modified files**

```bash
git add \
  gradle/libs.versions.toml \
  core/media/build.gradle.kts \
  core/media/src/main/java/com/stash/core/media/ \
  feature/nowplaying/build.gradle.kts \
  feature/nowplaying/src/main/java/com/stash/feature/nowplaying/ \
  app/src/main/AndroidManifest.xml \
  app/src/main/java/com/stash/app/ui/StashApp.kt \
  app/src/main/java/com/stash/app/navigation/StashNavHost.kt \
  app/src/main/res/raw/test_track* \
  core/media/src/test/ \
  feature/nowplaying/src/test/
```

- [ ] **Step 2: Verify build passes**

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

- [ ] **Step 3: Commit**

```bash
git commit -m "feat(media): add Media3 playback service, player repository, and Now Playing UI

- StashPlaybackService with ExoPlayer, audio focus, wake lock, and media session
- PlayerRepository interface with MediaController bridge implementation
- QueueManager with shuffle/repeat/queue manipulation
- PlaybackStateStore for resume position persistence
- NowPlayingScreen with ambient gradient background, glow effects, custom progress bar
- MiniPlayer composable wired into main Scaffold above bottom nav
- Album art color extraction via Palette API with LRU cache
- TestPlaybackHelper for development testing with bundled audio"
```

---

## Architecture Decisions & Notes

### Why MediaController bridge (not direct ExoPlayer access from UI)?
The `PlayerRepositoryImpl` connects to `StashPlaybackService` via `MediaController`. This is the correct Media3 pattern because: (1) the service runs in a separate process context for background playback, (2) MediaController survives configuration changes, (3) it integrates with the system media session for notification/Bluetooth/car controls. Direct ExoPlayer access from the ViewModel would break when the app is backgrounded.

### Why QueueManager is separate from PlayerRepositoryImpl?
ExoPlayer/Media3 has its own internal queue via `setMediaItems()`. The `QueueManager` provides a higher-level abstraction that maps `Track` domain objects to/from `MediaItem`s, handles shuffle logic with original-order preservation, and enables future features like "up next" queue display. For this initial implementation, `PlayerRepositoryImpl` manages the `MediaController` queue directly while `QueueManager` tracks the domain-level state. These will converge further in Phase 3 when the library screen needs queue display/reorder.

### Why DataStore for playback position (not Room)?
Playback position is transient state that changes every 200ms during playback. DataStore Preferences is designed for small key-value data with fast writes. Room would be overkill and introduce unnecessary I/O overhead for a single position value that is only read on app restart.

### Coil 3 bitmap extraction note
The `allowHardware(false)` flag on the Coil `ImageRequest` is critical. Hardware bitmaps (the default on API 26+) cannot be passed to `Palette.from()` because they are stored in GPU memory. Without this flag, `toBitmap()` would crash or return an unusable bitmap. The `resizeBitmapArea(128 * 128)` on the Palette builder ensures color extraction completes in under 50ms.

### Track model dependency
This plan assumes `com.stash.core.model.Track` exists from Phase 1 with at least these fields: `id: Long`, `title: String`, `artist: String`, `album: String?`, `durationMs: Long`, `filePath: String`, `fileFormat: String`, `source: String`, `albumArtPath: String?`, `albumArtUrl: String?`. If the Phase 1 model differs, adjust the `toMediaItem()` extension and `TestPlaybackHelper` accordingly.
```

---

### Critical Files for Implementation
- `C:/Users/theno/Projects/MP3APK/core/media/src/main/java/com/stash/core/media/PlayerRepositoryImpl.kt` - Core bridge between MediaController and UI, most complex wiring
- `C:/Users/theno/Projects/MP3APK/core/media/src/main/java/com/stash/core/media/service/StashPlaybackService.kt` - MediaSessionService that hosts ExoPlayer, foundation for all playback
- `C:/Users/theno/Projects/MP3APK/feature/nowplaying/src/main/java/com/stash/feature/nowplaying/NowPlayingScreen.kt` - Full Now Playing UI with all visual components composed together
- `C:/Users/theno/Projects/MP3APK/feature/nowplaying/src/main/java/com/stash/feature/nowplaying/NowPlayingViewModel.kt` - Connects player state to UI state, handles Palette color extraction
- `C:/Users/theno/Projects/MP3APK/app/src/main/AndroidManifest.xml` - Service registration and permissions, must be correct for background playback to work
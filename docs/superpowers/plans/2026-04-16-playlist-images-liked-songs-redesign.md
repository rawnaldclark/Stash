# Custom Playlist Cover Images + Liked Songs Card Redesign — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add custom cover images to user-created playlists via Android Photo Picker, and redesign the Liked Songs card on the Home tab with a bold layout and animated "living heart" icon.

**Architecture:** Two independent sub-features. Sub-feature 1 (playlist images) adds an image picker flow, local image storage, and a repository method to update `artUrl`. Sub-feature 2 (Liked Songs redesign) is a composable-only rewrite of `LikedSongsCard` and `SourceLikedChip` in HomeScreen.kt. No database migrations needed.

**Tech Stack:** Kotlin, Jetpack Compose, Coil3 (image loading), Room (persistence), Hilt (DI), AndroidX Activity Result (`PickVisualMedia`), Compose Animation (`infiniteTransition`)

**Spec:** `docs/superpowers/specs/2026-04-16-playlist-images-liked-songs-redesign.md`

---

## File Map

### Sub-feature 1: Custom Playlist Cover Images

| Action | File | Responsibility |
|--------|------|---------------|
| Modify | `core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepository.kt` | Add `updatePlaylistArtUrl()` interface method |
| Modify | `core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepositoryImpl.kt` | Implement `updatePlaylistArtUrl()` |
| Modify | `core/data/src/main/kotlin/com/stash/core/data/db/dao/PlaylistDao.kt` | Add `updateArtUrl()` DAO query |
| Modify | `feature/library/build.gradle.kts` | Add `activity-compose` dependency for `PickVisualMedia` |
| Create | `feature/library/src/main/kotlin/com/stash/feature/library/PlaylistImageHelper.kt` | Shared image save/delete logic (DRY — used by both ViewModels) |
| Modify | `feature/library/src/main/kotlin/com/stash/feature/library/LibraryViewModel.kt` | Add `setPlaylistImage()`, `removePlaylistImage()` using helper |
| Modify | `feature/library/src/main/kotlin/com/stash/feature/library/LibraryScreen.kt` | Add image menu items to bottom sheet, wire `PickVisualMedia` launcher |
| Modify | `feature/library/src/main/kotlin/com/stash/feature/library/PlaylistDetailViewModel.kt` | Add `setPlaylistImage()`, `removePlaylistImage()` using helper |
| Modify | `feature/library/src/main/kotlin/com/stash/feature/library/PlaylistDetailScreen.kt` | Add image menu option, wire `PickVisualMedia` launcher |

### Sub-feature 2: Liked Songs Card Redesign

| Action | File | Responsibility |
|--------|------|---------------|
| Modify | `feature/home/src/main/kotlin/com/stash/feature/home/HomeScreen.kt` | Rewrite `LikedSongsCard` (lines 910-1028) and `SourceLikedChip` (lines 1036-1083) |

---

## Task 1: Add `updatePlaylistArtUrl` to Data Layer

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/dao/PlaylistDao.kt:130`
- Modify: `core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepository.kt:88`
- Modify: `core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepositoryImpl.kt:178`

- [ ] **Step 1: Add DAO query**

In `PlaylistDao.kt`, add after the existing `updateSyncEnabled` method (around line 174):

```kotlin
@Query("UPDATE playlists SET art_url = :artUrl WHERE id = :playlistId")
suspend fun updateArtUrl(playlistId: Long, artUrl: String?)
```

- [ ] **Step 2: Add interface method to MusicRepository**

In `MusicRepository.kt`, add after `removePlaylist` (around line 88):

```kotlin
/** Update a playlist's cover art URL (local file path or remote URL). */
suspend fun updatePlaylistArtUrl(playlistId: Long, artUrl: String?)
```

- [ ] **Step 3: Implement in MusicRepositoryImpl**

In `MusicRepositoryImpl.kt`, add after `removePlaylist` (around line 178):

```kotlin
override suspend fun updatePlaylistArtUrl(playlistId: Long, artUrl: String?) {
    playlistDao.updateArtUrl(playlistId, artUrl)
}
```

- [ ] **Step 4: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/db/dao/PlaylistDao.kt \
       core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepository.kt \
       core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepositoryImpl.kt
git commit -m "feat: add updatePlaylistArtUrl to data layer"
```

---

## Task 2: Create PlaylistImageHelper + Wire into LibraryViewModel

**Files:**
- Modify: `feature/library/build.gradle.kts`
- Create: `feature/library/src/main/kotlin/com/stash/feature/library/PlaylistImageHelper.kt`
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/LibraryViewModel.kt:252`

- [ ] **Step 1: Add activity-compose dependency**

In `feature/library/build.gradle.kts`, add to the `dependencies` block:

```kotlin
implementation(libs.activity.compose)
```

- [ ] **Step 2: Create PlaylistImageHelper**

Create `feature/library/src/main/kotlin/com/stash/feature/library/PlaylistImageHelper.kt`:

```kotlin
package com.stash.feature.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistImageHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Saves the picked image as a 512x512 JPEG and returns a file:// URI
     * with a cache-busting query param for Coil.
     */
    fun savePlaylistCoverImage(playlistId: Long, imageUri: Uri): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(imageUri) ?: return null
            val original = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            // Center-crop to square
            val size = minOf(original.width, original.height)
            val x = (original.width - size) / 2
            val y = (original.height - size) / 2
            val cropped = Bitmap.createBitmap(original, x, y, size, size)

            // Scale to 512x512
            val scaled = Bitmap.createScaledBitmap(cropped, 512, 512, true)
            if (cropped !== original) original.recycle()
            if (scaled !== cropped) cropped.recycle()

            // Save to app-internal storage
            val dir = java.io.File(context.filesDir, "playlist_covers")
            dir.mkdirs()
            val file = java.io.File(dir, "$playlistId.jpg")
            file.outputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            scaled.recycle()

            // Return file:// URI with cache-busting timestamp
            "file://${file.absolutePath}?v=${System.currentTimeMillis()}"
        } catch (e: Exception) {
            null
        }
    }

    /** Deletes the cover image file for a playlist. */
    fun deletePlaylistCoverFile(playlistId: Long) {
        val file = java.io.File(context.filesDir, "playlist_covers/$playlistId.jpg")
        file.delete()
    }
}
```

- [ ] **Step 3: Add image methods to LibraryViewModel**

Add import at the top of `LibraryViewModel.kt`:

```kotlin
import android.net.Uri
```

Update the constructor to inject `PlaylistImageHelper`:

```kotlin
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
    private val tokenManager: TokenManager,
    private val playlistImageHelper: PlaylistImageHelper,
) : ViewModel()
```

Add these methods after `removePlaylist` (around line 265):

```kotlin
fun setPlaylistImage(playlistId: Long, imageUri: Uri) {
    viewModelScope.launch {
        val artUrl = playlistImageHelper.savePlaylistCoverImage(playlistId, imageUri)
        if (artUrl != null) {
            musicRepository.updatePlaylistArtUrl(playlistId, artUrl)
        }
    }
}

fun removePlaylistImage(playlistId: Long) {
    viewModelScope.launch {
        playlistImageHelper.deletePlaylistCoverFile(playlistId)
        musicRepository.updatePlaylistArtUrl(playlistId, null)
    }
}
```

- [ ] **Step 4: Update deletePlaylist to clean up cover image**

Modify the existing `deletePlaylist` method (line 252) to also delete the cover file:

```kotlin
fun deletePlaylist(playlist: Playlist) {
    viewModelScope.launch {
        val tracks = musicRepository.getTracksByPlaylist(playlist.id).first()
        tracks.forEach { musicRepository.deleteTrack(it) }
        playlistImageHelper.deletePlaylistCoverFile(playlist.id)
        musicRepository.removePlaylist(playlist)
    }
}
```

- [ ] **Step 5: Commit**

```bash
git add feature/library/build.gradle.kts \
       feature/library/src/main/kotlin/com/stash/feature/library/PlaylistImageHelper.kt \
       feature/library/src/main/kotlin/com/stash/feature/library/LibraryViewModel.kt
git commit -m "feat: add PlaylistImageHelper and wire into LibraryViewModel"
```

---

## Task 3: Add Image Picker to LibraryScreen Bottom Sheet

**Files:**
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/LibraryScreen.kt`

The callback threading chain is: `LibraryScreen` (ViewModel) → `LibraryContent` → `PlaylistsGrid`. Follow the same pattern as `onDeletePlaylist`.

- [ ] **Step 1: Add imports**

Add at top of `LibraryScreen.kt`:

```kotlin
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.stash.core.model.PlaylistType
```

- [ ] **Step 2: Thread callbacks through composable chain**

**In `LibraryScreen` (top-level composable, ~line 110):** Add alongside existing `onDeletePlaylist = viewModel::deletePlaylist`:

```kotlin
onSetPlaylistImage = viewModel::setPlaylistImage,
onRemovePlaylistImage = viewModel::removePlaylistImage,
```

**In `LibraryContent` signature (~line 136):** Add parameters alongside existing `onDeletePlaylist`:

```kotlin
onSetPlaylistImage: (Long, Uri) -> Unit,
onRemovePlaylistImage: (Long) -> Unit,
```

Pass them through to `PlaylistsGrid` where `LibraryContent` calls it (~line 212):

```kotlin
onSetPlaylistImage = onSetPlaylistImage,
onRemovePlaylistImage = onRemovePlaylistImage,
```

**In `PlaylistsGrid` signature (~line 440):** Add parameters alongside existing `onDeletePlaylist`:

```kotlin
onSetPlaylistImage: (Long, Uri) -> Unit,
onRemovePlaylistImage: (Long) -> Unit,
```

- [ ] **Step 3: Add image picker launcher in PlaylistsGrid**

Inside `PlaylistsGrid` (where `selectedPlaylist` state lives, ~line 459), add:

```kotlin
var playlistForImagePick by remember { mutableStateOf<Playlist?>(null) }

val imagePickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.PickVisualMedia(),
) { uri ->
    if (uri != null && playlistForImagePick != null) {
        onSetPlaylistImage(playlistForImagePick!!.id, uri)
    }
    playlistForImagePick = null
}
```

- [ ] **Step 4: Add menu items to the bottom sheet**

In the `ModalBottomSheet` (~lines 595-627), add image options **before** the "Remove Playlist" `BottomSheetActionRow`, guarded by CUSTOM type check. Use the existing `BottomSheetActionRow` helper to match the other menu items:

```kotlin
// Image options — only for custom playlists
if (playlist.type == PlaylistType.CUSTOM) {
    BottomSheetActionRow(
        icon = Icons.Default.Image,
        label = if (playlist.artUrl != null) "Change Image" else "Add Image",
        onClick = {
            playlistForImagePick = playlist
            selectedPlaylist = null
            imagePickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        },
    )

    // Remove Image — only shown when a custom image is set
    if (playlist.artUrl != null) {
        BottomSheetActionRow(
            icon = Icons.Default.ImageNotSupported,
            label = "Remove Image",
            onClick = {
                onRemovePlaylistImage(playlist.id)
                selectedPlaylist = null
            },
        )
    }
}
```

Note: Uses `Icons.Default.ImageNotSupported` (confirmed in material-icons-extended) instead of `HideImage` for reliability.

- [ ] **Step 5: Commit**

```bash
git add feature/library/src/main/kotlin/com/stash/feature/library/LibraryScreen.kt
git commit -m "feat: add image picker to library playlist bottom sheet"
```

---

## Task 4: Add Image Picker to PlaylistDetailScreen

**Files:**
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/PlaylistDetailViewModel.kt:50`
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/PlaylistDetailScreen.kt:74,370`

- [ ] **Step 1: Add image methods to PlaylistDetailViewModel**

Add import at the top of `PlaylistDetailViewModel.kt`:

```kotlin
import android.net.Uri
```

Update constructor to inject `PlaylistImageHelper`:

```kotlin
@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
    private val playlistImageHelper: PlaylistImageHelper,
) : ViewModel()
```

Add methods:

```kotlin
fun setPlaylistImage(playlistId: Long, imageUri: Uri) {
    viewModelScope.launch {
        val artUrl = playlistImageHelper.savePlaylistCoverImage(playlistId, imageUri)
        if (artUrl != null) {
            musicRepository.updatePlaylistArtUrl(playlistId, artUrl)
        }
    }
}

fun removePlaylistImage(playlistId: Long) {
    viewModelScope.launch {
        playlistImageHelper.deletePlaylistCoverFile(playlistId)
        musicRepository.updatePlaylistArtUrl(playlistId, null)
    }
}
```

- [ ] **Step 2: Add image picker and menu to PlaylistDetailScreen**

Add imports:

```kotlin
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.stash.core.model.PlaylistType
```

In the `PlaylistDetailScreen` composable (line 74), add the launcher:

```kotlin
val imagePickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.PickVisualMedia(),
) { uri ->
    val playlist = state.playlist ?: return@rememberLauncherForActivityResult
    if (uri != null) {
        viewModel.setPlaylistImage(playlist.id, uri)
    }
}
```

In the header action buttons area (around lines 370-420), add an image button for CUSTOM playlists only. Add it alongside the existing Play All / Shuffle / Search buttons:

```kotlin
if (playlist.type == PlaylistType.CUSTOM) {
    IconButton(
        onClick = {
            imagePickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        },
    ) {
        Icon(
            imageVector = Icons.Default.Image,
            contentDescription = "Set cover image",
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add feature/library/src/main/kotlin/com/stash/feature/library/PlaylistDetailViewModel.kt \
       feature/library/src/main/kotlin/com/stash/feature/library/PlaylistDetailScreen.kt
git commit -m "feat: add image picker to playlist detail screen"
```

---

## Task 5: Redesign LikedSongsCard — Living Heart + Bold Layout

**Files:**
- Modify: `feature/home/src/main/kotlin/com/stash/feature/home/HomeScreen.kt:910-1083`

- [ ] **Step 1: Add animation color import**

Add this import at the top of `HomeScreen.kt` (alongside the existing animation imports at lines 4-9):

```kotlin
import androidx.compose.animation.core.animateColor
import androidx.compose.animation.core.EaseInOut
```

- [ ] **Step 2: Rewrite LikedSongsCard composable**

Replace the entire `LikedSongsCard` composable (lines 910-1028) with:

```kotlin
@Composable
private fun LikedSongsCard(
    totalCount: Int,
    spotifyCount: Int,
    youtubeCount: Int,
    showSourceChips: Boolean,
    singleSource: MusicSource?,
    onPlayAll: () -> Unit,
    onPlaySpotify: () -> Unit,
    onPlayYouTube: () -> Unit,
    onClick: () -> Unit,
    onClickSpotify: () -> Unit,
    onClickYouTube: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extendedColors = StashTheme.extendedColors
    val infiniteTransition = rememberInfiniteTransition(label = "livingHeart")

    // Shifting gradient — cycles through purple hues
    val gradientColor1 by infiniteTransition.animateColor(
        initialValue = extendedColors.purpleLight,
        targetValue = extendedColors.purpleDark,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "gradientColor1",
    )
    val gradientColor2 by infiniteTransition.animateColor(
        initialValue = extendedColors.purpleDark,
        targetValue = extendedColors.purpleLight,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "gradientColor2",
    )

    // Breathing glow — shadow radius pulses
    val glowRadius by infiniteTransition.animateFloat(
        initialValue = 8f,
        targetValue = 20f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glowRadius",
    )
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glowAlpha",
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = extendedColors.glassBackground,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, extendedColors.glassBorder),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            Color.Transparent,
                        )
                    )
                ),
        ) {
            // Main row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
                    .padding(24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Text content on the left
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "YOUR COLLECTION",
                        style = MaterialTheme.typography.labelSmall.copy(
                            letterSpacing = 1.5.sp,
                        ),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "Liked Songs",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (singleSource != null) {
                            SourceIndicator(source = singleSource, size = 6.dp)
                        }
                    }
                    Text(
                        text = when (singleSource) {
                            MusicSource.SPOTIFY -> "$totalCount tracks on Spotify"
                            MusicSource.YOUTUBE -> "$totalCount tracks on YouTube Music"
                            else -> "$totalCount tracks \u00B7 2 sources"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Living heart icon on the right
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .drawBehind {
                            drawCircle(
                                color = gradientColor1.copy(alpha = glowAlpha),
                                radius = glowRadius.dp.toPx(),
                            )
                        }
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(gradientColor1, gradientColor2)
                            )
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            // Source chips — compact pills, dot + count only
            if (showSourceChips) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SourceLikedChip(
                        source = MusicSource.SPOTIFY,
                        count = spotifyCount,
                        onClick = onClickSpotify,
                    )
                    SourceLikedChip(
                        source = MusicSource.YOUTUBE,
                        count = youtubeCount,
                        onClick = onClickYouTube,
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 3: Rewrite SourceLikedChip composable**

Replace the entire `SourceLikedChip` composable (lines 1036-1083) with:

```kotlin
@Composable
private fun SourceLikedChip(
    source: MusicSource,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extendedColors = StashTheme.extendedColors
    val accent = when (source) {
        MusicSource.SPOTIFY -> extendedColors.spotifyGreen
        MusicSource.YOUTUBE -> extendedColors.youtubeRed
        MusicSource.BOTH -> MaterialTheme.colorScheme.primary
    }
    val sourceName = when (source) {
        MusicSource.SPOTIFY -> "Spotify"
        MusicSource.YOUTUBE -> "YouTube"
        MusicSource.BOTH -> ""
    }

    Surface(
        modifier = modifier
            .clickable(onClick = onClick),
        color = accent.copy(alpha = 0.12f),
        shape = RoundedCornerShape(20.dp),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(accent)
                    .semantics {
                        contentDescription = "$count $sourceName liked songs"
                    },
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = accent,
            )
        }
    }
}
```

- [ ] **Step 4: Add missing imports**

Ensure these imports are present at the top of `HomeScreen.kt`:

```kotlin
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
```

- [ ] **Step 5: Verify the SourceLikedChip signature change is compatible**

The old `SourceLikedChip` had a `label: String` parameter. The new one removes it. Check that no other call sites use `SourceLikedChip` besides the two calls inside `LikedSongsCard`. If there are other callers, update them.

- [ ] **Step 6: Commit**

```bash
git add feature/home/src/main/kotlin/com/stash/feature/home/HomeScreen.kt
git commit -m "feat: redesign LikedSongsCard with living heart and compact chips"
```

---

## Task 6: Build and Verify

- [ ] **Step 1: Build the project**

```bash
./gradlew assembleDebug
```

Fix any compilation errors.

- [ ] **Step 2: Manual verification checklist**

Run the app and verify:
- Library tab: long-press a custom playlist → "Add Image" appears in bottom sheet
- Library tab: long-press a synced playlist → no image options appear
- Pick an image → it appears as the playlist cover in the grid
- Navigate to playlist detail → same image shows in the header
- Long-press again → "Change Image" and "Remove Image" appear
- Remove image → falls back to gradient placeholder
- Change image → new image replaces old one (no stale cache)
- Delete a playlist that has a custom image → no orphaned file
- Home tab: Liked Songs card shows new layout with "YOUR COLLECTION" label
- Heart icon has shifting gradient and breathing glow animation
- Source chips show only colored dot + count number
- All tap targets work (play all, play source, navigate)

- [ ] **Step 3: Commit any fixes**

```bash
git add -A
git commit -m "fix: address build and UI issues from playlist images + liked songs redesign"
```

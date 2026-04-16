# Custom Playlist Cover Images + Liked Songs Card Redesign

## Overview

Two independent sub-features:
1. **Custom Playlist Cover Images** — let users set custom cover art on their Stash playlists via image picker
2. **Liked Songs Card Redesign** — visual overhaul of the LikedSongsCard on the Home tab

## Sub-feature 1: Custom Playlist Cover Images

### Scope

- Only user-created playlists (`type = CUSTOM`, `source = BOTH`)
- NOT synced Spotify/YouTube playlists (those get art from the remote source)

### Image Picker Flow

1. User long-presses a custom playlist in Library grid → existing `ModalBottomSheet` shows new items:
   - "Add Image" (when no custom image set)
   - "Change Image" (when custom image already set)
   - "Remove Image" (when custom image already set)
   - These options are only shown when `playlist.type == PlaylistType.CUSTOM` — never for DAILY_MIX or LIKED_SONGS playlists
2. Same options available from PlaylistDetailScreen header (via menu/edit button, also guarded by CUSTOM type check)
3. Tapping "Add Image" / "Change Image" launches Android Photo Picker (`PickVisualMedia()` with `ImageOnly` filter)
4. Selected image is:
   - Resized to 512x512 pixels (center-cropped to square)
   - Compressed as JPEG
   - Saved to `context.filesDir/playlist_covers/{playlistId}.jpg`
5. `artUrl` field on `PlaylistEntity` is updated to the local file path with a cache-busting timestamp query param (e.g., `file:///.../{playlistId}.jpg?v={System.currentTimeMillis()}`) so Coil invalidates its cache on image changes
6. Coil `AsyncImage` loads the image everywhere it appears:
   - Library grid cards (`LibraryScreen.kt`)
   - Playlist detail header (`PlaylistDetailScreen.kt`)
   - Home screen playlist sections (if applicable)

### Remove Image

- "Remove Image" menu option deletes the file from `playlist_covers/` directory
- Sets `artUrl = null` on the entity
- Card falls back to the existing gradient placeholder with music note icon

### Playlist Deletion Cleanup

- When a custom playlist is deleted, also delete its cover image file from `playlist_covers/` if one exists
- Prevents orphaned image files accumulating on disk

### Data Model

No schema changes. Reuses the existing `artUrl: String?` field on `PlaylistEntity` and `Playlist` domain model. Coil handles both remote URLs and local `file://` URIs transparently.

### Key Files

| File | Change |
|------|--------|
| `feature/library/.../LibraryScreen.kt` | Add "Add/Change/Remove Image" to long-press context menu |
| `feature/library/.../PlaylistDetailScreen.kt` | Add image menu option in header area |
| `feature/library/.../LibraryViewModel.kt` | Handle image pick result, resize/save, update entity |
| `feature/library/.../PlaylistDetailViewModel.kt` | Same image handling for detail screen |
| New utility function (inline or small helper) | Resize image to 512x512, save as JPEG |

### Image Picker Contract

Use `ActivityResultContracts.PickVisualMedia()` — the modern Android photo picker. Works natively on Android 13+, falls back to `ACTION_OPEN_DOCUMENT` intent on older devices (minSdk is 26).

### Image Storage

- Directory: `context.filesDir/playlist_covers/`
- Filename: `{playlistId}.jpg`
- Max dimensions: 512x512
- Format: JPEG at quality 85 (good quality, ~50-80KB per image)
- Overwriting: setting a new image overwrites the previous file for that playlist

---

## Sub-feature 2: Liked Songs Card Redesign

### Current State

The `LikedSongsCard` composable (~170 lines in `HomeScreen.kt:910-1083`) uses:
- Glass-morphic card with horizontal gradient
- 48dp rounded-square heart icon (gradient fill, white heart) on the LEFT
- `titleMedium` for "Liked Songs", `bodySmall` for track count
- Full-width source chips with colored dot + source name + count

### New Design: Bold & Minimal with Living Heart

#### Layout Changes

- **"YOUR COLLECTION" label** — uppercase, above the title. Space Grotesk SemiBold, small size (~10-11sp), primary color at 70% alpha, 1.5sp letter spacing
- **Title** — "Liked Songs" bumped up to `titleLarge` or `headlineSmall` (from `titleMedium`). Space Grotesk SemiBold, white
- **Subtitle** — "{count} tracks · 2 sources" when both sources have liked songs, "{count} tracks on Spotify" or "{count} tracks on YouTube Music" when single source, in `bodySmall`, onSurfaceVariant
- **Heart icon moves to the RIGHT** side of the row (currently on the left)

#### Living Heart Icon

A 52dp circular container with animated effects:

- **Shifting gradient**: background cycles through purple hues (`#BB86FC` → `#9B30FF` → `#7B2FBE` → loop) using `infiniteTransition` + `animateColor`. ~4 second cycle.
- **Breathing glow**: animated shadow/elevation that pulses in intensity on a separate ~3 second cycle. Uses `animateFloat` for shadow radius.
- **Static white heart icon** inside (24-26dp). The icon itself doesn't move — the light around it lives.

#### Source Chips Redesign

- Compact pill shape: colored dot (6dp) + count number only
- NO source name text — the green dot = Spotify, red dot = YouTube
- Smaller pills with 20dp border radius
- Same `rgba` tinted background per source
- Each chip must have a `contentDescription` for accessibility (e.g., "523 Spotify liked songs")

#### What Stays the Same

- Glass card `Surface` with `glassBackground` color and `glassBorder`
- Horizontal gradient background on the column
- All click targets and callbacks unchanged (`onPlayAll`, `onPlaySpotify`, `onPlayYouTube`, `onClick`, `onClickSpotify`, `onClickYouTube`)
- Source chips only shown when both sources have liked songs
- Single-source indicator dot behavior next to title
- `SourceLikedChip` still has its own `onClick` for source-specific navigation

### Key Files

| File | Change |
|------|--------|
| `feature/home/.../HomeScreen.kt` | Rewrite `LikedSongsCard` composable (~lines 910-1028) and `SourceLikedChip` composable (~lines 1036-1083) |

### Animation Implementation

```
// Pseudocode for Compose animations
val infiniteTransition = rememberInfiniteTransition()

// Gradient color cycling (~4s)
val animatedColor by infiniteTransition.animateColor(
    initialValue = purpleLight,  // #BB86FC
    targetValue = purpleDark,    // #7B2FBE
    animationSpec = infiniteRepeatable(
        animation = tween(4000, easing = LinearEasing),
        repeatMode = RepeatMode.Reverse
    )
)

// Glow shadow radius (~3s)
val glowRadius by infiniteTransition.animateFloat(
    initialValue = 8f,
    targetValue = 20f,
    animationSpec = infiniteRepeatable(
        animation = tween(3000, easing = EaseInOut),
        repeatMode = RepeatMode.Reverse
    )
)
```

---

## Out of Scope

- Image cropping UI (auto center-crop to square)
- Custom images for synced playlists
- Cloud backup of custom images
- Animated/video cover art

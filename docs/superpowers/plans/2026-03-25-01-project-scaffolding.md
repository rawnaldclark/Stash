# Phase 1: Project Scaffolding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Set up the complete Android project structure with multi-module Gradle build, Compose theme, shared UI components, and navigation shell.

**Architecture:** Multi-module Kotlin Android project using Jetpack Compose, Hilt for DI, and Compose Navigation with bottom nav bar.

**Tech Stack:** Kotlin 2.1.10, Jetpack Compose (BOM 2026.03.00), Hilt 2.56, Navigation Compose 2.9.7, Coil 3.4.0

**Package name:** `com.stash.app`

---

### Task 1: Create Root Project with settings.gradle.kts and Version Catalog

**Files:**
- Create: `settings.gradle.kts`
- Create: `gradle/libs.versions.toml`
- Create: `gradle.properties`
- Create: `local.properties` (gitignored, not committed)

- [ ] **Step 1: Create `settings.gradle.kts` with all module declarations**

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolution {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Stash"

include(":app")

include(":core:ui")
include(":core:model")
include(":core:common")
include(":core:data")
include(":core:media")
include(":core:auth")
include(":core:network")

include(":data:spotify")
include(":data:ytmusic")
include(":data:download")

include(":feature:home")
include(":feature:library")
include(":feature:nowplaying")
include(":feature:sync")
include(":feature:settings")
```

- [ ] **Step 2: Create `gradle/libs.versions.toml` version catalog**

```toml
# gradle/libs.versions.toml
[versions]
agp = "8.9.1"
kotlin = "2.1.10"
ksp = "2.1.10-1.0.31"
composeBom = "2026.03.00"
hilt = "2.56"
navigationCompose = "2.9.7"
coil = "3.4.0"
media3 = "1.9.2"
room = "2.7.1"
datastore = "1.1.4"
okhttp = "4.12.0"
kotlinxSerialization = "1.7.3"
palette = "1.0.0"
appauth = "0.11.2"
tink = "1.12.0"
youtubedlAndroid = "0.17.+"
workManager = "2.10.1"
coreKtx = "1.16.0"
activityCompose = "1.10.1"
lifecycleCompose = "2.9.0"
hiltNavigationCompose = "1.2.0"
coroutines = "1.10.1"

[libraries]
# Compose
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
compose-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
compose-material-icons-extended = { group = "androidx.compose.material", name = "material-icons-extended" }
compose-animation = { group = "androidx.compose.animation", name = "animation" }
compose-foundation = { group = "androidx.compose.foundation", name = "foundation" }

# AndroidX Core
core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycleCompose" }
lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycleCompose" }

# Navigation
navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigationCompose" }

# Hilt
hilt-android = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
hilt-android-compiler = { group = "com.google.dagger", name = "hilt-android-compiler", version.ref = "hilt" }
hilt-navigation-compose = { group = "androidx.hilt", name = "hilt-navigation-compose", version.ref = "hiltNavigationCompose" }

# Coil
coil-compose = { group = "io.coil-kt.coil3", name = "coil-compose", version.ref = "coil" }

# Room
room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }

# DataStore
datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }

# Networking
okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
okhttp-logging = { group = "com.squareup.okhttp3", name = "logging-interceptor", version.ref = "okhttp" }
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "kotlinxSerialization" }

# Media3
media3-exoplayer = { group = "androidx.media3", name = "media3-exoplayer", version.ref = "media3" }
media3-session = { group = "androidx.media3", name = "media3-session", version.ref = "media3" }

# Palette
palette-ktx = { group = "androidx.palette", name = "palette-ktx", version.ref = "palette" }

# WorkManager
work-runtime-ktx = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "workManager" }

# Auth & Security
appauth = { group = "net.openid", name = "appauth", version.ref = "appauth" }
tink-android = { group = "com.google.crypto.tink", name = "tink-android", version.ref = "tink" }

# yt-dlp
youtubedl-android = { group = "io.github.junkfood02.youtubedl-android", name = "library", version.ref = "youtubedlAndroid" }
youtubedl-ffmpeg = { group = "io.github.junkfood02.youtubedl-android", name = "ffmpeg", version.ref = "youtubedlAndroid" }

# Coroutines
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

- [ ] **Step 3: Create `gradle.properties`**

```properties
# gradle.properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=true
```

- [ ] **Step 4: Commit**

```
git add settings.gradle.kts gradle/libs.versions.toml gradle.properties
git commit -m "chore: initialize Gradle project with version catalog and module declarations"
```

---

### Task 2: Configure Root build.gradle.kts and Convention Plugins

**Files:**
- Create: `build.gradle.kts` (root)
- Create: `build-logic/convention/build.gradle.kts`
- Create: `build-logic/convention/src/main/kotlin/AndroidLibraryConventionPlugin.kt`
- Create: `build-logic/convention/src/main/kotlin/AndroidFeatureConventionPlugin.kt`
- Create: `build-logic/convention/src/main/kotlin/ComposeConventionPlugin.kt`
- Create: `build-logic/settings.gradle.kts`

- [ ] **Step 1: Create root `build.gradle.kts`**

```kotlin
// build.gradle.kts (root)
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
}
```

- [ ] **Step 2: Create `build-logic/settings.gradle.kts`**

```kotlin
// build-logic/settings.gradle.kts
dependencyResolution {
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
include(":convention")
```

- [ ] **Step 3: Create `build-logic/convention/build.gradle.kts`**

```kotlin
// build-logic/convention/build.gradle.kts
plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly(libs.plugins.android.application.toDep())
    compileOnly(libs.plugins.android.library.toDep())
    compileOnly(libs.plugins.kotlin.android.toDep())
    compileOnly(libs.plugins.compose.compiler.toDep())
    compileOnly(libs.plugins.hilt.toDep())
    compileOnly(libs.plugins.ksp.toDep())
}

fun Provider<PluginDependency>.toDep() = map {
    "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}"
}
```

- [ ] **Step 4: Create `AndroidLibraryConventionPlugin.kt`**

```kotlin
// build-logic/convention/src/main/kotlin/AndroidLibraryConventionPlugin.kt
import com.android.build.gradle.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("com.android.library")
                apply("org.jetbrains.kotlin.android")
            }

            extensions.configure<LibraryExtension> {
                compileSdk = 35

                defaultConfig {
                    minSdk = 26
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                    consumerProguardFiles("consumer-rules.pro")
                }

                compileOptions {
                    sourceCompatibility = org.gradle.api.JavaVersion.VERSION_17
                    targetCompatibility = org.gradle.api.JavaVersion.VERSION_17
                }
            }
        }
    }
}
```

- [ ] **Step 5: Create `ComposeConventionPlugin.kt`**

```kotlin
// build-logic/convention/src/main/kotlin/ComposeConventionPlugin.kt
import com.android.build.gradle.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class ComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

            extensions.configure<LibraryExtension> {
                buildFeatures {
                    compose = true
                }
            }

            val libs = extensions.getByType(
                org.gradle.api.artifacts.VersionCatalogsExtension::class.java
            ).named("libs")

            dependencies {
                val bom = libs.findLibrary("compose-bom").get()
                add("implementation", platform(bom))
                add("implementation", libs.findLibrary("compose-ui").get())
                add("implementation", libs.findLibrary("compose-ui-graphics").get())
                add("implementation", libs.findLibrary("compose-ui-tooling-preview").get())
                add("implementation", libs.findLibrary("compose-material3").get())
                add("implementation", libs.findLibrary("compose-foundation").get())
                add("debugImplementation", libs.findLibrary("compose-ui-tooling").get())
            }
        }
    }
}
```

- [ ] **Step 6: Create `AndroidFeatureConventionPlugin.kt`**

```kotlin
// build-logic/convention/src/main/kotlin/AndroidFeatureConventionPlugin.kt
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply(AndroidLibraryConventionPlugin::class.java)
                apply(ComposeConventionPlugin::class.java)
                apply("com.google.dagger.hilt.android")
                apply("com.google.devtools.ksp")
            }

            val libs = extensions.getByType(
                org.gradle.api.artifacts.VersionCatalogsExtension::class.java
            ).named("libs")

            dependencies {
                add("implementation", project(":core:ui"))
                add("implementation", project(":core:model"))
                add("implementation", project(":core:common"))

                add("implementation", libs.findLibrary("hilt-android").get())
                add("ksp", libs.findLibrary("hilt-android-compiler").get())
                add("implementation", libs.findLibrary("hilt-navigation-compose").get())

                add("implementation", libs.findLibrary("lifecycle-runtime-compose").get())
                add("implementation", libs.findLibrary("lifecycle-viewmodel-compose").get())
                add("implementation", libs.findLibrary("navigation-compose").get())
            }
        }
    }
}
```

- [ ] **Step 7: Register convention plugins in `build-logic/convention/build.gradle.kts`**

Append to the end of `build-logic/convention/build.gradle.kts`:

```kotlin
gradlePlugin {
    plugins {
        register("androidLibrary") {
            id = "stash.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidFeature") {
            id = "stash.android.feature"
            implementationClass = "AndroidFeatureConventionPlugin"
        }
        register("composeLibrary") {
            id = "stash.compose.library"
            implementationClass = "ComposeConventionPlugin"
        }
    }
}
```

- [ ] **Step 8: Include build-logic in root `settings.gradle.kts`**

Add at the top of `settings.gradle.kts`, before `pluginManagement`:

```kotlin
pluginManagement {
    includeBuild("build-logic")
    // ... existing repos block
}
```

- [ ] **Step 9: Commit**

```
git add build.gradle.kts build-logic/
git commit -m "chore: add root build config and convention plugins for library, compose, and feature modules"
```

---

### Task 3: Create :core:model Module with Domain Data Classes

**Files:**
- Create: `core/model/build.gradle.kts`
- Create: `core/model/src/main/AndroidManifest.xml`
- Create: `core/model/src/main/kotlin/com/stash/core/model/Track.kt`
- Create: `core/model/src/main/kotlin/com/stash/core/model/Playlist.kt`
- Create: `core/model/src/main/kotlin/com/stash/core/model/SyncStatus.kt`
- Create: `core/model/src/main/kotlin/com/stash/core/model/MusicSource.kt`
- Create: `core/model/src/main/kotlin/com/stash/core/model/PlayerState.kt`
- Create: `core/model/src/main/kotlin/com/stash/core/model/DownloadState.kt`
- Create: `core/model/src/main/kotlin/com/stash/core/model/QualityTier.kt`

- [ ] **Step 1: Create `core/model/build.gradle.kts`**

```kotlin
// core/model/build.gradle.kts
plugins {
    id("stash.android.library")
}

android {
    namespace = "com.stash.core.model"
}
```

- [ ] **Step 2: Create `core/model/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
```

- [ ] **Step 3: Create `MusicSource.kt`**

```kotlin
// core/model/src/main/kotlin/com/stash/core/model/MusicSource.kt
package com.stash.core.model

enum class MusicSource {
    SPOTIFY,
    YOUTUBE,
    BOTH
}
```

- [ ] **Step 4: Create `QualityTier.kt`**

```kotlin
// core/model/src/main/kotlin/com/stash/core/model/QualityTier.kt
package com.stash.core.model

enum class QualityTier(
    val label: String,
    val bitrateKbps: Int,
    val sizeMbPerMinute: Float,
) {
    BEST(label = "Best", bitrateKbps = 160, sizeMbPerMinute = 1.2f),
    HIGH(label = "High", bitrateKbps = 128, sizeMbPerMinute = 0.96f),
    NORMAL(label = "Normal", bitrateKbps = 96, sizeMbPerMinute = 0.72f),
    LOW(label = "Low", bitrateKbps = 64, sizeMbPerMinute = 0.48f),
}
```

- [ ] **Step 5: Create `Track.kt`**

```kotlin
// core/model/src/main/kotlin/com/stash/core/model/Track.kt
package com.stash.core.model

data class Track(
    val id: Long = 0,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0,
    val filePath: String? = null,
    val fileFormat: String = "opus",
    val qualityKbps: Int = 0,
    val fileSizeBytes: Long = 0,
    val source: MusicSource = MusicSource.SPOTIFY,
    val spotifyUri: String? = null,
    val youtubeId: String? = null,
    val albumArtUrl: String? = null,
    val albumArtPath: String? = null,
    val dateAdded: Long = System.currentTimeMillis(),
    val lastPlayed: Long? = null,
    val playCount: Int = 0,
    val isDownloaded: Boolean = false,
    val matchConfidence: Float = 0f,
)
```

- [ ] **Step 6: Create `Playlist.kt`**

```kotlin
// core/model/src/main/kotlin/com/stash/core/model/Playlist.kt
package com.stash.core.model

data class Playlist(
    val id: Long = 0,
    val name: String,
    val source: MusicSource,
    val sourceId: String = "",
    val type: PlaylistType = PlaylistType.CUSTOM,
    val mixNumber: Int? = null,
    val lastSynced: Long? = null,
    val trackCount: Int = 0,
    val isActive: Boolean = true,
    val artUrl: String? = null,
    val tracks: List<Track> = emptyList(),
)

enum class PlaylistType {
    DAILY_MIX,
    LIKED_SONGS,
    CUSTOM
}
```

- [ ] **Step 7: Create `PlayerState.kt`**

```kotlin
// core/model/src/main/kotlin/com/stash/core/model/PlayerState.kt
package com.stash.core.model

data class PlayerState(
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isShuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = 0,
)

enum class RepeatMode {
    OFF,
    ALL,
    ONE
}
```

- [ ] **Step 8: Create `SyncStatus.kt`**

```kotlin
// core/model/src/main/kotlin/com/stash/core/model/SyncStatus.kt
package com.stash.core.model

data class SyncStatus(
    val state: SyncState = SyncState.IDLE,
    val progressPercent: Float = 0f,
    val currentPhase: String = "",
    val tracksDownloaded: Int = 0,
    val totalTracksToDownload: Int = 0,
    val lastSyncTimestamp: Long? = null,
    val errorMessage: String? = null,
)

enum class SyncState {
    IDLE,
    AUTHENTICATING,
    FETCHING_PLAYLISTS,
    DIFFING,
    DOWNLOADING,
    FINALIZING,
    COMPLETED,
    FAILED
}
```

- [ ] **Step 9: Create `DownloadState.kt`**

```kotlin
// core/model/src/main/kotlin/com/stash/core/model/DownloadState.kt
package com.stash.core.model

enum class DownloadState {
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

- [ ] **Step 10: Commit**

```
git add core/model/
git commit -m "feat: add :core:model module with domain data classes (Track, Playlist, PlayerState, SyncStatus)"
```

---

### Task 4: Create :core:common Module with Utility Extensions

**Files:**
- Create: `core/common/build.gradle.kts`
- Create: `core/common/src/main/AndroidManifest.xml`
- Create: `core/common/src/main/kotlin/com/stash/core/common/extensions/StringExt.kt`
- Create: `core/common/src/main/kotlin/com/stash/core/common/extensions/LongExt.kt`
- Create: `core/common/src/main/kotlin/com/stash/core/common/constants/StashConstants.kt`
- Create: `core/common/src/main/kotlin/com/stash/core/common/result/StashResult.kt`

- [ ] **Step 1: Create `core/common/build.gradle.kts`**

```kotlin
// core/common/build.gradle.kts
plugins {
    id("stash.android.library")
}

android {
    namespace = "com.stash.core.common"
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
}
```

- [ ] **Step 2: Create `core/common/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
```

- [ ] **Step 3: Create `StringExt.kt`**

```kotlin
// core/common/src/main/kotlin/com/stash/core/common/extensions/StringExt.kt
package com.stash.core.common.extensions

import java.text.Normalizer
import java.util.Locale

/**
 * Normalizes a string for canonical comparison:
 * - Lowercase
 * - Remove diacritics
 * - Strip parentheticals and brackets (e.g., "(feat. XYZ)", "[Remix]")
 * - Remove "feat.", "ft.", "featuring"
 * - Trim and collapse whitespace
 */
fun String.toCanonical(): String {
    var result = this.lowercase(Locale.ENGLISH)
    // Remove diacritics
    result = Normalizer.normalize(result, Normalizer.Form.NFD)
        .replace(Regex("[\\p{InCombiningDiacriticalMarks}]"), "")
    // Remove parentheticals and brackets
    result = result.replace(Regex("\\([^)]*\\)"), "")
    result = result.replace(Regex("\\[[^]]*]"), "")
    // Remove feat./ft./featuring
    result = result.replace(Regex("\\b(feat\\.?|ft\\.?|featuring)\\b"), "")
    // Collapse whitespace
    result = result.replace(Regex("\\s+"), " ").trim()
    return result
}

/**
 * Converts a string to a filesystem-safe slug.
 */
fun String.toSlug(): String {
    var result = this.toCanonical()
    result = result.replace(Regex("[^a-z0-9\\s-]"), "")
    result = result.replace(Regex("\\s+"), "-")
    result = result.trim('-')
    return result.take(80)
}
```

- [ ] **Step 4: Create `LongExt.kt`**

```kotlin
// core/common/src/main/kotlin/com/stash/core/common/extensions/LongExt.kt
package com.stash.core.common.extensions

import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Formats a duration in milliseconds to "M:SS" or "H:MM:SS".
 */
fun Long.formatDuration(): String {
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(this)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

/**
 * Formats bytes to a human-readable string (e.g., "1.2 GB").
 */
fun Long.formatFileSize(): String {
    if (this < 1024) return "$this B"
    val units = arrayOf("KB", "MB", "GB")
    var value = this.toDouble()
    var unitIndex = -1
    do {
        value /= 1024.0
        unitIndex++
    } while (value >= 1024 && unitIndex < units.lastIndex)
    return String.format(Locale.US, "%.1f %s", value, units[unitIndex])
}

/**
 * Formats a timestamp to a relative time string (e.g., "2 hours ago").
 */
fun Long.toRelativeTimeString(): String {
    val now = System.currentTimeMillis()
    val diff = now - this
    return when {
        diff < TimeUnit.MINUTES.toMillis(1) -> "Just now"
        diff < TimeUnit.HOURS.toMillis(1) -> {
            val mins = TimeUnit.MILLISECONDS.toMinutes(diff)
            "$mins min${if (mins != 1L) "s" else ""} ago"
        }
        diff < TimeUnit.DAYS.toMillis(1) -> {
            val hours = TimeUnit.MILLISECONDS.toHours(diff)
            "$hours hour${if (hours != 1L) "s" else ""} ago"
        }
        diff < TimeUnit.DAYS.toMillis(7) -> {
            val days = TimeUnit.MILLISECONDS.toDays(diff)
            "$days day${if (days != 1L) "s" else ""} ago"
        }
        else -> {
            val days = TimeUnit.MILLISECONDS.toDays(diff)
            val weeks = days / 7
            "$weeks week${if (weeks != 1L) "s" else ""} ago"
        }
    }
}
```

- [ ] **Step 5: Create `StashConstants.kt`**

```kotlin
// core/common/src/main/kotlin/com/stash/core/common/constants/StashConstants.kt
package com.stash.core.common.constants

object StashConstants {
    const val MAX_CONCURRENT_DOWNLOADS = 3
    const val SPOTIFY_API_DELAY_MS = 100L
    const val TOKEN_REFRESH_BUFFER_MINUTES = 5
    const val POSITION_UPDATE_INTERVAL_MS = 200L
    const val STAGGER_ANIMATION_DELAY_MS = 50L
    const val MAX_STAGGER_ITEMS = 10
    const val ALBUM_ART_TARGET_SIZE = 500
    const val ALBUM_ART_PALETTE_SIZE = 128
    const val LOW_DISK_THRESHOLD_BYTES = 100L * 1024 * 1024 // 100 MB
}
```

- [ ] **Step 6: Create `StashResult.kt`**

```kotlin
// core/common/src/main/kotlin/com/stash/core/common/result/StashResult.kt
package com.stash.core.common.result

sealed interface StashResult<out T> {
    data class Success<T>(val data: T) : StashResult<T>
    data class Error(val exception: Throwable, val message: String? = null) : StashResult<Nothing>
    data object Loading : StashResult<Nothing>
}

inline fun <T, R> StashResult<T>.map(transform: (T) -> R): StashResult<R> = when (this) {
    is StashResult.Success -> StashResult.Success(transform(data))
    is StashResult.Error -> this
    is StashResult.Loading -> this
}

inline fun <T> StashResult<T>.onSuccess(action: (T) -> Unit): StashResult<T> {
    if (this is StashResult.Success) action(data)
    return this
}

inline fun <T> StashResult<T>.onError(action: (Throwable, String?) -> Unit): StashResult<T> {
    if (this is StashResult.Error) action(exception, message)
    return this
}
```

- [ ] **Step 7: Commit**

```
git add core/common/
git commit -m "feat: add :core:common module with string/long extensions, constants, and StashResult wrapper"
```

---

### Task 5: Create :core:ui Module with Theme (Colors, Typography, Shapes, Extended Colors)

**Files:**
- Create: `core/ui/build.gradle.kts`
- Create: `core/ui/src/main/AndroidManifest.xml`
- Create: `core/ui/src/main/kotlin/com/stash/core/ui/theme/Color.kt`
- Create: `core/ui/src/main/kotlin/com/stash/core/ui/theme/Type.kt`
- Create: `core/ui/src/main/kotlin/com/stash/core/ui/theme/Shape.kt`
- Create: `core/ui/src/main/kotlin/com/stash/core/ui/theme/Theme.kt`
- Create: `core/ui/src/main/res/font/space_grotesk_bold.ttf` (placeholder -- must be replaced with actual font file)
- Create: `core/ui/src/main/res/font/space_grotesk_semibold.ttf`
- Create: `core/ui/src/main/res/font/inter_regular.ttf`
- Create: `core/ui/src/main/res/font/inter_medium.ttf`
- Create: `core/ui/src/main/res/font/inter_semibold.ttf`

- [ ] **Step 1: Create `core/ui/build.gradle.kts`**

```kotlin
// core/ui/build.gradle.kts
plugins {
    id("stash.android.library")
    id("stash.compose.library")
}

android {
    namespace = "com.stash.core.ui"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(libs.core.ktx)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.animation)
    implementation(libs.coil.compose)
}
```

- [ ] **Step 2: Create `core/ui/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
```

- [ ] **Step 3: Create `Color.kt`**

```kotlin
// core/ui/src/main/kotlin/com/stash/core/ui/theme/Color.kt
package com.stash.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Core palette
val StashBackground = Color(0xFF06060C)
val StashSurface = Color(0xFF0D0D18)
val StashElevatedSurface = Color(0xFF1A1A2E)

val StashPurple = Color(0xFF8B5CF6)
val StashPurpleLight = Color(0xFFA78BFA)
val StashPurpleDark = Color(0xFF7C3AED)

val StashCyan = Color(0xFF06B6D4)
val StashCyanLight = Color(0xFF22D3EE)
val StashCyanDark = Color(0xFF0891B2)

val StashSpotifyGreen = Color(0xFF1DB954)
val StashYouTubeRed = Color(0xFFFF0033)

val StashTextPrimary = Color(0xFFE8E8F0)
val StashTextSecondary = Color(0xFFA0A0B8)
val StashTextTertiary = Color(0xFF606078)

val StashGlassBackground = Color(0x0AFFFFFF) // ~4% white
val StashGlassBackgroundHover = Color(0x14FFFFFF) // ~8% white
val StashGlassBorder = Color(0x0FFFFFFF) // ~6% white
val StashGlassBorderBright = Color(0x24FFFFFF) // ~14% white

val StashError = Color(0xFFEF4444)
val StashWarning = Color(0xFFF59E0B)
val StashSuccess = Color(0xFF10B981)

/**
 * Extended color tokens not covered by Material3's ColorScheme.
 */
@Immutable
data class StashExtendedColors(
    val spotifyGreen: Color = StashSpotifyGreen,
    val youtubeRed: Color = StashYouTubeRed,
    val cyan: Color = StashCyan,
    val cyanLight: Color = StashCyanLight,
    val cyanDark: Color = StashCyanDark,
    val purpleLight: Color = StashPurpleLight,
    val purpleDark: Color = StashPurpleDark,
    val elevatedSurface: Color = StashElevatedSurface,
    val glassBackground: Color = StashGlassBackground,
    val glassBackgroundHover: Color = StashGlassBackgroundHover,
    val glassBorder: Color = StashGlassBorder,
    val glassBorderBright: Color = StashGlassBorderBright,
    val textTertiary: Color = StashTextTertiary,
    val warning: Color = StashWarning,
    val success: Color = StashSuccess,
)

val LocalStashColors = staticCompositionLocalOf { StashExtendedColors() }
```

- [ ] **Step 4: Create `Type.kt`**

Note: Font TTF files must be placed in `core/ui/src/main/res/font/`. Download Space Grotesk (Bold, SemiBold) and Inter (Regular, Medium, SemiBold) from Google Fonts and place them there. The filenames must be lowercase with underscores.

```kotlin
// core/ui/src/main/kotlin/com/stash/core/ui/theme/Type.kt
package com.stash.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.stash.core.ui.R

val SpaceGrotesk = FontFamily(
    Font(R.font.space_grotesk_semibold, FontWeight.SemiBold),
    Font(R.font.space_grotesk_bold, FontWeight.Bold),
)

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

val StashTypography = Typography(
    // Display — Space Grotesk Bold
    displayLarge = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.Bold,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.Bold,
        fontSize = 45.sp,
        lineHeight = 52.sp,
    ),
    displaySmall = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
    ),

    // Headline — Space Grotesk SemiBold
    headlineLarge = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
    ),

    // Title — Space Grotesk SemiBold
    titleLarge = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),

    // Body — Inter
    bodyLarge = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),

    // Label — Inter Medium/SemiBold
    labelLarge = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
)
```

- [ ] **Step 5: Create `Shape.kt`**

```kotlin
// core/ui/src/main/kotlin/com/stash/core/ui/theme/Shape.kt
package com.stash.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val StashShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)
```

- [ ] **Step 6: Create `Theme.kt`**

```kotlin
// core/ui/src/main/kotlin/com/stash/core/ui/theme/Theme.kt
package com.stash.core.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val StashDarkColorScheme = darkColorScheme(
    primary = StashPurple,
    onPrimary = Color.White,
    primaryContainer = StashPurpleDark,
    onPrimaryContainer = StashPurpleLight,

    secondary = StashCyan,
    onSecondary = Color.Black,
    secondaryContainer = StashCyanDark,
    onSecondaryContainer = StashCyanLight,

    tertiary = StashCyan,
    onTertiary = Color.Black,

    background = StashBackground,
    onBackground = StashTextPrimary,

    surface = StashSurface,
    onSurface = StashTextPrimary,
    surfaceVariant = StashElevatedSurface,
    onSurfaceVariant = StashTextSecondary,

    error = StashError,
    onError = Color.White,

    outline = StashGlassBorder,
    outlineVariant = StashGlassBorderBright,
)

@Composable
fun StashTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Make status bar and nav bar transparent for edge-to-edge
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = false
            insetsController.isAppearanceLightNavigationBars = false
        }
    }

    CompositionLocalProvider(
        LocalStashColors provides StashExtendedColors(),
    ) {
        MaterialTheme(
            colorScheme = StashDarkColorScheme,
            typography = StashTypography,
            shapes = StashShapes,
            content = content,
        )
    }
}

/**
 * Convenience accessor for extended colors from anywhere in the Compose tree.
 * Usage: `StashTheme.extendedColors.spotifyGreen`
 */
object StashTheme {
    val extendedColors: StashExtendedColors
        @Composable
        get() = LocalStashColors.current
}
```

- [ ] **Step 7: Download font files**

```bash
# Download Space Grotesk and Inter from Google Fonts
# Place them in core/ui/src/main/res/font/
mkdir -p core/ui/src/main/res/font

# Space Grotesk - download from https://fonts.google.com/specimen/Space+Grotesk
# Rename to: space_grotesk_bold.ttf, space_grotesk_semibold.ttf

# Inter - download from https://fonts.google.com/specimen/Inter
# Rename to: inter_regular.ttf, inter_medium.ttf, inter_semibold.ttf
```

Note: Font files must be real TTF files. They cannot be empty placeholders. Download the actual .ttf files from Google Fonts and place them with the exact filenames listed above.

- [ ] **Step 8: Commit**

```
git add core/ui/
git commit -m "feat: add :core:ui module with StashTheme (dark color scheme, extended colors, typography, shapes)"
```

---

### Task 6: Create Shared Composables (GlassCard, SourceIndicator, SectionHeader, MiniPlayerBar)

**Files:**
- Create: `core/ui/src/main/kotlin/com/stash/core/ui/components/GlassCard.kt`
- Create: `core/ui/src/main/kotlin/com/stash/core/ui/components/SourceIndicator.kt`
- Create: `core/ui/src/main/kotlin/com/stash/core/ui/components/SectionHeader.kt`
- Create: `core/ui/src/main/kotlin/com/stash/core/ui/components/MiniPlayerBar.kt`

- [ ] **Step 1: Create `GlassCard.kt`**

```kotlin
// core/ui/src/main/kotlin/com/stash/core/ui/components/GlassCard.kt
package com.stash.core.ui.components

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stash.core.ui.theme.StashTheme

/**
 * A glassmorphic card with semi-transparent background and subtle border.
 * On API 31+, applies a blur effect for true glassmorphism.
 * On older APIs, uses a solid semi-transparent surface that still looks great on dark backgrounds.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    blurRadius: Dp = 24.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val extendedColors = StashTheme.extendedColors

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .then(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Modifier.graphicsLayer {
                        renderEffect = RenderEffect
                            .createBlurEffect(
                                blurRadius.toPx(),
                                blurRadius.toPx(),
                                android.graphics.Shader.TileMode.CLAMP,
                            )
                            .asComposeRenderEffect()
                    }
                } else {
                    Modifier
                }
            ),
        color = extendedColors.glassBackground,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, extendedColors.glassBorder),
    ) {
        Box(
            modifier = Modifier.padding(16.dp),
            content = content,
        )
    }
}
```

- [ ] **Step 2: Create `SourceIndicator.kt`**

```kotlin
// core/ui/src/main/kotlin/com/stash/core/ui/components/SourceIndicator.kt
package com.stash.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stash.core.model.MusicSource
import com.stash.core.ui.theme.StashTheme

/**
 * A small colored dot indicating the source of a track or playlist.
 * - SPOTIFY: green dot
 * - YOUTUBE: red dot
 * - BOTH: green + red dots side by side
 */
@Composable
fun SourceIndicator(
    source: MusicSource,
    modifier: Modifier = Modifier,
    size: Dp = 6.dp,
) {
    val extendedColors = StashTheme.extendedColors

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (source == MusicSource.SPOTIFY || source == MusicSource.BOTH) {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(extendedColors.spotifyGreen)
            )
        }
        if (source == MusicSource.YOUTUBE || source == MusicSource.BOTH) {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(extendedColors.youtubeRed)
            )
        }
    }
}
```

- [ ] **Step 3: Create `SectionHeader.kt`**

```kotlin
// core/ui/src/main/kotlin/com/stash/core/ui/components/SectionHeader.kt
package com.stash.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A section header with title and optional "See All" action.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onActionClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )

        if (actionText != null && onActionClick != null) {
            Text(
                text = actionText,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(onClick = onActionClick)
                    .padding(4.dp),
            )
        }
    }
}
```

- [ ] **Step 4: Create `MiniPlayerBar.kt` (stub)**

```kotlin
// core/ui/src/main/kotlin/com/stash/core/ui/components/MiniPlayerBar.kt
package com.stash.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stash.core.model.PlayerState
import com.stash.core.ui.theme.StashTheme

/**
 * Persistent mini player bar shown between content and bottom navigation.
 * Displays current track info, play/pause, and skip-next controls.
 * Tapping the bar opens the full Now Playing screen.
 *
 * This is a UI stub -- state will be wired in Phase 3 (media integration).
 */
@Composable
fun MiniPlayerBar(
    playerState: PlayerState,
    onPlayPauseClick: () -> Unit,
    onSkipNextClick: () -> Unit,
    onBarClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extendedColors = StashTheme.extendedColors
    val track = playerState.currentTrack ?: return

    val progress = if (playerState.durationMs > 0) {
        playerState.positionMs.toFloat() / playerState.durationMs.toFloat()
    } else {
        0f
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onBarClick),
        color = extendedColors.glassBackground,
        border = BorderStroke(1.dp, extendedColors.glassBorder),
        shape = MaterialTheme.shapes.small,
    ) {
        Column {
            // Progress indicator at top
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = extendedColors.glassBackground,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Album art placeholder
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Track info
                Column(
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Play/Pause
                IconButton(onClick = onPlayPauseClick) {
                    Icon(
                        imageVector = if (playerState.isPlaying) {
                            Icons.Default.Pause
                        } else {
                            Icons.Default.PlayArrow
                        },
                        contentDescription = if (playerState.isPlaying) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(28.dp),
                    )
                }

                // Skip Next
                IconButton(onClick = onSkipNextClick) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Skip to next",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 5: Commit**

```
git add core/ui/src/main/kotlin/com/stash/core/ui/components/
git commit -m "feat: add shared composables (GlassCard, SourceIndicator, SectionHeader, MiniPlayerBar stub)"
```

---

### Task 7: Create :app Module with Hilt Setup, MainActivity, and Navigation

**Files:**
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/kotlin/com/stash/app/StashApplication.kt`
- Create: `app/src/main/kotlin/com/stash/app/MainActivity.kt`
- Create: `app/src/main/kotlin/com/stash/app/navigation/StashNavHost.kt`
- Create: `app/src/main/kotlin/com/stash/app/navigation/TopLevelDestination.kt`
- Create: `app/src/main/kotlin/com/stash/app/navigation/StashScaffold.kt`
- Create: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/res/values/themes.xml`
- Create: `app/proguard-rules.pro`

- [ ] **Step 1: Create `app/build.gradle.kts`**

```kotlin
// app/build.gradle.kts
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.stash.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.stash.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:ui"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":feature:home"))
    implementation(project(":feature:library"))
    implementation(project(":feature:nowplaying"))
    implementation(project(":feature:sync"))
    implementation(project(":feature:settings"))

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.animation)
    debugImplementation(libs.compose.ui.tooling)

    // AndroidX
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)

    // Navigation
    implementation(libs.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.hilt.navigation.compose)
}
```

- [ ] **Step 2: Create `app/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

    <application
        android:name=".StashApplication"
        android:allowBackup="true"
        android:extractNativeLibs="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.Stash">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:theme="@style/Theme.Stash">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

    </application>

</manifest>
```

- [ ] **Step 3: Create `StashApplication.kt`**

```kotlin
// app/src/main/kotlin/com/stash/app/StashApplication.kt
package com.stash.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class StashApplication : Application()
```

- [ ] **Step 4: Create `MainActivity.kt`**

```kotlin
// app/src/main/kotlin/com/stash/app/MainActivity.kt
package com.stash.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.stash.app.navigation.StashScaffold
import com.stash.core.ui.theme.StashTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            StashTheme {
                StashScaffold()
            }
        }
    }
}
```

- [ ] **Step 5: Create `TopLevelDestination.kt`**

```kotlin
// app/src/main/kotlin/com/stash/app/navigation/TopLevelDestination.kt
package com.stash.app.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable

enum class TopLevelDestination(
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val label: String,
    val route: Any,
) {
    HOME(
        selectedIcon = Icons.Filled.Home,
        unselectedIcon = Icons.Outlined.Home,
        label = "Home",
        route = HomeRoute,
    ),
    LIBRARY(
        selectedIcon = Icons.Filled.LibraryMusic,
        unselectedIcon = Icons.Outlined.LibraryMusic,
        label = "Library",
        route = LibraryRoute,
    ),
    SYNC(
        selectedIcon = Icons.Filled.Sync,
        unselectedIcon = Icons.Outlined.Sync,
        label = "Sync",
        route = SyncRoute,
    ),
    SETTINGS(
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings,
        label = "Settings",
        route = SettingsRoute,
    ),
}

@Serializable data object HomeRoute
@Serializable data object LibraryRoute
@Serializable data object SyncRoute
@Serializable data object SettingsRoute
@Serializable data object NowPlayingRoute
```

- [ ] **Step 6: Create `StashNavHost.kt`**

```kotlin
// app/src/main/kotlin/com/stash/app/navigation/StashNavHost.kt
package com.stash.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.stash.feature.home.HomeScreen
import com.stash.feature.library.LibraryScreen
import com.stash.feature.settings.SettingsScreen
import com.stash.feature.sync.SyncScreen

@Composable
fun StashNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = HomeRoute,
        modifier = modifier,
    ) {
        composable<HomeRoute> {
            HomeScreen()
        }
        composable<LibraryRoute> {
            LibraryScreen()
        }
        composable<SyncRoute> {
            SyncScreen()
        }
        composable<SettingsRoute> {
            SettingsScreen()
        }
    }
}
```

- [ ] **Step 7: Create `StashScaffold.kt`**

```kotlin
// app/src/main/kotlin/com/stash/app/navigation/StashScaffold.kt
package com.stash.app.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.stash.core.model.PlayerState
import com.stash.core.model.Track
import com.stash.core.ui.components.MiniPlayerBar
import com.stash.core.ui.theme.StashTheme

@Composable
fun StashScaffold() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Stub player state -- will be replaced with real state in Phase 3
    var stubPlayerState by remember {
        mutableStateOf(
            PlayerState(
                currentTrack = Track(
                    title = "Welcome to Stash",
                    artist = "Set up sync to get started",
                ),
            )
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0.dp),
        bottomBar = {
            Column {
                // Mini Player sits above the bottom nav
                MiniPlayerBar(
                    playerState = stubPlayerState,
                    onPlayPauseClick = {
                        stubPlayerState = stubPlayerState.copy(
                            isPlaying = !stubPlayerState.isPlaying
                        )
                    },
                    onSkipNextClick = { /* stub */ },
                    onBarClick = { /* TODO: navigate to NowPlayingRoute */ },
                )

                // Bottom Navigation
                StashBottomBar(
                    currentRoute = currentRoute,
                    onNavigate = { destination ->
                        navController.navigate(destination.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        StashNavHost(
            navController = navController,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        )
    }
}

@Composable
private fun StashBottomBar(
    currentRoute: String?,
    onNavigate: (TopLevelDestination) -> Unit,
) {
    val extendedColors = StashTheme.extendedColors

    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
    ) {
        TopLevelDestination.entries.forEach { destination ->
            val isSelected = currentRoute == destination.route::class.qualifiedName

            NavigationBarItem(
                selected = isSelected,
                onClick = { onNavigate(destination) },
                icon = {
                    Icon(
                        imageVector = if (isSelected) {
                            destination.selectedIcon
                        } else {
                            destination.unselectedIcon
                        },
                        contentDescription = destination.label,
                    )
                },
                label = {
                    Text(
                        text = destination.label,
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = extendedColors.textTertiary,
                    unselectedTextColor = extendedColors.textTertiary,
                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                ),
            )
        }
    }
}
```

- [ ] **Step 8: Create `app/src/main/res/values/strings.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Stash</string>
</resources>
```

- [ ] **Step 9: Create `app/src/main/res/values/themes.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.Stash" parent="android:Theme.Material.NoActionBar">
        <item name="android:windowBackground">@android:color/black</item>
        <item name="android:statusBarColor">@android:color/transparent</item>
        <item name="android:navigationBarColor">@android:color/transparent</item>
        <item name="android:windowDrawsSystemBarBackgrounds">true</item>
        <item name="android:windowLightStatusBar">false</item>
    </style>
</resources>
```

- [ ] **Step 10: Create `app/proguard-rules.pro`**

```proguard
# Stash ProGuard Rules

# Keep Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.stash.**$$serializer { *; }
-keepclassmembers class com.stash.** {
    *** Companion;
}
-keepclasseswithmembers class com.stash.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep Hilt generated classes
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# Keep Room entities
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Coil
-dontwarn coil3.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# yt-dlp / youtubedl-android (keep native libs)
-keep class com.yausername.youtubedl_android.** { *; }
```

- [ ] **Step 11: Commit**

```
git add app/
git commit -m "feat: add :app module with Hilt, MainActivity, Scaffold with bottom nav and mini player"
```

---

### Task 8: Create Empty Feature Module Shells

**Files:**
- Create: `feature/home/build.gradle.kts`
- Create: `feature/home/src/main/AndroidManifest.xml`
- Create: `feature/home/src/main/kotlin/com/stash/feature/home/HomeScreen.kt`
- Create: `feature/library/build.gradle.kts`
- Create: `feature/library/src/main/AndroidManifest.xml`
- Create: `feature/library/src/main/kotlin/com/stash/feature/library/LibraryScreen.kt`
- Create: `feature/nowplaying/build.gradle.kts`
- Create: `feature/nowplaying/src/main/AndroidManifest.xml`
- Create: `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingScreen.kt`
- Create: `feature/sync/build.gradle.kts`
- Create: `feature/sync/src/main/AndroidManifest.xml`
- Create: `feature/sync/src/main/kotlin/com/stash/feature/sync/SyncScreen.kt`
- Create: `feature/settings/build.gradle.kts`
- Create: `feature/settings/src/main/AndroidManifest.xml`
- Create: `feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsScreen.kt`

- [ ] **Step 1: Create `feature/home/build.gradle.kts`**

```kotlin
// feature/home/build.gradle.kts
plugins {
    id("stash.android.feature")
}

android {
    namespace = "com.stash.feature.home"
}
```

- [ ] **Step 2: Create `feature/home/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
```

- [ ] **Step 3: Create `HomeScreen.kt`**

```kotlin
// feature/home/src/main/kotlin/com/stash/feature/home/HomeScreen.kt
package com.stash.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stash.core.ui.components.GlassCard
import com.stash.core.ui.components.SectionHeader

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        Text(
            text = "Stash",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
        )

        SectionHeader(title = "Sync Status")

        GlassCard(
            modifier = Modifier.padding(horizontal = 4.dp),
        ) {
            Column(
                modifier = Modifier.padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "No sources connected",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Connect Spotify or YouTube Music to start syncing",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        SectionHeader(title = "Daily Mixes", actionText = "See All", onActionClick = {})

        // Placeholder for daily mix carousel
        Column(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Your playlists will appear here",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
```

- [ ] **Step 4: Create `feature/library/build.gradle.kts`**

```kotlin
// feature/library/build.gradle.kts
plugins {
    id("stash.android.feature")
}

android {
    namespace = "com.stash.feature.library"
}
```

- [ ] **Step 5: Create `feature/library/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
```

- [ ] **Step 6: Create `LibraryScreen.kt`**

```kotlin
// feature/library/src/main/kotlin/com/stash/feature/library/LibraryScreen.kt
package com.stash.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun LibraryScreen(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        Text(
            text = "Library",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
        )

        // Placeholder for search bar and content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Your library is empty",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Sync your playlists to build your offline library",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
```

- [ ] **Step 7: Create `feature/nowplaying/build.gradle.kts`**

```kotlin
// feature/nowplaying/build.gradle.kts
plugins {
    id("stash.android.feature")
}

android {
    namespace = "com.stash.feature.nowplaying"
}
```

- [ ] **Step 8: Create `feature/nowplaying/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
```

- [ ] **Step 9: Create `NowPlayingScreen.kt`**

```kotlin
// feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingScreen.kt
package com.stash.feature.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun NowPlayingScreen(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Now Playing",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "Full player UI coming in Phase 3",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
```

- [ ] **Step 10: Create `feature/sync/build.gradle.kts`**

```kotlin
// feature/sync/build.gradle.kts
plugins {
    id("stash.android.feature")
}

android {
    namespace = "com.stash.feature.sync"
}
```

- [ ] **Step 11: Create `feature/sync/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
```

- [ ] **Step 12: Create `SyncScreen.kt`**

```kotlin
// feature/sync/src/main/kotlin/com/stash/feature/sync/SyncScreen.kt
package com.stash.feature.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stash.core.ui.components.GlassCard

@Composable
fun SyncScreen(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        Text(
            text = "Sync",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 16.dp, bottom = 16.dp),
        )

        GlassCard {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Connected Sources",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "No sources connected yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Placeholder for sync controls
        Column(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Connect a source to enable sync",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
```

- [ ] **Step 13: Create `feature/settings/build.gradle.kts`**

```kotlin
// feature/settings/build.gradle.kts
plugins {
    id("stash.android.feature")
}

android {
    namespace = "com.stash.feature.settings"
}
```

- [ ] **Step 14: Create `feature/settings/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
```

- [ ] **Step 15: Create `SettingsScreen.kt`**

```kotlin
// feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsScreen.kt
package com.stash.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stash.core.ui.components.GlassCard

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 16.dp, bottom = 16.dp),
        )

        GlassCard {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Audio Quality",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Best (160 kbps Opus)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Placeholder for more settings
        Column(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "More settings coming soon",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
```

- [ ] **Step 16: Commit**

```
git add feature/
git commit -m "feat: add feature module shells (home, library, nowplaying, sync, settings) with placeholder screens"
```

---

### Task 9: Create Remaining Core Module Stubs and Configure .gitignore and Edge-to-Edge

**Files:**
- Create: `core/data/build.gradle.kts`
- Create: `core/data/src/main/AndroidManifest.xml`
- Create: `core/network/build.gradle.kts`
- Create: `core/network/src/main/AndroidManifest.xml`
- Create: `core/auth/build.gradle.kts`
- Create: `core/auth/src/main/AndroidManifest.xml`
- Create: `core/media/build.gradle.kts`
- Create: `core/media/src/main/AndroidManifest.xml`
- Create: `data/spotify/build.gradle.kts`
- Create: `data/spotify/src/main/AndroidManifest.xml`
- Create: `data/ytmusic/build.gradle.kts`
- Create: `data/ytmusic/src/main/AndroidManifest.xml`
- Create: `data/download/build.gradle.kts`
- Create: `data/download/src/main/AndroidManifest.xml`
- Create: `.gitignore`

- [ ] **Step 1: Create `core/data/build.gradle.kts`**

```kotlin
// core/data/build.gradle.kts
plugins {
    id("stash.android.library")
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.stash.core.data"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
}
```

- [ ] **Step 2: Create `core/data/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
```

- [ ] **Step 3: Create `core/network/build.gradle.kts`**

```kotlin
// core/network/build.gradle.kts
plugins {
    id("stash.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.stash.core.network"
}

dependencies {
    implementation(project(":core:common"))
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
}
```

- [ ] **Step 4: Create `core/network/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
```

- [ ] **Step 5: Create `core/auth/build.gradle.kts`**

```kotlin
// core/auth/build.gradle.kts
plugins {
    id("stash.android.library")
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.stash.core.auth"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:network"))
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.appauth)
    implementation(libs.tink.android)
    implementation(libs.kotlinx.coroutines.android)
}
```

- [ ] **Step 6: Create `core/auth/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
```

- [ ] **Step 7: Create `core/media/build.gradle.kts`**

```kotlin
// core/media/build.gradle.kts
plugins {
    id("stash.android.library")
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.stash.core.media"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.kotlinx.coroutines.android)
}
```

- [ ] **Step 8: Create `core/media/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
```

- [ ] **Step 9: Create `data/spotify/build.gradle.kts`**

```kotlin
// data/spotify/build.gradle.kts
plugins {
    id("stash.android.library")
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.stash.data.spotify"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:network"))
    implementation(project(":core:auth"))
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
}
```

- [ ] **Step 10: Create `data/spotify/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
```

- [ ] **Step 11: Create `data/ytmusic/build.gradle.kts`**

```kotlin
// data/ytmusic/build.gradle.kts
plugins {
    id("stash.android.library")
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.stash.data.ytmusic"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:network"))
    implementation(project(":core:auth"))
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
}
```

- [ ] **Step 12: Create `data/ytmusic/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
```

- [ ] **Step 13: Create `data/download/build.gradle.kts`**

```kotlin
// data/download/build.gradle.kts
plugins {
    id("stash.android.library")
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.stash.data.download"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.youtubedl.android)
    implementation(libs.youtubedl.ffmpeg)
    implementation(libs.kotlinx.coroutines.android)
}
```

- [ ] **Step 14: Create `data/download/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
```

- [ ] **Step 15: Create root `.gitignore`**

```gitignore
# Built application files
*.apk
*.aar
*.ap_
*.aab

# Files for the ART/Dalvik VM
*.dex

# Java class files
*.class

# Generated files
bin/
gen/
out/

# Gradle files
.gradle/
build/
**/build/

# Local configuration file (sdk path, etc)
local.properties

# Android Studio
*.iml
.idea/
.idea/workspace.xml
.idea/tasks.xml
.idea/gradle.xml
.idea/assetWizardSettings.xml
.idea/dictionaries
.idea/libraries
.idea/caches

# Keystore files
*.jks
*.keystore

# External native build folder generated in Android Studio 2.2 and later
.externalNativeBuild
.cxx/

# Google Services (API keys)
google-services.json

# Secrets
*.env
secrets.properties

# OS generated files
.DS_Store
.DS_Store?
._*
.Spotlight-V100
.Trashes
ehthumbs.db
Thumbs.db

# Kotlin
*.kotlin_module

# Captures (Android Studio)
captures/
```

- [ ] **Step 16: Commit**

```
git add core/data/ core/network/ core/auth/ core/media/ data/ .gitignore
git commit -m "feat: add remaining core/data module stubs (data, network, auth, media, spotify, ytmusic, download) and .gitignore"
```

---

### Task 10: Build Verification and Final Commit

**Files:**
- Modify: None (verification only)

- [ ] **Step 1: Verify project structure is correct**

Run from the project root:

```bash
find . -name "build.gradle.kts" -not -path "./.gradle/*" | sort
```

Expected output:
```
./app/build.gradle.kts
./build.gradle.kts
./build-logic/convention/build.gradle.kts
./core/auth/build.gradle.kts
./core/common/build.gradle.kts
./core/data/build.gradle.kts
./core/media/build.gradle.kts
./core/model/build.gradle.kts
./core/network/build.gradle.kts
./core/ui/build.gradle.kts
./data/download/build.gradle.kts
./data/spotify/build.gradle.kts
./data/ytmusic/build.gradle.kts
./feature/home/build.gradle.kts
./feature/library/build.gradle.kts
./feature/nowplaying/build.gradle.kts
./feature/settings/build.gradle.kts
./feature/sync/build.gradle.kts
```

- [ ] **Step 2: Verify font files exist**

```bash
ls -la core/ui/src/main/res/font/
```

Expected output should list 5 `.ttf` files:
```
inter_medium.ttf
inter_regular.ttf
inter_semibold.ttf
space_grotesk_bold.ttf
space_grotesk_semibold.ttf
```

If missing, download them from Google Fonts before proceeding.

- [ ] **Step 3: Run Gradle sync and build**

```bash
./gradlew assembleDebug 2>&1 | tail -20
```

Expected output ends with:
```
BUILD SUCCESSFUL
```

If there are errors, fix them before proceeding. Common issues:
- Missing font files: Download from Google Fonts
- Kotlin version mismatch: Check `libs.versions.toml`
- Missing `consumer-rules.pro`: Create empty files in each library module

- [ ] **Step 4: Create empty `consumer-rules.pro` in each library module (if build fails)**

Each library module referenced in `AndroidLibraryConventionPlugin` expects a `consumer-rules.pro`. Create an empty file in each:

```bash
for dir in core/model core/common core/ui core/data core/network core/auth core/media data/spotify data/ytmusic data/download feature/home feature/library feature/nowplaying feature/sync feature/settings; do
    touch "$dir/consumer-rules.pro"
done
```

- [ ] **Step 5: Run the app on an emulator or device**

```bash
./gradlew installDebug
adb shell am start -n com.stash.app.debug/com.stash.app.MainActivity
```

Expected: The app launches with a dark background, bottom navigation bar with 4 tabs (Home, Library, Sync, Settings), a mini player bar above the nav bar, and each tab shows placeholder content with the correct StashTheme styling.

- [ ] **Step 6: Final commit with all remaining files**

```bash
git add -A
git status
git commit -m "chore: Phase 1 complete - project scaffolding, theme, navigation shell, and all module stubs"
```

---

## Summary

After completing all 10 tasks, the project will have:

- **16 Gradle modules** organized into `:app`, `:core:*`, `:data:*`, and `:feature:*`
- **Convention plugins** (`build-logic/`) eliminating boilerplate across modules
- **Version catalog** (`libs.versions.toml`) centralizing all dependency versions
- **Complete Compose theme**: `StashDarkColorScheme`, `StashExtendedColors`, `StashTypography` (Space Grotesk + Inter), `StashShapes`
- **4 shared composables**: `GlassCard` (glassmorphism with API 31+ blur), `SourceIndicator`, `SectionHeader`, `MiniPlayerBar`
- **Navigation shell**: `Scaffold` with bottom nav (Home/Library/Sync/Settings) + mini player
- **Edge-to-edge display** with transparent status and navigation bars
- **Hilt DI** set up at the application level
- **`.gitignore`** and **ProGuard rules** configured
- All feature screens rendering placeholder content
```

---

### Critical Files for Implementation

- `C:/Users/theno/Projects/MP3APK/settings.gradle.kts` - Root module declaration defining all 16 modules and repository configuration
- `C:/Users/theno/Projects/MP3APK/gradle/libs.versions.toml` - Version catalog centralizing every dependency version used across all modules
- `C:/Users/theno/Projects/MP3APK/core/ui/src/main/kotlin/com/stash/core/ui/theme/Theme.kt` - StashTheme composable with dark color scheme, extended colors, and edge-to-edge setup
- `C:/Users/theno/Projects/MP3APK/app/src/main/kotlin/com/stash/app/navigation/StashScaffold.kt` - Root scaffold with bottom nav, mini player bar, and NavHost wiring
- `C:/Users/theno/Projects/MP3APK/build-logic/convention/src/main/kotlin/AndroidFeatureConventionPlugin.kt` - Convention plugin that all feature modules use, eliminating build config duplication
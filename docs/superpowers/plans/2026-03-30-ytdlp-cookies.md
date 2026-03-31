# yt-dlp Cookie Authentication — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pass the user's stored YouTube cookies to yt-dlp via `--cookies` flag to bypass YouTube's bot detection.

**Architecture:** New `CookieFileWriter` utility converts a browser cookie string to Netscape format and writes it to a temp file. `DownloadExecutor` retrieves cookies from `TokenManager`, writes the file before each yt-dlp call, passes `--cookies`, and deletes the file in a `finally` block. Unique filenames prevent concurrent download collisions.

**Tech Stack:** Kotlin, Hilt DI, yt-dlp (youtubedl-android), Android `noBackupFilesDir`

**Spec:** `docs/superpowers/specs/2026-03-30-ytdlp-cookies-design.md`

---

## File Structure

### New files
| File | Purpose |
|------|---------|
| `data/download/src/main/kotlin/com/stash/data/download/CookieFileWriter.kt` | Converts browser cookie string → Netscape cookie file |

### Modified files
| File | What changes |
|------|-------------|
| `data/download/build.gradle.kts` | Add `implementation(project(":core:auth"))` |
| `data/download/src/main/kotlin/com/stash/data/download/DownloadExecutor.kt` | Inject `TokenManager`, write/delete cookie file around yt-dlp calls |

---

## Task 1: Add `:core:auth` dependency to `data:download` module

**Files:**
- Modify: `data/download/build.gradle.kts`

- [ ] **Step 1: Add the dependency**

Add `implementation(project(":core:auth"))` to the dependencies block, after the existing `:core:data` line:

```kotlin
dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":core:auth"))    // ADD THIS LINE
    // ... rest unchanged
}
```

- [ ] **Step 2: Build to verify**

Run: `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew :data:download:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add data/download/build.gradle.kts
git commit -m "build: add core:auth dependency to data:download for TokenManager access"
```

---

## Task 2: Create CookieFileWriter utility

**Files:**
- Create: `data/download/src/main/kotlin/com/stash/data/download/CookieFileWriter.kt`

- [ ] **Step 1: Create CookieFileWriter.kt**

```kotlin
package com.stash.data.download

import java.io.File

/**
 * Converts a browser cookie header string into a Netscape HTTP Cookie File
 * that yt-dlp can read via its --cookies flag.
 *
 * Input format:  "SAPISID=abc123; SID=def456; __Secure-3PSID=ghi789"
 * Output format: Netscape HTTP Cookie File (tab-separated fields)
 *
 * Security: callers should write to [Context.noBackupFilesDir] and delete
 * the file in a finally block after use.
 */
object CookieFileWriter {

    private const val HEADER = "# Netscape HTTP Cookie File"
    private const val DOMAIN = ".youtube.com"

    /**
     * Writes the cookie string to [outputFile] in Netscape format.
     *
     * @param cookieString The raw cookie header value (semicolon-separated key=value pairs).
     * @param outputFile   The file to write. Will be created or overwritten.
     */
    fun write(cookieString: String, outputFile: File) {
        outputFile.parentFile?.mkdirs()
        outputFile.bufferedWriter().use { writer ->
            writer.write(HEADER)
            writer.newLine()
            writer.newLine()

            cookieString.split(";").forEach { pair ->
                val trimmed = pair.trim()
                if (trimmed.isEmpty()) return@forEach
                val eqIndex = trimmed.indexOf('=')
                if (eqIndex <= 0) return@forEach

                val name = trimmed.substring(0, eqIndex).trim()
                val value = trimmed.substring(eqIndex + 1).trim()

                // Format: domain \t flag \t path \t secure \t expiry \t name \t value
                writer.write("$DOMAIN\tTRUE\t/\tTRUE\t0\t$name\t$value")
                writer.newLine()
            }
        }
    }
}
```

- [ ] **Step 2: Build to verify**

Run: `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew :data:download:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/CookieFileWriter.kt
git commit -m "feat: add CookieFileWriter to convert browser cookies to Netscape format"
```

---

## Task 3: Integrate cookie file into DownloadExecutor

**Files:**
- Modify: `data/download/src/main/kotlin/com/stash/data/download/DownloadExecutor.kt`

- [ ] **Step 1: Add TokenManager import and constructor parameter**

Add import:
```kotlin
import com.stash.core.auth.TokenManager
```

Change constructor to:
```kotlin
@Singleton
class DownloadExecutor @Inject constructor(
    private val ytDlpManager: YtDlpManager,
    @ApplicationContext private val context: Context,
    private val tokenManager: TokenManager,
) {
```

- [ ] **Step 2: Write cookie file before yt-dlp, delete in finally block**

Replace the entire `download()` method body with:

```kotlin
    suspend fun download(
        url: String,
        outputDir: File,
        filename: String,
        qualityArgs: List<String>,
        onProgress: (Float) -> Unit = {},
    ): File? = withContext(Dispatchers.IO) {
        ytDlpManager.initialize()

        // Write YouTube cookies to a temp file for yt-dlp authentication.
        // Unique filename prevents collisions with concurrent downloads (Semaphore allows 3).
        val cookieFile = File(context.noBackupFilesDir, "yt_cookies_${System.nanoTime()}.txt")

        try {
            val outputTemplate = File(outputDir, "$filename.%(ext)s").absolutePath
            val nativeLibDir = context.applicationInfo.nativeLibraryDir

            val request = YoutubeDLRequest(url).apply {
                qualityArgs.forEach { addOption(it) }
                addOption("-o", outputTemplate)
                addOption("--no-playlist")
                addOption("--ffmpeg-location", nativeLibDir)
            }

            // Add cookies if YouTube is authenticated
            val cookie = tokenManager.getYouTubeCookie()
            if (cookie != null) {
                CookieFileWriter.write(cookie, cookieFile)
                request.addOption("--cookies", cookieFile.absolutePath)
                Log.d("StashDL", "download: using cookies from ${cookieFile.absolutePath}")
            }

            Log.d("StashDL", "download: starting url=$url, output=$outputTemplate, args=$qualityArgs")

            val response = YoutubeDL.getInstance().execute(
                request,
                url,
            ) { progress, _, _ ->
                onProgress((progress / 100f).coerceIn(0f, 1f))
            }

            Log.d("StashDL", "download: yt-dlp exit=${response.exitCode}, stdout=${response.out?.take(500)}, stderr=${response.err?.take(500)}")

            val result = outputDir.listFiles()?.firstOrNull { it.nameWithoutExtension == filename }
            Log.d("StashDL", "download: outputFile=${result?.absolutePath}, exists=${result?.exists()}")
            result
        } catch (e: Exception) {
            Log.e("StashDL", "download: FAILED url=$url", e)
            null
        } finally {
            // Always delete the cookie file — cookies should not persist on disk
            if (cookieFile.exists()) {
                cookieFile.delete()
            }
        }
    }
```

- [ ] **Step 3: Build the full project**

Run: `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Install on device**

Run: `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew installDebug`
Expected: Installed on 1 device.

- [ ] **Step 5: Test on device**

1. Clear logcat: `adb logcat -c`
2. Open app, hit Sync, wait for "downloading X songs"
3. Capture logs: `adb logcat -d | grep "StashDL" | head -20`
4. Verify:
   - Log shows `using cookies from /data/user/0/com.stash.app.debug/no_backup/yt_cookies_XXXX.txt`
   - No more `Sign in to confirm you're not a bot` error
   - `download: yt-dlp exit=0` (success) for at least some tracks
   - `download: outputFile=... exists=true` for downloaded files

- [ ] **Step 6: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/DownloadExecutor.kt
git commit -m "feat: pass YouTube cookies to yt-dlp to bypass bot detection

Retrieves cookies from TokenManager, writes to a unique Netscape-format
temp file in noBackupFilesDir, passes --cookies to yt-dlp, and deletes
the file in a finally block. Fixes 'Sign in to confirm you're not a bot'
error that caused all downloads to fail."
```

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

// ── Release signing configuration ───────────────────────────────────────────
//
// Reads signing credentials from one of two sources, in order of precedence:
//
//  1. `keystore.properties` in the repo root (local development). The file is
//     gitignored and never leaves the developer's machine.
//
//  2. Environment variables (CI / GitHub Actions). The release workflow
//     base64-decodes a secret into a temporary .jks file and exports the
//     passwords as env vars before running `assembleRelease`.
//
// If neither source is available, the release build falls back to the debug
// keystore so the project still builds out-of-the-box for contributors. The
// fallback APK is unusable for distribution but keeps `assembleRelease` working
// during local testing.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

fun signingProp(key: String, envKey: String): String? =
    keystoreProperties.getProperty(key) ?: System.getenv(envKey)

val releaseStoreFilePath = signingProp("storeFile", "STASH_KEYSTORE_FILE")
val releaseStorePassword = signingProp("storePassword", "STASH_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingProp("keyAlias", "STASH_KEY_ALIAS")
val releaseKeyPassword = signingProp("keyPassword", "STASH_KEY_PASSWORD")

val hasReleaseSigning = releaseStoreFilePath != null &&
    releaseStorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null

android {
    namespace = "com.stash.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.stash.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "0.3.4-beta.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // AppAuth redirect scheme removed -- Spotify now uses sp_dc cookie auth
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // R8 minification is DISABLED because youtubedl-android embeds
            // Chaquopy (Python runtime) + Apache Commons Compress + native
            // binaries that use extensive reflection. R8 obfuscation renames
            // classes those libraries look up by string name, causing
            // "class X is not a concrete class" crashes on startup.
            //
            // This is the standard approach for apps in this space: NewPipe,
            // Seal, YTDLnis, InnerTune all ship with minification off. The
            // APK is ~5-10MB larger without minification (negligible next to
            // the ~145MB Python/yt-dlp bundle it already carries), and the
            // code-obfuscation benefit is moot for an open-source GPL-3.0
            // project anyway.
            //
            // Resource shrinking is also off because it requires minification.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Only wire the release signing config if credentials were found.
            // Otherwise release builds fall back to the debug key for local testing.
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        jniLibs {
            // Must extract native libs to disk so ffmpeg/ffprobe executables and
            // libc++_shared.so are in the same directory and can be linked at runtime.
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:ui"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":core:media"))
    implementation(project(":core:network"))
    implementation(project(":feature:home"))
    implementation(project(":feature:library"))
    implementation(project(":feature:nowplaying"))
    implementation(project(":feature:sync"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:search"))
    implementation(project(":data:download"))
    // data:ytmusic provides AlbumSummary, used by SearchScreen/ArtistProfileScreen
    // callback signatures that StashNavHost wires up for Album Discovery.
    implementation(project(":data:ytmusic"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.animation)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.hilt.navigation.compose)
    // Hilt WorkManager integration: provides HiltWorkerFactory for @HiltWorker classes.
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)
    implementation(libs.work.runtime.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)
}

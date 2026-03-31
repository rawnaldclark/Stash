plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
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
        // AppAuth redirect scheme removed -- Spotify now uses sp_dc cookie auth
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
            useLegacyPackaging = true
            // Exclude ffmpeg/ffprobe wrapper executables from APK — they can't find
            // libc++_shared.so from the APK's lib dir. FFmpeg.init() extracts the real
            // binaries (from .zip.so) to the app's data dir where libc++_shared.so lives.
            excludes += setOf("lib/*/libffmpeg.so", "lib/*/libffprobe.so")
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
    implementation(project(":feature:home"))
    implementation(project(":feature:library"))
    implementation(project(":feature:nowplaying"))
    implementation(project(":feature:sync"))
    implementation(project(":feature:settings"))
    implementation(project(":data:download"))
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
}

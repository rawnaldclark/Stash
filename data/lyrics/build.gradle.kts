plugins {
    id("stash.android.library")
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.stash.data.lyrics"

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            // Required for the Robolectric-backed LyricsSidecarWriterTest
            // so ApplicationProvider.getApplicationContext() resolves the
            // Android framework + a usable ContentResolver.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":core:network"))
    // Task 5: YtMusicLyricsSource wraps the InnerTube client for plain-text
    // lyrics fallback (when LRCLIB misses).
    implementation(project(":data:ytmusic"))
    // Task 7: LyricsSidecarWriter reuses FileOrganizerSlugs.slugify to keep
    // the SAF directory layout (<artist>/<album>/<title>.lrc) identical
    // to the download pipeline — no slug-semantics drift between the two.
    implementation(project(":data:download"))

    implementation(libs.hilt.android)
    implementation(libs.okhttp.dnsoverhttps)
    ksp(libs.hilt.android.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // OkHttp for upcoming LrclibLyricsSource HTTP client (Task 4).
    implementation(libs.okhttp)
    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)
    // SAF support for the upcoming sidecar writer (Task 7) — writing .lrc /
    // .txt files alongside downloads in a user-chosen tree.
    implementation("androidx.documentfile:documentfile:1.0.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.truth)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation(libs.mockk)
    // MockWebServer for the future LrclibLyricsSource HTTP test (Task 4).
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // TestListenableWorkerBuilder for the Task 8 worker tests.
    testImplementation(libs.work.testing)
}

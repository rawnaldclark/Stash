plugins {
    id("stash.android.feature")
}
android {
    namespace = "com.stash.feature.sync"

    testOptions {
        unitTests {
            // Return Kotlin defaults from stubbed Android SDK methods so
            // android.util.Log calls in production code don't throw in JVM tests.
            isReturnDefaultValues = true
        }
    }
}
dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:auth"))
    implementation(project(":core:media"))
    implementation(project(":data:download"))
    // Failed Matches' "find it yourself" (#19) runs the Search tab's own
    // YouTube Music search (YTMusicApiClient.searchAll).
    implementation(project(":data:ytmusic"))
    implementation(libs.compose.material.icons.extended)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    // FailedMatchesViewModel classifies PreviewPlayer's asynchronous
    // PlaybackException events before its one-shot yt-dlp retry.
    implementation(libs.media3.exoplayer)
    // For the "Fix wrong-version downloads" trigger (enqueues
    // YtLibraryBackfillWorker via WorkManager).
    implementation(libs.work.runtime.ktx)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation(libs.mockk)
}

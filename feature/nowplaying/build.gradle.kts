plugins {
    id("stash.android.feature")
}

android {
    namespace = "com.stash.feature.nowplaying"

    testOptions {
        unitTests {
            // Return Kotlin defaults from stubbed Android SDK methods so
            // android.util.Log calls in production code don't throw in JVM tests.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(project(":core:media"))
    implementation(project(":core:data"))
    implementation(project(":core:auth"))
    // Track-artist display collapses collaboration credits to their lead act
    // via String.primaryArtist.
    implementation(project(":core:common"))
    // v0.9.36 — Now Playing lyrics sheet observes LyricsRepository and
    // enqueues the priority on-open LyricsFetchWorker. work-runtime gives
    // us WorkManager + OneTimeWorkRequestBuilder for the priority enqueue.
    implementation(project(":data:lyrics"))
    // Tap-to-artist: resolveArtist(name) → YT browseId for the Now Playing
    // track → artist-profile navigation.
    implementation(project(":data:ytmusic"))
    implementation(libs.work.runtime.ktx)
    implementation(libs.palette.ktx)
    implementation(libs.coil.compose)
    implementation(libs.compose.material.icons.extended)

    // v0.9.18 — first tests in this module. Stand up the same JUnit +
    // mockk + coroutines-test + Robolectric harness that :feature:home
    // and :core:media use, so future ViewModel tests in this module
    // have a uniform foundation.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    // Turbine: ergonomic SharedFlow assertions. NowPlayingViewModel's
    // userMessages is a DROP_OLDEST buffer; without an active collector
    // sitting on the channel between emits, the first message in a
    // back-to-back pair is lost — Turbine's awaitItem() is the canonical
    // fix.
    testImplementation("app.cash.turbine:turbine:1.1.0")
    testImplementation(libs.mockk)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
}

plugins {
    id("stash.android.feature")
}
android {
    namespace = "com.stash.feature.home"

    testOptions {
        unitTests {
            // Return Kotlin defaults from stubbed Android SDK methods so
            // android.util.Log calls in production code don't throw in JVM tests.
            isReturnDefaultValues = true
            // HomeAlbumRowsSizeTest composes Because you like on Robolectric, which reads this module's strings.
            isIncludeAndroidResources = true
        }
    }
}
dependencies {
    // :core:auth was used by HomeViewModel for TokenManager / AuthState
    // to feed the SyncStatusCard's "Connect Spotify / YouTube" prompt.
    // Both moved to :feature:sync along with the card.
    implementation(project(":core:data"))
    implementation(project(":core:media"))
    // Still required after the SyncStatusCard relocation: the lossless
    // retry/backfill banners on Home read MetadataBackfillState,
    // LosslessRetryWorker, KennyySource, QobuzSource, and the
    // AggregatorRateLimiter from this module.
    implementation(project(":data:download"))
    // v0.9.36: LyricsBackfillState snapshot for the LyricsBackfillBanner
    // on Home — mirrors the :data:download dependency for the v0.9.35
    // metadata banner.
    implementation(project(":data:lyrics"))
    // Qobuz discovery rows: AlbumSummary/PlaylistSummary + AlbumSource for the
    // Home discovery feed (HomeDiscoveryRepository results + album/playlist taps).
    implementation(project(":data:ytmusic"))
    implementation(libs.compose.material.icons.extended)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    // For LosslessRetryWorker enqueue from the deferred-banner "retry"
    // action (HomeViewModel.onRetryDeferredRequested).
    implementation(libs.work.runtime.ktx)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("com.google.truth:truth:1.4.4")
    // Matches the test harness in :feature:library — see MixOfflineTapGuardTest.
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    // HomeAlbumRowsSizeTest composes Home's album rows on Robolectric, as :feature:search's RailFirstCardTest
    // does the artist page's. The Compose BOM is only on the main classpath, so it is applied again here.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.compose.bom))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.ui:ui-test-manifest")
}

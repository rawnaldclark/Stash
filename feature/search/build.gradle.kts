plugins {
    id("stash.android.feature")
}
android {
    namespace = "com.stash.feature.search"

    testOptions {
        unitTests {
            // Return Kotlin defaults (Unit) from stubbed Android SDK methods —
            // needed so android.util.Log calls inside production code don't
            // throw "not mocked" in JVM unit tests.
            isReturnDefaultValues = true
            // Required for the Robolectric-backed RecentSearchesStoreTest to
            // resolve ApplicationProvider / preferencesDataStore against
            // android resources (mirrors :data:download).
            isIncludeAndroidResources = true
        }
    }
}
dependencies {
    implementation(project(":core:auth"))
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":core:media"))
    implementation(project(":data:download"))
    implementation(project(":data:ytmusic"))
    implementation(libs.compose.material.icons.extended)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    // media3-exoplayer transitively exposes `androidx.media3.common.PlaybackException`
    // which `SearchViewModel.onPreviewError` accepts as a parameter.
    implementation(libs.media3.exoplayer)
    // Recent-searches persistence (RecentSearchesStore).
    implementation(libs.datastore.preferences)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("app.cash.turbine:turbine:1.1.0")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation(libs.truth)
    // Robolectric — Android env for the DataStore-backed RecentSearchesStoreTest.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // AlbumRegionFallbackWiringTest hands the real YTMusicApiClient InnerTube JSON.
    testImplementation(libs.kotlinx.serialization.json)
}

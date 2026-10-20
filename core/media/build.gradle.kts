plugins {
    id("stash.android.library")
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    // Compose compiler plugin: added for ColorExtractor, which is gone. No source here uses
    // Compose now; drop this, buildFeatures.compose and the Compose deps below together.
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.stash.core.media"

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            // Return Kotlin defaults (Unit) from stubbed Android SDK methods —
            // needed so android.util.Log / android.os.SystemClock calls inside
            // production code don't throw "not mocked" during JVM unit tests
            // (e.g. TrackActionsDelegateTest).
            isReturnDefaultValues = true
            // Required for Robolectric to resolve preferencesDataStoreFile (ApplicationProvider).
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    // TrackActionsDelegate dependencies — shared preview+download surface.
    implementation(project(":core:data"))
    implementation(project(":core:auth"))
    implementation(project(":data:download"))
    // Cloud storage (Dropbox / OneDrive): stash-cloud:// items resolve through data:cloud.
    implementation(project(":data:cloud"))
    // YT Music client for the streaming-fallback search-by-metadata path
    // (YouTubeStreamResolver.searchYouTubeForVideoId).
    implementation(project(":data:ytmusic"))

    // Media3
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.database)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)

    // DataStore
    implementation(libs.datastore.preferences)

    // Compose BOM + ui-graphics + runtime — added for ColorExtractor (removed; see the plugin note).
    // The runtime artifact satisfies the Compose compiler's classpath requirement.
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui.graphics)

    // DocumentFile — PlayerRepositoryImpl sizes SAF (content://) downloads. Declared
    // directly: it used to arrive transitively through palette-ktx, now removed.
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Serialization — EqState + future JSON-persisted state.
    implementation(libs.kotlinx.serialization.json)

    // Unit tests — TrackActionsDelegateTest.
    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.truth)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    // Robolectric — Android environment for EqStoreTest (DataStore + ApplicationProvider).
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // MockK — pure JVM mocking for EqMigrationTest.
    testImplementation(libs.mockk)
}

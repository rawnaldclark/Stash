plugins {
    id("stash.android.library")
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

// Cloud storage libraries (spec docs/superpowers/specs/2026-10-19-cloud-storage-design.md).
// Dropbox and OneDrive: sign-in (PKCE), folder browse, the resumable library scan,
// ranged tag reading and the playback/download link resolver. The user's own files go
// straight from their cloud to the phone; nothing here talks to a Stash server.
android {
    namespace = "com.stash.data.cloud"

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            // Robolectric scanner test runs an in-memory StashDatabase.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":core:auth"))
    implementation(project(":core:network"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.runtime)
    testImplementation(libs.room.ktx)
    // Custom Tabs for the sign-in browser (small; AppAuth is not used: one PKCE path serves both providers).
    implementation("androidx.browser:browser:1.8.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.truth)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

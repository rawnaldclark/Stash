plugins {
    id("stash.android.feature")
}
android {
    namespace = "com.stash.feature.settings"

    testOptions {
        unitTests {
            // Return Kotlin defaults from stubbed Android SDK methods so
            // android.util.Log calls in production code don't throw in JVM tests.
            isReturnDefaultValues = true
        }
    }
}
dependencies {
    implementation(project(":core:auth"))
    implementation(project(":core:data"))
    implementation(project(":core:media"))
    // For MoveLibraryCoordinator + MoveLibraryState (storage migration UI).
    implementation(project(":data:download"))
    implementation(libs.compose.material.icons.extended)
    // For the "Fix wrong-version downloads" backfill trigger in Settings,
    // which enqueues YtLibraryBackfillWorker via WorkManager.
    implementation(libs.work.runtime.ktx)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation(libs.truth)
    testImplementation(libs.mockk)
}

plugins {
    id("stash.android.feature")
}
android {
    namespace = "com.stash.feature.library"

    testOptions {
        unitTests {
            // Return Kotlin defaults (Unit) from stubbed Android SDK methods —
            // needed so android.util.Log calls inside production code don't
            // throw "not mocked" in JVM unit tests.
            isReturnDefaultValues = true
        }
    }
}
dependencies {
    implementation(project(":core:auth"))
    implementation(project(":core:data"))
    implementation(project(":core:media"))
    // LocalImportCoordinator for "Import from device" flow.
    implementation(project(":data:download"))
    implementation(project(":data:ytmusic"))
    implementation(libs.compose.material.icons.extended)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.activity.compose)
    // MixBuilderViewModel enqueues StashMixRefreshWorker on save.
    implementation(libs.work.runtime.ktx)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("com.google.truth:truth:1.4.4")
    // Matches the test harness in :feature:search — see SearchViewModelTest.
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
}

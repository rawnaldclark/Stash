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
            // The cloud cards' tests draw real strings and logos under Robolectric.
            isIncludeAndroidResources = true
        }
    }
}
// JoinedStringSpacesTest reads every module's strings: they're inputs of these tests too, so a change
// to any of them reruns it instead of leaving its last result up to date.
tasks.withType<Test>().configureEach {
    inputs.files(
        rootProject.allprojects.map { module ->
            fileTree(module.projectDir.resolve("src/main/res")) { include("values*/strings.xml") }
        },
    ).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("allModuleStrings")
}
dependencies {
    implementation(project(":core:auth"))
    implementation(project(":core:data"))
    implementation(project(":core:media"))
    implementation(project(":data:lyrics"))
    // For MoveLibraryCoordinator + MoveLibraryState (storage migration UI).
    implementation(project(":data:download"))
    // Cloud storage libraries (Dropbox / OneDrive): accounts and cloud folders settings.
    implementation(project(":data:cloud"))
    // YouTube Music profiles (#664): the profile list's model and the profile photos.
    implementation(project(":data:ytmusic"))
    implementation(libs.coil.compose)
    implementation(libs.compose.material.icons.extended)
    // WorkManager: FLAC upgrades, Library health and the About screen observe and enqueue work.
    implementation(libs.work.runtime.ktx)
    // For LoudnessFirstRunStore — shares the app-wide DataStore<Preferences>
    // with LoudnessStore / LoudnessProgressStore in :core:data and :core:media.
    implementation(libs.datastore.preferences)
    // Link Stash on the web: the QR scanner. CameraX + ZXing core, no Google Play services (spec 2026-10-10 link-sync §1).
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.zxing.core)

    implementation(libs.appcompat)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation(libs.truth)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.compose.bom))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.ui:ui-test-manifest")
}

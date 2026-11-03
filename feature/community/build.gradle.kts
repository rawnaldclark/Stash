plugins {
    id("stash.android.feature")
}
android {
    namespace = "com.stash.feature.community"

    testOptions {
        unitTests {
            // android.util.Log in ViewModels returns defaults instead of throwing "not mocked".
            isReturnDefaultValues = true
            // CommunityTextTest reads the module's real strings and plurals under Robolectric.
            isIncludeAndroidResources = true
        }
    }
}
dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:media"))
    implementation(project(":data:ytmusic"))
    implementation(libs.compose.material.icons.extended)
    implementation(libs.coil.compose)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("com.google.truth:truth:1.4.4")
    testImplementation("io.mockk:mockk:1.13.8") // same version core/data uses
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // CommunityVotePillTest composes the real pill on Robolectric. The Compose BOM is only on the main classpath (the
    // compose convention plugin), so re-apply it here to version the test artifacts.
    testImplementation(platform(libs.compose.bom))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.ui:ui-test-manifest")
}

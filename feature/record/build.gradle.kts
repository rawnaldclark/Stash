plugins {
    id("stash.android.feature")
}
android {
    namespace = "com.stash.feature.record"

    testOptions {
        unitTests {
            // android.util.Log in the host and the ViewModels returns defaults instead of throwing "not mocked".
            isReturnDefaultValues = true
        }
    }
}
dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:media"))
    implementation(libs.compose.material.icons.extended)
    implementation(libs.coil.compose)
    // The bundled record in a WebView: WebViewAssetLoader and the StashHost bridge (addWebMessageListener)
    implementation(libs.androidx.webkit)
    // Covers: Deezer and Apple's search, through the app's shared client (core:network provides it)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("com.google.truth:truth:1.4.4")
    testImplementation("io.mockk:mockk:1.13.8") // same version core/data uses
}

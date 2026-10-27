plugins {
    id("stash.android.feature")
    // The taste's body (`record_taste`), written and read as JSON
    alias(libs.plugins.kotlin.serialization)
}
android {
    namespace = "com.stash.feature.record"

    // The taste's vectors, read on the phone too (TasteIcuAndroidTest: ICU's regexes and ART's maths, not the JVM's)
    sourceSets.getByName("androidTest").assets.srcDir("src/test/resources/taste")

    testOptions {
        unitTests {
            // android.util.Log in the host and the ViewModels returns defaults instead of throwing "not mocked".
            isReturnDefaultValues = true
            // Robolectric (the import written through a real Room database) boots on the merged resources
            isIncludeAndroidResources = true
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
    // Bring your Spotify: the import runs as a foreground job, so it goes on when the person leaves the app
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)
    // WorkManager's updateWork answers with a ListenableFuture, awaited (the lengths pass's waiting job takes a Wi-Fi-only change)
    implementation(libs.kotlinx.coroutines.guava)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("com.google.truth:truth:1.4.4")
    testImplementation("io.mockk:mockk:1.13.8") // same version core/data uses
    // The golden export written through a real (in-memory) Room database, as core/data's DAO tests run it
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation(libs.room.runtime)
    // The lengths pass's job: what it asks of WorkManager (its network), against WorkManager's own test runtime
    testImplementation(libs.work.testing)

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

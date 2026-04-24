plugins {
    id("stash.android.library")
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}
android {
    namespace = "com.stash.core.data"

    testOptions {
        unitTests {
            // Return Kotlin defaults (Unit) from stubbed Android SDK methods —
            // needed so android.util.Log.d calls inside production code don't
            // throw "not mocked" in JVM unit tests.
            isReturnDefaultValues = true
            // Robolectric needs the merged AGP resources to boot its
            // Android runtime shim during JVM unit tests.
            isIncludeAndroidResources = true
        }
    }
}
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.generateKotlin", "true")
}
dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:auth"))
    implementation(project(":data:spotify"))
    implementation(project(":data:ytmusic"))
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    // DocumentFile: SAF-aware deletion of tracks stored on external SD card /
    // USB-OTG via the user's ACTION_OPEN_DOCUMENT_TREE grant. See the
    // deleteTrackFile helper in MusicRepositoryImpl.
    implementation("androidx.documentfile:documentfile:1.0.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("app.cash.turbine:turbine:1.1.0")
    testImplementation("androidx.room:room-testing:2.7.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("io.mockk:mockk:1.13.8")
}

plugins {
    id("stash.android.library")
}
android {
    namespace = "com.stash.core.common"
    testOptions {
        // BatterySaverTest drives the failure paths, which log through android.util.Log.
        unitTests.isReturnDefaultValues = true
    }
}
dependencies {
    implementation(libs.kotlinx.coroutines.android)

    // Unit tests for the pure-Kotlin artist-credit parser
    // (String.splitArtistCredits / String.primaryArtist). No Android
    // framework needed, so plain JUnit keeps the module's test surface minimal.
    testImplementation("junit:junit:4.13.2")
    // BatterySaverTest: awaitOff suspends until Battery Saver turns off.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
}

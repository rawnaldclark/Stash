plugins {
    id("stash.android.library")
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Stash Add-ons v1 host side (add-ons plan 2026-11-08, phase 1): the contract classes vendored
 * from stash-addon-spec, the add-on registry and the HTTP client. Nothing here knows the app's
 * sources: the seams (stream rungs, download sources, lyrics) live next to the code they extend
 * and depend on this module, so this module depends on none of them.
 */
android {
    namespace = "com.stash.core.addons"

    testOptions {
        unitTests {
            // android.util.Log in the client must not throw "not mocked" in JVM tests.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(project(":core:common"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    // The contract classes expose kotlinx.serialization types (JsonPrimitive), and the host ports
    // take OkHttp requests, so both are part of this module's API.
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.truth)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation(libs.mockk)
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

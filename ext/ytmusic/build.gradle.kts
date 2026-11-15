plugins {
    id("stash.android.library")
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.stash.ext.ytmusic"

    testOptions {
        unitTests {
            // Return Kotlin defaults (Unit) from stubbed Android SDK methods, so
            // android.util.Log calls inside production code don't throw in JVM unit tests.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // The hooks live in :core:data; this module only supplies a second implementation of
    // one of them, which is what keeps it optional: drop this module's include in
    // settings.gradle.kts and its dependency line in app/build.gradle.kts and Stash builds
    // and behaves as it did before.
    implementation(project(":core:data"))
    implementation(project(":core:model"))
    implementation(project(":core:auth"))
    implementation(project(":data:ytmusic"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.truth)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation(libs.mockk)
}

plugins {
    id("stash.android.library")
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    // Compose compiler plugin required for compose-ui-graphics Color usage in ColorExtractor.
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.stash.core.media"

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            // Return Kotlin defaults (Unit) from stubbed Android SDK methods —
            // needed so android.util.Log / android.os.SystemClock calls inside
            // production code don't throw "not mocked" during JVM unit tests
            // (e.g. TrackActionsDelegateTest).
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    // TrackActionsDelegate dependencies — shared preview+download surface.
    implementation(project(":core:data"))
    implementation(project(":core:auth"))
    implementation(project(":data:download"))

    // Media3
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)

    // DataStore
    implementation(libs.datastore.preferences)

    // Compose BOM + ui-graphics + runtime — needed for androidx.compose.ui.graphics.Color in ColorExtractor.
    // The runtime artifact satisfies the Compose compiler's classpath requirement.
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui.graphics)

    // Palette — color extraction from album artwork bitmaps.
    implementation(libs.palette.ktx)

    // Unit tests — TrackActionsDelegateTest.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("app.cash.turbine:turbine:1.1.0")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
}

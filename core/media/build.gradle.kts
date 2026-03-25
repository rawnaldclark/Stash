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
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))

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
}

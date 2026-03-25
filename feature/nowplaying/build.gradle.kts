plugins {
    id("stash.android.feature")
}

android {
    namespace = "com.stash.feature.nowplaying"
}

dependencies {
    implementation(project(":core:media"))
    implementation(libs.palette.ktx)
    implementation(libs.coil.compose)
    implementation(libs.compose.material.icons.extended)
}

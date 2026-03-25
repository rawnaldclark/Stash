plugins {
    id("stash.android.library")
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}
android {
    namespace = "com.stash.data.download"
}
dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.youtubedl.android)
    implementation(libs.youtubedl.ffmpeg)
    implementation(libs.kotlinx.coroutines.android)
}

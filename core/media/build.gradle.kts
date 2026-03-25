plugins {
    id("stash.android.library")
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}
android {
    namespace = "com.stash.core.media"
}
dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.kotlinx.coroutines.android)
}

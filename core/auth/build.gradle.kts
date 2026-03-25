plugins {
    id("stash.android.library")
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}
android {
    namespace = "com.stash.core.auth"
}
dependencies {
    implementation(project(":core:common"))
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.appauth)
    implementation(libs.tink.android)
    implementation(libs.kotlinx.coroutines.android)
}

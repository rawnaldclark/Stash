plugins {
    id("stash.android.library")
    id("stash.compose.library")
}
android {
    namespace = "com.stash.core.ui"
}
dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(libs.core.ktx)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.animation)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
}

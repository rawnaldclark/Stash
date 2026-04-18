plugins {
    id("stash.android.feature")
}
android {
    namespace = "com.stash.feature.library"
}
dependencies {
    implementation(project(":core:auth"))
    implementation(project(":core:data"))
    implementation(project(":core:media"))
    // LocalImportCoordinator for "Import from device" flow.
    implementation(project(":data:download"))
    implementation(libs.compose.material.icons.extended)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.activity.compose)
}

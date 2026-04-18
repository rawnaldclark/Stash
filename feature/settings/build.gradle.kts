plugins {
    id("stash.android.feature")
}
android {
    namespace = "com.stash.feature.settings"
}
dependencies {
    implementation(project(":core:auth"))
    implementation(project(":core:data"))
    implementation(project(":core:media"))
    // For MoveLibraryCoordinator + MoveLibraryState (storage migration UI).
    implementation(project(":data:download"))
    implementation(libs.compose.material.icons.extended)
}

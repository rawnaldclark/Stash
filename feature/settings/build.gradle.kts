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
    implementation(libs.compose.material.icons.extended)
}

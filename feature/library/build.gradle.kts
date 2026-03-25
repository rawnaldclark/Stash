plugins {
    id("stash.android.feature")
}
android {
    namespace = "com.stash.feature.library"
}
dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:media"))
    implementation(libs.compose.material.icons.extended)
}

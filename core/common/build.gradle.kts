plugins {
    id("stash.android.library")
}
android {
    namespace = "com.stash.core.common"
}
dependencies {
    implementation(libs.kotlinx.coroutines.android)
}

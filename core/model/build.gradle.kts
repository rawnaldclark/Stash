plugins {
    id("stash.android.library")
    alias(libs.plugins.kotlin.serialization)
}
android {
    namespace = "com.stash.core.model"
}
dependencies {
    implementation(libs.kotlinx.serialization.json)
}

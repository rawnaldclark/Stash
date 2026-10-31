plugins {
    id("stash.android.library")
    alias(libs.plugins.kotlin.serialization)
}
android { namespace = "com.stash.data.soundcloud" }
dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
}

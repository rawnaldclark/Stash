plugins {
    id("stash.android.library")
}
android {
    namespace = "com.stash.core.common"
}
dependencies {
    implementation(libs.kotlinx.coroutines.android)

    testImplementation("junit:junit:4.13.2")
}

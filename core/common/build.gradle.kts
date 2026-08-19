plugins {
    id("stash.android.library")
}
android {
    namespace = "com.stash.core.common"
}
dependencies {
    implementation(libs.kotlinx.coroutines.android)

    // Unit tests for the pure-Kotlin artist-credit parser
    // (String.splitArtistCredits / String.primaryArtist). No Android
    // framework needed, so plain JUnit keeps the module's test surface minimal.
    testImplementation("junit:junit:4.13.2")
}

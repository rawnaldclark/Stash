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

    // SelectionState unit tests run on the JVM. The Compose BOM is only on the
    // main classpath (added by stash.compose.library), so re-apply it here to
    // version-align compose-runtime + runtime-saveable on the test classpath.
    testImplementation(platform(libs.compose.bom))
    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.truth)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation(libs.compose.runtime)
    testImplementation("androidx.compose.runtime:runtime-saveable")
}

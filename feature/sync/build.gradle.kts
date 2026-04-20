plugins {
    id("stash.android.feature")
}
android {
    namespace = "com.stash.feature.sync"
}
dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:auth"))
    implementation(project(":core:media"))
    implementation(project(":data:download"))
    implementation(libs.compose.material.icons.extended)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    // For the "Fix wrong-version downloads" trigger (enqueues
    // YtLibraryBackfillWorker via WorkManager).
    implementation(libs.work.runtime.ktx)
}

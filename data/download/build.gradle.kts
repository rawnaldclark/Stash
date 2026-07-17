import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

plugins {
    id("stash.android.library")
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

// ── ARCOD private stream endpoint ────────────────────────────────────────────
// The arcod operator shared a private streaming endpoint on the condition it not
// be exposed in the public repo. Its base URL (host + path) is therefore injected
// at build time from `local.properties` (gitignored) or an env var (CI/release),
// never committed to source. Set it in local.properties as:
//   arcod.streamBase=<base url including path>
// Empty is valid — an unconfigured build simply skips ARCOD streaming and fails
// over to the next source, exactly like a missing Last.fm key no-ops scrobbling.
val arcodLocalProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val arcodStreamBase: String =
    arcodLocalProperties.getProperty("arcod.streamBase") ?: System.getenv("ARCOD_STREAM_BASE").orEmpty()

// ── qbdlx (direct-Qobuz) credentials + token pool ──────────────────────────
// Bundled at build time from local.properties / env. APP_ID + APP_SECRET are
// public (shown on qbdlx's login page). TOKEN_POOL is a comma-separated list of
// "user_auth_token:ISO2COUNTRY" pairs. Empty is valid — an unconfigured build
// simply has no bundled tokens and relies on a user-pasted token.
val qbdlxProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun qbdlxProp(key: String, env: String) =
    qbdlxProps.getProperty(key) ?: System.getenv(env).orEmpty()
val qbdlxAppId = qbdlxProp("qbdlx.appId", "QBDLX_APP_ID")
val qbdlxAppSecret = qbdlxProp("qbdlx.appSecret", "QBDLX_APP_SECRET")
val qbdlxTokenPool = qbdlxProp("qbdlx.tokenPool", "QBDLX_TOKEN_POOL")

// AES-256-GCM encrypt the pool at build time (mirrors the runtime
// QbdlxPoolCipher — keep the two in sync). The fixture test guards the RUNTIME
// decrypt only; nothing statically re-runs THIS encrypt, so a build-side-only
// scheme change ships a blob the runtime can't decrypt → silent empty pool. The
// catches for that are the runtime fp-mismatch Log.w (QbdlxModule) and the
// MANDATORY on-device verify (empty pool shows as the Settings paste prompt).
// Blank pool → emit "" so an unconfigured build still hits the paste path.
fun encryptPool(plain: String): String {
    if (plain.isBlank()) return ""
    val pass = "stash" + "-qbdlx-" + "pool-" + "v1"
    val digest = MessageDigest.getInstance("SHA-256").digest(pass.toByteArray())
    val key = SecretKeySpec(digest, "AES")
    val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
    return Base64.getEncoder().encodeToString(iv + cipher.doFinal(plain.toByteArray()))
}
// sha256(plaintextPool)[:8], lowercase hex — matches `printf %s "$POOL" | sha256sum | head -c 8`
// in release.yml. Non-secret; the CI verify step greps the dex for it to prove the
// CURRENT, non-blank pool actually shipped (the v0.9.69 blank/stale failure class).
// NOTE: `it.toInt() and 0xFF` is REQUIRED — a bare "%02x".format(byte) sign-extends
// negative bytes to 8 hex chars, so the fp would never match sha256sum.
fun poolFp(plain: String): String =
    if (plain.isBlank()) "" else
        MessageDigest.getInstance("SHA-256").digest(plain.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }.take(8)

val qbdlxTokenPoolEnc = encryptPool(qbdlxTokenPool)
val qbdlxPoolFp = poolFp(qbdlxTokenPool)

val qbdlxConfigured = qbdlxAppId.isNotBlank() && qbdlxAppSecret.isNotBlank() && qbdlxTokenPool.isNotBlank()
val arcodConfigured = arcodStreamBase.isNotBlank()

android {
    namespace = "com.stash.data.download"

    defaultConfig {
        // Private ARCOD stream base (host+path), injected from local.properties /
        // env at build time so it never lives in the public repo. Empty when
        // unconfigured — ARCOD streaming then no-ops and the registry fails over.
        buildConfigField("String", "ARCOD_STREAM_BASE", "\"$arcodStreamBase\"")
        buildConfigField("String", "QBDLX_APP_ID", "\"$qbdlxAppId\"")
        buildConfigField("String", "QBDLX_APP_SECRET", "\"$qbdlxAppSecret\"")
        buildConfigField("String", "QBDLX_TOKEN_POOL", "\"$qbdlxTokenPoolEnc\"")
        buildConfigField("String", "QBDLX_POOL_FP", "\"$qbdlxPoolFp\"")
        buildConfigField("Boolean", "QBDLX_CONFIGURED", "$qbdlxConfigured")
        buildConfigField("Boolean", "ARCOD_CONFIGURED", "$arcodConfigured")
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Return Kotlin defaults (Unit) from stubbed Android SDK methods —
            // needed so android.util.Log calls inside production code don't
            // throw "not mocked" during JVM unit tests.
            isReturnDefaultValues = true
            // Required for Robolectric-backed DataStore tests
            // (LosslessSourcePreferencesYoutubeFallbackTest) to resolve
            // ApplicationProvider/preferencesDataStore against android resources.
            isIncludeAndroidResources = true
        }
    }

    packaging {
        jniLibs {
            // Required by the instrumented MetadataEmbeddingIntegrationTest:
            // FFmpeg.init unpacks libffmpeg.zip.so from nativeLibraryDir, which
            // only exists on-disk when extractNativeLibs="true". Mirrors
            // app/build.gradle.kts so the test APK behaves like the real app.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":core:auth"))
    implementation(project(":core:network"))
    implementation(project(":data:ytmusic"))
    implementation(project(":data:spotify"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.youtubedl.android)
    implementation(libs.youtubedl.ffmpeg)
    implementation(libs.youtubedl.aria2c)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // OkHttp for the lossless-source HTTP clients (Qobuz API, future
    // Bandcamp / Internet Archive). The yt-dlp-bound paths use the
    // youtubedl-android wrapper instead.
    implementation(libs.okhttp)
    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)
    // SAF support for writing downloads to a user-chosen external-storage tree
    // (SD card / USB-OTG). DocumentFile wraps the raw content-tree Uri.
    implementation("androidx.documentfile:documentfile:1.0.1")
    // media3-datasource provides DataSpec, CacheDataSource, SimpleCache,
    // HttpDataSource.Factory, and CacheKeyFactory for SearchDownloadCoordinator.
    // media3-database provides DatabaseProvider (transitive dep of SimpleCache).
    // Not declared in :core:media because that module already pulls them
    // transitively, but :data:download is a leaf that doesn't depend on
    // :core:media (circular — core:media depends on data:download).
    implementation(libs.media3.datasource)
    implementation(libs.media3.database)

    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.truth)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("app.cash.turbine:turbine:1.1.0")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    // MockK for QobuzSource tests — suspend-function mocking is cleaner
    // than Mockito's, and matches the pattern used in :core:media tests.
    testImplementation(libs.mockk)
    // MockWebServer for QobuzApiClient tests — fake server, real OkHttp client.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // Robolectric — Android environment for DataStore-backed pref tests
    // (LosslessSourcePreferencesYoutubeFallbackTest), mirroring the
    // EqStoreTest setup in :core:media.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    // Instrumented tests — MetadataEmbeddingIntegrationTest runs against the
    // ffmpeg .so bundled by youtubedl-android on a real device, since that
    // shell-out is the only place where Opus attached_pic + Vorbis-comment
    // casing claims can be verified end-to-end.
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

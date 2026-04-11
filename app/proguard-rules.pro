# Stash ProGuard Rules

# ── Attributes ──────────────────────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions, EnclosingMethod

# ── kotlinx.serialization ──────────────────────────────────────────────────
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class com.stash.**$$serializer { *; }
-keepclassmembers class com.stash.** { *** Companion; }
-keepclasseswithmembers class com.stash.** { kotlinx.serialization.KSerializer serializer(...); }
-keepattributes RuntimeVisibleAnnotations, AnnotationDefault

# ── Hilt / Dagger ───────────────────────────────────────────────────────────
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.lifecycle.HiltViewModel
-keepclasseswithmembers class * {
    @dagger.hilt.InstallIn <methods>;
}

# ── Room ────────────────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *
-keepclassmembers class * {
    @androidx.room.* <fields>;
    @androidx.room.* <methods>;
}

# ── Chaquopy (Python runtime embedded by youtubedl-android) ─────────────────
# Chaquopy uses reflection to bridge Java ↔ Python. R8 obfuscation breaks
# its class-lookup code with "class X is not a concrete class" errors.
# The rules below keep every class, method, and field in the Chaquopy
# packages so reflection continues to work at runtime.
-keep class com.chaquo.python.** { *; }
-keep interface com.chaquo.python.** { *; }
-keep enum com.chaquo.python.** { *; }
-keepclassmembers class com.chaquo.python.** { *; }
-dontwarn com.chaquo.python.**

# ── youtubedl-android (yausername bindings on top of Chaquopy) ──────────────
-keep class com.yausername.** { *; }
-keep interface com.yausername.** { *; }
-keepclassmembers class com.yausername.** { *; }
-dontwarn com.yausername.**

# ── Google Tink (AES-256-GCM used for credential encryption) ────────────────
# Tink registers primitives via reflection at runtime.
-keep class com.google.crypto.tink.** { *; }
-keep interface com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# ── Media3 / ExoPlayer ──────────────────────────────────────────────────────
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ── Coil / OkHttp / Okio ────────────────────────────────────────────────────
-dontwarn coil3.**
-dontwarn okhttp3.**
-dontwarn okio.**

# ── Kotlin reflection ───────────────────────────────────────────────────────
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.** { *; }
-dontwarn kotlin.reflect.**

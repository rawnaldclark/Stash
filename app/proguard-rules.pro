# Stash R8 rules: the release build, and the debug build made with -Pstash.minifyDebug=true (app/build.gradle.kts).
#
# R8 removes code and resources nothing uses and optimizes the rest. It never renames anything (-dontobfuscate):
#   - WorkManager stores each worker's class name in its database, and Hilt's worker map, the navigation routes
#     and the bottom bar look classes up by name. Renamed classes would orphan queued work after an update.
#   - Stash is open source, so renaming would hide nothing.
#
# Every library brings its own rules inside its AAR or JAR, and R8 reads them: AndroidX (Room, WorkManager,
# DataStore, Glance, startup), Hilt, media3, kotlinx.serialization, OkHttp, Coil, Tink and play-services. So do the
# defaults in proguard-android-optimize.txt (JNI method names, enum values()/valueOf(), @JavascriptInterface
# methods, Parcelable CREATORs). The rules below cover only what none of those do. The modules' own name lookups
# sit in their consumer-rules.pro, next to the code: core/media (Tuning's JNI) and core/data (the tip jar URL).

-dontobfuscate

# Line numbers for stack traces; R8 drops them otherwise. Even unrenamed, a release trace needs that release's
# mapping.txt to read exactly: R8 records positions that the mapping turns back into source lines, and it inlines
# and merges code, so a frame can name the class a method was moved into. release.yml keeps the mapping as the
# r8-mapping-<tag> workflow artifact for 90 days.
-keepattributes SourceFile,LineNumberTable

# Apache Commons Compress, which youtubedl-android uses to unzip its Python and ffmpeg bundles (on a fresh install
# and after a library update): ExtraFieldUtils creates each zip extra-field class with Class.newInstance(). Nothing
# else creates them, so R8 drops their constructors and every unzip fails with "AsiExtraField is not a concrete
# class", leaving the app without yt-dlp and ffmpeg.
-keep class * implements org.apache.commons.compress.archivers.zip.ZipExtraField { <init>(); }

# androidx.core: an accessibility action that takes arguments (scroll to position, set text, ...) creates its
# argument holder with getDeclaredConstructor().newInstance(). Nothing else creates those holders, so R8 drops their
# constructors and the action runs without its arguments. Only library Views use these; Stash's Compose screens
# don't. Small (seven classes), and it keeps TalkBack working wherever they do.
-keep class * extends androidx.core.view.accessibility.AccessibilityViewCommand$CommandArguments { <init>(); }

# Cast: CastContext creates the class named in the manifest's OPTIONS_PROVIDER_CLASS_NAME meta-data
# (StashCastOptionsProvider) by reflection. play-services-cast-framework's own rule keeps its methods but not its
# constructor, and nothing else creates one, so R8 would remove the constructor and Cast would fail to start.
-keep class * implements com.google.android.gms.cast.framework.OptionsProvider { public <init>(); }

# youtubedl-android: YoutubeDL.getInfo() has Jackson fill these classes from yt-dlp's JSON by reflection. Stash
# doesn't call getInfo() today, so R8 would drop them; keeping them (four small classes) means a future caller
# doesn't get empty objects in release only. The rest of the library runs yt-dlp and ffmpeg as processes.
-keep class com.yausername.youtubedl_android.mapper.** { *; }

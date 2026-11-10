# Stash R8 rules: the release build, and the debug build made with -Pstash.minifyDebug=true (app/build.gradle.kts).
#
# R8 removes code and resources nothing uses and optimizes the rest. It renames nothing (-dontobfuscate):
#   - The navigation code compares a route's class name with the route strings built from @SerialName
#     (StashScaffold, StashNavigationBar), and Glance stores its widget receivers' class names. Renamed classes
#     would break both. (WorkManager's stored worker names would survive: its own rules keep them.)
#   - Stash is open source, so renaming would hide nothing.
# Names staying is not the whole story: R8 still merges classes, so an object can be created as another class
# (an inlined or merged frame names the class it moved into; retrace turns those back). Exceptions are kept
# out of merging below, because their type is what a crash file and the diagnostics print.
#
# Every library brings its own rules inside its AAR or JAR, and R8 reads them: AndroidX (Room, WorkManager,
# DataStore, Glance, startup), Hilt, media3, kotlinx.serialization, OkHttp, Coil, Tink and play-services. So do the
# defaults in proguard-android-optimize.txt (JNI method names, enum values()/valueOf(), @JavascriptInterface
# methods, Parcelable CREATORs). The rules below cover only what none of those do. The modules' own name lookups
# sit in their consumer-rules.pro, next to the code: core/media (Tuning's JNI) and core/data (the tip jar URL).

-dontobfuscate

# Line numbers for stack traces; R8 drops them otherwise. Even unrenamed, a release trace needs that release's
# mapping.txt to read exactly: most methods carry no line table (minSdk 26 lets R8 print instruction offsets that
# the mapping turns back into source lines), and R8 inlines and merges code, so a frame can name the class a
# method was moved into. release.yml attaches the mapping to each GitHub release as mapping-<tag>.txt.gz, and
# CONTRIBUTING.md (Release builds) shows the retrace command.
-keepattributes SourceFile,LineNumberTable

# Exceptions keep their own class. Otherwise R8 merges unrelated exceptions into one class: PoTokenException,
# DashManifestException and LinkStoreUnavailable were all created as androidx.camera.core.ImageCaptureException,
# and kotlin.NoWhenBranchMatchedException as coil3.network.HttpException. Crash files, logcat and the
# diagnostics' "(X)" lines print the type, and retrace can't put it back. -keepnames still lets R8 remove the
# exceptions nothing throws.
-keepnames class * extends java.lang.Throwable

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

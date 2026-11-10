# TipJarModule reads the supporters URL from :app's BuildConfig by name (Class.forName + getField), so that :core:data
# needn't depend on :app. Nothing references the field directly, so R8 would remove it and the supporters list would
# stop refreshing.
-keep class com.stash.app.BuildConfig { public static final java.lang.String SUPPORTERS_JSON_URL; }

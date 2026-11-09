# Tuning's engine (libstash_tuning.so, src/main/cpp/stash_tuning_jni.cpp) exports its functions as
# Java_com_stash_core_media_equalizer_dsp_SignalsmithNative_<method>, so this class and its native methods must keep
# exactly these names whenever the app is shrunk.
-keep class com.stash.core.media.equalizer.dsp.SignalsmithNative { native <methods>; }

# Tuning's pitch engine: Signalsmith Stretch behind a small JNI layer (stash_tuning_jni.cpp).
#
# Vendored, unmodified, MIT (notices in third_party/*/LICENSE.txt and in Settings > About):
#   third_party/signalsmith-stretch/signalsmith-stretch.h
#       github.com/Signalsmith-Audio/signalsmith-stretch, tag 1.4.0 (a670068d9aeb64913331d5cc29337b19a457a7df)
#   third_party/signalsmith-linear/{stft.h, fft.h}
#       github.com/Signalsmith-Audio/linear, tag 0.6.4 (de55e6a50ffcf6f8f43f649692d94691c7025151),
#       the version stretch 1.4.0 pins. Only the two headers the default (no Accelerate/IPP/PFFFT)
#       build includes.
# To update: copy the same files from the new tags and keep the linear tag the stretch tag pins.

cmake_minimum_required(VERSION 3.22.1)
project(stash_tuning CXX)

add_library(stash_tuning SHARED stash_tuning_jni.cpp)

target_include_directories(stash_tuning PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party
)

target_compile_features(stash_tuning PRIVATE cxx_std_17)

# Optimised in every variant, debug included: the stretcher is all number-crunching and runs up
# to 10x slower unoptimised (its README), which would stutter a debug build's playback. These come
# after CMAKE_CXX_FLAGS_DEBUG's -O0 on the command line, so they win.
target_compile_options(stash_tuning PRIVATE
    -O3
    -ffast-math
    -DNDEBUG
    -fvisibility=hidden
    -Wall
)

# NEON: always there on arm64; on armeabi-v7a the NDK has enabled it by default since r21, said
# again here so the engine never silently builds without it.
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    target_compile_options(stash_tuning PRIVATE -mfpu=neon)
endif()

# 16 KB pages (Android 15+ devices that use them): align the segments so the library still loads.
target_link_options(stash_tuning PRIVATE
    -Wl,-z,max-page-size=16384
    -Wl,--gc-sections
)

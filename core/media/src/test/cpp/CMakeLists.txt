# Host build of Tuning's engine core and its pitch test (tuning_pitch_test.cpp), for CI:
#   cmake -S core/media/src/test/cpp -B build/tuning-host -DCMAKE_BUILD_TYPE=Release
#   cmake --build build/tuning-host
#   ctest --test-dir build/tuning-host --output-on-failure
# The same flags as the app's build (src/main/cpp/CMakeLists.txt), so the numbers match a phone's.

cmake_minimum_required(VERSION 3.16)
project(stash_tuning_host_test CXX)

set(MAIN_CPP ${CMAKE_CURRENT_SOURCE_DIR}/../../main/cpp)

# The same guard as the app's build: the vendored header must carry the STASH PATCH.
file(READ ${MAIN_CPP}/third_party/signalsmith-stretch/signalsmith-stretch.h STRETCH_HEADER)
string(FIND "${STRETCH_HEADER}" "STASH PATCH" STASH_PATCH_AT)
if(STASH_PATCH_AT EQUAL -1)
    message(FATAL_ERROR "signalsmith-stretch.h is missing the STASH PATCH")
endif()

add_executable(tuning_pitch_test tuning_pitch_test.cpp)
target_include_directories(tuning_pitch_test PRIVATE ${MAIN_CPP} ${MAIN_CPP}/third_party)
target_compile_features(tuning_pitch_test PRIVATE cxx_std_17)
if(MSVC)
    target_compile_options(tuning_pitch_test PRIVATE /O2 /fp:fast /EHsc)
    target_compile_definitions(tuning_pitch_test PRIVATE _USE_MATH_DEFINES NDEBUG)
else()
    target_compile_options(tuning_pitch_test PRIVATE -O3 -ffast-math -DNDEBUG -Wall)
endif()

enable_testing()
add_test(NAME tuning_pitch COMMAND tuning_pitch_test)

# android/native/CMakeLists.txt -- the S1 device-feasibility probe.
#
# A standalone cross-build (no Gradle): it consumes the upstream engine
# unmodified through add_subdirectory() and adds two probe executables plus,
# with -DBUILD_TESTING=ON, the upstream ctest suite exactly as it is. Nothing
# under engine/ is edited by this file or by anything it builds.
#
# Configure (scripts/android-probe.sh wraps this):
#
#   cmake -S android/native -B build/android -G Ninja \
#     -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
#     -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 -DANDROID_STL=c++_static \
#     -DSPEKTRALAB_REPO="$PWD" \
#     -DSPEKTRALAB_VULKAN_HEADERS="$PWD/build/deps/Vulkan-Headers/include" \
#     -DSPEKTRALAB_LIBRAW_SOURCE="$PWD/build/deps/LibRaw-0.22.2" \
#     -DSPEKTRALAB_GLSLANG="$HOME/tools/bin/glslangValidator" \
#     -DCMAKE_BUILD_TYPE=Release -DBUILD_TESTING=ON
#
# The engine's own find_path/find_program for the Vulkan headers and glslang
# are re-rooted by the Android toolchain file, so both are passed explicitly
# here (see android/README.md).
cmake_minimum_required(VERSION 3.22)
project(spektralab_android_probe LANGUAGES C CXX)

foreach(required SPEKTRALAB_REPO SPEKTRALAB_VULKAN_HEADERS SPEKTRALAB_GLSLANG)
    if(NOT ${required})
        message(FATAL_ERROR
            "-D${required}=... is required; run scripts/android-probe.sh instead of cmake directly")
    endif()
endforeach()

# BUILD_TESTING + enable_testing() at the top level, so the upstream
# `if(BUILD_TESTING)` blocks in engine/CMakeLists.txt produce every spk_*
# test target without touching the upstream file.
include(CTest)

set(SPEKTRALAB_BUILD_NATIVE_RAW ON CACHE BOOL "android probe" FORCE)
set(SPEKTRALAB_BUILD_DESKTOP OFF CACHE BOOL "android probe" FORCE)
set(SPEKTRALAB_BUILD_HOST OFF CACHE BOOL "android probe" FORCE)
set(SPEKTRALAB_BUILD_TEST_DLL ON CACHE BOOL "android probe" FORCE)
set(SPEKTRALAB_REQUIRE_VULKAN ON CACHE BOOL "android probe" FORCE)

add_subdirectory("${SPEKTRALAB_REPO}/engine" engine)

# 16 KB page alignment is required by Android 15+ devices that use 16 KB
# pages; the $ORIGIN rpath lets the binaries run straight from
# /data/local/tmp/spk/engine next to the shared test engine.
set(SPEKTRALAB_ANDROID_LINK_OPTIONS "-Wl,-z,max-page-size=16384" "-Wl,-rpath,\$ORIGIN")

# Every probe is part of the S1 acceptance evidence, so its own optimisation
# state is printed at run time (vulkan_device_info) rather than assumed: a
# -O0 engine silently invalidates each number the probe reports.
function(spektralab_add_probe target)
    add_executable(${target} ${ARGN})
    target_link_libraries(${target} PRIVATE spektralab_engine)
    # spektralab_engine keeps its include dir PRIVATE (the upstream tests add
    # it themselves); the probes need the C ABI header and nothing else.
    target_include_directories(${target} PRIVATE "${SPEKTRALAB_REPO}/engine/include")
    target_link_options(${target} PRIVATE ${SPEKTRALAB_ANDROID_LINK_OPTIONS})
    # One staging directory: the probes sit next to the upstream test binaries
    #, the shared test engine and `resources/`, so one `adb push` of
    # `<build>/engine` carries everything the device run needs.
    set_target_properties(${target} PROPERTIES RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/engine")
    add_dependencies(${target} spektralab_vulkan_resources)
endfunction()

# vulkan_device_info: capabilities, device limits, memory heaps, budget.
# Links spk_volk directly: it enumerates the physical devices itself so the
# four limits vulkan_gpu.cpp gates on are visible even when the engine refuses
# to start on them.
spektralab_add_probe(vulkan_device_info tests/vulkan_device_info.cpp)
target_link_libraries(vulkan_device_info PRIVATE spk_volk)

# device_baseline: the S1 timings and memory table (DEVICE-BASELINE.md).
spektralab_add_probe(device_baseline tests/device_baseline.cpp)

# decode_probe: the S3 decode bridge and the S6 PNG16 writer, exercised against
# files on the host so their answers can be compared with spektralab-host's
# before either is trusted on a phone. It compiles the same sources the JNI
# library does (neither includes jni.h), plus the host I/O units they call.
if(NOT ANDROID)
    include("${CMAKE_CURRENT_SOURCE_DIR}/../cmake/host_io.cmake")
    add_executable(decode_probe
        tests/decode_probe.cpp
        "${CMAKE_CURRENT_SOURCE_DIR}/../engine/src/main/cpp/jni/decode_bridge.cpp"
        "${CMAKE_CURRENT_SOURCE_DIR}/../engine/src/main/cpp/jni/png16.cpp"
        ${SPEKTRALAB_HOST_IO_SOURCES})
    target_include_directories(decode_probe PRIVATE
        "${SPEKTRALAB_REPO}/engine/include"
        "${CMAKE_CURRENT_SOURCE_DIR}/../engine/src/main/cpp/jni"
        ${SPEKTRALAB_HOST_IO_INCLUDES})
    target_link_libraries(decode_probe PRIVATE spektralab_engine spektralab_core spk_image_io spk_libraw)
    set_target_properties(decode_probe PROPERTIES RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/engine")
endif()

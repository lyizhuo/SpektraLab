# android/engine/CMakeLists.txt -- libspektralab.so, the one native library the
# app loads: the upstream engine, the host file-I/O units it already has, and
# the JNI facade, statically linked into a single shared object.
#
# The engine itself arrives through add_subdirectory(); this file owns the
# JNI layer and the packaging flags, and nothing else. android/README.md
# records which upstream files (if any) a given build diverges from.
#
# Configured by android/engine/build.gradle.kts, which passes SPEKTRALAB_REPO,
# SPEKTRALAB_VULKAN_HEADERS, SPEKTRALAB_LIBRAW_SOURCE and SPEKTRALAB_GLSLANG
# (the Android toolchain file re-roots find_path/find_program, so they cannot
# be searched for here).
cmake_minimum_required(VERSION 3.22)
project(spektralab_android LANGUAGES C CXX)

foreach(required SPEKTRALAB_REPO SPEKTRALAB_VULKAN_HEADERS SPEKTRALAB_LIBRAW_SOURCE SPEKTRALAB_GLSLANG)
    if(NOT ${required})
        message(FATAL_ERROR "-D${required}=... is required; build through android/engine/build.gradle.kts")
    endif()
endforeach()

# The engine's own ctest targets are not wanted inside an APK; android/native
# builds exactly the same sources with BUILD_TESTING=ON for the device runs.
set(BUILD_TESTING OFF CACHE BOOL "android library" FORCE)
set(SPEKTRALAB_BUILD_NATIVE_RAW ON CACHE BOOL "android library" FORCE)
set(SPEKTRALAB_BUILD_DESKTOP OFF CACHE BOOL "android library" FORCE)
set(SPEKTRALAB_BUILD_HOST OFF CACHE BOOL "android library" FORCE)
set(SPEKTRALAB_BUILD_TEST_DLL OFF CACHE BOOL "android library" FORCE)
set(SPEKTRALAB_REQUIRE_VULKAN ON CACHE BOOL "android library" FORCE)

add_subdirectory("${SPEKTRALAB_REPO}/engine" engine)

set(SPEKTRALAB_ANDROID_JNI "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp/jni")

# The host's portable I/O units, exactly as engine/host/CMakeLists.txt
# composes them for `spektralab-host`, minus main.cpp (the stdio transport);
# the shared list is android/cmake/host_io.cmake, which android/native's
# decode_probe uses as well.
include("${CMAKE_CURRENT_SOURCE_DIR}/../cmake/host_io.cmake")

add_library(spektralab_android SHARED
    "${SPEKTRALAB_ANDROID_JNI}/engine_jni.cpp"
    "${SPEKTRALAB_ANDROID_JNI}/decode_bridge.cpp"
    "${SPEKTRALAB_ANDROID_JNI}/png16.cpp"
    ${SPEKTRALAB_HOST_IO_SOURCES})

target_compile_features(spektralab_android PRIVATE cxx_std_20)
# `nativeBuildInfo()` reports this: the plan's S1 acceptance turns on the
# library being optimised, and CMake is the only place that actually knows.
target_compile_definitions(spektralab_android PRIVATE
    SPEKTRALAB_ANDROID_CMAKE_BUILD_TYPE="${CMAKE_BUILD_TYPE}")
target_include_directories(spektralab_android PRIVATE
    "${SPEKTRALAB_REPO}/engine/include"
    "${SPEKTRALAB_REPO}/engine/src"
    ${SPEKTRALAB_HOST_IO_INCLUDES}
    "${SPEKTRALAB_ANDROID_JNI}")
target_link_libraries(spektralab_android PRIVATE
    spektralab_engine spektralab_core spk_image_io spk_libraw
    android log jnigraphics)
set_target_properties(spektralab_android PROPERTIES OUTPUT_NAME spektralab)

# 16 KB page alignment: required by Android 15+ devices that use 16 KB pages,
# and harmless elsewhere. The engine's Vulkan loader is not linked (volk
# dlopens libvulkan.so), so there is no NDK Vulkan stub in the graph either.
target_link_options(spektralab_android PRIVATE "-Wl,-z,max-page-size=16384")

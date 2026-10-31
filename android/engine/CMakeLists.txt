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

# Normalise the incoming paths before anything interpolates them.
#
# Gradle hands these over as native paths, so on Windows they arrive as
# `D:\200_Coding\SpektraLab`, and a quoted CMake string treats every backslash
# as an escape sequence: `\2` is not one, and configure dies with
#
#   Syntax error in cmake code when parsing string
#     D:\200_Coding\SpektraLab/engine/host/image_io.cpp
#   Invalid character escape '\2'.
#
# It only bites on Windows, and only when a directory component starts with a
# digit — which is why the POSIX path this project was written on never saw it.
# Converting once here fixes every use in this file, in host_io.cmake and in
# the engine's own CMakeLists, and leaves POSIX paths unchanged.
foreach(normalise SPEKTRALAB_REPO SPEKTRALAB_VULKAN_HEADERS SPEKTRALAB_LIBRAW_SOURCE SPEKTRALAB_GLSLANG)
    file(TO_CMAKE_PATH "${${normalise}}" ${normalise})
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
    # Always compiled: the file defines `encode_heif` twice, once as the real
    # encoder and once as a stub that refuses with a reason, so `exportImage`
    # needs no `#if` of its own and a build without libheif answers through the
    # same channel as every other export failure. Compiling it only under the
    # flag was a link error, not a graceful absence.
    "${SPEKTRALAB_ANDROID_JNI}/heif_encode.cpp"
    ${SPEKTRALAB_HOST_IO_SOURCES})

# HEIC, off by default.
#
# libheif + x265 are ~10 MB of static library per ABI and are built out of band
# (`android/scripts/build-heif.ps1`) rather than by Gradle, so the ordinary
# build must not depend on them existing. `-Pspektralab.heif=true` turns this on
# and passes SPEKTRALAB_HEIF_ROOT; without both, `encode_heif` is the stub and
# `HeifNative.available` is false, which the export page already treats as "this
# format is not offered".
if(SPEKTRALAB_WITH_HEIF)
    set(heif_dir "${SPEKTRALAB_HEIF_ROOT}/${ANDROID_ABI}")
    if(NOT EXISTS "${heif_dir}/lib/libheif.a")
        message(FATAL_ERROR
            "SPEKTRALAB_WITH_HEIF is on but ${heif_dir}/lib/libheif.a is missing; "
            "run android/scripts/build-heif.ps1 first, or build without -Pspektralab.heif=true.")
    endif()

    target_sources(spektralab_android PRIVATE "${SPEKTRALAB_ANDROID_JNI}/heif_encode.cpp")
    target_include_directories(spektralab_android PRIVATE "${heif_dir}/include")
    # The HEVC encoder comes after libheif, because these are static archives
    # and link order is the algorithm. Which one is built into a prefix is the
    # script's decision; both are listed so a prefix carrying either links.
    target_link_libraries(spektralab_android PRIVATE "${heif_dir}/lib/libheif.a")
    foreach(hevc kvazaar x265)
        if(EXISTS "${heif_dir}/lib/lib${hevc}.a")
            target_link_libraries(spektralab_android PRIVATE "${heif_dir}/lib/lib${hevc}.a")
            message(STATUS "HEIC HEVC encoder: ${hevc}")
        endif()
    endforeach()
    target_compile_definitions(spektralab_android PRIVATE SPEKTRALAB_WITH_HEIF=1)
    message(STATUS "HEIC encoder: libheif from ${heif_dir}")
endif()

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

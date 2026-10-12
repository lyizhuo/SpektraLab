# android/cmake/host_io.cmake -- the engine/host translation units the Android
# side links, in one place.
#
# `spektralab-host` composes the same units (engine/host/CMakeLists.txt) plus
# main.cpp (the stdio transport) and its Windows bits; Android replaces the
# transport with JNI, so this is the shared remainder.
#
# platform.cpp is not the CLI layer: it carries utf8_path / open_file /
# read_file / write_file_atomic / hardware_threads, which image_io.cpp and
# output.cpp call, and its Windows-specific code is behind _WIN32.
set(SPEKTRALAB_HOST_IO_SOURCES
    "${SPEKTRALAB_REPO}/engine/host/image_io.cpp"
    "${SPEKTRALAB_REPO}/engine/host/output.cpp"
    "${SPEKTRALAB_REPO}/engine/host/platform.cpp"
    "${SPEKTRALAB_REPO}/engine/host/stb_impl.cpp"
    "${SPEKTRALAB_REPO}/engine/host/tiff_ifd.cpp"
    "${SPEKTRALAB_REPO}/engine/host/white_balance.cpp")

set(SPEKTRALAB_HOST_IO_INCLUDES
    "${SPEKTRALAB_REPO}/engine/host"
    "${SPEKTRALAB_REPO}/engine/third_party/stb")

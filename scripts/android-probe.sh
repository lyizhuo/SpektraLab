#!/usr/bin/env bash
# scripts/android-probe.sh -- the S1 device-feasibility probe, end to end.
#
#   scripts/android-probe.sh build      cross-compile android/native (+ ctests)
#   scripts/android-probe.sh push       stage and push build output to the device
#   scripts/android-probe.sh test       run every spk_* test in /data/local/tmp
#   scripts/android-probe.sh baseline   run vulkan_device_info + device_baseline
#   scripts/android-probe.sh pull       pull the device logs into build/android-logs
#   scripts/android-probe.sh all        build, push, test, baseline, pull
#
# Environment (all optional, shown with their defaults):
#   ANDROID_NDK_HOME  ~/Android/Sdk/ndk/28.2.13676358
#   ANDROID_ABI       arm64-v8a        (x86_64 for an emulator)
#   ANDROID_SERIAL    (none: adb's own default device)
#   ANDROID_PLATFORM  android-29
#   GLSLANG           ~/tools/bin/glslangValidator
#   BUILD_DIR         build/android-$(abi)
#   BASELINE_ARGS     ""               extra flags for device_baseline, e.g.
#                                      "--sources 12 --skip-full --reps 3"
#   PROBE_RUN_DIR     /data/local/tmp/spk
#
# The probe reads the upstream engine through add_subdirectory(); nothing under
# engine/ is edited. `engine/setup-deps.sh` is run once when build/deps is
# missing, since LibRaw and the Vulkan headers are pinned there.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/.." && pwd)"

ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/28.2.13676358}"
ANDROID_ABI="${ANDROID_ABI:-arm64-v8a}"
ANDROID_PLATFORM="${ANDROID_PLATFORM:-android-29}"
GLSLANG="${GLSLANG:-$HOME/tools/bin/glslangValidator}"
BUILD_DIR="${BUILD_DIR:-$repo/build/android-$ANDROID_ABI}"
BASELINE_ARGS="${BASELINE_ARGS:-}"
PROBE_RUN_DIR="${PROBE_RUN_DIR:-/data/local/tmp/spk}"

adb_bin() {
    if [[ -n "${ANDROID_HOME:-}" && -x "$ANDROID_HOME/platform-tools/adb" ]]; then
        echo "$ANDROID_HOME/platform-tools/adb"
    elif [[ -x "$HOME/Android/Sdk/platform-tools/adb" ]]; then
        echo "$HOME/Android/Sdk/platform-tools/adb"
    else
        command -v adb
    fi
}

adb_cmd() {
    local bin; bin="$(adb_bin)"
    if [[ -n "${ANDROID_SERIAL:-}" ]]; then "$bin" -s "$ANDROID_SERIAL" "$@"; else "$bin" "$@"; fi
}

# The tests that carry S1's acceptance evidence, in the order the plan lists
# them: the two probes first, then the engine's own suite. Every one of them
# takes the resources directory as its only argument.
probe_tests=(
    vulkan_device_info
    spk_core_schema
    spk_core_setup
    spk_vulkan_smoke
    spk_vulkan_grain
    spk_vulkan_pointwise
    spk_vulkan_resample
    spk_vulkan_transfer
    spk_vulkan_geometry
    spk_vulkan_lut_di
    spk_windows_feature_boundaries
    spk_windows_c_abi
    spk_raw_decoder_errors
    spk_raw_metadata_link
    spk_raw_headroom
)

require_deps() {
    local deps="$repo/build/deps"
    if [[ ! -d "$deps/Vulkan-Headers/include" || ! -d "$deps/LibRaw-0.22.2" ]]; then
        echo "== fetching pinned dependencies (Vulkan-Headers, LibRaw 0.22.2)" >&2
        "$repo/engine/setup-deps.sh" "$deps" >&2
    fi
    [[ -x "$GLSLANG" ]] || { echo "glslangValidator not found at $GLSLANG" >&2; exit 1; }
    [[ -d "$ANDROID_NDK_HOME" ]] || { echo "NDK not found at $ANDROID_NDK_HOME" >&2; exit 1; }
}

do_build() {
    require_deps
    cmake -S "$repo/android/native" -B "$BUILD_DIR" -G Ninja \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$ANDROID_ABI" -DANDROID_PLATFORM="$ANDROID_PLATFORM" -DANDROID_STL=c++_static \
        -DSPEKTRALAB_REPO="$repo" \
        -DSPEKTRALAB_VULKAN_HEADERS="$repo/build/deps/Vulkan-Headers/include" \
        -DSPEKTRALAB_LIBRAW_SOURCE="$repo/build/deps/LibRaw-0.22.2" \
        -DSPEKTRALAB_GLSLANG="$GLSLANG" \
        -DBUILD_TESTING=ON
    ninja -C "$BUILD_DIR"
    echo "== built $BUILD_DIR/engine"
}

do_push() {
    adb_cmd shell "rm -rf $PROBE_RUN_DIR && mkdir -p $PROBE_RUN_DIR"
    adb_cmd push "$BUILD_DIR/engine" "$PROBE_RUN_DIR/engine"
    adb_cmd shell "cd $PROBE_RUN_DIR/engine && chmod +x ${probe_tests[*]} device_baseline"
}

do_test() {
    local list=""
    for t in "${probe_tests[@]}"; do list+="$t "; done
    # One shell round trip: each test runs from the staged directory with its
    # own log, and the exit status of each is echoed as `name=status`.
    adb_cmd shell "cd $PROBE_RUN_DIR/engine && export LD_LIBRARY_PATH=\$PWD && \
        for t in $list; do ./\"\$t\" resources >$PROBE_RUN_DIR/\"\$t\".log 2>&1; echo \"\$t=\$?\"; done"
}

do_baseline() {
    adb_cmd shell "cd $PROBE_RUN_DIR/engine && export LD_LIBRARY_PATH=\$PWD && \
        ./vulkan_device_info resources >$PROBE_RUN_DIR/vulkan_device_info.log 2>&1; echo \"vulkan_device_info=\$?\""
    adb_cmd shell "cd $PROBE_RUN_DIR/engine && export LD_LIBRARY_PATH=\$PWD && \
        ./device_baseline resources $PROBE_RUN_DIR/device_baseline.json $BASELINE_ARGS \
            >$PROBE_RUN_DIR/device_baseline.log 2>&1; echo \"device_baseline=\$?\""
    if [[ -n "${SPEKTRALAB_NODE_TIMINGS:-}" ]]; then
        adb_cmd shell "cd $PROBE_RUN_DIR/engine && export LD_LIBRARY_PATH=\$PWD && \
            SPEKTRAFILM_NODE_TIMINGS=1 ./device_baseline resources $PROBE_RUN_DIR/device_baseline_nodes.json \
                --sources 12 --skip-full --reps 2 --levers '' \
                >$PROBE_RUN_DIR/device_baseline_nodes.log 2>&1; echo \"device_baseline_nodes=\$?\""
    fi
}

do_pull() {
    local tag; tag="${ANDROID_SERIAL:-device}"
    local dir="$repo/build/android-logs/$tag"
    mkdir -p "$dir"
    adb_cmd pull "$PROBE_RUN_DIR" "$dir" >/dev/null
    echo "== logs in $dir"
    grep -h '=PASS\|=FAIL\|=PASSED\|=FAILED' "$dir"/spk/*.log 2>/dev/null || true
}

case "${1:-all}" in
    build) do_build ;;
    push) do_build; do_push ;;
    test) do_test ;;
    baseline) do_baseline ;;
    pull) do_pull ;;
    all) do_build; do_push; do_test; do_baseline; do_pull ;;
    *) echo "usage: $0 {build|push|test|baseline|pull|all}" >&2; exit 2 ;;
esac

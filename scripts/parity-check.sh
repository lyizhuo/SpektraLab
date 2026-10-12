#!/usr/bin/env bash
# scripts/parity-check.sh -- S7's cross-platform render check.
#
# The same frame and the same parameters on the device and on Linux, compared
# pixel by pixel against the engine's own bar: max absolute difference
# <= 3e-5 x 65535 code units (ARCHITECTURE.md §8.6, the same threshold the
# repository's `engine/tests/parity_*.py` enforce upstream).
#
#   scripts/parity-check.sh [fixture.tif] [--abi arm64-v8a]
#
# What it does:
#   1. decodes the fixture to `input.f32` (interleaved float32, the format
#      `spk_render_fixture` reads) with android/native's `decode_probe`, so both
#      machines start from the *same* floats -- the decode is not what is being
#      compared here;
#   2. renders it twice on the host with `spk_render_fixture` (that tool renders
#      the full tier and fails if two renders are not bit-identical, so the
#      parameters must be the deterministic ones below: no grain, no glare);
#   3. runs the same binary with the same arguments on the device;
#   4. compares the two RGBA16 dumps.
#
# Environment: ANDROID_SERIAL for a specific device, HOST_ENGINE to name a
# different host build tree, WORK for the scratch directory.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/.." && pwd)"

fixture="$repo/android/testdata/fixtures/portrait_leaves_32bit_linear_prophoto_rgb.tif"
abi="${ANDROID_ABI:-arm64-v8a}"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --abi) abi="$2"; shift 2 ;;
        --abi=*) abi="${1#*=}"; shift ;;
        *) fixture="$1"; shift ;;
    esac
done

work="${WORK:-$repo/build/parity}"
host_engine="${HOST_ENGINE:-$repo/build/linux-probe/engine}"
device_engine="$repo/build/android-$abi/engine"
device_dir="/data/local/tmp/spk-parity"
mkdir -p "$work"

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

for tool in "$host_engine/decode_probe" "$host_engine/spk_render_fixture" "$device_engine/spk_render_fixture"; do
    [[ -x "$tool" ]] || { echo "missing $tool (build it first: scripts/android-probe.sh build, and ninja -C build/linux-probe)" >&2; exit 1; }
done
[[ -f "$fixture" ]] || { echo "no such fixture: $fixture" >&2; exit 1; }

echo "== 1/4 decode the fixture on the host"
"$host_engine/decode_probe" f32 "$fixture" "$work/input.f32" | tee "$work/input.info"
width="$(sed -E 's/.*width=([0-9]+).*/\1/' "$work/input.info")"
height="$(sed -E 's/.*height=([0-9]+).*/\1/' "$work/input.info")"

# Grain and glare are stochastic and `spk_render_fixture` insists on two
# bit-identical renders, so both are off; everything else is the default.
cat > "$work/params.json" <<'JSON'
{"grain_active": false, "glare_active": false}
JSON

echo "== 2/4 render on the host (full tier, ${width}x${height})"
"$host_engine/spk_render_fixture" "$host_engine/resources" "$work/input.f32" "$width" "$height" \
    "$work/params.json" "$work/host.rgba16" "$work/host_report.json"

echo "== 3/4 render on the device ($abi)"
adb_cmd shell "rm -rf $device_dir && mkdir -p $device_dir"
adb_cmd push "$device_engine" "$device_dir/engine" >/dev/null
adb_cmd push "$work/input.f32" "$device_dir/input.f32" >/dev/null
adb_cmd push "$work/params.json" "$device_dir/params.json" >/dev/null
adb_cmd shell "cd $device_dir/engine && chmod +x spk_render_fixture && ./spk_render_fixture resources \
    $device_dir/input.f32 $width $height $device_dir/params.json $device_dir/device.rgba16 $device_dir/device_report.json"
adb_cmd pull "$device_dir/device.rgba16" "$work/device.rgba16" >/dev/null
adb_cmd pull "$device_dir/device_report.json" "$work/device_report.json" >/dev/null

echo "== 4/4 compare"
python3 - "$work/host.rgba16" "$work/device.rgba16" "$work/host_report.json" "$work/device_report.json" <<'PY'
import json
import struct
import sys

def load(path):
    with open(path, 'rb') as handle:
        data = handle.read()
    return struct.unpack('<%dH' % (len(data) // 2), data)

host = load(sys.argv[1])
device = load(sys.argv[2])
if len(host) != len(device):
    print('FAIL: different byte counts %d vs %d' % (len(host), len(device)))
    sys.exit(1)

bar = 3e-5 * 65535.0
worst = 0
total = 0
over = 0
for a, b in zip(host, device):
    delta = abs(a - b)
    total += delta
    if delta > worst:
        worst = delta
    if delta > bar:
        over += 1

report = {
    'samples': len(host),
    'max_abs': worst,
    'mean_abs': total / len(host),
    'over_bar_samples': over,
    'bar': bar,
    'host_report': json.load(open(sys.argv[3])),
    'device_report': json.load(open(sys.argv[4])),
}
print(json.dumps(report, indent=1))
if over:
    print('FAIL: %d samples above %.4f code units (3e-5 of full scale)' % (over, bar))
    sys.exit(1)
print('PASS: max_abs %.4f <= %.4f code units' % (worst, bar))
PY

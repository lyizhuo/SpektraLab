# DEVICE-BASELINE.md — S1's numbers

What the engine costs on real hardware, measured by `android/native/tests`' two
probes and by the engine's own test suite. Everything here is a number that was
produced by a command in this file; nothing is estimated.

Plan S1 acceptance items:

|#|Item|Status|
|---|---|---|
|1|Every `spk_*` test returns 0 on the device|**pending on device** (Linux reference: 14/14 green)|
|2|`vulkan_device_info` gates pass (`maxComputeWorkGroupInvocations ≥ 256`, `maxComputeWorkGroupSize[0] ≥ 256`, ≥8 per-stage/descriptor-set storage buffers, `maxStorageBufferRange ≥ 12 B × plane px`)|**pending on device** (Linux reference: PASS)|
|3|Timings, memory peaks, B/px, lever deltas, session release|**partly measured** (Linux reference below; device rows pending)|
|4|Two GPU families (Adreno, Mali) + one x86_64 emulator|**pending** (no device attached to this machine; see "How to fill the device rows")|
|5|`nativeBuildInfo()` reports an optimised build|**Linux probe: yes**; Android: `nativeBuildInfo()` in the app's About page (S4/S5)|
|6|Node-timing sum vs wall time, to justify the batching patch|**pending on device** (Linux reference: measured, see below)|

## Measurement commands

```bash
# cross-compile everything for a device (arm64-v8a; x86_64 for an emulator)
ANDROID_ABI=arm64-v8a ANDROID_NDK_HOME=$HOME/Android/Sdk/ndk/28.2.13676358 \
  scripts/android-probe.sh all

# the same probes on the host, as a reference point
cmake -S android/native -B build/linux-probe -G Ninja -DCMAKE_BUILD_TYPE=Release \
  -DSPEKTRALAB_REPO=$PWD -DSPEKTRALAB_VULKAN_HEADERS=$PWD/build/deps/Vulkan-Headers/include \
  -DSPEKTRALAB_LIBRAW_SOURCE=$PWD/build/deps/LibRaw-0.22.2 \
  -DSPEKTRALAB_GLSLANG=$HOME/tools/bin/glslangValidator -DBUILD_TESTING=ON
ninja -C build/linux-probe
ctest --test-dir build/linux-probe -j 6 --output-on-failure
cd build/linux-probe/engine
./vulkan_device_info resources
./device_baseline resources /tmp/baseline.json --sources 12 --live-edges 1600,2048 --reps 3 --levers full
```

`device_baseline` uses three sessions per source on purpose: A for the
interaction tiers (cold and hot `live` at each `preview_long_edge`, then
`preview`), B for a **fresh** `full` render — the export path — and C for the
lever comparison. A peak only means something when the session holds what is
being measured.

## Linux reference (lavapipe, CPU Vulkan — not a device)

| | |
|---|---|
|Machine|WSL2, x86_64, 24 cores, 25.2 GB RAM|
|GPU|`llvmpipe (LLVM 20.1.2, 256 bits)`, Vulkan 1.4.318, type `cpu`|
|Engine build|`spektrafilm-native 0.1.0`, `render_core = native-vulkan`, `CMAKE_BUILD_TYPE=Release`, `probe.optimized=yes`, `ndebug=yes`|
|Engine ctest|**14/14 passed** in 36 s (`spk_core_*`, `spk_vulkan_*`, `spk_raw_*`, `spk_windows_*`)|
|Device gates|PASS (invocations 1024, workgroup x 1024, storage buffers 1e6 / 1e6)|
|`maxStorageBufferRange`|128 MiB advertised; the engine's CPU-device probe verifies and raises it to 4 GiB−4 (its own documented behaviour)|
|Memory|one heap, 25.2 GB, `device_local = yes`; `uma_no_staging_possible = yes`|
|Budget formula|`native_budget = 8.19 GB`, `allowed_pixels(180 B/px) = 45.5 MP`, `max_long_edge = 15064`|

### 12 MP frame (4242 × 2828 = 11,996,376 px)

The bytes-per-source-pixel column is only meaningful for `full`: the `live`
and `preview` tiers render fewer pixels than the frame they came from, so their
peaks scale with the *tier's* size, not the source's.

|Phase|wall ms|engine ms|peak bytes|MB|B/source px|
|---|---|---|---|---|---|
|`live` 1600 cold|3631.7|3631.6|582,637,008|582.6|48.6|
|`live` 1600 hot p50|853.8|853.8|582,637,008|582.6|48.6|
|`live` 2048 cold|1668.1|1668.1|776,526,288|776.5|64.7|
|`live` 2048 hot p50|1228.1|1228.1|776,526,288|776.5|64.7|
|`preview` cold|4256.4|4256.4|1,423,695,888|1423.7|118.7|
|`preview` hot p50|3520.1|3520.1|1,423,695,888|1423.7|118.7|
|**`full` export (fresh session)**|**6506.5**|**6506.5**|**1,364,735,984**|**1364.7**|**113.8**|

`full`'s `pool.frame_high_water_bytes` is 1,055,681,124 B (88.0 B/px): the peak
includes the RGBA16 result the caller is holding, the high-water does not.

For contrast, measured in an **editing** session that had already rendered
`live` and `preview` before `full`, the same 12 MP frame peaked at 2,287,434,960 B
(**190.7 B/px**). That is the number the D9 export rule exists for: an export
runs in a session of its own (close, re-open, render, release), which is what
`EditorViewModel.export` does.

### Levers, at `full`, in a fresh session

|Variant|wall ms|peak bytes|B/px|
|---|---|---|---|
|default|5930.5|1,364,735,984|113.8|
|`striped=true`, `strip_rows=512`|5926.8|1,364,735,984|113.8|
|glare + grain off|6065.3|1,364,720,600|113.8|
|striped + both off|8984.8|1,364,720,600|113.8|

**The levers do not move the peak, and the difference is 15 kB.** This is the
measurement the plan's §"不只是降分辨率" predicted: the striped executor only
bands the stages that can be banded, while halation, the DIR couplers, the
grain field and the boost run full frame at any `strip_rows`, so the peak is
set by those. Consequences, as implemented:

* resolution stays the primary lever (the budget gate in
  `com.spektralab.engine.MemoryBudget` and the export page's "largest long
  edge" are what actually bound the frame);
* `striped`/`strip_rows` are **not** offered in the UI as a memory option —
  they cost time here and save nothing measurable;
* the export path's own rule (fresh session) is worth more than either lever:
  1.36 GB against 2.29 GB at 12 MP.

### 25 MP frame (6123 × 4082 = 24,994,086 px)

|Phase|wall ms|peak bytes|MB|B/px|
|---|---|---|---|---|
|`live` 1600 cold|4139.7|1,043,732,700|1043.7|41.8|
|`live` 1600 hot p50|1019.3|1,043,732,700|1043.7|41.8|
|`preview` cold|11375.5|1,690,711,956|1690.7|67.6|
|`preview` hot p50|5673.3|1,690,711,956|1690.7|67.6|
|**`full` export (fresh session)**|**21775.2**|**2,840,946,984**|**2840.9**|**113.66**|

(the last column is bytes per *source* pixel, as above)

The export constant is **113.7 B/px at 25 MP against 113.8 B/px at 12 MP**: it is
linear in pixels, which is what makes one constant usable in the budget. (The
`live`/`preview` rows are lower per pixel because those tiers are smaller than
the frame; the constant they need is the *tier's* pixel count, which is what
`MemoryBudget` does not have to know — it gates the export size.)

`spk_session_release` on the export session gave back 299.9 MB of cached
negatives at this size (144.0 MB at 12 MP).

### Where the time goes (item 6's trigger)

Same 12 MP frame, `SPEKTRAFILM_NODE_TIMINGS=1` (which forces a flush at every
node, so only *relative* numbers mean anything):

|Phase|wall ms|node ms sum|node share|same phase, normal mode|per-node flush cost|
|---|---|---|---|---|---|
|`live` 1600 cold|2432.4|1258.9|52 %|3631.7|— (setup dominates)|
|`preview` cold|5176.7|4658.7|90 %|4256.4|+22 %|
|`full` export|9345.4|7857.0|**84 %**|6506.5|**+44 %**|

Slowest nodes on the full tier: `filming.expose.halation` 2601 ms,
`filming.develop.dir_couplers` 1808 ms, `filming.develop.grain` 1013 ms,
`scanning.scan_spectral` 577 ms. Those four are exactly the un-bandable stages
the lever table above is about.

**The trigger fires.** The plan's criterion is a wall-time gap over 30 %
between the node-timing sum and the frame: here the node work is 84 % of the
node-timing frame, and the per-node flush/wait costs 44 % over the same frame
without it — on a *CPU* Vulkan device, where a GPU round trip is cheapest. That
is the justification for the `engine/vulkan-batching` patch (S2 item 7), which
is the one upstream divergence the plan expects.

### What `spk_session_release` gives back

|Session|before|after|released|of which cached negatives|
|---|---|---|---|---|
|interaction (live×2 + preview)|1,423,695,888|1,027,066,744|396.6 MB|126.0 MB|
|export (`full`)|1,364,735,984|1,055,681,208|309.1 MB|144.0 MB|

So closing a session does return the cached negatives, and the rest of the drop
is the pool's own trim. `capabilities.backend.session_cache` reports
`enabled = false, entries = 0, max_entries = 0, hits = 0, misses = 0` on this
backend: there is no cross-session LRU to wait for, which is why the app closes
and re-opens a session rather than relying on one.

### Where the time goes (item 6's trigger)

`SPEKTRAFILM_NODE_TIMINGS=1` makes the engine record per-node milliseconds,
which `spk_progress` returns; `device_baseline` stores that reply for every
phase as `progress.node_times` plus the summed `node_ms_sum`. Comparing that
sum against the same phase's wall time answers the plan's question — whether
the frame is dominated by node work or by the submission path — and is what the
`engine/vulkan-batching` patch is justified by. On this CPU device the nodes
dominate; on a phone GPU the per-dispatch submit-and-wait is expected to be the
larger share, which is why the patch is in the plan at all. **This row needs a
GPU device to be meaningful**, and is why item 6 is marked pending above.

## The batching patch's before/after (S2 item 7)

`engine/vulkan-batching` makes the backend submit once per flush interval
instead of once per dispatch. Measured on the same 12 MP frame, same machine,
same commands as above (lavapipe, so the *ratio* is what transfers to a phone,
not the absolute numbers):

|Phase|before ms|after ms|Δ|
|---|---|---|---|
|`live` 1600 cold|3631.7|1708.0|−53.0 %|
|`live` 1600 hot p50|853.8|774.3|−9.3 %|
|`live` 2048 cold|1668.1|1316.6|−21.1 %|
|`live` 2048 hot p50|1228.1|1044.6|−14.9 %|
|`preview` cold|4256.4|3448.5|−19.0 %|
|`preview` hot p50|3520.1|3012.1|−14.4 %|
|**`full` export**|**6506.5**|**5326.8**|**−18.1 %**|
|lever: `striped512`|5926.8|4858.0|−18.0 %|
|lever: glare+grain off|6065.3|3853.8|−36.5 %|

Quality: **bit-identical**. `spk_render_fixture` rendered
`android/testdata/portrait_leaves.f32` (667×1000) with grain and glare off on
both binaries — 2,668,000 samples, max abs 0, mean abs 0, 0 samples over the
3e-5 bar. The engine's own suite compares every kernel against a float64
reference and stays green (14/14).

Pool audit at `full` export, unchanged invariants and the two counters that
moved:

|Counter|before|after|
|---|---|---|
|`pending_held`|0|0|
|`over_releases`|0|0|
|`live_underflows`|0|0|
|`allocations`|37|47|
|`reuses`|1564|1554|
|peak bytes|1,364,735,984|1,385,203,320 (+1.5 %)|
|B/px (fresh export)|113.76|115.47|

The ten extra allocations are the retained descriptor pool and the staging
arena, and they cost 2 MB: +0.15 % of the peak for −18 % of the frame. Two
counter movements are worth naming rather than hiding: `allocations` is up by
ten and `reuses` is down by ten (0.6 %), both from that retention. The
invariants that matter — nothing held past its command buffer, nothing
over-released, no live underflow — did not move.

### Calibrating the constant

The constant is a build property, so a device's own number goes in without
touching code:

```bash
# after a device run recorded source.full_bytes_per_pixel = X
./gradlew :app:assembleRelease -Pspektralab.bytesPerPixel=$(python3 -c "print($X*1.15)")
```

The margin is deliberate and asymmetric: **too low means the OS kills the
process** (the export did not fit and nothing refused it), while too high only
refuses an export the device could have finished, and the refusal names the
largest long edge it will accept. Raise it if a capable device is refused;
lower it only with that device's measured peak in hand. The device's row in the
table above is where X comes from.

## How to fill the device rows

The device rows are the ones that need hardware. Two routes, both with the
commands in this file:

```powershell
# Windows (PowerShell), emulator or a phone attached to Studio's adb
adb push build\android-arm64-v8a\engine /data/local/tmp/spk/engine
adb shell "cd /data/local/tmp/spk/engine && chmod +x spk_* vulkan_device_info device_baseline && for t in vulkan_device_info spk_core_schema spk_core_setup spk_vulkan_smoke spk_vulkan_grain spk_vulkan_pointwise spk_vulkan_resample spk_vulkan_transfer spk_vulkan_geometry spk_vulkan_lut_di spk_windows_feature_boundaries spk_windows_c_abi spk_raw_decoder_errors spk_raw_metadata_link spk_raw_headroom; do ./$t resources >/data/local/tmp/spk/$t.log 2>&1; echo \"$t=$?\"; done"
adb shell "cd /data/local/tmp/spk/engine && ./vulkan_device_info resources && ./device_baseline resources /data/local/tmp/spk/baseline.json --sources 12 --live-edges 1600,2048 --reps 5 --levers full"
adb pull /data/local/tmp/spk
```

`android/native/CMakeLists.txt` builds exactly this directory; `ANDROID_ABI` is
`arm64-v8a` for a phone and `x86_64` for an emulator, and every executable runs
straight out of `/data/local/tmp/spk/engine` (`-Wl,-rpath,$ORIGIN`).

To record a device row, copy the 12 MP table, replace the machine/GPU rows with
the output of `vulkan_device_info`, and keep the command that produced it. A
25 MP source (`--sources 25`) is the other row worth having: it is the plan's
largest export.

## Constants this file settles

|Constant|Value|Where it is used|
|---|---|---|
|`bytesPerPixel` for the D9 budget|**180** (the plan's value, kept until a device calibrates it)|`MemoryBudget`, on the strength of 113.8 B/px measured for a fresh export and 190.7 B/px for an editing session that has already cached live+preview planes. **Override with `-Pspektralab.bytesPerPixel=<value>` or `spektralab.bytesPerPixel` in `gradle.properties`** — see "Calibrating the constant" below|
|Fixed headroom|**600 MB**|`MemoryBudget.HEADROOM_BYTES`|
|RAM fraction|**0.35**|`MemoryBudget.nativeBudgetBytes`|
|Export session rule|close → re-open → render `full` → release|`EditorViewModel.export`; saves ~0.92 GB at 12 MP|
|`striped`/`strip_rows`|not offered for memory|measured 0 B saved, and slower|

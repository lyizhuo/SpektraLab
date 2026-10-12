# TESTING.md — how to test each stage

The port is built and verified bottom-up, and the plan's stages are the test
plan. This file is the operator's manual: what to run, where, what a pass looks
like, and what to write down. It assumes the repository is at
`~/projects/SpektraLab` in WSL — Windows sees the same tree at
`\\wsl.localhost\<distro>\home\lyizhuo\projects\SpektraLab` (or clone it on
Windows and run `engine/setup-deps.sh` there once; nothing else is
platform-specific).

## One-time setup (Windows / Android Studio)

|What|How|
|---|---|
|NDK|SDK Manager → SDK Tools → *NDK (Side by side)* **28.2.13676358**. The version is pinned in `android/engine/build.gradle.kts` and `android/app/build.gradle.kts`; a different one is a different build.|
|CMake|SDK Manager → SDK Tools → **CMake 3.22.1** (AGP's external native build needs the SDK's CMake, not the system's).|
|SDK|Platform **36**, Build-Tools **36.0.0**, Platform-Tools.|
|Emulator|An **x86_64 API 36** image. Give it ≥4 GB RAM in the AVD settings and enable *GPU: Hardware* (Vulkan) — with software rendering the engine may report `gpu_available = false` and the app will show its read-only error page, which is a correct-but-useless result for performance work.|
|Pinned deps|`engine/setup-deps.sh` (run in WSL, or Git Bash with `bash`) fetches `build/deps/Vulkan-Headers` and `build/deps/LibRaw-0.22.2`; the Gradle build consumes them from there.|
|Shaders|Nothing to install: the NDK's own `glslc` compiles the 34 kernels, at build time, into the APK's assets.|
|Line endings|If `engine/*.sh` fails with `bash\r: No such file or directory`, the checkout has CRLF: `git config core.autocrlf false && git rm --cached -r . && git reset --hard`.|

Open `android/` in Android Studio. Sync uses the repository's Gradle wrapper
(9.7.1) and AGP 9.1, whose **built-in Kotlin** is why the Kotlin plugin is not
applied anywhere.

## S1 — the device probe

```powershell
# WSL: cross-compile for the emulator's ABI (or arm64-v8a for a phone)
ANDROID_ABI=x86_64 scripts/android-probe.sh build

# Windows: push and run the engine's own suite plus the two probes
adb push build\android-x86_64\engine /data/local/tmp/spk/engine
adb shell "cd /data/local/tmp/spk/engine && chmod +x spk_* vulkan_device_info device_baseline && for t in vulkan_device_info spk_core_schema spk_core_setup spk_vulkan_smoke spk_vulkan_grain spk_vulkan_pointwise spk_vulkan_resample spk_vulkan_transfer spk_vulkan_geometry spk_vulkan_lut_di spk_windows_feature_boundaries spk_windows_c_abi spk_raw_decoder_errors spk_raw_metadata_link spk_raw_headroom; do ./$t resources >/data/local/tmp/spk/$t.log 2>&1; echo \"$t=$?\"; done"
adb shell "cd /data/local/tmp/spk/engine && ./device_baseline resources /data/local/tmp/spk/baseline.json --sources 12 --live-edges 1600,2048 --reps 5 --levers full"
adb pull /data/local/tmp/spk
```

**A pass is** every `spk_*=0`, then `device.0.s1_gates=PASS` in
`vulkan_device_info.log`, then a `baseline.json`.

**Write down** (into `android/DEVICE-BASELINE.md`, replacing the pending rows):
the machine/SoC, `device.0.*` limits, the 12 MP table, the lever table, the
session-release deltas, `pool.audit.*`, and `source.full_bytes_per_pixel`.

`ANDROID_ABI=arm64-v8a` and a phone instead of an emulator gives the two
GPU-family rows the plan asks for (Adreno and Mali). Neither the emulator nor
this machine's WSL can supply them.

## S2 — the engine in the APK

```bash
./gradlew :engine:assembleDebug :app:assembleDebug
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -E 'libspektralab|assets/engine/vulkan'
%LOCALAPPDATA%\Android\Sdk\build-tools\36.0.0\zipalign.exe -c -P 16 -v 4 app\build\outputs\apk\debug\app-debug.apk
```

**A pass is** `lib/arm64-v8a/libspektralab.so`, `lib/x86_64/libspektralab.so`,
34 `assets/engine/vulkan/spk_*.spk`-style kernels, the profile JSONs, and
`zipalign` printing *Verification successful* (that is the 16 KB page rule).

Then open the app: **About** shows `nativeBuildInfo` — `optimized: true` and a
`cmake_build_type` of `RelWithDebInfo` (debug) or `Release`. If it says
`optimized: false`, stop: every timing below is meaningless.

After the `engine/vulkan-batching` patch lands, re-run S1's suite and put the
`live`/`preview` wall times and `pool.audit.*` before/after into
`DEVICE-BASELINE.md`.

## S3 — decoding

On the device (Studio's green arrow, or):

```bash
./gradlew :engine:connectedDebugAndroidTest --tests '*DecodeParityTest'
```

`DecodeParityTest.repositoryFixtureMatchesItsHostProbe` runs the repository
fixture through `probe` + `open` + `render`. For a camera file — which cannot
live in the repository — run `spektralab-host probe <file>` on Linux (WSL) and
pass its JSON to the test:

```bash
# WSL
build/host-linux-x64/spektralab-host probe /path/to/IMG_0001.ARW      # prints one JSON line
adb shell am instrument -w -e raw /sdcard/Download/IMG_0001.ARW \
  -e raw_probe '<that JSON>' \
  com.spektralab.engine.test/androidx.test.runner.AndroidJUnitRunner
```

**A pass is** `kind`, `width`, `height`, `make`, `model`, `datetime_original`
and the exposure fields agreeing with the host's probe, and the half-size
decode being smaller than the full one. Push the file first
(`adb push IMG_0001.ARW /sdcard/Download/`).

## S4 — the JNI facade

```bash
./gradlew :engine:connectedDebugAndroidTest --tests '*EngineSmokeTest'
```

**A pass is** both tests green. They assert the engine is optimised, the GPU is
available, tiers exist, `live` renders at `preview_long_edge`, the frame has at
least eight distinct luma buckets, `print_exposure` moves the mean by more than
one code value, `reprint` works, and `memoryReport` reports audit counters.

## S5 — the UI

```bash
./gradlew :app:connectedDebugAndroidTest
```

The three flows the plan names: *import and render*, *a parameter change
refreshing the canvas*, *crop then export*. Phone (**≤600dp**) and tablet
(**≥840dp**) screenshots are written by the tests into the app's
`Android/data/com.spektralab.app/files/screenshots/`; pull them with

```bash
adb pull /sdcard/Android/data/com.spektralab.app/files/screenshots android/screenshots
```

**A pass is** all three flows green and both screenshots present, with the
parameter panel showing no control for `digital_intermediate`,
`scene_latitude_mapping`, `contrast_mask`, `overscan` or `date_imprint` (the
engine refuses those by name on this backend, so a visible control would be a
lie).

## S6 — export and persistence

Covered by the app's instrumented tests plus a manual pass:

1. Export a TIFF16 to the album; the new file must appear in
   `Pictures/SpektraLab` and open in another app.
2. Kill and reopen the app, re-open the same photograph: the parameters must
   come back (the edits file in `filesDir/edits`).
3. Twenty parameter changes, then undo twenty times: back to the start.
4. Cancel an export mid-render: no pending MediaStore row (no invisible file
   in `Pictures/SpektraLab`), no leftover `cacheDir/export/*.part`.
5. Export beyond the device's budget: refused, with the largest usable long
   edge shown, and nothing written.

## S7 — device acceptance

```bash
# cross-platform pixel parity at the engine's own bar (max abs <= 3e-5 * 65535)
scripts/parity-check.sh --abi arm64-v8a            # phone
scripts/parity-check.sh --abi x86_64               # emulator

# performance and memory: the S1 table again, on the finished build
scripts/android-probe.sh baseline && scripts/android-probe.sh pull
```

**A pass is** `PASS: max_abs ... <= 1.9655` from `parity-check.sh`, the S1
numbers re-measured within 1.2× on the same machine, 12 MP export succeeding on
an 8 GB device and 25 MP on a 16 GB one (or the refusal naming the largest
usable long edge), and ten minutes of editing plus three exports with no ANR,
no native crash and no LMK kill (`adb logcat | grep -E 'FATAL|tombstone'`).

## When something fails

|Symptom|What it means|
|---|---|
|App shows the read-only error page with `gpu_available = false`|the emulator has no Vulkan (software rendering, or an image without it): switch the AVD to host GPU, or use a phone|
|`Vulkan probe: fma is not fused on a GPU device`|the driver contracts or splits `fma` — a real finding, record it with the device name; the engine refuses to start rather than rendering differently|
|`frame is too large for this GPU`|`max_texture_dimension_2d`; the message names the device's limit|
|`resolve_fd` failure, "this provider streams the file"|a document provider without a filesystem path (cloud storage): copy the file locally first|
|Gradle sync fails on `libs.versions.toml`|Android Studio is older than AGP 9.1; update Studio rather than downgrading the project|
|`glslc: error: unknown argument: '-V'`|the engine CMake is being read by an older checkout: this repository's `engine/CMakeLists.txt` passes the right flags for whichever compiler it is given|

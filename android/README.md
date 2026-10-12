# `android/` — the SpektraLab Android port

Everything new for Android lives under this directory plus `scripts/`. The
upstream engine (`engine/**`) is consumed **unmodified** through
`add_subdirectory()`; the only planned upstream edits travel on their own
`engine/<slug>` branches and are listed at the bottom of this file.

```
android/
  native/            S1: the device-feasibility probe (standalone CMake, no Gradle)
    CMakeLists.txt   include(CTest) + add_subdirectory(<repo>/engine)
    tests/           vulkan_device_info, device_baseline, decode_probe, probe_common.hpp
  engine/            S2/S3/S4: the Gradle library module that ships libspektralab.so
  app/               S5/S6: the Compose application
  cmake/             host_io.cmake: the engine/host units both builds link
  testdata/          the cross-platform fixtures (see "Fixtures" below)
  DEVICE-BASELINE.md S1/S7: every measured number, per device
  TESTING.md         the per-stage test recipes (Windows/Android Studio and WSL)
```

`scripts/` holds the drivers: `android-probe.sh` (build/push/test/baseline/pull)
and `parity-check.sh` (S7's cross-platform pixel comparison).

## S1 — the device probe

```bash
engine/setup-deps.sh                 # once: pinned Vulkan-Headers + LibRaw 0.22.2
scripts/android-probe.sh all         # build, push, run the engine's tests, measure
```

Environment (see the script for defaults): `ANDROID_NDK_HOME`, `ANDROID_ABI`
(`arm64-v8a`, or `x86_64` for an emulator), `ANDROID_SERIAL`, `GLSLANG`,
`BUILD_DIR`, `BASELINE_ARGS`, `PROBE_RUN_DIR`.

What it does, and what each step is evidence for:

1. `build` — cross-compiles the upstream engine **and** its ctest suite for the
   device, plus two probes. `-DBUILD_TESTING=ON` is what makes the upstream
   `spk_*` targets appear; no upstream file is edited. `CMAKE_BUILD_TYPE=Release`
   is set explicitly, because AGP's default for a debug variant is `Debug` and a
   `-O0` engine makes every number below meaningless (`vulkan_device_info`
   prints `probe.optimized` so this is checked, not assumed).
2. `push` — one directory (`build/<abi>/engine`: the executables, the shared test
   engine, `resources/` with the SPIR-V, the fixtures) into
   `/data/local/tmp/spk/engine`.
3. `test` — runs the two probes and every upstream `spk_*` test with the
   resources directory as their only argument. `spk_vulkan_*` compare each
   kernel against a float64 reference, which is the direct evidence that this
   GPU runs the backend correctly.
4. `baseline` — `vulkan_device_info` (limits, heaps, the S1 gates, the D9
   budget) and `device_baseline` (live/preview/full timings, memory peaks with
   and without the result alive, the striped/effect levers, what
   `spk_session_release` gives back). The JSON goes into
   `build/android-logs/<serial>/` and the numbers into `DEVICE-BASELINE.md`.
5. `pull` — the logs, for the record.

Runner notes: `-Wl,-rpath,$ORIGIN` is set on the probe binaries so they run
straight out of the staged directory, and every binary links with
`-Wl,-z,max-page-size=16384` for the Android 15+ devices that use 16 KB pages.
`glslangValidator` is a *host* tool: the Android toolchain file re-roots
`find_program`, so `SPEKTRALAB_GLSLANG` and `SPEKTRALAB_VULKAN_HEADERS` are
passed on the command line instead of being searched for.

## Engine rules an Android caller must respect

The full list is in `android/engine/README.md` (written with the JNI facade in
S4); the ones the probe already exercises:

- `spk_engine_create(resources_dir, nullptr)` — the Vulkan backend refuses a
  non-null `device`, and `spk_open_device` is refused too; the host-pixel
  `spk_open` is the only door.
- Tiers are exactly `"live" | "preview" | "full"`; the `live` tier's long edge is
  the session's `preview_long_edge` (800–8192), which is a *print-layer* field
  that still invalidates that tier's cached negative.
- `spk_result.rgba16` is valid only while the result handle is alive; on Vulkan
  `texture` owns a CPU copy, so the caller copies the pixels and calls
  `spk_result_free` immediately. `spk_result_free` does not need the engine.
- `spk_memory_report` is the only memory truth: `pressure.*` is always false on
  Vulkan (`vulkan_gpu.cpp` has no pressure monitor), so shrinking is driven by
  the application, not by the engine.

## Repository conventions this directory depends on

- **Line endings.** The repository stores LF, but a machine with
  `core.autocrlf=true` checks every script out as CRLF and `engine/*.sh` then
  fails with `bash\r: No such file or directory`. This checkout has
  `git config core.autocrlf false`.
- **Upstream sync.** New work stays in `android/` and `scripts/`; the baseline
  paths (`engine/src/**`, `engine/include/**`, `engine/CMakeLists.txt`,
  `engine/shaders/**`) stay byte-identical to `upstream/main` outside the
  `engine/<slug>` patch branches. `git diff --stat upstream/main...android/main`
  is the review entry point.

### The patch branches (the only divergence from upstream)

| Branch | What it changes | Trigger |
|---|---|---|
| `engine/vulkan-batching` | Per-node command-buffer batching, descriptor reuse, pre-created pipelines (S2, performance-engineering P0) | Always (the Vulkan path submits and waits once per dispatch; measured 44 % of the full-tier frame, DEVICE-BASELINE.md) |
| `engine/glslc-support` | The shader custom command passes `--target-env=vulkan1.0` when the compiler is `glslc` instead of `-V` | Always for Android: the NDK ships `glslc` and no `glslangValidator` |
| `engine/android-device-fallback` | Packed bindings for `spk_grain_layers`/`spk_grain_layer_one`, workgroup 256 → 128 | Only if a target device's limits fail the S1 gates |

### Fixtures

|File|What it is|
|---|---|
|`testdata/portrait_leaves_32bit_linear_prophoto_rgb.tif`|the plan's S7 fixture (from `andreavolpato/spektrafilm`): a 32-bit float TIFF with predictor 3, which the repository's reader **probes but cannot decode** (`TIFF predictor 3 not supported`) — the instrumented tests assert that refusal|
|`testdata/fixtures/portrait_leaves_16bit.tif`, `..._8bit.png`|the same photograph in encodings the decode path does read; generated once from the float TIFF and committed, because the render and decode tests need a real frame|
|`testdata/portrait_leaves.f32`|the fixture's exact float32 pixels, the input `scripts/parity-check.sh` renders on both machines|

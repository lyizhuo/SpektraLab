# `android/engine/` — libspektralab.so

One shared library holds everything native the app loads: the upstream engine
(core + pipeline + Vulkan backend + volk), the host's file-I/O units, LibRaw,
and the JNI facade. `curl -l` on the built AAR shows two `jni/<abi>/libspektralab.so`
and `assets/engine/**`; nothing else is native.

```
CMakeLists.txt      add_subdirectory(<repo>/engine) + libspektralab.so
build.gradle.kts    ABI filters, CMake args, the SPIR-V task, asset staging
src/main/cpp/jni/   engine_jni.cpp (the facade), decode_bridge.cpp (S3), png16.cpp
src/main/java/      EngineNative (the surface), EngineClient/EngineSession, MemoryBudget, EngineAssets
```

## The rules the C ABI imposes, and how this layer keeps them

Everything here comes from `engine/include/spektrafilm/spk_engine.h`; the ones
that have already caused a bug or a wrong number elsewhere are spelled out.

1. **Nothing crosses as a pointer.** Handles are `long` (a pointer in disguise);
   strings are JSON; failures are `{"ok": false, "status": <spk_status>,
   "error": "..."}`. `EngineNative` never throws across JNI: a failed call
   returns 0 or a JSON reply with `ok = false`.
2. **Ownership never crosses.** `spk_result.rgba16` is only valid while the
   result handle is alive, so `render` copies it into the caller's `Bitmap` and
   frees the result immediately. When the caller has no `Bitmap` of the right
   size yet, the result is kept as the session's *pending* result and
   `copyPendingToBitmap` finishes the job — one render, one frame, no second
   render to discover a size. `spk_result_free` does not need the engine, which
   is why a pending result can still be freed after the engine is destroyed.
3. **`spk_open` takes host pixels.** `spk_image` is float32, tightly packed,
   top row first, 3 or 4 channels (a 4th is dropped). The decoder here always
   produces **linear ProPhoto RGB with the orientation applied**, and the
   facade states that to the engine (`input_color_space = "ProPhoto RGB"`,
   `input_cctf_decoding = false`) rather than trusting each call site to.
   `spk_open_device` and a non-null `device` in `spk_engine_create` are both
   refused by the Vulkan backend: the host-pixel door is the only one.
4. **Tiers are `"live" | "preview" | "full"`.** The `live` tier's long edge is
   the session's `preview_long_edge` (800–8192) — a *print-layer* field that
   still invalidates the live tier's cached negative, so it is set at `spk_open`
   and by `setParams`, never assumed.
5. **One worker thread.** `open`, `setParams`, `getParams`, `render`,
   `exportImage` and `close` are all called from `EngineClient`'s single
   thread. `cancel` is the documented exception (`spk_cancel` deliberately does
   not take the session lock) and is callable from the main thread.
6. **Release order.** `spk_session_release` before `spk_engine_destroy`;
   `EngineClient` closes its session first and `destroy` releases any session a
   caller forgot, because the alternative is a crash in the engine's teardown.
7. **Memory truth is `spk_memory_report`.** `pressure.*` is always false on
   Vulkan (`vulkan_gpu.cpp` has no pressure monitor, and there are no
   file-backed planes), so shrinking is the app's job — see `MemoryBudget` and
   `EditorViewModel.onTrimMemory`.

## Decoding (S3)

`decode_bridge.cpp` adds no decoder. RAW and raster files go through
`spkhost::decode_linear_prophoto`, and thumbnails through `spkhost::thumbnail`
— the same code `spektralab-host` runs, with `host_smoke.py` on the Linux side.

Two Android-specific pieces:

* **Descriptors, not paths.** SAF gives a file descriptor with no path the host
  layer could open, so the bridge resolves `/proc/self/fd/<n>` and refuses with
  a message when that is not a regular file (a provider that streams). The
  descriptor is owned by the library once passed to `open`/`probe`/`thumbnail`
  — callers hand over `ParcelFileDescriptor.detachFd()` and never close it.
* **The half-size canvas decode.** `spkhost::decode_linear_prophoto` pins
  `half_size = 0`, and the interactive path needs LibRaw's half-size decode, so
  the bridge performs it here with the parameter policy of
  `configure_compatible` (engine/src/io/raw_decoder.cpp) copied field by field —
  AHD, ProPhoto, 16-bit, identity gamma, camera WB and matrix, no auto-bright —
  and converts LibRaw's 16-bit output exactly as the io layer does
  (`value / 65535.0`). It is preview-only: `half_size` and a decode-time white
  balance together are refused rather than silently ignored. Touching
  `engine/src/io/**` to expose a flag would have been a third upstream
  divergence; the plan keeps those to the Vulkan submission patch and the
  device fallbacks.

## Export (S6)

`exportImage` mirrors `engine/host/main.cpp`'s `m_export_image` + `encode_file`:
`spk_render("full")` → `spkhost::OutputTransform::to_linear` → optional
`resize_area` to the requested long edge → `spkhost::icc_for` → the writer →
`spkhost::write_file_atomic` (hidden partial + rename, so a reader never sees a
half-written file). Formats: `tiff16`, `png16`, `png8`, `jpeg8`.

`png16` is the one writer that is *not* a host function: the bundled
stb_image_write only writes 8-bit PNGs, and the host has no 16-bit PNG path.
`png16.cpp` writes the container itself — IHDR, the same `iCCP`/`eXIf` chunks
`spkhost::encode_png8` builds (from `spkhost::icc_for` and
`spkhost::exif_block`), and stb's own zlib for the pixels — rather than
diverging the engine's host code a third time.

## Build

```bash
cd android && ./gradlew :engine:assembleDebug
```

* `SPEKTRALAB_REPO`, `SPEKTRALAB_VULKAN_HEADERS` (`build/deps/Vulkan-Headers`,
  from `engine/setup-deps.sh`) and `SPEKTRALAB_LIBRAW_SOURCE` are passed as
  CMake arguments by `build.gradle.kts`; the Android toolchain file re-roots
  `find_path`/`find_program`, so they cannot be searched for from inside CMake.
* The shader compile happens **twice, deliberately**: CMake compiles the
  kernels for its own `resources/vulkan` (the engine's tests read that), and
  the Gradle `compileVulkanShaders` task compiles them into the app's assets
  with the NDK's `glslc`. Both run the same GLSL through glslang's front end;
  the engine's CMake accepts either spell of the target environment
  (`-V --target-env vulkan1.0` or glslc's `--target-env=vulkan1.0`).
* `CMAKE_BUILD_TYPE` is set per build type (`Release` / `RelWithDebInfo`) —
  AGP's default for the debug variant is `Debug`, i.e. `-O0`, and a `-O0`
  engine makes every measurement taken on it meaningless. `nativeBuildInfo()`
  reports what the library was actually compiled as.
* `-Wl,-z,max-page-size=16384` on the shared library: required by Android 15+
  devices that use 16 KB pages.

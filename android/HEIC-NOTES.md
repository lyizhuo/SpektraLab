# HEIC export — what is decided, what is built, what is not

Written 2026-11-01, while the export contract gained `Heic8`/`Heic10` and the
encoder did **not** follow. This file exists so that the next session starts
from the measurements rather than from the research.

## The decision that comes first

A HEIF file can name its colour two ways:

| mechanism | expresses | cannot express |
|---|---|---|
| `nclx` (primaries / transfer / matrix, three enumerations) | sRGB, Display P3, BT.2020 | anything measured |
| ICC profile in a `colr` box | arbitrary | — |

This engine delivers **linear ProPhoto RGB** and prints through measured paper
profiles. ROMM has no `nclx` code point. An encoder that can only write `nclx`
therefore does not "lose a little fidelity" on a ProPhoto export — it produces a
file that every viewer decodes as sRGB, which changes the light by about 1.5×
(`AGENTS.md` trap 27, measured there as 1.478×). That is why
`HeicColorPolicy` refuses that combination with a reason rather than exporting
something that looks fine and is wrong.

`HeicWriter.supportsIcc` is the single flag the policy consults. A writer that
sets it false is still useful for sRGB and P3; it is simply not allowed near
ProPhoto.

## What is built

- `export/ExportContract.kt` — `Heic8`, `Heic10`, `needsIccProfile`.
- `export/heic/HeicColorPolicy.kt` — the rule, plus the `HeicWriter` /
  `Result` interfaces. 9 unit tests.
- The export path still offers only the four formats it can actually write. No
  HEIC button appears; nothing pretends.

## What is not, and why

The encoder is **libheif** (LGPL-3.0, ICC-aware, 10/12-bit, HDR gain maps) plus
a HEVC encoder, because:

- The platform's `HeifWriter` is 8-bit YUV/RGBA only and has no ICC path — it
  cannot serve this product at all.
- `MediaMuxer.OutputFormat.MUXER_OUTPUT_HEIF` (API 28) likewise carries colour
  as `nclx` only, and its HEVC support is OEM-dependent.

That means a native dependency, and the two obstacles below were both measured
on 2026-11-01 before stopping:

### 1. kvazaar's CMakeLists injects a flag NDK r28 rejects

`kvazaar v2.3.1`, `CMakeLists.txt:239`, unconditionally prepends

```cmake
set(CMAKE_C_FLAGS "-Wa,-muse-unaligned-vector-move ${CMAKE_C_FLAGS}")
```

and NDK r28's clang answers

```
clang: error: unsupported argument '-muse-unaligned-vector-move' to option '-Wa,'
```

The fix is a two-line `check_c_compiler_flag` guard. With it, kvazaar builds and
installs cleanly for `arm64-v8a` (`libkvazaar.a`, verified at
`build/deps/heif/arm64-v8a`).

### 2. kvazaar installs a `.pc` but no CMake config, and libheif wants the latter

libheif v1.23.7's plugin detection is:

```cmake
plugin_option(KVAZAAR "kvazaar HEVC encoder" WITH_KVAZAAR OFF ...)
```

and `plugin_option` calls `find_package(Kvazaar)`. `kvazaar`'s install target
writes `lib/pkgconfig/kvazaar.pc` and no `kvazaar-config.cmake`, so the lookup
fails and the encoder is silently absent from libheif. A small imported-target
shim in `<prefix>/lib/cmake/kvazaar/kvazaar-config.cmake` is all that is needed.

### 3. The configure flags that will be needed

From libheif's own option list, a minimal encode-only build is:

```
-DBUILD_SHARED_LIBS=OFF
-DENABLE_PLUGIN_LOADING=OFF
-DBUILD_TESTING=OFF -DWITH_EXAMPLES=OFF -DWITH_GDK_PIXBUF=OFF
-DBUILD_DOCUMENTATION=OFF -DWITH_LIBSHARPYUV=OFF
-DWITH_LIBDE265=OFF -DWITH_X265=OFF -DWITH_AOM_ENCODER=OFF -DWITH_AOM_DECODER=OFF
-DWITH_DAV1D=OFF -DWITH_RAV1E=OFF -DWITH_SvtEnc=OFF
-DWITH_KVAZAAR=ON
```

## Two more findings from the same session

- **The SDK's CMake 3.22 cannot cross-compile against NDK 28** without
  `-DANDROID_USE_LEGACY_TOOLCHAIN_FILE=OFF` and `CMAKE_MAKE_PROGRAM` pointing at
  the SDK's own `ninja.exe`; the legacy path adds flags r28's clang rejects.
  The Gradle build is unaffected because AGP passes its own toolchain settings,
  but any hand-run `cmake` in `android/native` will hit it.
- **`assembleDebug` shipped an APK with zero assets** until the asset-staging
  task was wired into `merge*Assets` (see `engine/build.gradle.kts`). Any
  engine work measured before that fix was measured on a device where the
  engine could not start.

## Order of work when this resumes

1. Build kvazaar + the config shim + libheif into `build/deps/heif/<abi>`, as a
   script under `android/scripts/`, not as a Gradle ExternalProject — the
   dependency is large and should be built once.
2. Add `SPEKTRALAB_WITH_HEIF` to `android/engine/CMakeLists.txt`, default OFF,
   so the ordinary build stays independent of it.
3. `jni/heif_encode.cpp`: RGBA16 in, bytes out, ICC passed through to
   `heif_colorspace` + `heif_metadata` (the `colr` box).
4. `HeifWriter` implementation with `supportsIcc = true`; then
   `HeicColorPolicyTest` should pass unchanged, which is the point of having
   written the rule first.

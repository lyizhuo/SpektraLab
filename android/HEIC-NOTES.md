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
- `engine/src/main/cpp/jni/heif_encode.cpp` + `HeifNative.kt` — a working
  libheif encode path, built only when `-Pspektralab.heif=true`.
- `engine/CMakeLists.txt` — `SPEKTRALAB_WITH_HEIF`, default OFF, with a
  `FATAL_ERROR` that names the script when it is on but the library is absent.
- The export path still offers only the four formats it can actually write. No
  HEIC button appears; nothing pretends.

## Where it stands, measured 2026-11-01

The encoder **works once** and **aborts on the second encode in a process**.

What was verified on `Pixel_9_Pro(AVD) - 16` before the abort:

- `libheif <version> (HEVC via kvazaar, kvazaar)` — the library and the encoder
  are both present.
- A 10-bit encode produces a well-formed ISO-BMFF file: `ftyp` at offset 4,
  major brand `mif1`, `heic` among the compatible brands.
- The ICC profile is embedded **byte for byte** (a 64-byte slice of `sRGB.icc`
  from offset 128 is found in the output).
- The colour travels in a `colr` box typed `prof`, not as an `nclx` enum —
  which is the whole reason for this dependency.
- 8-bit and 12-bit are **refused**, not silently downgraded.

Then:

```
F libc : FORTIFY: pthread_mutex_destroy called on a destroyed mutex (0x...)
F libc : Fatal signal 6 (SIGABRT) ... in tid ... (roidJUnitRunner)
Abort message: 'FORTIFY: pthread_mutex_destroy called on a destroyed mutex'
  #04 pc ... libspektralab.so
```

Raising `threads` to 1 does not avoid it. That points at the encoder's own
teardown (kvazaar's thread pool or libheif's plugin registry) rather than at the
caller. `HeifEncodeInstrumentedTest` therefore carries a class-level `@Ignore`
with this text: a red `connectedDebugAndroidTest` aborts the whole run and would
hide every other failure.

## The order of work when this resumes

1. **Fix the abort, or move encoders.** It is a double-destroy in teardown, so
   the candidates are (a) the kvazaar plugin's `dealloc` path, (b) libheif's
   plugin registry when `ENABLE_PLUGIN_LOADING=OFF`. Switching to x265 with
   `-DHIGH_BIT_DEPTH=ON` is the cheap experiment: it is a different encoder
   behind the same libheif call, and if the abort disappears the bug is in
   kvazaar's plugin rather than in libheif.
2. **Decide 8-bit vs 10-bit as a product question.** kvazaar is one bit depth
   per build (`KVZ_BIT_DEPTH`), and libheif *compares against that same macro*
   (`encoder_kvazaar.cc`, `if (bit_depth != KVZ_BIT_DEPTH)`), so **both** the
   library and libheif must be compiled with the same `-DKVZ_BIT_DEPTH`. Getting
   this wrong yields "Bit depth not supported by kvazaar" for every encode. If
   8-bit HEIC is wanted too, that is two builds and a runtime choice.
3. **A decoder.** The platform's HEIF decoder is 8-bit, so a 10-bit file cannot
   be read back with `BitmapFactory` — a round-trip test needs libheif built
   with `libde265`, which is a second dependency for tests only.
4. Then implement `HeicWriter` with `supportsIcc = true` and wire the export
   page. `HeicColorPolicyTest` should pass unchanged, which is the point of
   having written the rule first.

## Two build obstacles, both solved

### kvazaar's CMakeLists injects a flag NDK r28 rejects

`kvazaar v2.3.1`, `CMakeLists.txt:239`, unconditionally prepends

```cmake
set(CMAKE_C_FLAGS "-Wa,-muse-unaligned-vector-move ${CMAKE_C_FLAGS}")
```

and NDK r28's clang answers

```
clang: error: unsupported argument '-muse-unaligned-vector-move' to option '-Wa,'
```

The fix is a two-line `check_c_compiler_flag` guard. With it, kvazaar builds and
installs cleanly for both ABIs.

### The SDK's CMake 3.22 cannot configure against NDK 28 by default

`-DANDROID_USE_LEGACY_TOOLCHAIN_FILE=OFF` and `CMAKE_MAKE_PROGRAM` pointing at
the SDK's own `ninja.exe` are both required for a hand-run `cmake`. The Gradle
build is unaffected because AGP passes its own settings.

## The working configure line

Built for `arm64-v8a` and `x86_64`, into `build/deps/heif/<abi>`:

```sh
cmake -G Ninja -S kvazaar -B <build> \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_USE_LEGACY_TOOLCHAIN_FILE=OFF \
  -DANDROID_ABI=$ABI -DANDROID_PLATFORM=android-29 -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=OFF -DBUILD_TESTS=OFF -DCMAKE_C_FLAGS=-DKVZ_BIT_DEPTH=10 \
  -DCMAKE_INSTALL_PREFIX=<prefix>

cmake -G Ninja -S libheif -B <build> \
  -DCMAKE_TOOLCHAIN_FILE=... -DANDROID_USE_LEGACY_TOOLCHAIN_FILE=OFF \
  -DANDROID_ABI=$ABI -DANDROID_PLATFORM=android-29 -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=OFF -DENABLE_PLUGIN_LOADING=OFF \
  -DBUILD_TESTING=OFF -DWITH_EXAMPLES=OFF -DWITH_GDK_PIXBUF=OFF \
  -DBUILD_DOCUMENTATION=OFF -DWITH_LIBSHARPYUV=OFF \
  -DWITH_LIBDE265=OFF -DWITH_X265=OFF -DWITH_AOM_ENCODER=OFF -DWITH_AOM_DECODER=OFF \
  -DWITH_DAV1D=OFF -DWITH_RAV1E=OFF -DWITH_SvtEnc=OFF -DWITH_UVG266=OFF \
  -DWITH_JPEG_DECODER=OFF -DWITH_JPEG_ENCODER=OFF -DWITH_OpenH264_DECODER=OFF \
  -DWITH_KVAZAAR=ON \
  -DCMAKE_C_FLAGS=-DKVZ_BIT_DEPTH=10 -DCMAKE_CXX_FLAGS=-DKVZ_BIT_DEPTH=10 \
  -DCMAKE_PREFIX_PATH=<prefix> -DCMAKE_FIND_ROOT_PATH=<prefix> \
  -DCMAKE_INSTALL_PREFIX=<prefix>
```

`Findkvazaar.cmake` uses pkg-config plus `find_library`, **not** a CMake package
config, so no shim is needed — the earlier guess in this file was wrong.
`CMAKE_FIND_ROOT_PATH` is what lets it find the cross-compiled library at all.

## Two more findings from the same session

- **`assembleDebug` shipped an APK with zero assets** until the asset-staging
  task was wired into `merge*Assets` (see `engine/build.gradle.kts`). Any
  engine work measured before that fix was measured on a device where the
  engine could not start.
- **Backtick test names with spaces are legal in JVM unit tests and illegal in
  instrumented tests** — DEX before version 040 rejects them, so `dexBuilder`
  fails with "Space characters in SimpleName ... are not allowed". `androidTest`
  methods must be camelCase.


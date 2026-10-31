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

**The encoder works.** x265 (10-bit) behind libheif, both ABIs, seven
instrumented cases green including eight encodes in a row in one process.

What is verified on `Pixel_9_Pro(AVD) - 16`:

- `libheif <version>` reports itself and has an HEVC encoder.
- A 10-bit encode is a well-formed ISO-BMFF file: `ftyp` at offset 4, and the
  major brand is **`heix`** — the >8-bit HEVC image brand, which is the file
  saying it holds more than 8 bits. (`heic` is the 8-bit form, `mif1` generic
  HEIF; libheif writes whichever the encoder produced.)
- The ICC profile is embedded **byte for byte** (a 64-byte slice of `sRGB.icc`
  from offset 128 is found in the output).
- The colour travels in a `colr` box typed `prof`, not as an `nclx` enum —
  which is the whole reason for this dependency.
- 8-bit and 12-bit are **refused**, not silently downgraded.
- **Eight encodes in one process do not abort it.**

`HeifEncodeInstrumentedTest` is un-`@Ignore`d. It carries the repetition case
because the failure it was written for only appeared on the second encode.

### The abort was kvazaar, and switching encoders fixed it

With **kvazaar** as the HEVC encoder, the second encode in a process aborted:

```
FORTIFY: pthread_mutex_destroy called on a destroyed mutex
Fatal signal 6 (SIGABRT) ... libspektralab.so
```

Setting the encoder to one thread did not avoid it. Rebuilding the same
`heif_encode.cpp` against **x265** (`HIGH_BIT_DEPTH=ON`) made the abort
disappear with no change to the calling code, which localises the bug to
kvazaar's plugin teardown rather than to libheif or to this file. kvazaar is no
longer built.

### What is still missing: the render→encode bridge

`ExportRunner` asks the *engine* to write a file (`session.export(path, spec)`),
and the engine has no HEIF writer — by design, `ARCHITECTURE.md` §8.8 puts file
writing in the host. So a HEIC export needs the render's 16-bit pixels in
memory, handed to `LibheifWriter.encodeRgba16`, not a path handed to the engine.

That bridge does not exist, so **the picker does not offer HEIC**:
`ExportFormats.offered()` returns only the four formats the engine's writer
produces, and `ExportFormats.pendingReason()` is what the panel prints beside
the two it cannot. This is deliberate — the HEIC enum values existed before
anything could write one, and the panel rendered `ExportFormat.entries`, so the
app briefly offered two chips that would have failed at the end of a full
render. `ExportFormatsTest` pins both directions.

## The order of work when this resumes

1. **Render to memory.** The engine already has a 16-bit PNG writer in
   `jni/png16.cpp`; what is needed is the pixels without a file, either a
   `render_to_buffer` entry point or reading back the PNG the engine can
   already produce and decoding it as 16-bit in Kotlin (`BitmapFactory` will
   not do — it decodes 16-bit PNG to 8-bit `ARGB_8888`).
2. Then `ExportFormats.offered` gains `Heic10`, and `LibheifWriter` is already
   the writer it needs.
3. Gain maps (ISO 21496-1) are still untouched: libheif writes them, but this
   product has no HDR headroom to declare until the export path can carry more
   than one rendition.


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


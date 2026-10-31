echo off
rem build-heif.ps1 -- builds libheif + kvazaar for Android, out of band.
rem
rem   pwsh -File android/scripts/build-heif.ps1 [-Abi arm64-v8a|x86_64] [-All]
rem
rem Why out of band. These are ~12 MB of static library per ABI and take
rem minutes to compile; making Gradle own that would put a five-minute build in
rem front of every `assembleDebug`. The build is opt-in twice: run this script,
rem then build with -Pspektralab.heif=true.
rem
rem Two things this script encodes that cost real time to discover, both
rem recorded again in android/HEIC-NOTES.md:
rem
rem   * kvazaar's CMakeLists unconditionally adds -Wa,-muse-unaligned-vector-move,
rem     which NDK r28's clang rejects. The checkout is patched here, idempotently.
rem   * kvazaar and libheif must both be compiled with -DKVZ_BIT_DEPTH=10.
rem     libheif's kvazaar plugin compares the image's depth against that macro
rem     (encoder_kvazaar.cc), so a default 8-bit libheif rejects every 10-bit
rem     image with "Bit depth not supported by kvazaar".
rem
rem NDK 28 + the SDK's CMake 3.22 need the modern toolchain flag and an explicit
rem ninja; the legacy path adds assembler flags the NDK no longer accepts.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

param(
    [string[]]$Abi = @('arm64-v8a', 'x86_64'),
    [string]$Ndk = "",
    [string]$SdkRoot = ""
)

$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not $SdkRoot) {
    $SdkRoot = $env:ANDROID_HOME
    if (-not $SdkRoot) { $SdkRoot = $env:ANDROID_SDK_ROOT }
    if (-not $SdkRoot) { $SdkRoot = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
}
if (-not $Ndk) { $Ndk = Join-Path $SdkRoot 'ndk/28.2.13676358' }
$cmake = Join-Path $SdkRoot 'cmake/3.22.1/bin/cmake.exe'
$ninja = Join-Path $SdkRoot 'cmake/3.22.1/bin/ninja.exe'
$toolchain = Join-Path $Ndk 'build/cmake/android.toolchain.cmake'

foreach ($p in @($cmake, $ninja, $toolchain)) {
    if (-not (Test-Path $p)) { throw "missing build tool: $p" }
}

$root = Join-Path $repo 'build/deps/heif'
$src = Join-Path $root 'src'
New-Item -ItemType Directory -Force $src | Out-Null

if (-not (Test-Path (Join-Path $src 'kvazaar/CMakeLists.txt'))) {
    git clone -q --depth 1 --branch v2.3.1 https://github.com/ultravideo/kvazaar.git (Join-Path $src 'kvazaar')
}
if (-not (Test-Path (Join-Path $src 'libheif/CMakeLists.txt'))) {
    git clone -q --depth 1 --branch v1.23.7 https://github.com/strukturag/libheif.git (Join-Path $src 'libheif')
}

# Patch kvazaar's assembler flag once; the guard makes the original line apply
# only where the assembler understands it.
$kvzCmake = Join-Path $src 'kvazaar/CMakeLists.txt'
$text = Get-Content $kvzCmake -Raw
if ($text -notmatch 'KVAZAAR_HAVE_MOVE_NEON') {
    $original = 'set(CMAKE_C_FLAGS "-Wa,-muse-unaligned-vector-move ${CMAKE_C_FLAGS}")'
    $patched = @'
# PATCH(local): NDK r28's clang rejects -Wa,-muse-unaligned-vector-move.
include(CheckCCompilerFlag)
check_c_compiler_flag("-Wa,-muse-unaligned-vector-move" KVAZAAR_HAVE_MOVE_NEON)
if(KVAZAAR_HAVE_MOVE_NEON)
  set(CMAKE_C_FLAGS "-Wa,-muse-unaligned-vector-move ${CMAKE_C_FLAGS}")
endif()
'@
    if ($text -notmatch [regex]::Escape($original)) { throw "kvazaar CMakeLists changed; the patch no longer applies" }
    Set-Content $kvzCmake ($text.Replace($original, $patched)) -NoNewline
}

$common = @(
    "-DCMAKE_MAKE_PROGRAM=$ninja",
    "-DCMAKE_TOOLCHAIN_FILE=$toolchain",
    "-DANDROID_USE_LEGACY_TOOLCHAIN_FILE=OFF",
    "-DANDROID_PLATFORM=android-29",
    "-DCMAKE_BUILD_TYPE=Release",
    "-DBUILD_SHARED_LIBS=OFF",
    "-DCMAKE_C_FLAGS=-DKVZ_BIT_DEPTH=10",
    "-DCMAKE_CXX_FLAGS=-DKVZ_BIT_DEPTH=10"
)

foreach ($a in $Abi) {
    $prefix = Join-Path $root $a
    Write-Host "== $a -> $prefix"

    & $cmake -G Ninja -S (Join-Path $src 'kvazaar') -B (Join-Path $root "build-kvazaar-$a") `
        "-DANDROID_ABI=$a" "-DCMAKE_INSTALL_PREFIX=$prefix" -DBUILD_TESTS=OFF @common
    if ($LASTEXITCODE -ne 0) { throw "kvazaar configure failed for $a" }
    & $cmake --build (Join-Path $root "build-kvazaar-$a") --target install
    if ($LASTEXITCODE -ne 0) { throw "kvazaar build failed for $a" }

    & $cmake -G Ninja -S (Join-Path $src 'libheif') -B (Join-Path $root "build-libheif-$a") `
        "-DANDROID_ABI=$a" "-DCMAKE_INSTALL_PREFIX=$prefix" `
        "-DCMAKE_PREFIX_PATH=$prefix" "-DCMAKE_FIND_ROOT_PATH=$prefix" `
        -DENABLE_PLUGIN_LOADING=OFF -DBUILD_TESTING=OFF -DWITH_EXAMPLES=OFF `
        -DWITH_GDK_PIXBUF=OFF -DBUILD_DOCUMENTATION=OFF -DWITH_LIBSHARPYUV=OFF `
        -DWITH_LIBDE265=OFF -DWITH_X265=OFF -DWITH_AOM_ENCODER=OFF -DWITH_AOM_DECODER=OFF `
        -DWITH_DAV1D=OFF -DWITH_RAV1E=OFF -DWITH_SvtEnc=OFF -DWITH_UVG266=OFF `
        -DWITH_JPEG_DECODER=OFF -DWITH_JPEG_ENCODER=OFF -DWITH_OpenH264_DECODER=OFF `
        -DWITH_KVAZAAR=ON @common
    if ($LASTEXITCODE -ne 0) { throw "libheif configure failed for $a" }
    & $cmake --build (Join-Path $root "build-libheif-$a") --target install
    if ($LASTEXITCODE -ne 0) { throw "libheif build failed for $a" }

    foreach ($lib in @('libheif.a', 'libkvazaar.a')) {
        $path = Join-Path $prefix "lib/$lib"
        if (-not (Test-Path $path)) { throw "$lib missing after build for $a" }
        Write-Host ("   {0,-14} {1:N1} MB" -f $lib, ((Get-Item $path).Length / 1MB))
    }
}

Write-Host ""
Write-Host "done. build with:  ./gradlew :app:assembleDebug -Pspektralab.heif=true"
Write-Host "NOTE: the encoder is known broken for a second encode in one process"
Write-Host "      (see android/HEIC-NOTES.md); do not ship a build with it on."

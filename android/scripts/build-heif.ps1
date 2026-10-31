echo off
rem build-heif.ps1 -- builds libheif + x265 for Android, out of band.
rem
rem   pwsh -File android/scripts/build-heif.ps1 [-Abi arm64-v8a,x86_64]
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
rem   * the encoder is x265 with HIGH_BIT_DEPTH=ON. kvazaar also builds and also
rem     produces correct files, but it aborts the process on the second encode
rem     (FORTIFY: pthread_mutex_destroy called on a destroyed mutex), which is
rem     why it is not used. See android/HEIC-NOTES.md.
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

# The encoder. x265 rather than kvazaar: both build and both produce correct
# 10-bit files, but kvazaar's plugin aborts the process on the second encode
# (see android/HEIC-NOTES.md). x265 is the encoder libheif is developed against.
if (-not (Test-Path (Join-Path $src 'x265/source/CMakeLists.txt'))) {
    git clone -q --depth 1 --branch 3.4 https://github.com/videolan/x265.git (Join-Path $src 'x265')
}
if (-not (Test-Path (Join-Path $src 'libheif/CMakeLists.txt'))) {
    git clone -q --depth 1 --branch v1.23.7 https://github.com/strukturag/libheif.git (Join-Path $src 'libheif')
}

# Patch kvazaar's assembler flag once; the guard makes the original line apply
# only where the assembler understands it.
$kvzCmake = Join-Path $src 'kvazaar/CMakeLists.txt'
# Only if a kvazaar checkout happens to be around: it is no longer built, but
# the patch is kept because the reason it exists is a trap worth not
# rediscovering. See the header.
$text = if (Test-Path $kvzCmake) { Get-Content $kvzCmake -Raw } else { "" }
if ($text -and $text -notmatch 'KVAZAAR_HAVE_MOVE_NEON') {
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
    "-DBUILD_SHARED_LIBS=OFF"
)

foreach ($a in $Abi) {
    $prefix = Join-Path $root $a
    Write-Host "== $a -> $prefix"

    # HIGH_BIT_DEPTH=ON makes this a 10-bit encoder; ENABLE_ASSEMBLY=OFF avoids
    # needing nasm on a Windows host, which x265 otherwise requires.
    & $cmake -G Ninja -S (Join-Path $src 'x265/source') -B (Join-Path $root "build-x265-$a") `
        "-DANDROID_ABI=$a" "-DCMAKE_INSTALL_PREFIX=$prefix" `
        -DHIGH_BIT_DEPTH=ON -DMAIN12=OFF -DENABLE_SHARED=OFF -DENABLE_CLI=OFF `
        -DENABLE_ASSEMBLY=OFF -DENABLE_PIC=ON @common
    if ($LASTEXITCODE -ne 0) { throw "x265 configure failed for $a" }
    & $cmake --build (Join-Path $root "build-x265-$a") --target install
    if ($LASTEXITCODE -ne 0) { throw "x265 build failed for $a" }

    & $cmake -G Ninja -S (Join-Path $src 'libheif') -B (Join-Path $root "build-libheif-$a") `
        "-DANDROID_ABI=$a" "-DCMAKE_INSTALL_PREFIX=$prefix" `
        "-DCMAKE_PREFIX_PATH=$prefix" "-DCMAKE_FIND_ROOT_PATH=$prefix" `
        -DENABLE_PLUGIN_LOADING=OFF -DBUILD_TESTING=OFF -DWITH_EXAMPLES=OFF `
        -DWITH_GDK_PIXBUF=OFF -DBUILD_DOCUMENTATION=OFF -DWITH_LIBSHARPYUV=OFF `
        -DWITH_LIBDE265=OFF -DWITH_X265=OFF -DWITH_AOM_ENCODER=OFF -DWITH_AOM_DECODER=OFF `
        -DWITH_DAV1D=OFF -DWITH_RAV1E=OFF -DWITH_SvtEnc=OFF -DWITH_UVG266=OFF `
        -DWITH_JPEG_DECODER=OFF -DWITH_JPEG_ENCODER=OFF -DWITH_OpenH264_DECODER=OFF `
        -DWITH_KVAZAAR=OFF -DWITH_X265=ON @common
    if ($LASTEXITCODE -ne 0) { throw "libheif configure failed for $a" }
    & $cmake --build (Join-Path $root "build-libheif-$a") --target install
    if ($LASTEXITCODE -ne 0) { throw "libheif build failed for $a" }

    foreach ($lib in @('libheif.a', 'libx265.a')) {
        $path = Join-Path $prefix "lib/$lib"
        if (-not (Test-Path $path)) { throw "$lib missing after build for $a" }
        Write-Host ("   {0,-14} {1:N1} MB" -f $lib, ((Get-Item $path).Length / 1MB))
    }
}

Write-Host ""
Write-Host "done. build with:  ./gradlew :app:assembleDebug -Pspektralab.heif=true"
Write-Host "NOTE: the encoder is known broken for a second encode in one process"
Write-Host "      (see android/HEIC-NOTES.md); do not ship a build with it on."

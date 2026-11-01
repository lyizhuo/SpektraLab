# device-shot.ps1 -- put the debug build on a device and photograph what it draws.
#
#   pwsh -File android/scripts/device-shot.ps1 -Out .tmp/shots/c1.png
#   pwsh -File android/scripts/device-shot.ps1 -Out .tmp/shots/c3.png `
#        -Activity .CameraActivity -Tap 540,1900 -Wait 2
#
# Why a script. Every visual question in this repository is settled by looking at
# the running app, and doing that by hand is four commands and two guesses about
# which device is attached. This pins them: it installs the APK this checkout
# just built, launches one activity, optionally taps a few points (in the
# device's own pixels), and writes the screen to -Out.
#
# It takes the first attached device unless -Serial names one. The emulator is
# usually the right target for a layout question (its 427x952 dp window is
# closest to the prototype's 412x915) and a phone is the right target for
# whether the thing is usable.

param(
    [Parameter(Mandatory = $true)][string]$Out,
    [string]$Serial = "",
    [string]$Activity = ".HomeActivity",
    [int]$Wait = 3,
    [string[]]$Tap = @(),
    [switch]$NoInstall,
    [switch]$Fresh
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$adb = Join-Path $sdk 'platform-tools\adb.exe'
if (-not (Test-Path $adb)) { throw "adb not found at $adb; set ANDROID_HOME" }

if (-not $Serial) {
    $devices = & $adb devices | Select-String -Pattern '^(\S+)\s+device$' | ForEach-Object { $_.Matches[0].Groups[1].Value }
    if (-not $devices) { throw "no device attached" }
    $Serial = @($devices)[0]
}

$apk = Join-Path $repo 'android\app\build\outputs\apk\debug\app-debug.apk'
if (-not $NoInstall) {
    if (-not (Test-Path $apk)) { throw "no APK at $apk; run :app:assembleDebug first" }
    & $adb -s $Serial install -r $apk | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "install failed on $Serial" }
}

if ($Fresh) {
    & $adb -s $Serial shell pm clear com.spektralab.app | Out-Null
    & $adb -s $Serial shell pm grant com.spektralab.app android.permission.READ_MEDIA_IMAGES 2>&1 | Out-Null
    & $adb -s $Serial shell pm grant com.spektralab.app android.permission.CAMERA 2>&1 | Out-Null
}

& $adb -s $Serial shell am force-stop com.spektralab.app | Out-Null
& $adb -s $Serial shell am start -n "com.spektralab.app/$Activity" | Out-Null
Start-Sleep -Seconds $Wait

foreach ($point in $Tap) {
    $parts = $point.Split(',')
    & $adb -s $Serial shell input tap $parts[0] $parts[1] | Out-Null
    Start-Sleep -Milliseconds 900
}
if ($Tap.Count -gt 0) { Start-Sleep -Seconds 1 }

$folder = Split-Path -Parent $Out
if ($folder -and -not (Test-Path $folder)) { New-Item -ItemType Directory -Force $folder | Out-Null }
$target = Join-Path (Resolve-Path -LiteralPath $folder).Path (Split-Path -Leaf $Out)
# A PNG cannot travel through a PowerShell pipeline: `exec-out` writes bytes and
# assigning them to a variable decodes them as text. cmd's redirection is the
# route that keeps them intact.
& cmd /c "`"$adb`" -s $Serial exec-out screencap -p > `"$target`""
if (-not (Test-Path $target)) { throw "screencap wrote nothing to $target" }
Write-Output "$Serial -> $Out ($((Get-Item $target).Length) bytes)"

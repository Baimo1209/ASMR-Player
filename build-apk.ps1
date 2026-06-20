param(
    [switch]$Release
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$sdk = Join-Path $root ".android-sdk"
$gradle = Join-Path $root ".gradle-local\gradle-8.10.2\bin\gradle.bat"

$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk

if ($Release) {
    & $gradle clean assembleRelease lintRelease
    $buildFile = Get-Content (Join-Path $root "app\build.gradle") -Raw
    $versionName = [regex]::Match($buildFile, 'versionName\s+"([^"]+)"').Groups[1].Value
    if (-not $versionName) {
        throw "Unable to read versionName from app/build.gradle"
    }
    $apkName = "ASMR-Player-v$versionName-release.apk"
    $sourceApk = Join-Path $root "app\build\outputs\apk\release\app-release.apk"
} else {
    & $gradle clean assembleDebug lintDebug
    $apkName = [string]::Concat([char]0x767D, [char]0x6CAB, [char]0x64AD, [char]0x653E, [char]0x5668, "-debug.apk")
    $sourceApk = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"
}

$apkPath = Join-Path $root $apkName
Copy-Item $sourceApk $apkPath -Force

Write-Host "APK ready: $apkPath"

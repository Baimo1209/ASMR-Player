$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$sdk = Join-Path $root ".android-sdk"
$gradle = Join-Path $root ".gradle-local\gradle-8.10.2\bin\gradle.bat"

$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk

& $gradle clean assembleDebug lintDebug
$apkName = [string]::Concat([char]0x767D, [char]0x6CAB, [char]0x64AD, [char]0x653E, [char]0x5668, "-debug.apk")
$apkPath = Join-Path $root $apkName
Copy-Item (Join-Path $root "app\build\outputs\apk\debug\app-debug.apk") $apkPath -Force

Write-Host "APK ready: $apkPath"

$ErrorActionPreference = "Stop"

$apk = Join-Path $PSScriptRoot "release\app-release.apk"
if (-not (Test-Path $apk)) {
    $apk = Join-Path $PSScriptRoot "app\build\outputs\apk\release\app-release.apk"
}
if (-not (Test-Path $apk)) {
    throw "APK not found. Run .\build.ps1 first."
}

adb devices -l
adb install -r $apk
if ($LASTEXITCODE -ne 0) {
    throw "adb install failed with exit code $LASTEXITCODE"
}

adb shell su -c "pm grant com.codex.aicallguard android.permission.READ_CONTACTS"
adb shell am start -n com.codex.aicallguard/com.codex.aicallguard.MainActivity

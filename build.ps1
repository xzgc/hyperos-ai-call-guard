$ErrorActionPreference = "Stop"

if (-not $env:JAVA_HOME) {
    throw "Set JAVA_HOME to a JDK 17 installation first."
}
$sdk = $env:ANDROID_HOME
if (-not $sdk) {
    $sdk = $env:ANDROID_SDK_ROOT
}
if (-not $sdk -or -not (Test-Path $sdk)) {
    throw "Set ANDROID_HOME or ANDROID_SDK_ROOT to the Android SDK path."
}

New-Item -ItemType Directory -Force C:\gtmp | Out-Null
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$env:TEMP = "C:\gtmp"
$env:TMP = "C:\gtmp"
$env:GRADLE_OPTS = "-Djava.io.tmpdir=C:\gtmp -Djava.net.preferIPv4Stack=true"

$gradleCommand = $env:GRADLE_BIN
if (-not $gradleCommand) {
    $systemGradle = Get-Command gradle -ErrorAction SilentlyContinue
    if ($systemGradle) {
        $gradleCommand = $systemGradle.Source
    } else {
        $gradleCommand = "$PSScriptRoot\gradlew.bat"
    }
}

& $gradleCommand assembleDebug assembleRelease --no-daemon --console=plain
if ($LASTEXITCODE -ne 0) {
    throw "Gradle build failed with exit code $LASTEXITCODE"
}

New-Item -ItemType Directory -Force "$PSScriptRoot\release" | Out-Null
Copy-Item "$PSScriptRoot\app\build\outputs\apk\release\app-release.apk" "$PSScriptRoot\release\app-release.apk" -Force
Compress-Archive -Path "$PSScriptRoot\kernel-module\*" -DestinationPath "$PSScriptRoot\release\ai_call_guard_boot.zip" -Force

Write-Host "Debug APK: $(Resolve-Path app\build\outputs\apk\debug\app-debug.apk)"
Write-Host "Release APK: $(Resolve-Path release\app-release.apk)"
Write-Host "Kernel module: $(Resolve-Path release\ai_call_guard_boot.zip)"

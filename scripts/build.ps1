# ChargeGuard build helper. Uses the portable toolchain in D:\AndroidDev (no system changes).
# Usage:  powershell -ExecutionPolicy Bypass -File scripts\build.ps1 [gradle tasks...]
# Default tasks: testDebugUnitTest assembleDebug
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Tasks)
$ErrorActionPreference = 'Stop'
$toolchain = if ($env:CHARGEGUARD_TOOLCHAIN) { $env:CHARGEGUARD_TOOLCHAIN } else { 'D:\AndroidDev' }
$env:JAVA_HOME = Join-Path $toolchain 'jdk17'
$env:ANDROID_HOME = Join-Path $toolchain 'sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = Join-Path $toolchain 'gradle-home'
$root = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path "$root\local.properties")) {
    "sdk.dir=$($env:ANDROID_HOME -replace '\\','\\')" | Set-Content "$root\local.properties" -Encoding ASCII
}
if (-not $Tasks -or $Tasks.Count -eq 0) { $Tasks = @('testDebugUnitTest', 'assembleDebug') }
Push-Location $root
try {
    & "$root\gradlew.bat" @Tasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
} finally { Pop-Location }

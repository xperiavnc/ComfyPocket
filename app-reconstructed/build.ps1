param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$SdkRoot = $env:ANDROID_HOME,
    [switch]$Clean
)

$ErrorActionPreference = 'Stop'
if (-not $JavaHome -and (Test-Path -LiteralPath 'C:\Program Files\Java\jdk-17')) {
    $JavaHome = 'C:\Program Files\Java\jdk-17'
}
if (-not $SdkRoot -and (Test-Path -LiteralPath 'C:\Android\Sdk')) {
    $SdkRoot = 'C:\Android\Sdk'
}
if ($JavaHome) {
    $env:JAVA_HOME = (Resolve-Path -LiteralPath $JavaHome).Path
}
if ($SdkRoot) {
    $env:ANDROID_HOME = (Resolve-Path -LiteralPath $SdkRoot).Path
}

Push-Location -LiteralPath $PSScriptRoot
try {
    $tasks = @(':app:assembleDebug', ':app:testDebugUnitTest')
    if ($Clean) {
        $tasks = @('clean') + $tasks
    }
    & (Join-Path $PSScriptRoot 'gradlew.bat') @tasks --no-daemon --console=plain
    $buildExit = $LASTEXITCODE
    if ($buildExit -ne 0) {
        throw "Gradle exited with status $buildExit."
    }
    $apk = Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk'
    if (-not (Test-Path -LiteralPath $apk)) {
        throw 'Gradle completed without the expected debug APK.'
    }
    Get-Item -LiteralPath $apk | Select-Object FullName, Length
    Get-FileHash -LiteralPath $apk -Algorithm SHA256
} finally {
    Pop-Location
}

param(
    [string]$JavaHome,
    [switch]$Install
)
$ErrorActionPreference = 'Stop'
$candidatePaths = @($JavaHome, $env:JAVA_HOME)
if (Test-Path -LiteralPath (Join-Path $PSScriptRoot '.tools')) {
    $candidatePaths += Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot '.tools') -Directory |
        Where-Object { $_.Name -like 'jdk-17*' } | Select-Object -ExpandProperty FullName
}
$candidatePaths += @('C:\Program Files\Java\jdk-17')
$resolvedJava = $null
foreach ($candidate in $candidatePaths) {
    if ([string]::IsNullOrWhiteSpace($candidate)) { continue }
    $releaseFile = Join-Path $candidate 'release'
    if ((Test-Path -LiteralPath $releaseFile) -and
        (Select-String -LiteralPath $releaseFile -Pattern '^JAVA_VERSION="17[\.]' -Quiet)) {
        $resolvedJava = $candidate
        break
    }
}
if ($null -eq $resolvedJava) {
    throw 'Java 17 is required. Run .\build.ps1 -JavaHome "C:\path\to\jdk-17" or put JDK 17 in .tools.'
}
$env:JAVA_HOME = $resolvedJava
Push-Location -LiteralPath $PSScriptRoot
try {
    Write-Host "Using Java 17: $resolvedJava"
    & .\gradlew.bat :app:assembleDebug :app:lintDebug :app:testDebugUnitTest --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Build or checks failed (exit $LASTEXITCODE)." }
    if ($Install) {
        & .\gradlew.bat :app:installDebug --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Installation failed (exit $LASTEXITCODE)." }
    }
    Write-Host 'APK: app\build\outputs\apk\debug\app-debug.apk'
} finally { Pop-Location }

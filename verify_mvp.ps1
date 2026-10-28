param(
    [string]$PythonExecutable = "",
    [string]$JavaHome = ""
)

$ErrorActionPreference = "Stop"
$ProjectRoot = $PSScriptRoot

if ([string]::IsNullOrWhiteSpace($PythonExecutable)) {
    $PythonCommand = Get-Command python -ErrorAction SilentlyContinue
    if ($null -eq $PythonCommand) {
        throw "Python was not found. Install Python 3.10+ or pass -PythonExecutable with the path to python.exe."
    }
    $PythonExecutable = $PythonCommand.Source
}

Push-Location -LiteralPath $ProjectRoot
try {
    Write-Host "[1/2] Receiver contract and HTTP integration tests"
    & $PythonExecutable -m unittest discover -s receiver/tests -v
    if ($LASTEXITCODE -ne 0) {
        throw "Receiver tests failed (exit $LASTEXITCODE)."
    }

    Write-Host "[2/2] Android APK, Lint and JVM tests"
    if ([string]::IsNullOrWhiteSpace($JavaHome)) {
        & .\build.ps1
    }
    else {
        & .\build.ps1 -JavaHome $JavaHome
    }
    if ($LASTEXITCODE -ne 0) {
        throw "Android verification failed (exit $LASTEXITCODE)."
    }

    Write-Host "MVP verification completed successfully."
    Write-Host "APK: app\build\outputs\apk\debug\app-debug.apk"
}
finally {
    Pop-Location
}

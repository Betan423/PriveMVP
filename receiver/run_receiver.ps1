param(
    [string]$BindAddress = "127.0.0.1",
    [ValidateRange(1, 65535)]
    [int]$Port = 8000,
    [string]$DataDirectory = "",
    [string]$PythonExecutable = ""
)

$ErrorActionPreference = "Stop"
$ReceiverRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Split-Path -Parent $ReceiverRoot

if ([string]::IsNullOrWhiteSpace($DataDirectory)) {
    $DataDirectory = Join-Path $ReceiverRoot "data"
}

if ([string]::IsNullOrWhiteSpace($PythonExecutable)) {
    $PythonCommand = Get-Command python -ErrorAction SilentlyContinue
    if ($null -eq $PythonCommand) {
        throw "Python was not found. Install Python 3.10+ or pass -PythonExecutable with the path to python.exe."
    }
    $PythonExecutable = $PythonCommand.Source
}

Push-Location $ProjectRoot
try {
    & $PythonExecutable -m receiver.server --host $BindAddress --port $Port --data-dir $DataDirectory
    if ($LASTEXITCODE -ne 0) {
        throw "Receiver failed, exit code: $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}

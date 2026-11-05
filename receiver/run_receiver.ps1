param(
    [string]$BindAddress = "127.0.0.1",
    [ValidateRange(1, 65535)]
    [int]$Port = 8080,
    [string]$DataDirectory = "",
    [string]$PythonExecutable = "",
    # Optional real cloud proxy. Local Qwen is used by default to simulate it.
    [string]$PlannerUrl = "off",
    # Local Ollama receives only the privacy-filtered packet, never the raw message.
    [string]$PrivacyAiUrl = "http://127.0.0.1:11434",
    [string]$PrivacyAiModel = "qwen3.5:9b"
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
    & $PythonExecutable -m receiver.server --host $BindAddress --port $Port --data-dir $DataDirectory `
        --planner-url $PlannerUrl --privacy-ai-url $PrivacyAiUrl --privacy-ai-model $PrivacyAiModel
    if ($LASTEXITCODE -ne 0) {
        throw "Receiver failed, exit code: $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}

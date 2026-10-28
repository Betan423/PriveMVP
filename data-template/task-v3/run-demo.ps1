param([string]$InputPath = '', [string]$PythonPath = '')
$ErrorActionPreference = 'Stop'
if (-not $InputPath) { $InputPath = Join-Path $PSScriptRoot 'examples\source.json' }
if (-not $PythonPath) {
    $candidateRuntime = Join-Path $env:USERPROFILE '.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
    if (Test-Path -LiteralPath $candidateRuntime) { $PythonPath = $candidateRuntime }
    else { $PythonPath = (Get-Command python -ErrorAction Stop).Source }
}
$outputRoot = Join-Path $PSScriptRoot 'outputs'
New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null
$runTag = [DateTime]::Now.ToString('yyyyMMdd_HHmmss_ffff')
$outputPath = Join-Path $outputRoot ('run_' + $runTag)
& $PythonPath (Join-Path $PSScriptRoot 'bundle_engine.py') $InputPath --output $outputPath
if ($LASTEXITCODE -ne 0) { throw 'Validation or output failed.' }
Write-Output ('Results: ' + $outputPath)

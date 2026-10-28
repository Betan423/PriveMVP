param(
    [string]$InputPath = (Join-Path $PSScriptRoot 'examples\tasks.ndjson'),
    [string]$PythonPath
)
$ErrorActionPreference = 'Stop'
if (-not $PythonPath) {
    $bundledPython = Join-Path $env:USERPROFILE '.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
    if (Test-Path -LiteralPath $bundledPython -PathType Leaf) {
        $PythonPath = $bundledPython
    } else {
        $command = Get-Command python -ErrorAction SilentlyContinue
        if (-not $command) { throw 'Python 3.10+ required. Use -PythonPath.' }
        $PythonPath = $command.Source
    }
}
$inputResolved = (Resolve-Path -LiteralPath $InputPath).Path
$outputsRoot = Join-Path $PSScriptRoot 'outputs'
if (-not (Test-Path -LiteralPath $outputsRoot)) { New-Item -ItemType Directory -Path $outputsRoot | Out-Null }
$outputPath = Join-Path $outputsRoot (Get-Date -Format 'yyyyMMdd_HHmmss_ffff')
& $PythonPath (Join-Path $PSScriptRoot 'task_engine.py') $inputResolved --output $outputPath --require-coverage
$demoExit = $LASTEXITCODE
if ($demoExit -eq 0) { Write-Host ('Synthetic simulation results: ' + $outputPath) }
else { Write-Host 'No valid complete output. Review the validation result or execution error.' }
exit $demoExit

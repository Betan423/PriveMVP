param(
    [string]$InputPath = (Join-Path $PSScriptRoot 'examples\sample.ndjson'),
    [switch]$DefaultBatch,
    [string]$PythonPath
)

$ErrorActionPreference = 'Stop'
if (-not $PythonPath) {
    $bundledPython = Join-Path $env:USERPROFILE '.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
    if (Test-Path -LiteralPath $bundledPython -PathType Leaf) {
        $PythonPath = $bundledPython
    } else {
        $pythonCommand = Get-Command python -ErrorAction SilentlyContinue
        if (-not $pythonCommand) { throw 'Python 3.10+ is required. Supply -PythonPath with the executable path.' }
        $PythonPath = $pythonCommand.Source
    }
}

$resolvedInput = (Resolve-Path -LiteralPath $InputPath).Path
$outputDir = Join-Path $PSScriptRoot 'outputs'
if (-not (Test-Path -LiteralPath $outputDir)) {
    New-Item -ItemType Directory -Path $outputDir | Out-Null
}
$runTag = Get-Date -Format 'yyyyMMdd_HHmmss_ffff'
$reportPath = Join-Path $outputDir ($runTag + '_validation.json')
$csvPath = Join-Path $outputDir ($runTag + '_synthetic.csv')
$validationArgs = @((Join-Path $PSScriptRoot 'validate.py'), $resolvedInput, '--report', $reportPath, '--csv', $csvPath)
if ($DefaultBatch) { $validationArgs += '--default-batch' }
& $PythonPath @validationArgs
$validationExit = $LASTEXITCODE
if ($validationExit -eq 0) {
    Write-Host ('Validated CSV: ' + $csvPath)
    Write-Host ('Validation report: ' + $reportPath)
} elseif ($validationExit -eq 1) {
    Write-Host ('Validation failed. No CSV exported. Report: ' + $reportPath)
} else {
    Write-Host 'Validation could not run. Check the input, Python version, and output permissions.'
}
exit $validationExit

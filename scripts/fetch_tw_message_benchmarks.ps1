param(
    [string]$OutputDirectory = "",
    [switch]$DownloadCofacts
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path (Split-Path -Parent $repoRoot) "privacy-sms-datasets"
}
$target = [System.IO.Path]::GetFullPath($OutputDirectory)
if (-not (Test-Path -LiteralPath $target)) {
    New-Item -ItemType Directory -Path $target | Out-Null
}

# TS-Bench is public under Apache-2.0. Keep the checkout outside the repository so raw benchmark
# messages never become an accidental app asset or Git change.
$tsBenchDirectory = Join-Path $target "TS-Bench"
if (-not (Test-Path -LiteralPath (Join-Path $tsBenchDirectory ".git"))) {
    & git clone --depth 1 https://github.com/mtkresearch/TS-Bench.git $tsBenchDirectory
    if ($LASTEXITCODE -ne 0) { throw "Unable to clone TS-Bench (git exit $LASTEXITCODE)" }
}
$tsBenchRevision = (& git -C $tsBenchDirectory rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw "Unable to resolve TS-Bench revision" }
$tsBenchCsv = Join-Path $tsBenchDirectory "data\TSB400.csv"
if (-not (Test-Path -LiteralPath $tsBenchCsv)) { throw "TS-Bench CSV missing: $tsBenchCsv" }
$tsBenchRows = (Import-Csv -LiteralPath $tsBenchCsv).Count
if ($tsBenchRows -ne 400) { throw "Expected 400 TS-Bench rows, found $tsBenchRows" }

$cofactsState = [ordered]@{
    requested = [bool]$DownloadCofacts
    downloaded = $false
    source = "https://huggingface.co/datasets/Cofacts/line-msg-fact-check-tw"
    license = "CC-BY-SA-4.0 plus Cofacts Data User Agreement"
    note = "The script never accepts the agreement. The Hugging Face account behind HF_TOKEN must already have accepted it."
}

if ($DownloadCofacts) {
    $tokenCandidates = @(
        $env:HF_TOKEN,
        $env:HUGGING_FACE_HUB_TOKEN,
        [Environment]::GetEnvironmentVariable("HF_TOKEN", "User"),
        [Environment]::GetEnvironmentVariable("HUGGING_FACE_HUB_TOKEN", "User")
    )
    $token = $tokenCandidates | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -First 1
    if ([string]::IsNullOrWhiteSpace($token)) {
        $cachedTokenPath = Join-Path $env:USERPROFILE ".cache\huggingface\token"
        if (Test-Path -LiteralPath $cachedTokenPath) {
            $token = (Get-Content -LiteralPath $cachedTokenPath -Raw).Trim()
        }
    }
    if ([string]::IsNullOrWhiteSpace($token)) {
        throw "Cofacts requires user acceptance. Log in at https://huggingface.co/datasets/Cofacts/line-msg-fact-check-tw, accept the Cofacts Data User Agreement, then set HF_TOKEN for that account."
    }

    $cofactsDirectory = Join-Path $target "Cofacts-line-msg-fact-check-tw"
    $archiveDirectory = Join-Path $cofactsDirectory "archives"
    $extractedDirectory = Join-Path $cofactsDirectory "extracted"
    foreach ($directory in @($cofactsDirectory, $archiveDirectory, $extractedDirectory)) {
        if (-not (Test-Path -LiteralPath $directory)) {
            New-Item -ItemType Directory -Path $directory | Out-Null
        }
    }

    $headers = @{ Authorization = "Bearer $token" }
    $files = @("articles.csv.zip", "article_replies.csv.zip", "replies.csv.zip")
    $hashes = [ordered]@{}
    foreach ($name in $files) {
        $archive = Join-Path $archiveDirectory $name
        if (-not (Test-Path -LiteralPath $archive)) {
            $encodedName = [Uri]::EscapeDataString($name)
            $uri = "https://huggingface.co/datasets/Cofacts/line-msg-fact-check-tw/resolve/main/$encodedName"
            Invoke-WebRequest -Uri $uri -Headers $headers -OutFile $archive
        }
        $hashes[$name] = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash
        $expectedCsv = Join-Path $extractedDirectory $name.Substring(0, $name.Length - 4)
        if (-not (Test-Path -LiteralPath $expectedCsv)) {
            Expand-Archive -LiteralPath $archive -DestinationPath $extractedDirectory -Force
        }
    }
    foreach ($name in @("articles.csv", "article_replies.csv", "replies.csv")) {
        if (-not (Test-Path -LiteralPath (Join-Path $extractedDirectory $name))) {
            throw "Cofacts archive did not contain expected file: $name"
        }
    }
    $cofactsState.downloaded = $true
    $cofactsState.extracted_directory = $extractedDirectory
    $cofactsState.archive_sha256 = $hashes
}

$manifest = [ordered]@{
    generated_at_utc = [DateTime]::UtcNow.ToString("o")
    ts_bench = [ordered]@{
        source = "https://github.com/mtkresearch/TS-Bench"
        license = "Apache-2.0"
        revision = $tsBenchRevision
        csv_sha256 = (Get-FileHash -LiteralPath $tsBenchCsv -Algorithm SHA256).Hash
        rows = $tsBenchRows
    }
    cofacts = $cofactsState
}
$manifestPath = Join-Path $target "tw-message-dataset-manifest.json"
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
[System.IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 8), $utf8NoBom)

Write-Host "Taiwan message datasets directory: $target"
Write-Host "TS-Bench revision: $tsBenchRevision ($tsBenchRows rows)"
Write-Host "Cofacts downloaded: $($cofactsState.downloaded)"
Write-Host "Manifest: $manifestPath"

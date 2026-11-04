param(
    [string]$OutputDirectory = "",
    [switch]$SkipCertificateCheck
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

$uciUri = "https://cdn.uci-ics-mlr-prod.aws.uci.edu/228/sms%2Bspam%2Bcollection.zip"
$uciZip = Join-Path $target "sms-spam-collection.zip"
if (-not (Test-Path -LiteralPath $uciZip)) {
    $download = @{ Uri = $uciUri; OutFile = $uciZip }
    if ($SkipCertificateCheck) { $download.SkipCertificateCheck = $true }
    Invoke-WebRequest @download
}
$uciDirectory = Join-Path $target "uci-sms-spam-collection"
if (-not (Test-Path -LiteralPath $uciDirectory)) {
    New-Item -ItemType Directory -Path $uciDirectory | Out-Null
}
$uciMessages = Join-Path $uciDirectory "SMSSpamCollection"
if (-not (Test-Path -LiteralPath $uciMessages)) {
    & tar -xf $uciZip -C $uciDirectory
    if ($LASTEXITCODE -ne 0) { throw "Unable to extract UCI archive (tar exit $LASTEXITCODE)" }
}

# The upstream FBS files use ':' in category names. Windows cannot check out those paths, so keep
# a no-checkout Git object store and export each text blob with ':' replaced by '_'.
$fbsGit = Join-Path $target "FBS_SMS_Dataset"
if (-not (Test-Path -LiteralPath (Join-Path $fbsGit ".git"))) {
    & git clone --depth 1 --no-checkout https://github.com/Cypher-Z/FBS_SMS_Dataset.git $fbsGit
    if ($LASTEXITCODE -ne 0) { throw "Unable to clone FBS dataset (git exit $LASTEXITCODE)" }
}
$fbsRevision = (& git -C $fbsGit rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw "Unable to resolve FBS revision" }
$fbsOutput = Join-Path $target "FBS_SMS_Dataset_sanitized"
if (-not (Test-Path -LiteralPath $fbsOutput)) {
    New-Item -ItemType Directory -Path $fbsOutput | Out-Null
}
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
$paths = & git -C $fbsGit ls-tree -r --name-only HEAD
foreach ($path in $paths) {
    $safeName = $path.Replace(":", "_").Replace("/", "_")
    $destination = Join-Path $fbsOutput $safeName
    if (Test-Path -LiteralPath $destination) { continue }
    $content = & git -C $fbsGit show "HEAD:$path"
    if ($LASTEXITCODE -ne 0) { throw "Unable to export FBS blob: $path" }
    [System.IO.File]::WriteAllLines($destination, [string[]]$content, $utf8NoBom)
}

$manifest = [ordered]@{
    generated_at_utc = [DateTime]::UtcNow.ToString("o")
    uci = [ordered]@{
        source = "https://archive.ics.uci.edu/dataset/228/sms+spam+collection"
        download = $uciUri
        archive_sha256 = (Get-FileHash -LiteralPath $uciZip -Algorithm SHA256).Hash
        rows = (Get-Content -LiteralPath $uciMessages).Count
    }
    fbs = [ordered]@{
        source = "https://github.com/Cypher-Z/FBS_SMS_Dataset"
        revision = $fbsRevision
        files = ($paths | Measure-Object).Count
        rows = (Get-ChildItem -LiteralPath $fbsOutput -File | Where-Object Name -ne "README.md" |
            ForEach-Object { (Get-Content -LiteralPath $_.FullName).Count } | Measure-Object -Sum).Sum
    }
}
$manifestPath = Join-Path $target "dataset-manifest.json"
[System.IO.File]::WriteAllText(
    $manifestPath,
    ($manifest | ConvertTo-Json -Depth 5),
    $utf8NoBom
)

Write-Host "External SMS datasets ready: $target"
Write-Host "Manifest: $manifestPath"

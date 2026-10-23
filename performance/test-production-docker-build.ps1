Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$dockerfile = Get-Content (Join-Path $root 'deploy/novel-front/Dockerfile') -Raw
[xml]$pom = Get-Content (Join-Path $root 'pom.xml') -Raw
foreach ($module in $pom.project.modules.module) {
    $copy = [regex]::Match($dockerfile, '(?m)^COPY\s+' + [regex]::Escape("$module/pom.xml") + '\s+')
    if (-not $copy.Success -or $copy.Index -gt $dockerfile.IndexOf('RUN mvn')) {
        throw "Missing reactor POM before dependency resolution: $module"
    }
}
$source = [regex]::Match($dockerfile, '(?m)^COPY\s+novel-cache-contract/src\s+novel-cache-contract/src\s*$')
if (-not $source.Success -or $source.Index -lt $dockerfile.IndexOf('dependency:go-offline') -or
    $source.Index -gt $dockerfile.IndexOf('-pl novel-front -am package')) {
    throw 'Cache contract source must be copied between dependency resolution and packaging.'
}
$rules = @(Get-Content (Join-Path $root '.dockerignore') | Where-Object { $_ -and $_ -notmatch '^\s*#' })
if ($rules[0] -ne '*') { throw 'The production context must remain an allowlist.' }
foreach ($include in @('!novel-cache-contract/pom.xml', '!novel-cache-contract/src/**')) {
    if ($include -notin $rules) { throw "Missing contract context inclusion: $include" }
}
# Only the contract POM and source tree may be re-included. A module-wide
# exception would also send target, private files and unrelated artifacts.
$contractExceptions = @($rules | Where-Object { $_ -like '!novel-cache-contract*' })
if ($contractExceptions.Count -ne 2) { throw 'Cache contract context exceptions must remain narrow.' }
foreach ($line in @($dockerfile -split "`r?`n" | Where-Object { $_ -match '^COPY\s' -and $_ -notmatch '--from=' })) {
    $parts = @($line -split '\s+' | Where-Object { $_ -notmatch '^--' })
    if (-not (Test-Path -LiteralPath (Join-Path $root $parts[1]))) { throw "Missing COPY source: $($parts[1])" }
}
Write-Host 'Production Docker build contract passed (reactor POMs, source order, narrow context, COPY sources).'

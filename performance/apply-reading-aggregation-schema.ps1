$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$schema = Join-Path $root 'doc/sql/20260911_reading_daily_aggregation.sql'
if (-not (Test-Path -LiteralPath $schema -PathType Leaf)) {
    throw 'The dated reading aggregation schema is missing.'
}

# Pin the write target. No remote database/container override is accepted.
$health = docker inspect --format '{{.State.Health.Status}}' novel-mysql 2>$null
if ($LASTEXITCODE -ne 0 -or ($health | Out-String).Trim() -ne 'healthy') {
    throw 'Local novel-mysql must be running and healthy before applying the reading schema.'
}

# MYSQL_PWD avoids the command-line password warning. stderr is withheld;
# Windows PowerShell may still throw on unexpected native stderr (fail closed).
# A returned nonzero exit code is also checked before reporting success.
$previousOutputEncoding = $OutputEncoding
try {
    $OutputEncoding = New-Object System.Text.UTF8Encoding($false)
    Get-Content -LiteralPath $schema -Raw -Encoding UTF8 |
        docker exec -i -e MYSQL_PWD=123456 novel-mysql mysql --default-character-set=utf8mb4 -uroot novel_plus 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw 'Reading schema application failed in local novel-mysql; inspect the local MySQL logs.'
    }
} finally {
    $OutputEncoding = $previousOutputEncoding
}
Write-Host 'Reading aggregation schema applied to local novel_plus (existing tables/data preserved).'

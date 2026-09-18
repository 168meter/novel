param(
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$')][string]$Container = 'novel-mysql',
    [ValidatePattern('^[A-Za-z][A-Za-z0-9_]{0,63}$')][string]$Database = 'novel_plus',
    [ValidatePattern('^[A-Za-z][A-Za-z0-9_]{0,31}$')][string]$User = 'root'
)
$ErrorActionPreference = 'Stop'
# Set MYSQL_PWD in the invoking environment. Its value is never placed in arguments or logs.
if ([string]::IsNullOrEmpty($env:MYSQL_PWD)) { throw 'MYSQL_PWD must be set in the invoking environment.' }
$schema = Join-Path (Split-Path -Parent $PSScriptRoot) 'doc/sql/20260917_authentication_security.sql'
if (-not (Test-Path -LiteralPath $schema -PathType Leaf)) { throw 'Authentication migration is missing.' }
try { $health = docker inspect --format '{{.State.Health.Status}}' $Container 2>$null }
catch { throw 'Cannot inspect the target MySQL container.' }
if ($LASTEXITCODE -ne 0 -or ($health | Out-String).Trim() -ne 'healthy') {
    throw 'The target MySQL container must be running and healthy.'
}
$previousOutputEncoding = $OutputEncoding
try {
    $OutputEncoding = New-Object System.Text.UTF8Encoding($false)
    Get-Content -LiteralPath $schema -Raw -Encoding UTF8 |
        docker exec -i -e MYSQL_PWD $Container mysql --default-character-set=utf8mb4 --batch "--user=$User" "--database=$Database" 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'MySQL execution failed.' }
} catch {
    throw 'Authentication migration failed; investigate the target database before retrying.'
} finally {
    $OutputEncoding = $previousOutputEncoding
}
Write-Host 'Authentication schema applied successfully.'

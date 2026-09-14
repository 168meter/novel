$ErrorActionPreference = 'Stop'
$scriptPath = Join-Path $PSScriptRoot 'apply-reading-aggregation-schema.ps1'
if (-not (Test-Path -LiteralPath $scriptPath)) { throw 'Reading schema application script is missing.' }

# Exercise the real script with only its external Docker boundary replaced.
# A missing health guard, wrong SQL payload, or ignored exit code must fail.
$global:readingSchemaTestState = @{ mode = 'Healthy'; calls = @(); sqlPayload = '' }
function docker {
    $arguments = @($args)
    $global:readingSchemaTestState.calls += ,$arguments
    if ($arguments[0] -eq 'inspect') {
        $global:LASTEXITCODE = 0
        if ($global:readingSchemaTestState.mode -eq 'Unhealthy') { return 'starting' }
        return 'healthy'
    }
    if ($OutputEncoding.WebName -ne 'utf-8') { throw 'SQL pipe must preserve UTF-8 Chinese comments on Windows PowerShell.' }
    $global:readingSchemaTestState.sqlPayload = ($input | Out-String)
    $global:LASTEXITCODE = 0
    if ($global:readingSchemaTestState.mode -eq 'MySqlFailed') { $global:LASTEXITCODE = 1 }
}

try {
    & $scriptPath
    if ($global:readingSchemaTestState.calls.Count -ne 2) { throw 'Expected health inspection and one SQL execution.' }
    $inspection = $global:readingSchemaTestState.calls[0]
    if ($inspection[0] -ne 'inspect' -or $inspection[-1] -ne 'novel-mysql') { throw 'Wrong health inspection target.' }
    $execution = $global:readingSchemaTestState.calls[1]
    if (($execution -join ' ') -ne 'exec -i -e MYSQL_PWD=123456 novel-mysql mysql --default-character-set=utf8mb4 -uroot novel_plus') {
        throw 'Schema application must target only the local novel-mysql / novel_plus.'
    }
    $expected = Get-Content -LiteralPath (Join-Path (Split-Path -Parent $PSScriptRoot) 'doc/sql/20260911_reading_daily_aggregation.sql') -Raw -Encoding UTF8
    if ($global:readingSchemaTestState.sqlPayload.Trim() -ne $expected.Trim()) { throw 'Wrong schema SQL payload.' }

    foreach ($mode in @('Unhealthy', 'MySqlFailed')) {
        $global:readingSchemaTestState.mode = $mode
        $global:readingSchemaTestState.calls = @()
        $failed = $false
        try { & $scriptPath } catch { $failed = $true }
        if (-not $failed) { throw "Expected a failure for $mode." }
        if ($mode -eq 'Unhealthy' -and $global:readingSchemaTestState.calls.Count -ne 1) { throw 'Unhealthy MySQL must not receive SQL.' }
        if ($mode -eq 'MySqlFailed' -and $global:readingSchemaTestState.calls.Count -ne 2) { throw 'MySQL failure must follow SQL execution.' }
    }
    Write-Host 'Reading schema script behavior passed (success, unhealthy container, SQL failure).'
} finally {
    Remove-Item Function:docker
    Remove-Variable readingSchemaTestState -Scope Global
}

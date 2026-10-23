#requires -Version 7
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Moderation-Evidence.ps1')
$dir=Join-Path $PSScriptRoot ('artifacts/evidence-check-'+[guid]::NewGuid().ToString('N'))
$null=New-Item -ItemType Directory -Path $dir
$started=[DateTime]::UtcNow
$old=Join-Path $dir 'TEST-example.OldTest.xml';'<testsuite tests="1" failures="0" errors="0" skipped="0"/>'|Set-Content -LiteralPath $old
[IO.File]::SetLastWriteTimeUtc($old,$started.AddMinutes(-10))
if(@(Get-ModerationFreshReports -Directory $dir -StartedUtc $started -Names OldTest).Count -ne 0){throw 'Stale successful report accepted'}
$fresh=Join-Path $dir 'TEST-example.NewTest.xml';'<testsuite tests="1" failures="0" errors="0" skipped="0"/>'|Set-Content -LiteralPath $fresh
if(@(Get-ModerationFreshReports -Directory $dir -StartedUtc $started -Names NewTest).Count -ne 1){throw 'Fresh report rejected'}
if(@(Get-ModerationFreshReports -Directory $dir -StartedUtc $started -Names MissingTest).Count -ne 0){throw 'Unselected report accepted'}
Write-Host "PASS: 3 evidence freshness checks; fixtures retained: $dir"

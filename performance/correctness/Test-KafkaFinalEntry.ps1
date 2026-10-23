#requires -Version 7
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'KafkaFinal-Common.ps1')
function Need($Ok,$Message){if(-not $Ok){throw $Message}}
$running=[pscustomobject]@{Id=('a'*64);Name='/target';RestartCount=0;State=[pscustomobject]@{Running=$true;Paused=$false;Restarting=$false;OOMKilled=$false;Status='running'};Config=[pscustomobject]@{Labels=[pscustomobject]@{'com.docker.compose.project'='expected';'com.docker.compose.service'='kafka'}}}
Assert-KviContainer $running 'target' ('a'*64) 'expected' 'kafka'
$running.State.Running=$false
$rejected=$false;try{Assert-KviContainer $running 'target' ('a'*64) 'expected' 'kafka'}catch{$rejected=$true}
Need $rejected 'Stopped dependency was accepted'
$running.State.Running=$true
$rejected=$false;try{Assert-KviContainer $running 'target' ('b'*64) 'expected' 'kafka'}catch{$rejected=$true}
Need $rejected 'Drifted container was accepted'
$running.State|Add-Member NoteProperty Health ([pscustomobject]@{Status='unhealthy'})
$rejected=$false;try{Assert-KviContainer $running 'target' ('a'*64) 'expected' 'kafka'}catch{$rejected=$true}
Need $rejected 'Unhealthy dependency was accepted'
$tmp=Join-Path ([IO.Path]::GetTempPath()) ('kv-entry-'+[guid]::NewGuid().ToString('N'))
$null=New-Item -ItemType Directory $tmp
try {
 $pwsh=(Get-Process -Id $PID).Path
 $got=Invoke-KviProcess $pwsh @('-NoProfile','-Command','[Console]::Write("bounded-ok")') (Join-Path $tmp 'normal') 15
 Need ($got -ceq 'bounded-ok') 'Process output was not captured'
 $rejected=$false
 try{Invoke-KviProcess $pwsh @('-NoProfile','-Command','Start-Sleep -Seconds 30') (Join-Path $tmp 'timeout') 1|Out-Null}catch{$rejected=$_.Exception.Message -match 'timed out'}
 Need $rejected 'Process timeout was not enforced'
 $rejected=$false
 try{Invoke-KviProcess $pwsh @('-NoProfile','-Command','exit 7') (Join-Path $tmp 'exit') 15|Out-Null}catch{$rejected=$_.Exception.Message -match 'exit=7'}
 Need $rejected 'Nonzero exit code was ignored'
 Need ((Get-KviCleanupState $tmp $false) -ceq 'NOT_NEEDED') 'No run incorrectly reported cleanup'
 Need ((Get-KviCleanupState $tmp $true) -ceq 'UNVERIFIED') 'Missing cleanup evidence accepted'
 '{"cleanup":"FAILED"}'|Set-Content (Join-Path $tmp 'cleanup-retry.json')
 Need ((Get-KviCleanupState $tmp $true) -ceq 'FAILED') 'Failed cleanup was masked'
 '{"cleanup":"VERIFIED"}'|Set-Content (Join-Path $tmp 'cleanup-retry.json')
 Need ((Get-KviCleanupState $tmp $true) -ceq 'VERIFIED') 'Successful cleanup was lost on failed run'
}finally{Get-ChildItem -LiteralPath $tmp -File|Remove-Item;Remove-Item -LiteralPath $tmp}
Write-Output 'Kafka entry offline checks: 11 PASSED (no Docker access)'

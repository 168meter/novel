#requires -Version 7
param([Parameter(Mandatory)][string]$Run,[ValidateSet('builtin','green','orange')][string]$Variant='builtin',[string]$BaseUrl='http://127.0.0.1:28283')
$ErrorActionPreference='Stop'
$source=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$node=Join-Path $env:USERPROFILE '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node.exe'
& $node (Join-Path $PSScriptRoot 'Test-BrowserInteraction.cjs') $BaseUrl $Run $Variant $source
if($LASTEXITCODE -ne 0){throw 'Actual browser checks incomplete; inspect variant JSON. No dependency installation or browser download attempted.'}

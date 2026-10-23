#requires -Version 7
param([Parameter(Mandatory)][string]$Evidence)
$ErrorActionPreference='Stop';Set-StrictMode -Version Latest
$toolsRoot=$PSScriptRoot;$runtime=[IO.Path]::GetFullPath($Evidence)
if(Test-Path -LiteralPath $runtime){throw 'Choose a new evidence folder; preserve previous results'}
$null=New-Item -ItemType Directory -Path $runtime
$lex=$null;$errors=$null;$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $toolsRoot 'Run-InteractionAcceptance.ps1'),[ref]$lex,[ref]$errors)
if($errors){throw ($errors|Out-String)}
$f=$ast.Find({param($n)$n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq 'FrontConfig'},$true)
# Exercise the exact dynamic loader used by Resume-InteractionBrowser.
Invoke-Expression ($f.Extent.Text.Replace('$PSScriptRoot','$toolsRoot'))
$checks=0
foreach($variant in @('builtin','green','orange')){
 FrontConfig $variant
 $text=Get-Content (Join-Path $runtime 'front.yml') -Raw
 $prefix=if($variant -eq 'builtin'){'classpath:/templates/'}else{"file:/app/templates/$variant/html/"}
 if(-not $text.Contains($prefix)){throw "Wrong template prefix: $variant"}
 if($text -notmatch 'mail.smtp.auth: false'){throw 'Original verification overrides lost'}
 Copy-Item -LiteralPath (Join-Path $runtime 'front.yml') -Destination (Join-Path $runtime "$variant.yml")
 $checks++
}
# Pin the continuation loader to a captured directory rather than automatic variable scope.
$resume=Get-Content (Join-Path $toolsRoot 'Resume-InteractionBrowser.ps1') -Raw
if(-not $resume.Contains('$toolsRoot=$PSScriptRoot') -or -not $resume.Contains(".Replace('`$PSScriptRoot','`$toolsRoot')")){throw 'Continuation loader no longer captures directory'}
$checks++
"PASS: $checks dynamic-helper path/config checks; no Docker/SQL/browser operations."|Tee-Object -FilePath (Join-Path $runtime 'result.log')

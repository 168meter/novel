#requires -Version 7
[CmdletBinding()]
param([switch]$Offline,[string]$CredentialFile=(Join-Path $PSScriptRoot 'runtime/deploy-20261017233540699/verify.env'))
$ErrorActionPreference='Stop'
# Reuse only the bounded native-process helper; import performs no Docker operations.
. (Join-Path $PSScriptRoot 'KafkaFinal-Common.ps1')
$source=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$token=(Get-Date -Format yyyyMMddHHmmssfff)+'_'+[guid]::NewGuid().ToString('N').Substring(0,8)
$run=Join-Path $PSScriptRoot "artifacts/redis-final-$token"
$private=Join-Path $PSScriptRoot "runtime/redis-final-$token"
$null=New-Item -ItemType Directory $run,$private
$state=[ordered]@{status='RUNNING';A='BLOCKED';B='BLOCKED';C='BLOCKED';D='BLOCKED';regressions='BLOCKED';REDIS_READY_TO_FREEZE=$false;formalLoad='NOT_RUN';deployment='NOT_RUN';testData='RETAINED';started=[DateTimeOffset]::Now.ToString('o')}
$java='C:\Program Files\Java\jdk-21.0.10\bin\java.exe'
$javac='C:\Program Files\Java\jdk-21.0.10\bin\javac.exe'
$envNames=@('RCF_RUN','RCF_IDENTITY','RCF_SCHEMA','RCF_USER','RCF_PASSWORD','RCF_REDIS_PASSWORD','RCF_OUT')
$previous=@{};foreach($k in $envNames){$previous[$k]=[Environment]::GetEnvironmentVariable($k,'Process')}
$race=$null;$phase='preparation'
function Save([string]$name,$value){$value|ConvertTo-Json -Depth 20|Set-Content -LiteralPath (Join-Path $run $name) -Encoding utf8NoBOM}
function Need($ok,[string]$message){if(-not $ok){throw $message}}
function QuoteJava([string]$value){'"'+$value.Replace('\','\\').Replace('"','\"')+'"'}
function Inputs {
 $paths=@(Get-Item -LiteralPath (Join-Path $source 'pom.xml'))
 foreach($m in @('novel-cache-contract','novel-common','novel-interaction','novel-front','novel-admin','novel-crawl')){
  $paths+=Get-Item -LiteralPath (Join-Path $source "$m/pom.xml")
  $paths+=Get-ChildItem -LiteralPath (Join-Path $source "$m/src") -Recurse -File
 }
 $paths+=Get-Item -LiteralPath $PSCommandPath,(Join-Path $PSScriptRoot 'Run-ChapterCacheChecks.ps1'),(Join-Path $PSScriptRoot 'KafkaFinal-Common.ps1')
 $paths+=Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'redis-final') -File
 @($paths|Sort-Object FullName -Unique|ForEach-Object{[ordered]@{path=[IO.Path]::GetRelativePath($source,$_.FullName);sha256=(Get-FileHash -LiteralPath $_.FullName).Hash}})
}
function RunJava([string]$role,[string[]]$arguments,[string]$label){
 $null=Invoke-KviProcess $java (@('@'+(Join-Path $private "$role.args"))+$arguments) (Join-Path $run $label) 70
}
function PassFile([string]$name){
 $path=Join-Path $run "$name.json";Need (Test-Path -LiteralPath $path) "Missing real result: $name"
 $v=Get-Content -LiteralPath $path -Raw|ConvertFrom-Json;Need ($v.status -ceq 'PASS') "Result not PASS: $name"
}
function StartRace {
 $info=[Diagnostics.ProcessStartInfo]::new();$info.FileName=$java;$info.UseShellExecute=$false;$info.CreateNoWindow=$true
 $info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
 $info.StandardOutputEncoding=[Text.Encoding]::UTF8;$info.StandardErrorEncoding=[Text.Encoding]::UTF8
 $info.ArgumentList.Add('@'+(Join-Path $private 'front.args'));$info.ArgumentList.Add('race')
 $p=[Diagnostics.Process]::new();$p.StartInfo=$info;$null=$p.Start()
 return @{process=$p;stdout=$p.StandardOutput.ReadToEndAsync();stderr=$p.StandardError.ReadToEndAsync();finished=$false}
}
function FinishRace($r,[bool]$abort){
 if($r.finished){return}
 $p=$r.process
 try{
  if($abort -and -not $p.HasExited){$p.Kill($true)}
  if(-not $p.WaitForExit(70000)){$p.Kill($true);$p.WaitForExit();throw 'C bounded worker timed out'}
  if(-not $r.stdout.Wait(10000) -or -not $r.stderr.Wait(10000)){throw 'C pipe drain timed out'}
  [IO.File]::WriteAllText((Join-Path $run 'C-front.stdout.txt'),$r.stdout.GetAwaiter().GetResult(),[Text.UTF8Encoding]::new($false))
  [IO.File]::WriteAllText((Join-Path $run 'C-front.stderr.txt'),$r.stderr.GetAwaiter().GetResult(),[Text.UTF8Encoding]::new($false))
  if(-not $abort){Need ($p.ExitCode -eq 0) 'C worker failed; inspect C-front logs'}
 }finally{$r.finished=$true;$p.Dispose()}
}
try{
 foreach($file in @($java,$javac)){Need (Test-Path -LiteralPath $file) "Cached tool missing: $file"}
 $state.head=(Invoke-KviProcess 'git.exe' @('-C',$source,'rev-parse','HEAD') (Join-Path $run 'git-head') 20).Trim()
 $null=Invoke-KviProcess 'git.exe' @('-C',$source,'status','--porcelain=v1') (Join-Path $run 'git-status') 20
 $null=Invoke-KviProcess 'git.exe' @('-C',$source,'diff','--binary') (Join-Path $run 'git-diff') 30
 $null=Invoke-KviProcess 'git.exe' @('-C',$source,'diff','--cached','--binary') (Join-Path $run 'git-staged') 30
 $inputs=@(Inputs);Save 'source-inputs.json' $inputs
 $state.codeFingerprint=(Get-FileHash -LiteralPath (Join-Path $run 'source-inputs.json')).Hash
 $frozen=@(Get-Content -LiteralPath (Join-Path $PSScriptRoot 'artifacts/chapter-identity-20261023102501127/kafka-frozen-sha256.json') -Raw|ConvertFrom-Json)
 foreach($f in $frozen){Need ((Get-FileHash -LiteralPath (Join-Path $source $f.path)).Hash -ceq $f.sha256) 'Frozen Kafka drift'}
 Save 'kafka-frozen-sha256.json' $frozen
 if(-not $Offline){
  # Exactly the existing published private endpoints; no Docker probing, start, stop or reconfiguration.
  foreach($port in @(23307,26381)){
   $tcp=[Net.Sockets.TcpClient]::new()
   try{Need ($tcp.ConnectAsync('127.0.0.1',$port).Wait(3000)) "Private endpoint unavailable: $port";Need $tcp.Connected "Private endpoint refused: $port"}finally{$tcp.Dispose()}
  }
  $mem=Get-CimInstance Win32_PerfFormattedData_PerfOS_Memory
  $resource=[ordered]@{time=[DateTimeOffset]::Now.ToString('o');availableBytes=[long]$mem.AvailableBytes;commitHeadroom=[long]$mem.CommitLimit-[long]$mem.CommittedBytes;maximumConcurrentClients=2;clientHeapEach=256MB}
  Save 'resource-preflight.json' $resource
  Need ($resource.availableBytes -ge 1536MB -and $resource.commitHeadroom -ge 1536MB) 'Insufficient headroom for bounded local build/clients; no settings changed'
 }
 Write-Host 'Redis final: current-source local regressions and module-specific test clients'
 $pwsh=(Get-Command pwsh.exe -ErrorAction Stop).Source
 $text=Invoke-KviProcess $pwsh @('-NoProfile','-File',(Join-Path $PSScriptRoot 'Run-ChapterCacheChecks.ps1')) (Join-Path $run 'local-regressions') 480
 $match=[regex]::Match($text,'Chapter cache evidence: (.+); status=LOCAL_TESTS_PASSED_REDIS_PENDING;')
 Need $match.Success 'Fresh local regression success missing'
 $local=[IO.Path]::GetFullPath($match.Groups[1].Value.Trim());Need ($local.StartsWith([IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'artifacts'))+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) 'Regression evidence outside artifacts'
 $summary=Get-Content -LiteralPath (Join-Path $local 'summary.json') -Raw|ConvertFrom-Json
 Need ($summary.totals.tests -eq 107 -and $summary.totals.failures -eq 0 -and $summary.totals.errors -eq 0 -and $summary.totals.skipped -eq 0) 'Local regression totals incomplete'
 $state.localTests=107;$state.localEvidence=$local
 $definitions=@(
  @{role='admin';module='novel-admin';suite='ChapterIdentityContractTest';source='ChapterFinalAdmin.java';main='com.java2nb.novel.acceptance.ChapterFinalAdmin'},
  @{role='front';module='novel-front';suite='ChapterContentCacheTest';source='ChapterFinalFront.java';main='com.java2nb.novel.service.cache.ChapterFinalFront'},
  @{role='crawl';module='novel-crawl';suite='BookServiceChapterCacheEvictionTest';source='ChapterFinalCrawl.java';main='com.java2nb.novel.acceptance.ChapterFinalCrawl'}
 )
 foreach($d in $definitions){
  $xmls=@(Get-ChildItem -LiteralPath (Join-Path $local $d.module) -Filter "TEST-*.$($d.suite).xml");Need ($xmls.Count -eq 1) 'Fresh module classpath missing'
  [xml]$xml=Get-Content -LiteralPath $xmls[0].FullName -Raw
  $cp=[string]($xml.testsuite.properties.property|Where-Object name -ceq 'java.class.path').value;Need ([bool]$cp) 'Missing Surefire classpath'
  foreach($p in $cp.Split(';',[StringSplitOptions]::RemoveEmptyEntries)){Need (Test-Path -LiteralPath $p) "Classpath input missing: $p"}
  $dest=Join-Path $private "classes-$($d.role)";$null=New-Item -ItemType Directory $dest
  $argfile=Join-Path $private "compile-$($d.role).args"
  @('-encoding','UTF-8','-proc:none','-cp',(QuoteJava $cp),'-d',(QuoteJava $dest),(QuoteJava (Join-Path $PSScriptRoot 'redis-final/ChapterFinalSupport.java')),(QuoteJava (Join-Path $PSScriptRoot ('redis-final/'+$d.source))))|Set-Content -LiteralPath $argfile -Encoding utf8NoBOM
  $null=Invoke-KviProcess $javac @('@'+$argfile) (Join-Path $run "compile-$($d.role)") 90
  $redisClient=if($d.role -ceq 'crawl'){'jedis'}else{'lettuce'}
  @('-Xmx256m','-Dfile.encoding=UTF-8',"-Drcf.redis.client=$redisClient",'-cp',(QuoteJava ($dest+';'+$cp)),$d.main)|Set-Content -LiteralPath (Join-Path $private "$($d.role).args") -Encoding utf8NoBOM
  RunJava $d.role @('probe') "probe-$($d.role)"
 }
 $state.guardProbes=3
 if($Offline){$state.status='READY_TO_RUN';$state.integration='NOT_RUN'}else{
  $phase='namespace'
  $credential=[IO.Path]::GetFullPath($CredentialFile);$allowed=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'runtime'))+[IO.Path]::DirectorySeparatorChar
  Need ($credential.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase) -and [IO.Path]::GetFileName($credential) -ceq 'verify.env') 'Only preserved runtime verify.env credentials allowed'
  $cv=@{};foreach($line in Get-Content -LiteralPath $credential){if($line -match '^([A-Z_]+)=(.*)$'){$cv[$Matches[1]]=$Matches[2]}}
  Need ($cv.CV_DATABASE -ceq 'cv_app_20261017231415443' -and $cv.CV_DATABASE_USER -ceq 'cv_20261017231415443' -and $cv.CV_DATABASE_PASSWORD -and $cv.CV_REDIS_PASSWORD) 'Private credential identity mismatch'
  $env:RCF_RUN=$token;$env:RCF_IDENTITY='20261017231415443';$env:RCF_SCHEMA="cv_it_$($env:RCF_IDENTITY)_rc_$token";$env:RCF_USER=$cv.CV_DATABASE_USER;$env:RCF_PASSWORD=$cv.CV_DATABASE_PASSWORD;$env:RCF_REDIS_PASSWORD=$cv.CV_REDIS_PASSWORD;$env:RCF_OUT=$run
  $state.schema=$env:RCF_SCHEMA
  RunJava 'admin' @('setup') 'setup';PassFile 'setup'
  foreach($case in @('A','B')){
   $phase=$case;$slot=if($case -ceq 'A'){'1'}else{'2'}
   RunJava 'front' @("$case-baseline",$slot,'old') "$case-baseline";PassFile "$case-baseline"
   RunJava 'admin' @($case) "$case-admin";PassFile "$case-write"
   $expected=if($case -ceq 'A'){'new'}else{'old'}
   RunJava 'front' @("$case-read",$slot,$expected) "$case-read";PassFile "$case-read";$state[$case]='PASS'
  }
  $phase='C';$race=StartRace
  $deadline=[DateTime]::UtcNow.AddSeconds(20)
  while(-not(Test-Path -LiteralPath (Join-Path $run 'C-loader-held.signal'))){
   Need (-not $race.process.HasExited) 'C worker exited before loader checkpoint'
   Need ([DateTime]::UtcNow -lt $deadline) 'C loader checkpoint timeout';Start-Sleep -Milliseconds 25
  }
  RunJava 'admin' @('C') 'C-admin';PassFile 'C-write'
  FinishRace $race $false;PassFile 'C';$state.C='PASS'
  $phase='D';RunJava 'admin' @('D') 'D-admin';PassFile 'D';$state.D='PASS'
  $phase='regressions';RunJava 'admin' @('create') 'admin-create';PassFile 'admin-regression'
  RunJava 'front' @('author') 'author';PassFile 'author-regression'
  RunJava 'front' @('crawl-baseline','6','old') 'crawl-baseline';PassFile 'crawl-baseline'
  RunJava 'crawl' @('run') 'crawl';PassFile 'crawl-regression';PassFile 'txt-write'
  RunJava 'front' @('crawl-read','6','crawl new') 'crawl-read';PassFile 'crawl-read'
  RunJava 'front' @('txt') 'txt-read';PassFile 'txt-regression'
  $state.regressions='PASS';$state.integrationChecks=8;$state.integrationFailures=0
  $state.status='REDIS_READY_TO_FREEZE';$state.REDIS_READY_TO_FREEZE=$true
 }
 foreach($i in $inputs){Need ((Get-FileHash -LiteralPath (Join-Path $source $i.path)).Hash -ceq $i.sha256) "Source drift: $($i.path)"}
 foreach($f in $frozen){Need ((Get-FileHash -LiteralPath (Join-Path $source $f.path)).Hash -ceq $f.sha256) 'Frozen Kafka drift'}
}catch{
 $state.status=if($phase -in @('preparation','namespace')){'BLOCKED'}else{'FAILED'}
 $state.REDIS_READY_TO_FREEZE=$false;$state.failedPhase=$phase;$state.error=$_.Exception.Message
 $_|Out-String|Set-Content -LiteralPath (Join-Path $run 'exception.txt') -Encoding utf8NoBOM
 if($phase -in @('A','B','C','D')){$state[$phase]='FAIL'}
 if($race){try{FinishRace $race $true}catch{$state.workerCaptureError=$_.Exception.Message}}
}finally{
 foreach($k in $envNames){[Environment]::SetEnvironmentVariable($k,$previous[$k],'Process')}
 $state.finished=[DateTimeOffset]::Now.ToString('o');Save 'summary.json' $state
 Write-Host "Redis final evidence: $run; status=$($state.status); A=$($state.A); B=$($state.B); C=$($state.C); D=$($state.D); regressions=$($state.regressions)"
}
if($state.status -in @('FAILED','BLOCKED')){throw "Redis acceptance incomplete; preserve evidence: $run"}


#requires -Version 7
param([switch]$IncludeRedis,[string]$CredentialFile)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Moderation-Evidence.ps1')
$source=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$run=Join-Path $PSScriptRoot ('artifacts/reaction-'+(Get-Date -Format 'yyyyMMddHHmmssfff'))
if(Test-Path -LiteralPath $run){throw 'Evidence path already exists'}
$null=New-Item -ItemType Directory -Path $run
$state=[ordered]@{status='RUNNING';realRedis=[bool]$IncludeRedis;started=[DateTimeOffset]::Now.ToString('o');boundaries=@('No Docker/deployment/database writes/migration or frozen experiment operations.','Real Redis uses private correctness endpoint 127.0.0.1:26381 and previously absent random keys; keys/evidence retained.','Target SQL visibility is mocked in real Redis fixture; HTTP uses MockMvc authenticated attributes, not deployed login.','Atomic Redis snapshot is not a DB/Redis transaction or ordering guarantee for different intentions.')}
$keys=@('JAVA_HOME','CORRECTNESS_REDIS_PASSWORD','CORRECTNESS_REDIS_PORT','CORRECTNESS_EVIDENCE')
$previous=@{};foreach($key in $keys){$previous[$key]=[Environment]::GetEnvironmentVariable($key,'Process')}
$testNames=@('ReactionVisibilityTest','ReactionStateServiceTest','ReactionControllerTest','LikeServiceImplTest','ContentReactionWiringTest')
$tests='ReactionVisibilityTest,ReactionStateServiceTest,ReactionControllerTest,LikeServiceImplTest,ContentReactionWiringTest#reactionsStartWithBothBaseAndFrontMappersAndUseTheIntendedTargets'
try {
 $env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.10';$env:CORRECTNESS_EVIDENCE=$run
 [Environment]::SetEnvironmentVariable('CORRECTNESS_REDIS_PASSWORD',$null,'Process')
 $env:CORRECTNESS_REDIS_PORT='26381'
 if($IncludeRedis){
  if(-not $CredentialFile){throw 'Specify an existing correctness runtime verify.env; no frozen experiment credentials'}
  $full=[IO.Path]::GetFullPath($CredentialFile);$runtime=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'runtime'))+[IO.Path]::DirectorySeparatorChar
  if(-not $full.StartsWith($runtime,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($full) -cne 'verify.env'){throw 'Only a preserved correctness runtime verify.env is accepted'}
  $values=@{};foreach($line in Get-Content -LiteralPath $full){if($line -match '^([A-Z_]+)=(.*)$'){$values[$Matches[1]]=$Matches[2]}}
  if($values.CV_DATABASE -cne 'cv_app_20261017231415443' -or -not $values.CV_REDIS_PASSWORD){throw 'Unexpected validation identity or missing private Redis credential'}
  $env:CORRECTNESS_REDIS_PASSWORD=$values.CV_REDIS_PASSWORD
  $tests+=',LikeConcurrencyRedisIT';$testNames+= 'LikeConcurrencyRedisIT'
  $state.redisEndpoint='127.0.0.1:26381'
 }
 $node=Join-Path $env:USERPROFILE '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node.exe'
 & $node (Join-Path $PSScriptRoot 'Test-ReactionUi.cjs') *> (Join-Path $run 'ui.log')
 if($LASTEXITCODE -ne 0){throw 'Reaction UI checks failed'}
 $uiText=Get-Content -LiteralPath (Join-Path $run 'ui.log') -Raw
 if($uiText -notmatch 'PASS: ([0-9]+) reaction UI checks'){throw 'UI check total missing'}
 $state.uiChecks=[int]$Matches[1]
 $maven=Join-Path $env:USERPROFILE '.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn.cmd'
 $agent=Join-Path $env:USERPROFILE '.m2/repository/org/mockito/mockito-core/5.14.2/mockito-core-5.14.2.jar'
 $start=[DateTime]::UtcNow
 & $maven -f (Join-Path $source 'pom.xml') -o -q -pl novel-front -am '-Dmaven.test.skip=false' '-DskipTests=false' "-DargLine=-javaagent:$agent" "-Dtest=$tests" '-Dsurefire.failIfNoSpecifiedTests=false' test *> (Join-Path $run 'front.log')
 $exit=$LASTEXITCODE;$totals=[ordered]@{tests=0;failures=0;errors=0;skipped=0};$suites=@()
 $dest=Join-Path $run 'novel-front';$null=New-Item -ItemType Directory -Path $dest
 foreach($report in Get-ModerationFreshReports -Directory (Join-Path $source 'novel-front/target/surefire-reports') -StartedUtc $start -Names $testNames){
  Copy-Item -LiteralPath $report.FullName -Destination $dest
  [xml]$xml=Get-Content -LiteralPath $report.FullName -Raw;$suites+=([string]$xml.testsuite.name).Split('.')[-1]
  foreach($key in @('tests','failures','errors','skipped')){$totals[$key]+=[int]$xml.testsuite.$key}
 }
 $state.totals=$totals;$state.suites=$suites
 if($exit -ne 0){throw "Selected reaction Java tests failed: $run/front.log"}
 foreach($name in $testNames){if($name -notin $suites){throw "Missing fresh suite: $name"}}
 if($totals.tests -lt 28 -or $totals.failures -ne 0 -or $totals.errors -ne 0 -or $totals.skipped -ne 0){throw 'Incomplete/failed/skipped selected tests'}
 if($IncludeRedis){
  [xml]$redisReport=Get-Content -LiteralPath (Join-Path $dest 'TEST-com.java2nb.novel.service.impl.LikeConcurrencyRedisIT.xml') -Raw
  $before=@(Get-ChildItem -LiteralPath $run -Filter 'redis-*-before.json' -File);$after=@(Get-ChildItem -LiteralPath $run -Filter 'redis-*-after.json' -File)
  if($before.Count -ne [int]$redisReport.testsuite.tests -or $after.Count -ne $before.Count){throw 'Missing per-test real Redis before/after snapshots'}
  foreach($snapshot in $before){$data=Get-Content -Raw -LiteralPath $snapshot.FullName|ConvertFrom-Json;if($data.redisPort -ne 26381 -or @($data.keys|Where-Object {$_.type -cne 'NONE'}).Count -gt 0){throw 'Real Redis baseline was not isolated and empty'}}
  $state.redisTests=[int]$redisReport.testsuite.tests;$state.redisSnapshots=$before.Count+$after.Count
 }
 $state.status=if($IncludeRedis){'REDIS_TESTS_PASSED'}else{'LOCAL_TESTS_PASSED_REDIS_PENDING'}
} catch {$state.status='FAILED';$state.error=$_.Exception.Message;throw} finally {
 $state.finished=[DateTimeOffset]::Now.ToString('o');$state|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $run 'summary.json') -Encoding utf8NoBOM
 foreach($key in $keys){[Environment]::SetEnvironmentVariable($key,$previous[$key],'Process')}
 Write-Host "Reaction evidence: $run; status=$($state.status)"
}

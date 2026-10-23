#requires -Version 7
param([switch]$MySql,[string]$CredentialFile)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Moderation-Evidence.ps1')
$source=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$run=Join-Path $PSScriptRoot ('artifacts/moderation-'+(Get-Date -Format 'yyyyMMddHHmmssfff'))
if(Test-Path -LiteralPath $run){throw 'Evidence path already exists'}
$null=New-Item -ItemType Directory -Path $run
$state=[ordered]@{status='RUNNING';mysql=[bool]$MySql;started=[DateTimeOffset]::Now.ToString('o');boundaries=@('No Docker, deployment, source-schema writes, data cleanup or Stage B operations.','MySQL mode creates only new prefixed test schemas; all are retained.','H2 checks and permission annotations are not deployed Shiro acceptance.')}
$keys=@('JAVA_HOME','CORRECTNESS_MYSQL_PREFIX','CORRECTNESS_MYSQL_USER','CORRECTNESS_MYSQL_PASSWORD','CORRECTNESS_EVIDENCE')
$previous=@{};foreach($key in $keys){$previous[$key]=[Environment]::GetEnvironmentVariable($key,'Process')}
$tests='CommentModerationTest,ModerationMigrationTest,CommentCorrectnessTest,CommentDeletionTest,BookControllerCommentHttpTest,CommentPageVisibilityTest,BookCommentCounterProtectionTest'
try {
 $env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.10'
 $env:CORRECTNESS_EVIDENCE=$run
 foreach($key in @('CORRECTNESS_MYSQL_PREFIX','CORRECTNESS_MYSQL_USER','CORRECTNESS_MYSQL_PASSWORD')){[Environment]::SetEnvironmentVariable($key,$null,'Process')}
 if($MySql){
  if(-not $CredentialFile){throw 'Specify the preserved correctness verify.env; do not supply production credentials'}
  $credentialFull=[IO.Path]::GetFullPath($CredentialFile)
  $runtimeRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'runtime'))+[IO.Path]::DirectorySeparatorChar
  if(-not $credentialFull.StartsWith($runtimeRoot,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($credentialFull) -cne 'verify.env'){throw 'Credentials must be an existing correctness runtime verify.env'}
  $values=@{};foreach($line in Get-Content -LiteralPath $credentialFull){if($line -match '^([A-Z_]+)=(.*)$'){$values[$Matches[1]]=$Matches[2]}}
  if($values.CV_DATABASE -notmatch '^cv_app_([0-9]{17})$'){throw 'Unexpected preserved validation schema identity'}
  $identity=$Matches[1]
  if($values.CV_DATABASE_USER -cne "cv_$identity" -or -not $values.CV_DATABASE_PASSWORD){throw 'Credential identity mismatch'}
  $env:CORRECTNESS_MYSQL_PREFIX="cv_it_$identity"
  $env:CORRECTNESS_MYSQL_USER=$values.CV_DATABASE_USER
  $env:CORRECTNESS_MYSQL_PASSWORD=$values.CV_DATABASE_PASSWORD
  $state.identity=$values.CV_DATABASE
 }
 $maven=Join-Path $env:USERPROFILE '.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn.cmd'
 $agent=Join-Path $env:USERPROFILE '.m2/repository/org/mockito/mockito-core/5.14.2/mockito-core-5.14.2.jar'
 if(-not(Test-Path -LiteralPath $maven) -or -not(Test-Path -LiteralPath $agent)){throw 'Cached Maven/Mockito runtime missing'}
 & (Join-Path $PSScriptRoot 'Test-ModerationEvidence.ps1') *> (Join-Path $run 'evidence-checks.log')
 $node=Join-Path $env:USERPROFILE '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node.exe'
 if(-not(Test-Path -LiteralPath $node)){throw 'Bundled Node runtime missing for selected UI checks'}
 & $node (Join-Path $PSScriptRoot 'Test-ModerationUi.cjs') *> (Join-Path $run 'ui-checks.log')
 if($LASTEXITCODE -ne 0){throw 'Selected UI checks failed'}
 $state.uiChecks=13;$state.evidenceChecks=3
 $testStartedUtc=[DateTime]::UtcNow
 & $maven -f (Join-Path $source 'pom.xml') -o -q -pl novel-front,novel-admin -am '-Dmaven.test.skip=false' '-DskipTests=false' "-DargLine=-javaagent:$agent" "-Dtest=$tests" '-Dsurefire.failIfNoSpecifiedTests=false' test *> (Join-Path $run 'maven.log')
 $exit=$LASTEXITCODE
  $suites=@()
 $totals=[ordered]@{tests=0;failures=0;errors=0;skipped=0}
 foreach($module in @('novel-interaction','novel-front','novel-admin')) {
  $dest=Join-Path $run $module;$null=New-Item -ItemType Directory -Path $dest
  foreach($name in $tests.Split(',')) {
   foreach($report in Get-ModerationFreshReports -Directory (Join-Path $source "$module/target/surefire-reports") -StartedUtc $testStartedUtc -Names @($name)) {
    Copy-Item -LiteralPath $report.FullName -Destination $dest
    [xml]$xml=Get-Content -LiteralPath $report.FullName -Raw
    $suites+=([string]$xml.testsuite.name).Split('.')[-1]
    foreach($key in @('tests','failures','errors','skipped')){$totals[$key]+=[int]$xml.testsuite.$key}
   }
  }
 }
 $state.totals=$totals;$state.suites=$suites
 if($exit -ne 0){throw "Selected tests failed; inspect $run/maven.log"}
 foreach($name in $tests.Split(',')){if($name -notin $suites){throw "Expected report missing: $name"}}
 if($totals.tests -lt 50 -or $totals.failures -ne 0 -or $totals.errors -ne 0){throw 'Missing/incomplete test reports'}
 if($MySql -and $totals.skipped -ne 0){throw 'Real MySQL run must have no skipped checks'}
 if($MySql){
  foreach($pattern in @("mysql-cv_it_$($identity)_mod_*.txt",'mysql-front-*.txt','mysql-admin-*.txt',"migration-cv_it_$($identity)_mig_*.txt",'controlled-moderation-lock-wait.txt','controlled-lock-wait.txt')){
   if(@(Get-ChildItem -LiteralPath $run -Filter $pattern -File).Count -eq 0){throw "This run is missing raw MySQL evidence: $pattern"}
  }
 }
 $state.status=if($MySql){'MYSQL_TESTS_PASSED'}else{'LOCAL_TESTS_PASSED_MYSQL_PENDING'}
} catch {$state.status='FAILED';$state.error=$_.Exception.Message;throw} finally {
 $state.finished=[DateTimeOffset]::Now.ToString('o');$state|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $run 'summary.json') -Encoding utf8NoBOM
 foreach($key in $keys){[Environment]::SetEnvironmentVariable($key,$previous[$key],'Process')}
 Write-Host "Moderation evidence: $run; status=$($state.status)"
}

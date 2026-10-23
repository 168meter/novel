#requires -Version 7
param([switch]$IncludeRedis,[string]$CredentialFile=(Join-Path $PSScriptRoot 'runtime/deploy-20261017233540699/verify.env'))
$ErrorActionPreference='Stop'
$source=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$run=Join-Path $PSScriptRoot ('artifacts/chapter-cache-'+(Get-Date -Format yyyyMMddHHmmssfff))
if(Test-Path -LiteralPath $run){throw 'Evidence collision'}
$null=New-Item -ItemType Directory -Path $run,(Join-Path $run 'java-tmp')
$state=[ordered]@{status='RUNNING';realRedis=[bool]$IncludeRedis;mysqlIntegration='NOT_RUN';REDIS_READY_TO_FREEZE=$false;
 knownConcurrentIdentityMoveGap='CLOSED_BY_IMMUTABLE_APPLICATION_IDENTITIES';
 identityContract='book_index.book_id/storage_type and book_content.index_id immutable after creation; direct SQL/old application versions are outside this guarantee';
 scope='Targeted checks only. No Docker/deployment/Kafka/server-MySQL access/performance tests. Opt-in Redis uses fresh keys on private 26381; new values expire.';
 started=[DateTimeOffset]::Now.ToString('o')}
$names=@{
 'novel-cache-contract'=@('ChapterContentCacheKeyTest','ChapterCacheIdentityTest');
 'novel-front'=@('ChapterContentCacheTest','ChapterContentCacheFenceTest','FrontChapterCacheInvalidatorTest','BookServiceChapterCacheEvictionTest','BookControllerChapterOwnershipTest','BookChapterOwnershipTest','ChapterOwnershipReuseTest','FileChapterOwnershipTest');
 'novel-admin'=@('AdminChapterCacheInvalidatorTest','BookIndexChapterCacheEvictionTest','BookServiceChapterCacheEvictionTest','BookContentServiceChapterCacheEvictionTest','ChapterCacheMutationTransactionTest','ChapterIdentityContractTest','ChapterIdentityUpdateSqlTest','ChapterIdentityHttpTest','BookContentDaoCacheIdentityTest');
 'novel-crawl'=@('CrawlChapterCacheInvalidatorTest','BookServiceChapterCacheEvictionTest')
}
$envNames=@('JAVA_HOME','CORRECTNESS_REDIS_PASSWORD','CORRECTNESS_REDIS_PORT','CORRECTNESS_EVIDENCE','CHAPTER_CACHE_REDIS_IT')
$previous=@{};foreach($k in $envNames){$previous[$k]=[Environment]::GetEnvironmentVariable($k,'Process')}
try{
 $env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.10';$env:CORRECTNESS_EVIDENCE=$run
 $env:CHAPTER_CACHE_REDIS_IT=$null;$env:CORRECTNESS_REDIS_PASSWORD=$null;$env:CORRECTNESS_REDIS_PORT=$null
 $accepted=Join-Path $PSScriptRoot 'artifacts/kafka-final-20261022230546266_4a82e73e/source-inputs.json'
 $frozen=@(Get-Content -LiteralPath $accepted -Raw|ConvertFrom-Json|Where-Object {$_.path -match '(BookVisit|BookControllerVisitCountTest|KafkaFinal|BookController.java)'})
 foreach($f in $frozen){if((Get-FileHash -LiteralPath (Join-Path $source $f.path)).Hash -cne $f.sha256){throw "Frozen Kafka input changed: $($f.path)"}}
 $frozen|ConvertTo-Json -Depth 5|Set-Content (Join-Path $run 'kafka-frozen-sha256.json') -Encoding utf8NoBOM
 if($IncludeRedis){
  $full=[IO.Path]::GetFullPath($CredentialFile);$runtime=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'runtime'))+[IO.Path]::DirectorySeparatorChar
  if(-not $full.StartsWith($runtime,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($full) -cne 'verify.env'){throw 'Only correctness runtime verify.env credentials are accepted'}
  $cv=@{};foreach($line in Get-Content -LiteralPath $full){if($line -match '^([A-Z_]+)=(.*)$'){$cv[$Matches[1]]=$Matches[2]}}
  if($cv.CV_DATABASE -cne 'cv_app_20261017231415443' -or -not $cv.CV_REDIS_PASSWORD){throw 'Private validation credential identity mismatch'}
  $env:CHAPTER_CACHE_REDIS_IT='true';$env:CORRECTNESS_REDIS_PORT='26381';$env:CORRECTNESS_REDIS_PASSWORD=$cv.CV_REDIS_PASSWORD
  $names['novel-front']+='ChapterContentCacheFenceRedisIT'
 }
 $inputs=@();foreach($module in $names.Keys){$inputs+=Get-ChildItem -LiteralPath (Join-Path $source "$module/src") -Recurse -File|ForEach-Object{@{path=[IO.Path]::GetRelativePath($source,$_.FullName);sha256=(Get-FileHash -LiteralPath $_.FullName).Hash}}}
 $inputs|ConvertTo-Json -Depth 5|Set-Content (Join-Path $run 'source-sha256.json') -Encoding utf8NoBOM
 $tests=(@($names.Values|ForEach-Object {$_})|Sort-Object -Unique)-join ','
 $maven=Join-Path $env:USERPROFILE '.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9'
 $agent=Join-Path $env:USERPROFILE '.m2/repository/org/mockito/mockito-core/5.14.2/mockito-core-5.14.2.jar'
 $java=Join-Path $env:JAVA_HOME 'bin/java.exe'
 foreach($f in @($java,$agent,"$maven/boot/plexus-classworlds-2.8.0.jar")){if(-not(Test-Path -LiteralPath $f)){throw "Cached build dependency missing: $f"}}
 $started=[DateTime]::UtcNow
 $args=@('-Xmx512m',"-Dmaven.home=$maven","-Dmaven.multiModuleProjectDirectory=$source","-Dclassworlds.conf=$maven/bin/m2.conf",'-cp',"$maven/boot/plexus-classworlds-2.8.0.jar",'org.codehaus.plexus.classworlds.launcher.Launcher','-f',"$source/pom.xml",'-o','-q','-fae','-pl','novel-front,novel-admin,novel-crawl','-am','-Dmaven.test.skip=false','-DskipTests=false',"-DargLine=-Xmx256m -javaagent:$agent -Djava.io.tmpdir=$run/java-tmp","-Dtest=$tests",'-Dsurefire.failIfNoSpecifiedTests=false','-Dsurefire.timeout=180','test')
 & $java @args *> (Join-Path $run 'maven.log');$exit=$LASTEXITCODE
 $totals=[ordered]@{tests=0;failures=0;errors=0;skipped=0};$suites=@()
 foreach($module in $names.Keys){
  $dest=Join-Path $run $module;$null=New-Item -ItemType Directory $dest
  foreach($name in $names[$module]){
   $files=@(Get-ChildItem -LiteralPath (Join-Path $source "$module/target/surefire-reports") -Filter "TEST-*.$name.xml"|Where-Object LastWriteTimeUtc -ge $started)
   if($files.Count -ne 1){throw "Fresh report missing: $module/$name"}
   Copy-Item -LiteralPath $files[0].FullName -Destination $dest
   [xml]$xml=Get-Content -LiteralPath $files[0].FullName -Raw
   foreach($k in @('tests','failures','errors','skipped')){$totals[$k]+=[int]$xml.testsuite.$k}
   $suites+="$module/$name"
  }
 }
 $state.totals=$totals;$state.suites=$suites
 if($exit -ne 0 -or $totals.failures -or $totals.errors -or $totals.skipped){throw 'Selected checks failed or skipped; inspect raw Maven log and XML'}
 foreach($i in $inputs){if((Get-FileHash -LiteralPath (Join-Path $source $i.path)).Hash -cne $i.sha256){throw "Source changed during checks: $($i.path)"}}
 foreach($f in $frozen){if((Get-FileHash -LiteralPath (Join-Path $source $f.path)).Hash -cne $f.sha256){throw 'Frozen Kafka input drift'}}
 if($IncludeRedis){
  $before=@(Get-ChildItem -LiteralPath $run -Filter 'chapter-redis-*-before.json');$after=@(Get-ChildItem -LiteralPath $run -Filter 'chapter-redis-*-after.json')
  if($before.Count -ne 5 -or $after.Count -ne 5){throw 'Missing real Redis snapshots'}
  foreach($b in $before){$v=Get-Content $b.FullName -Raw|ConvertFrom-Json;if($v.redisPort -ne 26381 -or @($v.keys|Where-Object {$_.type -cne 'NONE'}).Count){throw 'Redis namespace was not initially empty'}}
  $state.status='REDIS_LUA_CHECKS_PASSED_MYSQL_PENDING';$state.realRedisTests=5
 }else{$state.status='LOCAL_TESTS_PASSED_REDIS_PENDING'}
}catch{$state.status='FAILED';$state.error=$_.Exception.Message;$_|Out-String|Set-Content (Join-Path $run 'exception.txt') -Encoding utf8NoBOM}
finally{
 foreach($k in $envNames){[Environment]::SetEnvironmentVariable($k,$previous[$k],'Process')}
 $state.finished=[DateTimeOffset]::Now.ToString('o');$state|ConvertTo-Json -Depth 10|Set-Content (Join-Path $run 'summary.json') -Encoding utf8NoBOM
 Write-Host "Chapter cache evidence: $run; status=$($state.status); mysqlIntegration=$($state.mysqlIntegration)"
}
if($state.status -eq 'FAILED'){throw "Chapter cache verification failed; retain evidence: $run"}

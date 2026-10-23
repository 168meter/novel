#requires -Version 7
[CmdletBinding()]
param([switch]$Offline,[string]$CredentialFile=(Join-Path $PSScriptRoot 'runtime/deploy-20261017233540699/verify.env'))
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'KafkaFinal-Common.ps1')
$source=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$token=(Get-Date -Format yyyyMMddHHmmssfff)+'_'+[guid]::NewGuid().ToString('N').Substring(0,8)
$run=Join-Path $PSScriptRoot "artifacts/kafka-final-$token"
$private=Join-Path $PSScriptRoot "runtime/kafka-final-$token"
if((Test-Path $run) -or (Test-Path $private)){throw 'Namespace collision; no resources adopted'}
$null=New-Item -ItemType Directory $run,$private
$state=[ordered]@{status='RUNNING';A='BLOCKED';B='BLOCKED';C='BLOCKED';cleanup='NOT_NEEDED';
 KAFKA_READY_TO_FREEZE=$false;formalLoad='NOT_RUN';started=[DateTimeOffset]::Now.ToString('o');
 scope='Only new acceptance topics/groups/schema and one ephemeral Java client. No existing application JAR starts.'}
$javaBefore=$env:JAVA_HOME
$docker=$null;$client=$null;$clientAttempted=$false;$canCleanup=$false;$envFile=$null;$ordinal=0
function Save([string]$Name,$Value){$Value|ConvertTo-Json -Depth 25|Set-Content -LiteralPath (Join-Path $run $Name) -Encoding utf8NoBOM}
function D([string[]]$Arguments,[int]$Timeout=30){
 $script:ordinal++;Invoke-KviProcess $docker (@('--context','desktop-linux')+$Arguments) (Join-Path $run ('docker-{0:d3}' -f $script:ordinal)) $Timeout
}
function Need($Ok,[string]$Message){if(-not $Ok){throw $Message}}
function Inspect([string]$Name){
 # Never save raw Config.Env (contains shared credentials).
 $start=[Diagnostics.ProcessStartInfo]::new();$start.FileName=$docker;$start.UseShellExecute=$false;$start.CreateNoWindow=$true
 $start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
 foreach($a in @('--context','desktop-linux','inspect',$Name)){$start.ArgumentList.Add($a)}
 $p=[Diagnostics.Process]::new();$p.StartInfo=$start
 try{$null=$p.Start();$o=$p.StandardOutput.ReadToEndAsync();$e=$p.StandardError.ReadToEndAsync()
  if(-not $p.WaitForExit(15000)){$p.Kill($true);throw 'Inspect timed out'}
  $raw=$o.GetAwaiter().GetResult();$err=$e.GetAwaiter().GetResult()
  if($p.ExitCode -ne 0){throw "Inspect failed: $Name ($err)"}
  return @($raw|ConvertFrom-Json)[0]
 }finally{$p.Dispose()}
}
function StopOwnClient {
 if(-not $script:clientAttempted){return}
 $ids=@((D @('ps','-aq','--filter',"name=^/$client`$")) -split '\r?\n'|Where-Object {$_})
 if($ids.Count -eq 0){return}
 Need ($ids.Count -eq 1) 'Ambiguous test client identity'
 $c=Inspect $client
 Need ($c.Config.Labels.'novel.acceptance.run' -ceq $token -and $c.Config.Labels.'novel.acceptance.kind' -ceq 'kafka-final') 'Refusing to remove unowned client'
 $null=D @('rm','-f',$c.Id)
}
try {
 $env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.10'
 $java=Join-Path $env:JAVA_HOME 'bin/java.exe'
 $maven=Join-Path $env:USERPROFILE '.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9'
 $agent=Join-Path $env:USERPROFILE '.m2/repository/org/mockito/mockito-core/5.14.2/mockito-core-5.14.2.jar'
 foreach($f in @($java,$agent,(Join-Path $maven 'boot/plexus-classworlds-2.8.0.jar'))){Need (Test-Path $f -PathType Leaf) "Cached build dependency missing: $f"}
 $git=(Get-Command git.exe -ErrorAction Stop).Source
 $null=Invoke-KviProcess $git @('-C',$source,'status','--porcelain=v1') (Join-Path $run 'git-status') 20
 $null=Invoke-KviProcess $git @('-C',$source,'diff','--binary') (Join-Path $run 'git-diff') 30
 $null=Invoke-KviProcess $git @('-C',$source,'diff','--cached','--binary') (Join-Path $run 'git-staged') 30
 $state.head=(Invoke-KviProcess $git @('-C',$source,'rev-parse','HEAD') (Join-Path $run 'git-head') 20).Trim()
 $inputs=Get-KviInputs $source;Save 'source-inputs.json' $inputs
 $state.sourceInputsSha256=(Get-FileHash (Join-Path $run 'source-inputs.json') -Algorithm SHA256).Hash
 $sourceDigest=($inputs|ConvertTo-Json -Depth 5 -Compress)
 $null=& (Join-Path $PSScriptRoot 'Test-KafkaFinalEntry.ps1')

 if(-not $Offline){
  $docker=(Get-Command docker.exe -ErrorAction Stop).Source
  $null=D @('version','--format','{{json .}}')
  $null=D @('info','--format','{{json .}}')
  $g1=@(Get-Content (Join-Path $PSScriptRoot 'artifacts/g1-20261021132829400/final-containers.json') -Raw|ConvertFrom-Json)
  $frontPin=@($g1|Where-Object service -ceq 'front');Need ($frontPin.Count -eq 1) 'Accepted JRE image checkpoint missing'
  $kafka=Inspect 'novel-cache-ab-20261016-kafka-1';$mysql=Inspect 'novel-cache-ab-20261016-mysql-1'
  Assert-KviContainer $kafka 'novel-cache-ab-20261016-kafka-1' '3d24bf6ab888071c172236c30f8674d603c798f9ba9e18afaa35c55b969f2055' 'novel-cache-ab-20261016' 'kafka'
  Assert-KviContainer $mysql 'novel-cache-ab-20261016-mysql-1' '2bde39b9bd95850a629c324e3c66d5b6aca97264ef32dcd05118e1ad9d37fb92' 'novel-cache-ab-20261016' 'mysql'
  $front=Inspect 'novel-interaction-20261020152352233-front-1'
  Need ($front.Image -ceq $frontPin[0].image) 'JRE donor image differs from accepted checkpoint'
  $image=$front.Image
  $network='novel-cache-ab-20261016_backend'
  foreach($c in @($kafka,$mysql)) {Need ($null -ne $c.NetworkSettings.Networks.PSObject.Properties[$network]) 'Dependency network mismatch'}
  Need ($kafka.Config.Env -ccontains 'KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://kafka:19092') 'Unexpected advertised Kafka listener'
  $net=D @('network','inspect',$network);$netObject=@($net|ConvertFrom-Json)[0]
  # Kafka metadata uses the alias kafka; reject a duplicate alias on this network.
  foreach($p in $netObject.Containers.PSObject.Properties){
   $member=Inspect $p.Name
   if(@($member.NetworkSettings.Networks.$network.Aliases) -ccontains 'kafka') {Need ($member.Id -ceq $kafka.Id) 'Kafka DNS alias collision'}
  }
  Save 'dependencies-before.json' @((Get-KviSafeContainer $kafka),(Get-KviSafeContainer $mysql),(Get-KviSafeContainer $front))
  $statsRaw=D @('stats','--no-stream','--format','{{json .}}')
  $stats=@($statsRaw -split '\r?\n'|Where-Object {$_}|ForEach-Object {$_|ConvertFrom-Json})
  foreach($c in @($kafka,$mysql)){
   $sample=@($stats|Where-Object {$_.Name -ceq $c.Name.TrimStart('/')})
   Need ($sample.Count -eq 1) 'Dependency memory observation missing'
   $percent=[double]::Parse($sample[0].MemPerc.TrimEnd('%'),[Globalization.CultureInfo]::InvariantCulture)
   Need ($percent -lt 90) "Shared dependency exceeds safe preflight working-set gate: $($c.Name)"
  }
  $memRaw=D @('exec',$kafka.Id,'cat','/proc/meminfo')
  Need ($memRaw -match '(?m)^MemAvailable:\s+(\d+)\s+kB') 'Docker VM MemAvailable unavailable'
  $vmAvailable=[long]$Matches[1]*1024
  $os=Get-CimInstance Win32_OperatingSystem
  $win=Get-CimInstance Win32_PerfFormattedData_PerfOS_Memory
  $resource=@{time=[DateTimeOffset]::Now.ToString('o');windowsAvailable=[long]$os.FreePhysicalMemory*1024;
   commitHeadroom=[long]$win.CommitLimit-[long]$win.CommittedBytes;dockerVmAvailable=$vmAvailable;
   testClientLimit=384MB;testClientHeap=192MB;buildHeap=512MB}
  Save 'resource-preflight.json' $resource
  Need ($resource.windowsAvailable -ge 1536MB -and $resource.commitHeadroom -ge 1536MB -and $vmAvailable -ge 768MB) 'Insufficient headroom for one bounded test client/build; no resources changed'
  # Read the actual Broker installation, avoiding an extra Kafka CLI JVM in its tight cgroup.
  $installed=D @('exec',$kafka.Id,'sh','-c','for f in /opt/kafka/libs/kafka_*.jar; do echo "$f"; done')
  Need ($installed.Trim() -match '^/opt/kafka/libs/kafka_[0-9.]+-([0-9]+\.[0-9]+\.[0-9]+)\.jar$') 'Broker distribution version is not uniquely identifiable'
  $state.brokerDistributionVersion=$Matches[1]
  $state.brokerDistributionVersionLog=('docker-{0:d3}.stdout.txt' -f $script:ordinal)
  $fullCredential=[IO.Path]::GetFullPath($CredentialFile)
  $runtimeRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'runtime'))+[IO.Path]::DirectorySeparatorChar
  Need ($fullCredential.StartsWith($runtimeRoot,[StringComparison]::OrdinalIgnoreCase) -and [IO.Path]::GetFileName($fullCredential) -ceq 'verify.env') 'Only preserved correctness runtime credentials are accepted'
  $cv=@{};foreach($line in Get-Content -LiteralPath $fullCredential){if($line -match '^([A-Z_]+)=(.*)$'){$cv[$Matches[1]]=$Matches[2]}}
  Need ($cv.CV_DATABASE -match '^cv_app_([0-9]{17})$') 'Unexpected restricted credential identity'
  $identity=$Matches[1]
  Need ($cv.CV_DATABASE_USER -ceq "cv_$identity" -and $cv.CV_DATABASE_PASSWORD -and $cv.CV_DATABASE_PASSWORD -notmatch '[\r\n\x00]') 'Credential mismatch'
  $envFile=Join-Path $private 'client.env'
  @("KVI_RUN=$token","KVI_IDENTITY=$identity","KVI_USER=$($cv.CV_DATABASE_USER)","KVI_PASSWORD=$($cv.CV_DATABASE_PASSWORD)",'KVI_EVIDENCE=/out')|Set-Content -LiteralPath $envFile -Encoding utf8NoBOM
 }

 Write-Host 'Kafka acceptance: compiling current source and running targeted offline regressions'
 $tests='BookVisitEventPublisherTest,BookVisitEventConsumerTest,BookVisitBatchAggregatorTest,BookVisitBatchWriterTest,BookVisitKafkaConfigTest,BookVisitKafkaPropertiesTest,BookControllerVisitCountTest,BookVisitBatchRecoveryTest,BookVisitBatchWriterTransactionTest,BookVisitKafkaAcceptanceGuardTest'
 $null=New-Item -ItemType Directory (Join-Path $run 'java-tmp')
 $started=[DateTime]::UtcNow
 $mvnArgs=@('-Xmx512m',"-Dmaven.home=$maven","-Dmaven.multiModuleProjectDirectory=$source","-Dclassworlds.conf=$maven/bin/m2.conf",'-cp',"$maven/boot/plexus-classworlds-2.8.0.jar",'org.codehaus.plexus.classworlds.launcher.Launcher','-f',"$source/pom.xml",'-o','-q','-pl','novel-front','-am','-Dmaven.test.skip=false','-DskipTests=false',"-DargLine=-Xmx256m -javaagent:$agent -Djava.io.tmpdir=$run/java-tmp","-Dtest=$tests",'-Dsurefire.failIfNoSpecifiedTests=false','test')
 $null=Invoke-KviProcess $java $mvnArgs (Join-Path $run 'maven') 420
 $reports=Join-Path $run 'test-reports';$null=New-Item -ItemType Directory $reports
 $total=0
 foreach($test in $tests.Split(',')){
  $xmls=@(Get-ChildItem -LiteralPath (Join-Path $source 'novel-front/target/surefire-reports') -Filter "TEST-*.$test.xml"|Where-Object LastWriteTimeUtc -ge $started)
  Need ($xmls.Count -eq 1) "Fresh regression report missing: $test"
  [xml]$xml=Get-Content -LiteralPath $xmls[0].FullName -Raw
  Need ([int]$xml.testsuite.failures -eq 0 -and [int]$xml.testsuite.errors -eq 0 -and [int]$xml.testsuite.skipped -eq 0) "Regression incomplete: $test"
  $total+=[int]$xml.testsuite.tests;Copy-Item -LiteralPath $xmls[0].FullName -Destination $reports
 }
 $state.offlineTests=$total
 # Use THIS RUN's Surefire classpath, not installed reactor artifacts or the running front JAR.
 $cp=($xml.testsuite.properties.property|Where-Object name -ceq 'java.class.path').value
 Need ([bool]$cp) 'Fresh test classpath unavailable'
 $bundle=Join-Path $private 'bundle';$null=New-Item -ItemType Directory "$bundle/lib"
 $classpath=@();$n=0
 foreach($path in $cp.Split([IO.Path]::PathSeparator,[StringSplitOptions]::RemoveEmptyEntries)){
  Need (Test-Path -LiteralPath $path) "Classpath input missing: $path"
  if(Test-Path -LiteralPath $path -PathType Container){
   $dest="c$n";$null=New-Item -ItemType Directory "$bundle/$dest"
   Get-ChildItem -LiteralPath $path -Force|Copy-Item -Destination "$bundle/$dest" -Recurse
   $classpath+="/work/$dest"
  }else{
   $name="$n-"+[IO.Path]::GetFileName($path)
   Copy-Item -LiteralPath $path -Destination (Join-Path "$bundle/lib" $name)
   $classpath+="/work/lib/$name"
  }
  $n++
 }
 # An argument file preserves Maven classpath order and avoids Windows' command-line length limit.
 @('-Xms32m','-Xmx192m','-XX:MaxMetaspaceSize=96m','-cp',($classpath -join ':'),'com.java2nb.novel.config.BookVisitKafkaAcceptance')|Set-Content -LiteralPath "$bundle/client.args" -Encoding utf8NoBOM
 $windowsClasspath=@($classpath|ForEach-Object {$_.Replace('/work/',$bundle.Replace('\','/')+'/')}) -join ';'
 @('-Xmx192m','-cp',('"'+$windowsClasspath+'"'),'com.java2nb.novel.config.BookVisitKafkaAcceptance','probe',('"'+$run.Replace('\','/')+'/offline-classpath-probe.json"'))|Set-Content -LiteralPath "$private/probe.args" -Encoding utf8NoBOM
 $null=Invoke-KviProcess $java @("@$private/probe.args") (Join-Path $run 'classpath-probe') 30
 Save 'bundle-sha256.json' @(Get-ChildItem -LiteralPath $bundle -Recurse -File|ForEach-Object{@{path=[IO.Path]::GetRelativePath($bundle,$_.FullName);sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}})
 $state.bundleManifestSha256=(Get-FileHash (Join-Path $run 'bundle-sha256.json') -Algorithm SHA256).Hash
 Need (($sourceDigest) -ceq ((Get-KviInputs $source)|ConvertTo-Json -Depth 5 -Compress)) 'Source changed during compilation'
 if($Offline){$state.status='READY_TO_RUN';$state.cleanup='NOT_NEEDED';$state.A='NOT_RUN';$state.B='NOT_RUN';$state.C='NOT_RUN'}
 else {
  $client="cv-kafka-final-$($token.Replace('_','-'))";$clientAttempted=$true
  $runArgs=@('run','--name',$client,'--pull=never','--label',"novel.acceptance.run=$token",'--label','novel.acceptance.kind=kafka-final',
   '--network',$network,'--memory','384m','--memory-swap','384m','--cpus','1','--read-only','--no-healthcheck','--tmpfs','/tmp:rw,size=32m',
   '--mount',"type=bind,source=$bundle,target=/work,readonly",'--mount',"type=bind,source=$run,target=/out",'--env-file',$envFile,
   '--env','JAVA_TOOL_OPTIONS=','--env','JDK_JAVA_OPTIONS=','--env','_JAVA_OPTIONS=',
   '--entrypoint','java',$image,'@/work/client.args')
  $canCleanup=$true
  try{$null=D ($runArgs+@('probe','/out/client-classpath-probe.json')) 30}finally{StopOwnClient}
  $hostProbe=Get-Content (Join-Path $run 'offline-classpath-probe.json') -Raw|ConvertFrom-Json
  $clientProbe=Get-Content (Join-Path $run 'client-classpath-probe.json') -Raw|ConvertFrom-Json
  Need (($hostProbe.loadedInputs|ConvertTo-Json -Compress) -ceq ($clientProbe.loadedInputs|ConvertTo-Json -Compress)) 'Container loaded different compiled classes/Mapper XML'
  Write-Host 'Kafka acceptance: A/B/C against independent topics/groups/schema (bounded 240 seconds)'
  try{$null=D ($runArgs+@('run')) 240}
  finally {
   StopOwnClient
   # Independent bounded cleanup also covers a killed/failed test JVM. It never repeats A/B/C.
   $client="$client-cleanup";$clientAttempted=$true
   $cleanupArgs=@($runArgs);$cleanupArgs[2]=$client
   try{$null=D ($cleanupArgs+@('cleanup')) 150}finally{StopOwnClient}
  }
  $acceptance=Get-Content -LiteralPath (Join-Path $run 'acceptance.json') -Raw|ConvertFrom-Json
  foreach($s in @('A','B','C')){$state[$s]=$acceptance.$s.status}
  $cleanup=Get-Content -LiteralPath (Join-Path $run 'cleanup-retry.json') -Raw|ConvertFrom-Json
  $state.cleanup=$cleanup.cleanup
  Need ($acceptance.status -ceq 'ABC_PASSED' -and $state.A -ceq 'PASS' -and $state.B -ceq 'PASS' -and $state.C -ceq 'PASS' -and $state.cleanup -ceq 'VERIFIED') 'A/B/C or cleanup not fully accepted'
  $afterKafka=Inspect $kafka.Name.TrimStart('/');$afterMysql=Inspect $mysql.Name.TrimStart('/')
  foreach($pair in @(@($kafka,$afterKafka),@($mysql,$afterMysql))){
   Need ($pair[0].Id -ceq $pair[1].Id -and $pair[0].State.StartedAt -ceq $pair[1].State.StartedAt -and $pair[0].RestartCount -eq $pair[1].RestartCount -and -not $pair[1].State.OOMKilled) 'Shared dependency changed state; acceptance invalid'
  }
  Save 'dependencies-after.json' @((Get-KviSafeContainer $afterKafka),(Get-KviSafeContainer $afterMysql))
  Need ($sourceDigest -ceq ((Get-KviInputs $source)|ConvertTo-Json -Depth 5 -Compress)) 'Source drift during real acceptance'
  $state.status='KAFKA_READY_TO_FREEZE';$state.KAFKA_READY_TO_FREEZE=$true
 }
}catch{
 $state.status='FAILED';$state.error=$_.Exception.Message
 $_|Out-String|Set-Content -LiteralPath (Join-Path $run 'exception.txt') -Encoding utf8NoBOM
 if(Test-Path (Join-Path $run 'acceptance.json')){
  $partial=Get-Content (Join-Path $run 'acceptance.json') -Raw|ConvertFrom-Json
  foreach($s in @('A','B','C')){$state[$s]=$partial.$s.status}
 }
 # If an outer failure occurred before the inner finally, remove only our labelled client.
 if($clientAttempted){try{StopOwnClient}catch{$state.clientCleanupError=$_.Exception.Message}}
 if($canCleanup -and -not (Test-Path (Join-Path $run 'cleanup-retry.json'))){$state.cleanup='UNVERIFIED'}
}finally{
 $state.cleanup=Get-KviCleanupState $run $canCleanup
 if($envFile -and (Test-Path -LiteralPath $envFile)){Remove-Item -LiteralPath $envFile}
 $env:JAVA_HOME=$javaBefore;$state.finished=[DateTimeOffset]::Now.ToString('o')
 Save 'summary.json' $state
 Write-Host "Kafka final evidence: $run; status=$($state.status); A=$($state.A); B=$($state.B); C=$($state.C); cleanup=$($state.cleanup)"
}
if($state.status -eq 'FAILED'){throw "Kafka acceptance incomplete; preserve evidence: $run"}

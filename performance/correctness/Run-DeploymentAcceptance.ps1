#requires -Version 7.0
[CmdletBinding()]
param([switch]$SkipReadingRegression,[ValidatePattern('^deploy-[0-9]{17}$')][string]$ReuseBuildFrom,
      [ValidatePattern('^deploy-[0-9]{17}$')][string]$ResumeFrom,
      [ValidatePattern('^dependency-state-[0-9]{8}-[0-9]{6}-[0-9]{3}$')][string]$RecoverFromDependencyState)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$root=$PSScriptRoot
$source=[IO.Path]::GetFullPath((Join-Path $root '../..'))
$frozen=[IO.Path]::GetFullPath((Join-Path $source '../experiment-src/performance/redis-ab'))
. (Join-Path $frozen 'Common.ps1')
. (Join-Path $root 'Read-Metrics.ps1')
. (Join-Path $root 'Native-Utf8.ps1')
$project='novel-correctness-20261017'
$id=Get-Date -Format 'yyyyMMddHHmmssfff'
$runId="deploy-$id"
$run=Join-Path $root "artifacts/$runId"
$runtime=Join-Path $root "runtime/$runId"
$database="cv_app_$id"
$prefix="cv_it_$id"
$dbUser="cv_$id"
$dbPassword=[guid]::NewGuid().ToString('N')
$redisPassword=[guid]::NewGuid().ToString('N')
$jwt=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(64))
$candidate="novel-correctness-front:$id"
$adminImage="novel-correctness-admin:$id"
$topic="cv-$id"
$maven=Join-Path $env:USERPROFILE '.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn.cmd'
$mockito=Join-Path $env:USERPROFILE '.m2/repository/org/mockito/mockito-core/5.14.2/mockito-core-5.14.2.jar'
$base='http://127.0.0.1:28183'
$management='http://127.0.0.1:28184'
$result=[ordered]@{run=$runId;status='PREPARING';schema=$database;itPrefix=$prefix;project=$project;started=[DateTimeOffset]::Now.ToString('o');checks=[ordered]@{};boundaries=@('Captcha value is read from dedicated server session by test harness; no CAPTCHA recognition test.','Signed fixture tokens test JWT/version validation, not password login.','InnoDB global counters include any simultaneous activity on the isolated MySQL server.','Reading regression is a small bounded version comparison, not the frozen Redis A/B experiment.')}
$previous=@{}
$monitor=$null
$databaseReady=$false
$previousConsoleEncoding=[Console]::OutputEncoding
$script:cvSqlOrdinal=0
$dockerExe='docker.exe'
function Save([string]$Name,$Value){$Value|ConvertTo-Json -Depth 30|Set-Content -LiteralPath (Join-Path $run $Name) -Encoding utf8NoBOM}
function Require([bool]$Ok,[string]$Message){if(-not $Ok){throw $Message}}
function Read-CvResume([string]$Label){
    Require ($Label -match '^deploy-[0-9]{17}$') 'Unsafe resume label'
    $prior=Join-Path $root "artifacts/$Label"
    $state=Get-Content (Join-Path $prior 'acceptance.json') -Raw|ConvertFrom-Json -AsHashtable
    $plan=Get-Content (Join-Path $prior 'plan.json') -Raw|ConvertFrom-Json -AsHashtable
    Require ($state.status -eq 'FAILED') 'Resume requires a failed checkpoint'
    $phase=if($state.failure -like 'Application not ready:*'){'reading'}elseif($state.failure -eq 'Maven failed; inspect deployed-admin.log (evidence retained)'){'admin'}else{throw 'Unsupported resume checkpoint'}
    Require ($state.checks.ContainsKey('innodb') -and $state.checks.ContainsKey('indexes') -and $state.checks.innodb -like 'PASS*' -and $state.checks.indexes -like 'PASS*') 'Resume requires completed MySQL/index checks'
    if($phase -eq 'reading'){Require ($state.checks.Count -eq 2) 'Startup resume cannot repeat later mutations'}
    Require ($state.project -eq $project -and $state.run -eq $Label) 'Resume project/run mismatch'
    $identity=if($plan.ContainsKey('identityRun')){$plan.identityRun}else{$Label}
    Require ($identity -match '^deploy-[0-9]{17}$') 'Unsafe identity run'
    $suffix=$identity.Substring(7)
    Require ($plan.schema -ceq "cv_app_$suffix" -and $plan.itPrefix -ceq "cv_it_$suffix" -and $plan.topic -ceq "cv-$suffix" -and $state.schema -ceq $plan.schema) 'Resume schema/topic identity mismatch'
    if($phase -eq 'admin'){
        Require ($identity -cne $Label) 'Admin identity checkpoint must distinguish database creation from resumed execution'
        Require ($state.checks.Count -eq 6 -and $state.checks.ContainsKey('front') -and $state.checks.front -like 'PASS*' -and $state.checks.ContainsKey('kafka') -and $state.checks.kafka -like 'PASS*' -and $state.checks.ContainsKey('reading') -and $state.checks.ContainsKey('auditVisibility')) 'Admin resume requires finished front/Kafka/reading checks'
        $reading=Get-Content (Join-Path $prior 'reading-summary.json') -Raw|ConvertFrom-Json -AsHashtable
        Require ($reading.status -eq 'COMPLETE' -and @($reading.rounds).Count -eq 6 -and @($reading.rounds|Where-Object{[int]$_.requests -ne 60 -or [int]$_.failures -ne 0}).Count -eq 0) 'Completed reading evidence missing or unsuccessful'
        # This checkpoint is safe only because the test failed on its first,
        # read-only identity assertion, before creating any users or comments.
        [xml]$adminReport=Get-Content (Join-Path $prior 'deployed-admin-novel-admin/TEST-com.java2nb.novel.service.DeployedAdminIT.xml') -Raw
        $failure=$adminReport.SelectSingleNode('/testsuite/testcase/failure')
        Require ([int]$adminReport.testsuite.tests -eq 1 -and [int]$adminReport.testsuite.failures -eq 1 -and [int]$adminReport.testsuite.errors -eq 0 -and $null -ne $failure -and $failure.InnerText.Contains("expected: `"$Label`"") -and $failure.InnerText.Contains("but was: `"$identity`"")) 'Admin checkpoint is not the pre-write identity failure; refuse replay'
    }
    $priorRuntime=Join-Path $root "runtime/$Label"
    Require ([IO.Path]::GetFullPath($plan.runtime) -ieq [IO.Path]::GetFullPath($priorRuntime)) 'Resume runtime outside saved run'
    $envValues=[ordered]@{}
    foreach($line in Get-Content (Join-Path $priorRuntime 'verify.env')){if($line -match '^([A-Z0-9_]+)=(.*)$'){$envValues[$Matches[1]]=$Matches[2]}}
    foreach($key in @('CV_FRONT_IMAGE','CV_ADMIN_IMAGE','CV_DATABASE','CV_DATABASE_USER','CV_DATABASE_PASSWORD','CV_REDIS_PASSWORD','CV_JWT_SECRET','CV_SECRET','CV_ADMIN_TEST_PASSWORD','CV_TOPIC','CV_RUNTIME_DIR')){
        Require ($envValues.Contains($key) -and -not [string]::IsNullOrEmpty($envValues[$key])) "Resume config incomplete: $key"
    }
    Require ($envValues.CV_DATABASE -ceq $plan.schema -and $envValues.CV_DATABASE_USER -ceq "cv_$suffix" -and $envValues.CV_TOPIC -ceq $plan.topic) 'Resume credentials target mismatch'
    Require ($envValues.CV_ADMIN_IMAGE -match '^novel-correctness-admin:[0-9]{17}$') 'Unsafe saved admin image'
    Require (Test-Path (Join-Path $priorRuntime 'admin-sharding.yml') -PathType Leaf) 'Saved admin sharding config missing'
    foreach($entry in @(@('novel-front','com.java2nb.novel.service.impl.CommentCorrectnessTest'),@('novel-admin','com.java2nb.novel.service.CommentDeletionTest'))){
        # Keep links to the original raw reports instead of re-running completed DB cases.
        $evidenceRoot=if($state.ContainsKey('databaseEvidence')){$state.databaseEvidence}else{$prior}
        Require ([IO.Path]::GetFullPath($evidenceRoot).StartsWith([IO.Path]::GetFullPath((Join-Path $root 'artifacts'))+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) 'Unsafe database evidence path'
        [xml]$report=Get-Content (Join-Path $evidenceRoot "real-mysql-$($entry[0])/TEST-$($entry[1]).xml") -Raw
        Require ([int]$report.testsuite.tests -gt 0 -and [int]$report.testsuite.errors -eq 0 -and [int]$report.testsuite.failures -eq 0 -and [int]$report.testsuite.skipped -eq 0) 'Saved real MySQL tests did not pass'
    }
    return @{prior=$prior;runtime=$priorRuntime;state=$state;plan=$plan;values=$envValues;identity=$identity;evidence=$evidenceRoot;phase=$phase}
}
function Read-CvRestartProof([string]$Label,$Context){
    Require ($Label -match '^dependency-state-[0-9]{8}-[0-9]{6}-[0-9]{3}$') 'Unsafe dependency-state label'
    Require ($Context.phase -eq 'admin') 'Dependency restart recovery is limited to final functional verification; never reuse it for reading measurements'
    $proof=Get-Content (Join-Path $root "artifacts/$Label/dependency-state.json") -Raw|ConvertFrom-Json -AsHashtable
    Require ($proof.mode -ceq 'READ_ONLY' -and $proof.checkpoint -ceq $Context.state.run -and $proof.context -ceq 'desktop-linux' -and @($proof.errors).Count -eq 0) 'Dependency evidence is incomplete or belongs to another checkpoint'
    $names=@('mysql','redis','kafka','mailpit','front'|ForEach-Object{"$AbProject-$_-1"})+@("$project-front-1","$project-admin-1","$project-validation-redis-1")
    Require ((@($proof.containers.name|Sort-Object) -join ',') -ceq (@($names|Sort-Object) -join ',')) 'Dependency evidence must cover exactly the expected eight containers'
    foreach($row in $proof.containers){
        $expectedProject=if($row.name -like "$AbProject-*"){$AbProject}else{$project}
        Require ($row.labels['com.docker.compose.project'] -ceq $expectedProject -and @($row.networks).Count -eq 1 -and $row.networks[0] -ceq 'novel-cache-ab-20261016_backend') 'Dependency evidence project/network mismatch'
    }
    return $proof
}
function Assert-CvDependencyCheckpoint([hashtable]$Current,[hashtable]$Saved,$Context,$Proof){
    Require ($Current.Count -eq 5 -and $Saved.Count -eq 5) 'Dependency checkpoint incomplete'
    foreach($service in @('mysql','redis','kafka','mailpit','front')){
        Require ($Current.ContainsKey($service) -and $Saved.ContainsKey($service)) "Dependency checkpoint missing: $service"
        Require ($Current[$service].id -ceq $Saved[$service].id -and $Current[$service].image -ceq $Saved[$service].image) "Original dependency identity changed: $service"
        if([DateTimeOffset]$Current[$service].started -ne [DateTimeOffset]$Saved[$service].started){
            Require ($null -ne $Proof -and $Context.phase -eq 'admin') "Original dependency restarted: $service; explicit RecoverFromDependencyState evidence required for final verification"
            $record=@($Proof.containers|Where-Object name -CEQ "$AbProject-$service-1")
            Require ($record.Count -eq 1 -and $record[0].id -ceq $Current[$service].id -and $record[0].image -ceq $Current[$service].image -and [DateTimeOffset]$record[0].state.startedAt -eq [DateTimeOffset]$Current[$service].started) "Dependency changed after diagnostic: $service"
            [pscustomobject]@{service=$service;id=$Current[$service].id;image=$Current[$service].image;oldStartedAt=$Saved[$service].started;newStartedAt=$Current[$service].started;affectsHistoricalMeasurements=$false}
        }
    }
}
function Start-CvStoppedApplications($Context,$Proof){
    Require ($Context.phase -eq 'admin' -and $Proof.checkpoint -ceq $Context.state.run) 'Application recovery requires final-verification checkpoint evidence'
    # Validate every dependency and both targets before the first start command.
    foreach($service in @('mysql','redis','kafka','mailpit')){
        $c=Get-AbContainer $service
        $record=@($Proof.containers|Where-Object name -CEQ "$AbProject-$service-1")
        Require ($record.Count -eq 1 -and $c.Id -ceq $record[0].id -and $c.Image -ceq $record[0].image -and $c.HostConfig.Memory -eq $record[0].memoryLimit) "Dependency differs from recovery evidence: $service"
        Require ($c.State.Running -and -not $c.State.OOMKilled -and $c.State.Health.Status -eq 'healthy') "Dependency not healthy: $service; recovery will not start/stop dependencies"
    }
    $privateRedis=NewContainer 'validation-redis'
    $redisRecord=@($Proof.containers|Where-Object name -CEQ "$project-validation-redis-1")
    Require ($redisRecord.Count -eq 1 -and $privateRedis.Id -ceq $redisRecord[0].id -and $privateRedis.Image -ceq $redisRecord[0].image -and $privateRedis.HostConfig.Memory -eq $redisRecord[0].memoryLimit -and $privateRedis.State.Running -and $privateRedis.State.Health.Status -eq 'healthy') 'Private Redis differs from recovery evidence or is not healthy'
    $targets=@();$before=@()
    foreach($service in @('front','admin')){
        $c=NewContainer $service
        $record=@($Proof.containers|Where-Object name -CEQ "$project-$service-1")
        Require ($record.Count -eq 1 -and $c.Id -ceq $record[0].id -and $c.Image -ceq $record[0].image -and $c.HostConfig.Memory -eq $record[0].memoryLimit) "Application differs from recovery evidence: $service"
        Require (-not $c.State.OOMKilled) "Application OOM requires investigation: $service"
        $before+=@{service=$service;id=$c.Id;image=$c.Image;status=$c.State.Status;startedAt=$c.State.StartedAt}
        if(-not $c.State.Running){
            Require ($c.State.Status -eq 'exited' -and $c.State.ExitCode -eq 1) "Unexpected stopped application state: $service"
            $targets+=@{service=$service;id=$c.Id}
        }
    }
    Save 'application-recovery.json' @{before=$before;startTargets=$targets;containersRecreated=$false;dependenciesModified=$false;readingRepeated=$false}
    foreach($target in $targets){Invoke-AbDocker @('start',$target.id)|Out-Null}
    WaitHttp 'http://127.0.0.1:28184/actuator/health' '"status"\s*:\s*"UP"'
    WaitHttp 'http://127.0.0.1:28180/login'
}
function Assert-CvResumeRuntime($Context,[switch]$AllowStoppedApplications){
    $names=@(Invoke-AbDocker @('ps','-a','--filter',"label=com.docker.compose.project=$project",'--format','{{.Names}}'))
    $services=if($Context.phase -eq 'admin'){@('front','admin','validation-redis')}else{@('front','validation-redis')}
    Require ($names.Count -eq $services.Count) 'Unexpected validation containers at resume checkpoint'
    foreach($service in $services){
        Require ("$project-$service-1" -in $names) 'Missing expected validation container'
        $c=NewContainer $service
        Require ($c.Config.Labels.'com.docker.compose.service' -ceq $service) 'Resume service label mismatch'
        Require (@($c.NetworkSettings.Networks.PSObject.Properties.Name).Count -eq 1 -and @($c.NetworkSettings.Networks.PSObject.Properties.Name)[0] -ceq 'novel-cache-ab-20261016_backend') 'Resume network mismatch'
        foreach($port in $c.HostConfig.PortBindings.PSObject.Properties){foreach($binding in @($port.Value)){Require ($binding.HostIp -ceq '127.0.0.1') 'Resume port is not loopback'}}
        if($service -eq 'front'){
            foreach($expected in @("MYSQL_DATABASE=$($Context.values.CV_DATABASE)","MYSQL_USER=$($Context.values.CV_DATABASE_USER)","MYSQL_PASSWORD=$($Context.values.CV_DATABASE_PASSWORD)",'SPRING_DATA_REDIS_HOST=validation-redis')){Require ($c.Config.Env -ccontains $expected) 'Failed front does not belong to saved runtime'}
        }elseif($service -eq 'validation-redis'){Require ($c.State.Running -and $c.Config.Env -ccontains "REDIS_PASSWORD=$($Context.values.CV_REDIS_PASSWORD)") 'Private Redis state/identity mismatch'}
        else{Require ($c.Config.Env -ccontains 'SPRING_REDIS_HOST=validation-redis' -and $c.Config.Env -ccontains "SPRING_REDIS_PASSWORD=$($Context.values.CV_REDIS_PASSWORD)") 'Admin Redis target mismatch'}
        if($Context.phase -eq 'admin'){
            $canStart=$AllowStoppedApplications -and $service -in @('front','admin') -and $c.State.Status -eq 'exited' -and $c.State.ExitCode -eq 1
            Require (($c.State.Running -or $canStart) -and -not $c.State.OOMKilled) "Existing validation service is not running: $service"
            if($service -ne 'validation-redis'){Require ($c.Image -ceq (Get-Content (Join-Path $Context.prior "$service.image") -Raw).Trim()) "Existing validation image changed: $service"}
        }
    }
}
function Invoke-CvSqlTransport([string]$Text,[string]$InputFile,[string]$Schema='novel_ab'){
    $null=Get-AbContainer 'mysql'
    Require ($Schema -eq 'novel_ab' -or $Schema -match '^cv_(app|it)_[a-z0-9_]+$') 'Unsafe SQL transport schema'
    $script:cvSqlOrdinal++
    $errorPath=Join-Path $run ('sql-{0:D5}.stderr.txt' -f $script:cvSqlOrdinal)
    $argsList=@('--context','desktop-linux','exec','-i',"$AbProject-mysql-1",'sh','-ec','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --default-character-set=utf8mb4 -uroot -N -B "$1"','sh',$Schema)
    $parameters=@{Executable=$dockerExe;Arguments=$argsList;ErrorFile=$errorPath}
    if($InputFile){$parameters.InputFile=$InputFile}else{$parameters.InputText=$Text}
    $response=Invoke-CvNativeUtf8 @parameters
    Require ($response.ExitCode -eq 0) "MySQL SQL step $($script:cvSqlOrdinal) failed (exit $($response.ExitCode)); raw stderr: $errorPath"
    if($response.Stdout){return $response.Stdout.TrimEnd([char[]]"`r`n") -split '\r?\n'}
}
# Local replacement only: the frozen experiment Common.ps1 is never changed.
function Invoke-AbSql([string]$Sql){Invoke-CvSqlTransport -Text $Sql}
function Export-CvSnapshot([string]$Path){
    $null=Get-AbContainer 'mysql'
    $argsList=@('--context','desktop-linux','exec',"$AbProject-mysql-1",'sh','-ec','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --skip-lock-tables --skip-add-locks --skip-add-drop-table --no-tablespaces --set-gtid-purged=OFF --hex-blob --default-character-set=utf8mb4 novel_ab')
    $native=Invoke-CvNativeUtf8 -Executable $dockerExe -Arguments $argsList -OutputFile $Path -ErrorFile (Join-Path $run 'snapshot-export.stderr.txt')
    Require ($native.ExitCode -eq 0) 'Snapshot export failed; inspect snapshot-export.stderr.txt'
    # Validate strict UTF-8 without rewriting it. Native stdout goes directly to file.
    $text=[IO.File]::ReadAllText($Path,[Text.UTF8Encoding]::new($false,$true))
    Require ($text -notmatch '(?im)^\s*(USE|CREATE DATABASE|DROP DATABASE)\b') 'Dump unexpectedly changes database context'
    Get-FileHash -LiteralPath $Path -Algorithm SHA256|Export-Csv (Join-Path $run 'snapshot-sha256.csv') -NoTypeInformation
}
function Verify-CvSnapshot {
    $manifest=Get-Content (Join-Path $frozen 'runtime/manifest.json') -Raw|ConvertFrom-Json
    $selects=@($manifest.chapters|ForEach-Object{
        Require ([string]$_.bookId -match '^\d+$' -and [string]$_.chapterId -match '^\d+$') 'Unsafe fixture IDs'
        $shard=[long]$_.chapterId%10
        "SELECT JSON_OBJECT('chapterId',CAST(b.id AS CHAR),'bookId',CAST(b.book_id AS CHAR),'sha256',LOWER(SHA2(c.content,256))) FROM book_content$shard c JOIN book_index b ON b.id=c.index_id WHERE b.id=$($_.chapterId) AND b.book_id=$($_.bookId) AND b.is_vip=0 AND b.storage_type='db'"
    })
    $proof=@(Sql (($selects -join ' UNION ALL ')+ ';'))
    Require ($proof.Count -eq $manifest.chapters.Count) 'Snapshot chapter count mismatch'
    foreach($line in $proof){
        $row=$line|ConvertFrom-Json
        $expected=@($manifest.chapters|Where-Object chapterId -eq $row.chapterId)
        Require ($expected.Count -eq 1 -and $row.bookId -eq $expected[0].bookId -and $row.sha256 -ceq $expected[0].sha256) 'Snapshot chapter ownership/content SHA256 mismatch'
    }
    $proof|Set-Content (Join-Path $run 'snapshot-chapter-proof.jsonl') -Encoding utf8NoBOM
}
function Reuse-CvBuild([string]$Label){
    $prior=Join-Path $root "artifacts/$Label"
    $priorPlan=Get-Content (Join-Path $prior 'plan.json') -Raw|ConvertFrom-Json
    $priorEnv=@{}
    foreach($line in Get-Content (Join-Path $root "runtime/$Label/verify.env")){
        if($line -match '^([A-Z0-9_]+)=(.*)$'){$priorEnv[$Matches[1]]=$Matches[2]}
    }
    $hashes=Import-Csv (Join-Path $prior 'jar-sha256.csv')
    Require (@($hashes).Count -eq 2) 'Prior JAR provenance incomplete'
    foreach($module in @('novel-front','novel-admin')){
        $jar=Join-Path $source "$module/target/$module-5.3.3.jar"
        $record=@($hashes|Where-Object{[IO.Path]::GetFullPath($_.Path) -ieq [IO.Path]::GetFullPath($jar)})
        Require ($record.Count -eq 1 -and (Get-FileHash $jar).Hash -ceq $record[0].Hash) 'JAR changed since prior build; refuse image reuse'
        # Refuse source edits newer than the packaged JAR. Test/tool changes are not image inputs.
        foreach($inputRoot in @('novel-cache-contract/src/main','novel-common/src/main',"$module/src/main",'templates','deploy/correctness','deploy/novel-front/entrypoint.sh','deploy/shardingsphere/shardingsphere-jdbc.yml.template')){
            $newer=@(Get-ChildItem (Join-Path $source $inputRoot) -Recurse -File|Where-Object LastWriteTimeUtc -gt (Get-Item $jar).LastWriteTimeUtc)
            Require ($newer.Count -eq 0) "Image input changed: $inputRoot; build again without ReuseBuildFrom"
        }
    }
    $tags=@{front=[string]$priorPlan.newImage;admin=[string]$priorEnv.CV_ADMIN_IMAGE}
    foreach($service in @('front','admin')){
        Require ($tags[$service] -match "^novel-correctness-${service}:[0-9]{17}$") 'Unexpected reusable image tag'
        $builtId=(Get-Content (Join-Path $prior "$service.image") -Raw).Trim()
        Require ($builtId -match '^sha256:[a-f0-9]{64}$') 'Invalid prior build image ID'
        $tagId=(Invoke-AbDocker @('image','inspect',$tags[$service],'--format','{{.Id}}'))
        $idFromBuild=(Invoke-AbDocker @('image','inspect',$builtId,'--format','{{.Id}}'))
        Require ($tagId -eq $idFromBuild) 'Reusable image tag no longer matches saved build'
        Set-Content (Join-Path $run "$service.image") $tagId -Encoding utf8NoBOM
    }
    Copy-Item (Join-Path $prior 'jar-sha256.csv') (Join-Path $run 'jar-sha256.csv')
    Save 'reused-build.json' @{from=$Label;images=$tags;jarsVerified=$true}
    return $tags
}
function AssertVerifySpec([hashtable]$Spec){
    Require ((@($Spec.services.Keys|Sort-Object) -join ',') -eq 'admin,front,validation-redis' -and -not $Spec.ContainsKey('volumes')) 'Unexpected validation services or persistent volumes'
    Require ($Spec.networks.backend.external -and $Spec.networks.backend.name -eq 'novel-cache-ab-20261016_backend') 'Unexpected external dependency network'
    $expectedPorts=@{front=@(28183,28184);admin=@(28180);'validation-redis'=@(26381)}
    foreach($service in $Spec.services.Keys){
        $s=$Spec.services[$service]
        Require ($s.networks.Keys.Count -eq 1 -and $s.networks.ContainsKey('backend')) 'Unexpected network'
        Require (@($s.ports).Count -eq $expectedPorts[$service].Count) 'Unexpected port count'
        foreach($p in $s.ports){Require ($p.host_ip -eq '127.0.0.1' -and [int]$p.published -in $expectedPorts[$service]) 'Unsafe port binding'}
        if($s.ContainsKey('volumes')){foreach($v in @($s.volumes)){Require ($v.type -eq 'bind' -and $v.read_only -and [IO.Path]::GetFullPath($v.source).StartsWith($root+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) 'Unsafe validation mount'}}
    }
    Require ($Spec.services.front.environment.MYSQL_DATABASE -eq $database -and $Spec.services.front.environment.SPRING_DATA_REDIS_HOST -eq 'validation-redis') 'Incorrect front database/Redis target'
    $kafka=($Spec.services.front.environment.SPRING_APPLICATION_JSON|ConvertFrom-Json -AsHashtable).novel.kafka
    foreach($kind in @('book-visit','reading-engagement')){
        $suffix=if($kind -eq 'book-visit'){'visit'}else{'reading'}
        Require ($kafka[$kind].topic -eq "$topic-$suffix" -and $kafka[$kind]['dlt-topic'] -eq "$topic-$suffix-dlt" -and $kafka[$kind]['group-id'] -eq "$topic-$suffix-writer") 'Incorrect Kafka isolation'
    }
    Require ($Spec.services.admin.environment.SPRING_REDIS_HOST -eq 'validation-redis') 'Incorrect admin Redis target'
}
function Sql([string]$Text,[string]$Schema=$database){
    Require ($Schema -match '^cv_(app|it)_[a-z0-9_]+$') 'Refuse SQL outside validation schema'
    return Invoke-AbSql "USE $Schema; $Text"
}
function ComposeArgs {return @('compose','--project-name',$project,'--project-directory',$root,'--env-file',(Join-Path $runtime 'verify.env'),'-f',(Join-Path $root 'compose.verify.yml'))}
function NewContainer([string]$Service){
    Require ($Service -in @('front','admin','validation-redis')) 'Unknown validation service'
    $data=@((Invoke-AbDocker @('inspect',"$project-$Service-1")) -join "`n"|ConvertFrom-Json)[0]
    Require ($data.Config.Labels.'com.docker.compose.project' -eq $project -and $data.Config.Labels.'novel.validation' -eq 'correctness-v1') 'Validation container identity mismatch'
    return $data
}
function Collect-CvFailureLogs {
    $listing=Invoke-CvNativeUtf8 -Executable $dockerExe -Arguments @('--context','desktop-linux','ps','-a','--filter',"label=com.docker.compose.project=$project",'--format','{{.Names}}') -ErrorFile (Join-Path $run 'failure-container-list.stderr.txt')
    if($listing.ExitCode -ne 0){return}
    $names=@($listing.Stdout -split '\r?\n')
    foreach($service in @('front','admin','validation-redis')){
        if("$project-$service-1" -in $names){
            try{$null=NewContainer $service;Invoke-AbDocker @('logs',"$project-$service-1") *> (Join-Path $run "failed-$service.log")}catch{}
        }
    }
}
function WaitHttp([string]$Url,[string]$BodyPattern=''){
    $watch=[Diagnostics.Stopwatch]::StartNew()
    do{
        try{$r=Invoke-WebRequest $Url -SkipHttpErrorCheck -TimeoutSec 5; if($r.StatusCode -eq 200 -and (-not $BodyPattern -or (Convert-MetricResponseBody $r.Content) -match $BodyPattern)){return}}catch{}
        Start-Sleep -Seconds 2
    }while($watch.Elapsed.TotalSeconds -lt 240)
    throw "Application not ready: $Url"
}
function SwitchFront([string]$Image){
    $text=Get-Content (Join-Path $runtime 'verify.env') -Raw
    $text=[regex]::Replace($text,'(?m)^CV_FRONT_IMAGE=.*$',"CV_FRONT_IMAGE=$Image")
    [IO.File]::WriteAllText((Join-Path $runtime 'verify.env'),$text,[Text.UTF8Encoding]::new($false))
    Invoke-AbDocker ((ComposeArgs)+@('up','-d','--no-deps','--no-build','--force-recreate','front'))|Out-Null
    WaitHttp "$management/actuator/health" '"status"\s*:\s*"UP"'
    $container=NewContainer 'front'
    Require (-not $container.State.OOMKilled) 'Front OOM killed'
    Require ($container.Config.Env -contains "MYSQL_DATABASE=$database") 'Front schema mismatch'
    Require ($container.Config.Env -contains 'SPRING_DATA_REDIS_HOST=validation-redis') 'Front Redis isolation mismatch'
    $jsonEnv=@($container.Config.Env|Where-Object{$_ -like 'SPRING_APPLICATION_JSON=*'})
    Require ($jsonEnv.Count -eq 1) 'Runtime Kafka override missing'
    $kafka=($jsonEnv[0].Substring('SPRING_APPLICATION_JSON='.Length)|ConvertFrom-Json -AsHashtable).novel.kafka
    Require ($kafka['book-visit'].topic -eq "$topic-visit" -and $kafka['reading-engagement'].topic -eq "$topic-reading") 'Runtime Kafka target mismatch'
}
function Form([string]$Path,[hashtable]$Values,[string]$Token=''){
    $parameters=@{Uri="$base$Path";Method='Post';Body=$Values;ContentType='application/x-www-form-urlencoded';SkipHttpErrorCheck=$true;TimeoutSec=20}
    if($Token){$parameters.Headers=@{Authorization=$Token}}
    $r=Invoke-WebRequest @parameters
    $body=Convert-MetricResponseBody $r.Content
    return [pscustomobject]@{status=[int]$r.StatusCode;json=($body|ConvertFrom-Json);body=$body}
}
function Token([long]$User,[bool]$Forge=$false){
    function B64([byte[]]$Bytes){[Convert]::ToBase64String($Bytes).TrimEnd('=').Replace('+','-').Replace('/','_')}
    $now=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $header=B64 ([Text.Encoding]::UTF8.GetBytes('{"alg":"HS512","typ":"JWT"}'))
    $payload=B64 ([Text.Encoding]::UTF8.GetBytes((@{userId=$User;nickName='synthetic';tokenVersion=0;iat=$now;exp=($now+3600)}|ConvertTo-Json -Compress)))
    $signer=[Security.Cryptography.HMACSHA512]::new([Convert]::FromBase64String($jwt))
    try{$signature=B64 ($signer.ComputeHash([Text.Encoding]::UTF8.GetBytes("$header.$payload")))}finally{$signer.Dispose()}
    if($Forge){$payload=B64 ([Text.Encoding]::UTF8.GetBytes('{"userId":700000102,"tokenVersion":0,"exp":4102444800}'))}
    return "$header.$payload.$signature"
}
function Metric([string]$Name,[string]$Label,[switch]$AllowMissing){
    $r=Invoke-WebRequest "$management/actuator/metrics/$Name" -TimeoutSec 10 -SkipHttpErrorCheck
    $body=Convert-MetricResponseBody $r.Content
    if($r.StatusCode -eq 404 -and $AllowMissing){Save "metric-$Label-$Name-unavailable.json" @{status=404;reason='Event-driven meter not observed; no zero substituted';body=$body};return $null}
    Require ($r.StatusCode -eq 200) "Metric HTTP $($r.StatusCode): $Name"
    $data=$body|ConvertFrom-Json
    Require ($null -ne $data.PSObject.Properties['measurements'] -and @($data.measurements).Count -gt 0) "Metric missing: $Name"
    Set-Content -LiteralPath (Join-Path $run "metric-$Label-$Name.json") -Value $body -Encoding utf8NoBOM
    return $data
}
function Maven([string]$Modules,[string]$Tests,[string]$Label,[switch]$Package){
    $argsList=@('-f',(Join-Path $source 'pom.xml'),'-B','-q','-pl',$Modules,'-am','-Dmaven.antrun.skip=true')
    # Packaging plugins were not all cached by previous test-only runs. Host may download fixed dependencies.
    if($Package){$argsList+=@('-Dmaven.test.skip=false','-DskipTests','package')}else{$argsList+=@('-o','-Dmaven.test.skip=false',"-DargLine=-javaagent:$mockito","-Dtest=$Tests",'-Dsurefire.failIfNoSpecifiedTests=false','test')}
    & $maven @argsList *> (Join-Path $run "$Label.log")
    $exit=$LASTEXITCODE
    if(-not $Package){foreach($module in $Modules.Split(',')){
        $destination=Join-Path $run "$Label-$module";New-Item -ItemType Directory -Path $destination -Force|Out-Null
        foreach($test in $Tests.Split(',')){Get-ChildItem (Join-Path $source "$module/target/surefire-reports") -File -ErrorAction SilentlyContinue|Where-Object Name -like "*.$test.*"|Copy-Item -Destination $destination}
    }}
    Require ($exit -eq 0) "Maven failed; inspect $Label.log (evidence retained)"
}
function CaptureMysql([string]$Label){
    Invoke-AbSql "SELECT VERSION(),@@general_log,@@performance_schema; SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_row_lock_current_waits','Innodb_row_lock_waits','Innodb_row_lock_time','Threads_connected','Threads_running'); SELECT NAME,COUNT FROM information_schema.INNODB_METRICS WHERE NAME='lock_deadlocks'; SHOW ENGINE INNODB STATUS;"|Set-Content (Join-Path $run "mysql-$Label.txt") -Encoding utf8NoBOM
}
function Explain([string]$Label){
    $queries=@(
        'SELECT t1.id,t1.book_id,t1.comment_content,t1.location,t1.reply_count,t1.create_time,t2.username create_user_name,t2.user_photo create_user_photo FROM book_comment t1 INNER JOIN user t2 ON t1.create_user_id=t2.id AND t1.book_id=900003 ORDER BY t1.create_time DESC,t1.id DESC LIMIT 0,5',
        'SELECT t1.id,t1.book_id,t1.comment_content,t1.location,t1.reply_count,t1.create_time,t2.username create_user_name,t2.user_photo create_user_photo FROM book_comment t1 INNER JOIN user t2 ON t1.create_user_id=t2.id AND t1.create_user_id=700000001 ORDER BY t1.create_time DESC,t1.id DESC LIMIT 0,5',
        'SELECT t1.id,t1.reply_content,t1.location,t1.create_time,t2.username create_user_name,t2.user_photo create_user_photo FROM book_comment_reply t1 INNER JOIN user t2 ON t1.create_user_id=t2.id AND t1.comment_id=800000001 ORDER BY t1.create_time DESC,t1.id DESC LIMIT 0,5',
        'SELECT t1.id,t1.reply_content,t1.location,t1.create_time,t2.username create_user_name,t2.user_photo create_user_photo FROM book_comment_reply t1 INNER JOIN user t2 ON t1.create_user_id=t2.id AND t1.create_user_id=700000001 ORDER BY t1.create_time DESC,t1.id DESC LIMIT 0,5'
    )
    for($n=0;$n -lt $queries.Count;$n++){
        $q=$queries[$n];Set-Content (Join-Path $run "query-$n.sql") "$q;"
        Sql "EXPLAIN FORMAT=JSON $q; EXPLAIN ANALYZE $q;"|Set-Content (Join-Path $run "explain-$Label-$n.txt")
        Sql "$q;"|Set-Content (Join-Path $run "query-result-$Label-$n.tsv")
    }
}
New-Item -ItemType Directory -Path $run -Force|Out-Null
try{
    [Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
    # Docker Desktop also installs an extensionless Unix shim beside docker.exe.
    # Never pass the unqualified lookup's .Source array to the native transport.
    $dockerExe=(Get-Command docker.exe -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
    Require ($dockerExe -is [string] -and (Test-Path -LiteralPath $dockerExe -PathType Leaf)) 'Expected a single Windows Docker executable'
    Save 'native-tool.json' @{dockerExecutable=$dockerExe;context='desktop-linux'}
    foreach($key in @('JAVA_HOME','CORRECTNESS_MYSQL_PREFIX','CORRECTNESS_MYSQL_USER','CORRECTNESS_MYSQL_PASSWORD','CORRECTNESS_EVIDENCE','CORRECTNESS_DEPLOYED','CORRECTNESS_APP_JDBC','CORRECTNESS_RUN_ID','CORRECTNESS_REDIS_PASSWORD','CORRECTNESS_ADMIN_PASSWORD')){$previous[$key]=[Environment]::GetEnvironmentVariable($key,'Process')}
    Require ((git -C $source branch --show-current) -eq 'correctness-hardening-20261017') 'Wrong development branch'
    Require (Test-Path $maven) 'Cached Maven required'
    Require (-not($ResumeFrom -and $ReuseBuildFrom)) 'Use ResumeFrom or ReuseBuildFrom, not both'
    Require (-not $RecoverFromDependencyState -or $ResumeFrom) 'RecoverFromDependencyState requires an explicit ResumeFrom checkpoint'
    $resume=$null;$recoveryProof=$null
    if($ResumeFrom){
        $resume=Read-CvResume $ResumeFrom
        $database=$resume.plan.schema;$prefix=$resume.plan.itPrefix;$topic=$resume.plan.topic
        $values=$resume.values;$dbUser=$values.CV_DATABASE_USER;$dbPassword=$values.CV_DATABASE_PASSWORD
        $redisPassword=$values.CV_REDIS_PASSWORD;$jwt=$values.CV_JWT_SECRET;$adminImage=$values.CV_ADMIN_IMAGE
        $result.schema=$database;$result.itPrefix=$prefix;$result.resumedFrom=$ResumeFrom;$result.databaseEvidence=$resume.evidence
        if($resume.phase -eq 'admin'){$candidate=$resume.plan.newImage}
        if($RecoverFromDependencyState){$recoveryProof=Read-CvRestartProof $RecoverFromDependencyState $resume}
    }
    if($ReuseBuildFrom){$reused=Reuse-CvBuild $ReuseBuildFrom;$candidate=$reused.front;$adminImage=$reused.admin}
    $env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.10'
    Assert-AbMarker
    $original=@{};foreach($service in @('mysql','redis','kafka','mailpit','front')){$c=Get-AbContainer $service;$original[$service]=@{id=$c.Id;image=$c.Image;started=$c.State.StartedAt}}
    Save 'original-containers-before.json' $original
    Require ((Invoke-AbSql 'SELECT @@general_log;') -eq '0') 'General Log is ON; do not alter globally or measure until its owner disables it'
    Require (((Invoke-AbSql "SELECT VERSION();") -join '') -like '8.*') 'MySQL 8 required'
    if($resume){
        Assert-CvResumeRuntime $resume -AllowStoppedApplications:($null -ne $recoveryProof)
        Require ((Sql 'SELECT label FROM correctness_identity WHERE id=1;') -ceq $resume.identity) 'Saved database identity mismatch'
        if($resume.phase -eq 'reading'){
            Require ((Sql 'SELECT COUNT(*) FROM book_comment WHERE book_id=900099;') -eq '0') 'HTTP smoke already mutated fixture; resume is not safe at this checkpoint'
        }else{
            Require ((Sql "SELECT (SELECT COUNT(*) FROM sys_user WHERE user_id IN (990000001,990000002) OR username IN ('cvuser1','cvuser2'))+(SELECT COUNT(*) FROM sys_role WHERE role_id=990000001)+(SELECT COUNT(*) FROM sys_menu WHERE menu_id=990000001)+(SELECT COUNT(*) FROM sys_user_role WHERE user_id IN (990000001,990000002))+(SELECT COUNT(*) FROM sys_role_menu WHERE role_id=990000001);") -eq '0') 'Admin fixtures already exist; refuse replay without a verified checkpoint'
        }
        $savedOriginal=Get-Content (Join-Path $resume.prior 'original-containers-before.json') -Raw|ConvertFrom-Json -AsHashtable
        $restartChanges=@(Assert-CvDependencyCheckpoint $original $savedOriginal $resume $recoveryProof)
        if($recoveryProof){
            Save 'dependency-restarts.json' @{evidence=$RecoverFromDependencyState;changes=$restartChanges;readingEvidence=$resume.prior;newMeasurements=$false}
            $result.boundaries+='Dependencies restarted after historical reading measurements; this continuation only verifies functionality. Private Redis has no persistence; historical warm cache/session state is not assumed.'
        }
        $databaseReady=$true
    }else{
        Require (@(Invoke-AbDocker @('ps','-aq','--filter',"label=com.docker.compose.project=$project")).Count -eq 0) 'Validation containers already exist; preserve them and inspect previous evidence before resuming'
        foreach($port in @(26381,28180,28183,28184)){Require (@(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue).Count -eq 0) "Port occupied: $port"}
    }
    if($resume -and $resume.phase -eq 'admin'){
        # Existing applications are already running. Finish in place: no build,
        # Compose up, reading workload, front mutations or database reinitialization.
        $runtime=$resume.runtime
        foreach($service in @('front','admin')){
            Copy-Item (Join-Path $resume.prior "$service.image") (Join-Path $run "$service.image")
        }
        $hashes=Import-Csv (Join-Path $resume.prior 'jar-sha256.csv')
        Require (@($hashes).Count -eq 2) 'Existing application JAR provenance missing'
        foreach($hash in $hashes){Require ((Get-FileHash -LiteralPath $hash.Path).Hash -ceq $hash.Hash) 'Application JAR changed since deployed checkpoint'}
        Copy-Item (Join-Path $resume.prior 'jar-sha256.csv') (Join-Path $run 'jar-sha256.csv')
        foreach($name in $resume.state.checks.Keys){$result.checks[$name]=$resume.state.checks[$name]}
        $result.readingEvidence=$resume.prior
        foreach($file in @('reading-summary.json','reading-rounds.json')){Copy-Item (Join-Path $resume.prior $file) (Join-Path $run $file)}
        Save 'plan.json' $resume.plan
        Save 'resume.json' @{from=$ResumeFrom;phase='admin';databaseIdentity=$resume.identity;databaseEvidence=$resume.evidence;readingEvidence=$resume.prior;rebuilt=$false;containersRecreated=$false;frontSmokeRepeated=$false;readingRepeated=$false}
        Invoke-AbSql 'SELECT id,visit_count,comment_count FROM book ORDER BY id; SELECT COUNT(*),COALESCE(SUM(reply_count),0) FROM book_comment; SELECT COUNT(*) FROM book_comment_reply;'|Set-Content (Join-Path $run 'original-data-before.tsv')
        Require ((Get-Content (Join-Path $run 'original-data-before.tsv') -Raw) -ceq (Get-Content (Join-Path $resume.prior 'original-data-before.tsv') -Raw)) 'Original source data changed since checkpoint'
        if($recoveryProof){
            Start-CvStoppedApplications $resume $recoveryProof
            Assert-CvResumeRuntime $resume
        }
        WaitHttp 'http://127.0.0.1:28180/login'
    }else{
    $network=((Invoke-AbDocker @('network','inspect','novel-cache-ab-20261016_backend')) -join "`n"|ConvertFrom-Json)[0]
    Require ($network.Driver -eq 'bridge' -and $network.Labels.'com.docker.compose.project' -eq $AbProject) 'Wrong dependency network'
    $memory=[long](Invoke-AbDocker @('info','--format','{{.MemTotal}}'))
    $stats=Invoke-AbDocker @('stats','--no-stream','--format','{{json .}}');$stats|Set-Content (Join-Path $run 'docker-stats-before.jsonl')
    $used=0L
    foreach($line in $stats){
        $usage=($line|ConvertFrom-Json).MemUsage
        Require ($usage -match '^\s*([0-9.]+)\s*(GiB|MiB|KiB|GB|MB|kB|B)\s*/') 'Cannot parse Docker memory use; review budget'
        $amount=[double]::Parse($Matches[1],[Globalization.CultureInfo]::InvariantCulture)
        $factor=switch -CaseSensitive ($Matches[2]){'GiB'{1GB};'MiB'{1MB};'KiB'{1KB};'GB'{1000000000};'MB'{1000000};'kB'{1000};'B'{1}}
        $used+=[long]($amount*$factor)
    }
    Require ($memory-$used -ge 2176MB) 'Insufficient Docker headroom for 1664 MiB validation limits + 512 MiB reserve; no existing service/resource setting will be changed'
    New-Item -ItemType Directory -Path $runtime -Force|Out-Null
    Require (-not(Test-Path (Join-Path $runtime 'verify.env'))) 'Runtime already exists; preserve credentials/schema instead of replacing a previous run'
    if($resume){
        $values.CV_FRONT_IMAGE=$original.front.image;$values.CV_RUNTIME_DIR=$runtime.Replace('\','/')
        Copy-Item (Join-Path $resume.runtime 'admin-sharding.yml') (Join-Path $runtime 'admin-sharding.yml')
    }else{
        $values=[ordered]@{CV_FRONT_IMAGE=$original.front.image;CV_ADMIN_IMAGE=$adminImage;CV_DATABASE=$database;CV_DATABASE_USER=$dbUser;CV_DATABASE_PASSWORD=$dbPassword;CV_REDIS_PASSWORD=$redisPassword;CV_JWT_SECRET=$jwt;CV_SECRET=[guid]::NewGuid().ToString('N');CV_ADMIN_TEST_PASSWORD=[guid]::NewGuid().ToString('N');CV_TOPIC=$topic;CV_RUNTIME_DIR=$runtime.Replace('\','/')}
    }
    foreach($key in $values.Keys){Require (-not [Environment]::GetEnvironmentVariable($key,'Process')) "Remove conflicting process override before continuing: $key"}
    ($values.GetEnumerator()|ForEach-Object{"$($_.Key)=$($_.Value)"})|Set-Content (Join-Path $runtime 'verify.env') -Encoding utf8NoBOM
    $spec=(Invoke-AbDocker ((ComposeArgs)+@('config','--format','json'))) -join "`n"|ConvertFrom-Json -AsHashtable
    AssertVerifySpec $spec
    $expectedPorts=@{front=@(28183,28184);admin=@(28180);'validation-redis'=@(26381)}
    foreach($service in $spec.services.Keys){
        $s=$spec.services[$service]
        foreach($key in @($s.environment.Keys)){if($key -match 'PASSWORD|SECRET'){$s.environment[$key]='REDACTED'}}
    }
    Save 'compose-redacted.json' $spec
    Save 'plan.json' @{schema=$database;itPrefix=$prefix;topic=$topic;identityRun=if($resume){$resume.identity}else{$runId};ports=$expectedPorts;oldImage=$original.front.image;newImage=$candidate;runtime=$runtime;limitsMiB=@{front=896;admin=640;redis=128};reading=@{concurrency=2;measuredRequestsPerVersionPerPair=60;pairs=3;warmupSeconds=10};dockerMemoryBytes=$memory;dockerMemoryUsedBytes=$used;codeSha=(git -C $source rev-parse HEAD)}
    git -C $source diff --binary | Set-Content (Join-Path $run 'development.patch') -Encoding utf8NoBOM
    git -C $source status --short | Set-Content (Join-Path $run 'git-status.txt')
    if(-not $ReuseBuildFrom){
        if($resume){
            # Admin business code did not change. Verify and retain its previous image/JAR.
            $adminHash=@(Import-Csv (Join-Path $resume.prior 'jar-sha256.csv')|Where-Object{[IO.Path]::GetFileName($_.Path) -eq 'novel-admin-5.3.3.jar'})
            Require ($adminHash.Count -eq 1 -and (Get-FileHash (Join-Path $source 'novel-admin/target/novel-admin-5.3.3.jar')).Hash -ceq $adminHash[0].Hash) 'Saved admin JAR changed'
            $adminId=Invoke-AbDocker @('image','inspect',$adminImage,'--format','{{.Id}}')
            Require ($adminId -ceq (Get-Content (Join-Path $resume.prior 'admin.image') -Raw).Trim()) 'Saved admin image changed'
            Set-Content (Join-Path $run 'admin.image') $adminId -Encoding utf8NoBOM
            Maven 'novel-front' '' 'package' -Package
            $builds=,@('front','Dockerfile.front',$candidate)
        }else{
            Maven 'novel-front,novel-admin' '' 'package' -Package
            $builds=@(@('front','Dockerfile.front',$candidate),@('admin','Dockerfile.admin',$adminImage))
        }
        foreach($entry in $builds){
            Invoke-AbDocker @('build','-f',(Join-Path $source "deploy/correctness/$($entry[1])"),'-t',$entry[2],'--iidfile',(Join-Path $run "$($entry[0]).build-iid"),$source) *> (Join-Path $run "build-$($entry[0]).log")
            # BuildKit may write a manifest-list digest; container.Image is the config ID.
            Invoke-AbDocker @('image','inspect',$entry[2],'--format','{{.Id}}')|Set-Content (Join-Path $run "$($entry[0]).image") -Encoding utf8NoBOM
        }
        Get-FileHash (Join-Path $source 'novel-front/target/novel-front-5.3.3.jar'),(Join-Path $source 'novel-admin/target/novel-admin-5.3.3.jar')|Export-Csv (Join-Path $run 'jar-sha256.csv') -NoTypeInformation
    }
    if(-not $resume){
    # CREATE only a new database. mysqldump has no USE/CREATE DATABASE and never drops source tables.
    $snapshot=Join-Path $run 'synthetic-snapshot.sql'
    Export-CvSnapshot $snapshot
    Invoke-AbSql "CREATE DATABASE $database CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; CREATE USER '$dbUser'@'%' IDENTIFIED BY '$dbPassword'; GRANT ALL ON ``$($database.Replace('_','\_'))``.* TO '$dbUser'@'%'; GRANT ALL ON ``$($prefix.Replace('_','\_'))\_%``.* TO '$dbUser'@'%';"|Out-Null
    Invoke-CvSqlTransport -InputFile $snapshot -Schema $database|Out-Null
    Verify-CvSnapshot
    $engines=Sql "SELECT TABLE_NAME,ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('book','book_comment','book_comment_reply','user') ORDER BY TABLE_NAME;"
    $engines|Set-Content (Join-Path $run 'application-table-engines.tsv')
    Require (@($engines).Count -eq 4 -and @($engines|Where-Object{$_ -notmatch '\tInnoDB$'}).Count -eq 0) 'Application comment tables must all be InnoDB'
    Sql "CREATE TABLE correctness_identity(id INT PRIMARY KEY,label VARCHAR(64)); INSERT INTO correctness_identity VALUES(1,'$runId');"|Out-Null
    $databaseReady=$true
    $sharding=[IO.File]::ReadAllText((Join-Path $source 'deploy/shardingsphere/shardingsphere-jdbc.yml.template')).Replace('${MYSQL_DATABASE}',$database).Replace('${MYSQL_USER_YAML}',"'$dbUser'").Replace('${MYSQL_PASSWORD_YAML}',"'$dbPassword'")
    [IO.File]::WriteAllText((Join-Path $runtime 'admin-sharding.yml'),$sharding,[Text.UTF8Encoding]::new($false))
    Invoke-AbSql 'SELECT id,visit_count,comment_count FROM book ORDER BY id; SELECT COUNT(*),COALESCE(SUM(reply_count),0) FROM book_comment; SELECT COUNT(*) FROM book_comment_reply;'|Set-Content (Join-Path $run 'original-data-before.tsv')
    CaptureMysql 'before-tests'
    $monitor=Start-Job -ArgumentList $run,$prefix -ScriptBlock {
        param($out,$p)
        $sql="SELECT JSON_OBJECT('at',NOW(6),'schema',t.PROCESSLIST_DB,'requester',w.REQUESTING_THREAD_ID,'blocker',w.BLOCKING_THREAD_ID) FROM performance_schema.data_lock_waits w JOIN performance_schema.threads t ON t.THREAD_ID=w.REQUESTING_THREAD_ID WHERE LEFT(t.PROCESSLIST_DB,LENGTH('$p'))='$p';"
        while(-not(Test-Path (Join-Path $out 'monitor.stop'))){
            $rows=$sql|& docker --context desktop-linux exec -i novel-cache-ab-20261016-mysql-1 sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B'
            if($LASTEXITCODE -ne 0){throw 'Lock monitor SQL failed'}
            if($rows){$rows|Add-Content (Join-Path $out 'innodb-lock-waits.jsonl') -Encoding utf8}
            Start-Sleep -Milliseconds 200
        }
    }
    $env:CORRECTNESS_MYSQL_PREFIX=$prefix;$env:CORRECTNESS_MYSQL_USER=$dbUser;$env:CORRECTNESS_MYSQL_PASSWORD=$dbPassword;$env:CORRECTNESS_EVIDENCE=$run
    Maven 'novel-front,novel-admin' 'CommentCorrectnessTest,CommentDeletionTest' 'real-mysql'
    Set-Content (Join-Path $run 'monitor.stop') 'stop';Wait-Job $monitor -Timeout 15|Out-Null;Receive-Job $monitor -ErrorAction Stop|Out-Null
    Require ($monitor.State -eq 'Completed') 'Lock monitor did not finish'
    CaptureMysql 'after-tests'
    $result.checks.innodb='PASS; see per-case rows, Surefire and lock evidence'
    $seed=@'
CREATE TEMPORARY TABLE cv_numbers(n INT PRIMARY KEY);
INSERT INTO cv_numbers WITH RECURSIVE a(n) AS (SELECT 0 UNION ALL SELECT n+1 FROM a WHERE n<59),b(n) AS (SELECT 0 UNION ALL SELECT n+1 FROM b WHERE n<19) SELECT a.n+b.n*60 FROM a CROSS JOIN b;
INSERT INTO user(id,username,password,nick_name,create_time,update_time) SELECT 700000000+n,CONCAT('cv_reader_',n),'NOT_A_LOGIN_HASH',CONCAT('reader',n),NOW(),NOW() FROM cv_numbers WHERE n<130;
INSERT INTO book(id,pic_url,book_name,author_name,book_desc,score,update_time,comment_count) SELECT 900003+n,'/images/logo.png',CONCAT('Validation book ',n),'Synthetic','Validation fixture',8,NOW(),0 FROM cv_numbers WHERE n<20;
INSERT INTO book(id,pic_url,book_name,author_name,book_desc,score,update_time,comment_count) VALUES(900099,'/images/logo.png','HTTP validation','Synthetic','Validation fixture',8,NOW(),0);
INSERT INTO book_comment(id,book_id,comment_content,reply_count,audit_status,create_time,create_user_id) SELECT 800000000+n,900003+FLOOR(n/60),CONCAT('index fixture ',n),2,MOD(n,3),TIMESTAMPADD(SECOND,n,'2026-01-01'),700000000+MOD(n,60) FROM cv_numbers;
INSERT INTO book_comment_reply(id,comment_id,reply_content,audit_status,create_time,create_user_id) SELECT 810000000+2*n,800000000+n,CONCAT('reply a ',n),0,TIMESTAMPADD(SECOND,n,'2026-01-01'),700000000+MOD(n,60) FROM cv_numbers;
INSERT INTO book_comment_reply(id,comment_id,reply_content,audit_status,create_time,create_user_id) SELECT 810000001+2*n,800000000+n,CONCAT('reply b ',n),1,TIMESTAMPADD(SECOND,n,'2026-01-01'),700000000+MOD(n,60) FROM cv_numbers;
UPDATE book b SET comment_count=(SELECT COUNT(*) FROM book_comment c WHERE c.book_id=b.id) WHERE b.id BETWEEN 900003 AND 900022;
DROP TEMPORARY TABLE cv_numbers;
'@
    Sql $seed|Out-Null;Set-Content (Join-Path $run 'index-fixture.sql') $seed
    Explain 'before'
    Sql (Get-Content (Join-Path $source 'doc/sql/20261017_comment_indexes.sql') -Raw)|Out-Null
    Sql 'ANALYZE TABLE book_comment,book_comment_reply; SHOW INDEX FROM book_comment; SHOW INDEX FROM book_comment_reply;'|Set-Content (Join-Path $run 'indexes-after.tsv')
    Explain 'after'
    for($n=0;$n -lt 4;$n++){Require ((Get-Content (Join-Path $run "query-result-before-$n.tsv") -Raw) -ceq (Get-Content (Join-Path $run "query-result-after-$n.tsv") -Raw)) 'Index changed query results'}
    $result.checks.indexes='PASS DDL/results; inspect EXPLAIN before/after, no performance claim from index existence'
    }else{
        Verify-CvSnapshot
        $indexNames=@(Sql "SELECT DISTINCT INDEX_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND ((TABLE_NAME='book_comment' AND INDEX_NAME IN ('idx_comment_book_page','idx_comment_user_page')) OR (TABLE_NAME='book_comment_reply' AND INDEX_NAME IN ('idx_reply_comment_page','idx_reply_user_page'))) ORDER BY INDEX_NAME;")
        Require (($indexNames -join ',') -ceq 'idx_comment_book_page,idx_comment_user_page,idx_reply_comment_page,idx_reply_user_page') 'Completed comment indexes missing'
        $result.checks.innodb="PASS reused real MySQL evidence: $($resume.evidence)"
        $result.checks.indexes="PASS reused DDL/results/EXPLAIN evidence: $($resume.evidence); indexes still present"
        Invoke-AbSql 'SELECT id,visit_count,comment_count FROM book ORDER BY id; SELECT COUNT(*),COALESCE(SUM(reply_count),0) FROM book_comment; SELECT COUNT(*) FROM book_comment_reply;'|Set-Content (Join-Path $run 'original-data-before.tsv')
        Require ((Get-Content (Join-Path $run 'original-data-before.tsv') -Raw) -ceq (Get-Content (Join-Path $resume.prior 'original-data-before.tsv') -Raw)) 'Original source data changed since checkpoint'
        Save 'resume.json' @{from=$ResumeFrom;databaseEvidence=$resume.evidence;schema=$database;databaseImported=$false;mysqlCasesRerun=$false;indexesRecreated=$false;frontRebuilt=$true;adminReused=$true;readingRestarted=$true;oldPartialReadingExcluded=$true}
    }
    $env:CORRECTNESS_MYSQL_PREFIX=$prefix;$env:CORRECTNESS_MYSQL_USER=$dbUser;$env:CORRECTNESS_MYSQL_PASSWORD=$dbPassword;$env:CORRECTNESS_EVIDENCE=$run
    Invoke-AbDocker ((ComposeArgs)+@('up','-d','--no-build','--no-recreate','validation-redis'))|Out-Null
    if(-not $SkipReadingRegression){. (Join-Path $root 'Reading-Regression.ps1') -Run $run -OriginalImage $original.front.image -CandidateImage $candidate}else{$result.checks.reading='NOT_RUN (SkipReadingRegression)'}
    SwitchFront $candidate
    Invoke-AbDocker ((ComposeArgs)+@('up','-d','--no-build','admin'))|Out-Null
    WaitHttp 'http://127.0.0.1:28180/login'
    . (Join-Path $root 'Smoke-Deployed.ps1') -Run $run
    }
    $env:CORRECTNESS_MYSQL_PREFIX=$prefix;$env:CORRECTNESS_MYSQL_USER=$dbUser;$env:CORRECTNESS_MYSQL_PASSWORD=$dbPassword;$env:CORRECTNESS_EVIDENCE=$run
    $env:CORRECTNESS_DEPLOYED='true';$env:CORRECTNESS_APP_JDBC="jdbc:mysql://127.0.0.1:23307/$database`?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=Asia/Shanghai"
    # CORRECTNESS_RUN_ID is the database marker expected by DeployedAdminIT,
    # not the new evidence directory ID when reusing an existing database.
    $env:CORRECTNESS_RUN_ID=if($resume){$resume.identity}else{$runId}
    $env:CORRECTNESS_REDIS_PASSWORD=$redisPassword
    $env:CORRECTNESS_ADMIN_PASSWORD=$values.CV_ADMIN_TEST_PASSWORD
    Maven 'novel-admin' 'DeployedAdminIT' 'deployed-admin'
    $result.checks.admin='PASS HTTP login/session/permissions/comment mutations; CAPTCHA value read from test Redis'
    foreach($name in @('jvm.memory.used','hikaricp.connections.active','novel.chapter.cache.lookup')){$null=Metric $name 'final'}
    $gc=Metric 'jvm.gc.pause' 'final' -AllowMissing
    if($null -eq $gc){$result.boundaries+='No GC pause event meter observed (HTTP 404); not treated as zero.'}
    $prom=Invoke-WebRequest "$management/actuator/prometheus" -TimeoutSec 10
    $promBody=Convert-MetricResponseBody $prom.Content;Require ($promBody -match 'jvm_memory_used_bytes') 'Prometheus JVM metrics missing';Set-Content (Join-Path $run 'final.prom') $promBody
    $result.checks.metrics='PASS UTF-8 Actuator JSON and Prometheus'
    foreach($service in @('front','admin','validation-redis')){
        $c=NewContainer $service
        Require ($c.State.Running -and -not $c.State.OOMKilled) "Service failed: $service"
        foreach($port in $c.NetworkSettings.Ports.PSObject.Properties){foreach($binding in @($port.Value)){Require ($binding.HostIp -eq '127.0.0.1') 'Runtime port exposed beyond loopback'}}
        Invoke-AbDocker @('logs',"$project-$service-1") *> (Join-Path $run "final-$service.log")
        Save "deployed-$service.json" @{id=$c.Id;image=$c.Image;state=$c.State;ports=$c.NetworkSettings.Ports;networks=@($c.NetworkSettings.Networks.PSObject.Properties.Name);mounts=$c.Mounts}
    }
    Require ((NewContainer 'front').Image -eq (Get-Content (Join-Path $run 'front.image') -Raw).Trim()) 'Final front image is not candidate'
    foreach($service in $original.Keys){$c=Get-AbContainer $service;Require ($c.Id -eq $original[$service].id -and $c.Image -eq $original[$service].image -and $c.State.StartedAt -eq $original[$service].started) "Original service changed: $service"}
    Invoke-AbSql 'SELECT id,visit_count,comment_count FROM book ORDER BY id; SELECT COUNT(*),COALESCE(SUM(reply_count),0) FROM book_comment; SELECT COUNT(*) FROM book_comment_reply;'|Set-Content (Join-Path $run 'original-data-after.tsv')
    Require ((Get-Content (Join-Path $run 'original-data-before.tsv') -Raw) -ceq (Get-Content (Join-Path $run 'original-data-after.tsv') -Raw)) 'Original data counters changed; inspect external activity'
    $result.status=if([string]$result.checks.reading -like 'REVIEW*'){'REVIEW_REQUIRED'}elseif($SkipReadingRegression){'INCOMPLETE'}else{'COMPLETE'}
    $result.deployment='candidate front + admin + dedicated Redis running; existing dependencies preserved'
}catch{
    $result.status='FAILED';$result.failure=$_.Exception.Message
    $_|Out-String|Set-Content (Join-Path $run 'exception.txt')
    $_.ScriptStackTrace|Set-Content (Join-Path $run 'exception-stack.txt')
    Write-Warning "FAILED; no cleanup/rollback/deletion performed. Evidence: $run"
}finally{
    if($monitor){Set-Content (Join-Path $run 'monitor.stop') 'stop';Wait-Job $monitor -Timeout 5|Out-Null;if($monitor.State -eq 'Running'){Stop-Job $monitor};Receive-Job $monitor -ErrorAction SilentlyContinue *> (Join-Path $run 'lock-monitor-output.txt');Remove-Job $monitor}
    if($databaseReady){try{CaptureMysql 'final'}catch{$result.monitorFinalError=$_.Exception.Message}}
    if($result.status -eq 'FAILED'){
        try{Collect-CvFailureLogs}catch{}
    }
    foreach($key in $previous.Keys){[Environment]::SetEnvironmentVariable($key,$previous[$key],'Process')}
    $result.finished=[DateTimeOffset]::Now.ToString('o');Save 'acceptance.json' $result
    $summary=@("# correctness-src 部署验收","","状态：$($result.status)","","schema：$database；Project：$project。原实验证据未改写。","")
    foreach($key in $result.checks.Keys){$summary+="- $key : $($result.checks[$key])"}
    if($result.Contains('failure')){$summary+="`n失败：$($result.failure)"}
    $summary+="`n本文件由宿主机运行生成；阅读回归详见 reading-summary.json；索引收益须根据 explain-before/after 分析。"
    $summary|Set-Content (Join-Path $run '部署验收结果.md') -Encoding utf8NoBOM
    [Console]::OutputEncoding=$previousConsoleEncoding
}
if($result.status -eq 'REVIEW_REQUIRED'){
    Write-Warning "REVIEW_REQUIRED: functional/deployment checks finished; measured reading regression still requires review. Evidence: $run"
    return
}
if($result.status -ne 'COMPLETE'){throw "Acceptance stopped; evidence retained: $run"}
Write-Host "COMPLETE: $run; front http://127.0.0.1:28183; admin http://127.0.0.1:28180"

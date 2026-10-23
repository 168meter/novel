#requires -Version 7
[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'Native-Utf8.ps1')
. (Join-Path $PSScriptRoot 'G1-Common.ps1')
$source=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$id=Get-Date -Format 'yyyyMMddHHmmssfff';$label="joint-$id";$project="novel-interaction-$id"
$run=Join-Path $PSScriptRoot "artifacts/$label";$runtime=Join-Path $PSScriptRoot "runtime/$label"
$database="cv_it_${id}_app";$trial="cv_it_${id}_trial";$restore="cv_it_${id}_restore";$prefix="cv_it_$id"
$dbUser="joint_$id";$password=[guid]::NewGuid().ToString('N');$redisPassword=[guid]::NewGuid().ToString('N')
$secret=[guid]::NewGuid().ToString('N');$jwt=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(64))
$frontImage="novel-interaction-front:$id";$adminImage="novel-interaction-admin:$id"
$mysql='novel-cache-ab-20261016-mysql-1';$network='novel-cache-ab-20261016_backend'
$maven=Join-Path $env:USERPROFILE '.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn.cmd'
$agent=Join-Path $env:USERPROFILE '.m2/repository/org/mockito/mockito-core/5.14.2/mockito-core-5.14.2.jar'
$node=Join-Path $env:USERPROFILE '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node.exe'
$state=[ordered]@{run=$label;status='PREPARING';schema=$database;trial=$trial;restore=$restore;project=$project;started=[DateTimeOffset]::Now.ToString('o');codeTests='NOT_RUN';migration='NOT_RUN';deployment='NOT_RUN';http='NOT_RUN';browser='NOT_RUN';writesResumed=$false;boundaries=@('Only new cv_it schemas and new project containers; existing applications, schema and volumes preserved.','No frozen performance experiments or reading comparison run.','Old immediate count rollback must not run after application writes resume.','No automatic cleanup or rollback on failure; partial DDL and evidence retained.')}
$null=New-Item -ItemType Directory -Path $run
$null=New-Item -ItemType Directory -Path $runtime
$script:ordinal=0;$previous=@{};$docker='';$original=@();$snapshotsReady=$false;$p=@();$preserved=@()
function Require([bool]$ok,[string]$message){if(-not $ok){throw $message}}
function Save([string]$name,$value){$value|ConvertTo-Json -Depth 30|Set-Content -LiteralPath (Join-Path $run $name) -Encoding utf8NoBOM}
function Docker([string[]]$Arguments){
 $script:ordinal++;$f=Join-Path $run ("native-{0:D5}.stderr.txt" -f $script:ordinal)
 $r=Invoke-CvNativeUtf8 -Executable $docker -Arguments (@('--context','desktop-linux')+$Arguments) -ErrorFile $f
 Require ($r.ExitCode -eq 0) "Docker operation failed; inspect $f"
 if($r.Stdout){$r.Stdout.TrimEnd([char[]]"`r`n") -split '\r?\n'}
}
function Assert-JointSchema([string]$schema){Require ($schema -in @($database,$trial,$restore) -and $schema -match '^cv_it_[0-9]{17}_(app|trial|restore)$') 'SQL target must be one of this run new schemas'}
function Sql([string]$Text,[string]$Schema=$database){
 Assert-JointSchema $Schema
 return RootSql "USE ``$Schema``; $Text"
}
function RootSql([string]$Text){
 $script:ordinal++;$r=Invoke-CvNativeUtf8 -Executable $docker -Arguments @('--context','desktop-linux','exec','-i',$mysql,'sh','-ec','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --default-character-set=utf8mb4 -uroot -N -B') -InputText $Text -ErrorFile (Join-Path $run ("sql-{0:D5}.stderr.txt" -f $script:ordinal))
 Require ($r.ExitCode -eq 0) "SQL operation failed at step $script:ordinal; retain partial state"
 if($r.Stdout){$r.Stdout.TrimEnd([char[]]"`r`n") -split '\r?\n'}
}
function Import([string]$file,[string]$schema){
 Assert-JointSchema $schema;$script:ordinal++
 $r=Invoke-CvNativeUtf8 -Executable $docker -Arguments @('--context','desktop-linux','exec','-i',$mysql,'sh','-ec','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --default-character-set=utf8mb4 -uroot "$1"','sh',$schema) -InputFile $file -ErrorFile (Join-Path $run ("import-{0:D5}.stderr.txt" -f $script:ordinal))
 Require ($r.ExitCode -eq 0) "Snapshot import failed: $schema"
}
function Dump([string]$schema,[string]$file){
 Require ($schema -eq 'novel_ab' -or $schema -eq $database) 'Dump source is not approved'
 $r=Invoke-CvNativeUtf8 -Executable $docker -Arguments @('--context','desktop-linux','exec',$mysql,'sh','-ec','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --skip-lock-tables --skip-add-locks --skip-add-drop-table --no-tablespaces --set-gtid-purged=OFF --hex-blob --default-character-set=utf8mb4 "$1"','sh',$schema) -OutputFile $file -ErrorFile (Join-Path $run ([IO.Path]::GetFileName($file)+'.stderr.txt'))
 Require ($r.ExitCode -eq 0) 'Snapshot dump failed'
 $text=[IO.File]::ReadAllText($file,[Text.UTF8Encoding]::new($false,$true))
 Require ($text -notmatch '(?im)^\s*(USE|CREATE DATABASE|DROP DATABASE|DROP TABLE)\b') 'Dump changes context or drops tables'
 Get-FileHash $file|Export-Csv ($file+'.sha256.csv') -NoTypeInformation
}
function Counts([string]$schema){
 Sql "SELECT 'comment_status',COALESCE(audit_status,-1),COUNT(*) FROM book_comment GROUP BY audit_status ORDER BY audit_status; SELECT 'reply_status',COALESCE(audit_status,-1),COUNT(*) FROM book_comment_reply GROUP BY audit_status ORDER BY audit_status; SELECT 'book',id,COALESCE(CAST(comment_count AS CHAR),'NULL') FROM book ORDER BY id; SELECT 'comment',id,COALESCE(CAST(reply_count AS CHAR),'NULL') FROM book_comment ORDER BY id;" $schema
}
function Statuses([string]$schema){Sql "SELECT 'c',COALESCE(audit_status,-1),COUNT(*) FROM book_comment GROUP BY audit_status ORDER BY audit_status; SELECT 'r',COALESCE(audit_status,-1),COUNT(*) FROM book_comment_reply GROUP BY audit_status ORDER BY audit_status;" $schema}
function Invariants([string]$schema){
 $bad=Sql "SELECT (SELECT COUNT(*) FROM book b WHERE NOT(b.comment_count <=> (SELECT COUNT(*) FROM book_comment c WHERE c.book_id=b.id AND c.audit_status=1)))+(SELECT COUNT(*) FROM book_comment c WHERE NOT(c.reply_count <=> (SELECT COUNT(*) FROM book_comment_reply r WHERE r.comment_id=c.id AND r.audit_status=1)));" $schema
 Require (($bad -join '') -ceq '0') "Approved count mismatch in $schema"
}
function Migrate([string]$schema){
 Write-Host "Verifying migration: $schema"
 Assert-JointSchema $schema
 $before=Statuses $schema
 Sql (Get-Content (Join-Path $source 'doc/sql/moderation-20261019/01-preflight.sql') -Raw) $schema |Set-Content (Join-Path $run "$schema-preflight.tsv") -Encoding utf8NoBOM
 $columns=Sql "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name IN ('book_comment','book_comment_reply') AND column_name='moderation_version';" $schema
 Require (($columns -join '') -ceq '0') 'Snapshot already has version columns; inspect prior/partial state rather than dropping them'
 foreach($file in @('02-add-version.sql','03-recount.sql')){
  $sql="SET @moderation_expected_database='$schema';SET @moderation_writes_paused=1;`n"+(Get-Content (Join-Path $source "doc/sql/moderation-20261019/$file") -Raw)
  Sql $sql $schema|Set-Content (Join-Path $run "$schema-$file.log") -Encoding utf8NoBOM
 }
 Require ((($before)-join "`n") -ceq ((Statuses $schema)-join "`n")) 'Migration changed audit-status populations'
 Invariants $schema
 $bad=Sql "SELECT ((SELECT COUNT(*) FROM book)<>(SELECT COUNT(*) FROM cv_mod_book_backup_20261019))+((SELECT COUNT(*) FROM book_comment)<>(SELECT COUNT(*) FROM cv_mod_comment_backup_20261019))+((SELECT COUNT(*) FROM book_comment_reply)<>(SELECT COUNT(*) FROM cv_mod_reply_backup_20261019)); SELECT state FROM cv_mod_marker_20261019 WHERE schema_name=DATABASE();" $schema
 Require (($bad -join ',') -ceq '0,RECOUNTED') 'Count backup completeness/marker mismatch'
 Counts $schema|Set-Content (Join-Path $run "$schema-counts-after.tsv") -Encoding utf8NoBOM
 # Add the already validated pagination indices before enabling any new app writers.
 Apply-CommentPageIndexes -Schema $schema -AllowedSchemas @($database,$trial) -ReadIndexes {Sql (Get-CommentIndexReadSql) $schema} -Execute {param($ddl) Sql "SET SESSION lock_wait_timeout=5; $ddl" $schema} -Record {param($name,$rows) $rows|Set-Content (Join-Path $run "$schema-index-$name.tsv") -Encoding utf8NoBOM}
}
function Compose {return @('compose','-p',$project,'-f',(Join-Path $runtime 'compose.json'))}
function WaitHttp([string]$url){
 $timer=[Diagnostics.Stopwatch]::StartNew()
 do{try{$r=Invoke-WebRequest $url -SkipHttpErrorCheck -TimeoutSec 5;if($r.StatusCode -eq 200){return}}catch{};Start-Sleep -Seconds 2}while($timer.Elapsed.TotalSeconds -lt 240)
 throw "Application not ready: $url"
}
function Maven([string]$label,[string[]]$extra){
 Write-Host "Running Maven $label; evidence=$run/$label.log"
 $start=[DateTime]::UtcNow
 & $maven -f (Join-Path $source 'pom.xml') -o -q -pl novel-front,novel-admin -am '-Dmaven.antrun.skip=true' '-Dmaven.test.skip=false' "-DargLine=-javaagent:$agent" @extra *> (Join-Path $run "$label.log")
 Require ($LASTEXITCODE -eq 0) "Maven failed: $label.log"
 if($label -ne 'package'){
  $total=@{tests=0;failures=0;errors=0;skipped=0};$names=@()
  foreach($mod in @('novel-cache-contract','novel-interaction','novel-common','novel-front','novel-admin')){
   $dir=Join-Path $run "$label-$mod";$null=New-Item -ItemType Directory -Path $dir
   foreach($f in Get-ChildItem (Join-Path $source "$mod/target/surefire-reports") -Filter 'TEST-*.xml' -File -ErrorAction SilentlyContinue|Where-Object LastWriteTimeUtc -ge $start){
    Copy-Item -LiteralPath $f.FullName -Destination $dir;[xml]$x=Get-Content -LiteralPath $f.FullName -Raw;$names+=$x.testsuite.name
    foreach($key in @('tests','failures','errors','skipped')){$total[$key]+=[int]$x.testsuite.$key}
   }
  }
  Require ($total.tests -gt 0 -and $total.failures -eq 0 -and $total.errors -eq 0) 'Fresh test reports missing or failed'
  if($label -eq 'http'){Require ($names -contains 'com.java2nb.novel.service.DeployedInteractionIT' -and $total.skipped -eq 0) 'Actual deployed HTTP test skipped/missing'}
  Save "$label-tests.json" @{totals=$total;suites=$names}
 }
}
function Inputs([string]$folder){
 $result=@();foreach($file in Get-ChildItem $source -Recurse -File|Sort-Object FullName|Where-Object {$_.FullName -notmatch '[\\/](\.git|target|runtime|artifacts|logs|backups)[\\/]' -and $_.Extension -ne '.env'}){
  $relative=[IO.Path]::GetRelativePath($source,$file.FullName);$result+=@{path=$relative;sha256=(Get-FileHash -LiteralPath $file.FullName).Hash}
  if($folder){$dest=Join-Path $folder $relative;$null=New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($dest)) -Force;Copy-Item -LiteralPath $file.FullName -Destination $dest}
 };return $result
}
function Assert-JointSpec([hashtable]$Spec){
 Require ((@($Spec.services.Keys|Sort-Object) -join ',') -ceq 'admin,front,validation-redis' -and -not $Spec.ContainsKey('volumes')) 'Unexpected services or named volumes'
 Require ($Spec.networks.backend.external -and $Spec.networks.backend.name -ceq $network) 'Unexpected dependency network'
 $ports=@{front=@('127.0.0.1:28283:8083','127.0.0.1:28284:8084');admin=@('127.0.0.1:28280:8080');'validation-redis'=@('127.0.0.1:26382:6379')}
 foreach($name in $Spec.services.Keys){
  $c=$Spec.services[$name]
  Require ((@($c.networks) -join ',') -ceq 'backend' -and (@($c.ports|Sort-Object) -join ',') -ceq (@($ports[$name]|Sort-Object) -join ',')) 'Unsafe ports/network'
  if($c.ContainsKey('volumes')){foreach($v in $c.volumes){Require ($v.EndsWith(':ro') -and $v.StartsWith($runtime.Replace('\','/')+'/',[StringComparison]::OrdinalIgnoreCase)) 'Unexpected writable/outside bind'}}
 }
 Require (($Spec.services['validation-redis'].command -join ' ').Contains('$$REDIS_PASSWORD')) 'Compose must defer Redis password expansion to the container'
 Require ($Spec.services.front.environment.MYSQL_DATABASE -ceq $database -and $Spec.services.front.environment.MYSQL_USER -ceq $dbUser) 'Wrong application schema/principal'
 Require ($Spec.services.front.environment.SPRING_DATA_REDIS_HOST -ceq "$project-validation-redis-1" -and $Spec.services.admin.environment.SPRING_REDIS_HOST -ceq "$project-validation-redis-1") 'Ambiguous/shared Redis host'
 $props=($Spec.services.front.environment.SPRING_APPLICATION_JSON|ConvertFrom-Json -AsHashtable).novel.kafka
 Require ($props['book-visit'].topic -ceq "joint-$id-visit" -and $props['reading-engagement'].topic -ceq "joint-$id-reading") 'Unexpected Kafka topics'
}
function FrontConfig([string]$variant){
 $pfx=if($variant -eq 'builtin'){'classpath:/templates/'}else{"file:/app/templates/$variant/html/"}
 $static=if($variant -eq 'builtin'){'classpath:/static/'}else{"file:/app/templates/$variant/static/"}
 $text=(Get-Content (Join-Path $PSScriptRoot 'front-verify.yml') -Raw)+"`nspring.thymeleaf.prefix: '$pfx'`nspring.thymeleaf.cache: false`nspring.web.resources.static-locations: '$static'`n"
 # Dotted YAML keys are flattened by Spring; reuse original verify mail/AI overrides.
 $text|Set-Content (Join-Path $runtime 'front.yml') -Encoding utf8NoBOM
}
try{
 Require ((git -C $source branch --show-current) -ceq 'interaction-moderation-20261019') 'Wrong branch'
 git -C $source status --short --branch|Set-Content (Join-Path $run 'git-status-before.txt') -Encoding utf8NoBOM
 git -C $source diff --binary|Set-Content (Join-Path $run 'working.patch') -Encoding utf8NoBOM
 $head=(git -C $source rev-parse HEAD);$inputs=Inputs (Join-Path $runtime 'input-checkpoint');Save 'input-sha256.json' $inputs
 Save 'git-identity.json' @{branch='interaction-moderation-20261019';head=$head;uncommitted=$true}
 $docker=(Get-Command docker.exe -CommandType Application|Select-Object -First 1).Source
 $all=((Docker @('ps','-a','--format','{{.ID}}'))|Where-Object {$_})
 foreach($c in $all){$x=((Docker @('inspect',$c))-join "`n"|ConvertFrom-Json)[0];$original+=@{id=$x.Id;image=$x.Image;started=$x.State.StartedAt;name=$x.Name}}
 Save 'original-containers.json' $original
 $db=((Docker @('inspect',$mysql))-join "`n"|ConvertFrom-Json)[0]
 Require ($db.State.Running -and $db.Config.Labels.'com.docker.compose.project' -ceq 'novel-cache-ab-20261016' -and $db.Config.Labels.'com.docker.compose.service' -ceq 'mysql') 'Existing isolated MySQL identity/running state mismatch'
 $net=((Docker @('network','inspect',$network))-join "`n"|ConvertFrom-Json)[0]
 Require ($net.Driver -ceq 'bridge' -and $net.Labels.'com.docker.compose.project' -ceq 'novel-cache-ab-20261016') 'Wrong existing dependency network'
 foreach($port in @(28280,28283,28284,26382)){Require (@(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue).Count -eq 0) "Port already in use: $port"}
 $mem=[long](Docker @('info','--format','{{.MemTotal}}'));$used=0L
 foreach($line in Docker @('stats','--no-stream','--format','{{json .}}')){
  $usage=($line|ConvertFrom-Json).MemUsage;Require ($usage -match '^\s*([0-9.]+)\s*(GiB|MiB|KiB|GB|MB|kB|B)\s*/') 'Unrecognized Docker memory units'
  $n=[double]::Parse($Matches[1],[Globalization.CultureInfo]::InvariantCulture);$factor=switch -CaseSensitive ($Matches[2]){'GiB'{1GB};'MiB'{1MB};'KiB'{1KB};'GB'{1000000000};'MB'{1000000};'kB'{1000};'B'{1}};$used+=[long]($n*$factor)
 }
 Require ($mem-$used -ge 2176MB) 'Need 2176 MiB free for new isolated app/Redis limits plus reserve; existing services will not be stopped'
 $version=(RootSql 'SELECT VERSION();') -join '';Require ($version -like '8.*') 'MySQL 8 required';Save 'database-version.json' @{version=$version}
 $preserved=@(RootSql "SELECT schema_name FROM information_schema.schemata WHERE schema_name='novel_ab' OR schema_name LIKE 'cv\\_app\\_%' ORDER BY schema_name;")
 foreach($s in $preserved){Require ($s -eq 'novel_ab' -or $s -match '^cv_app_[0-9]{17}$') 'Unexpected preserved schema';RootSql "CHECKSUM TABLE $s.book,$s.book_comment,$s.book_comment_reply,$s.user;"|Set-Content (Join-Path $run "$s-before.tsv")}
 $dump=Join-Path $run 'source-readonly-snapshot.sql';Dump 'novel_ab' $dump
 foreach($schema in @($trial,$restore,$database)){RootSql "CREATE DATABASE ``$schema`` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"|Out-Null}
 Import $dump $database
 # Synthetic legacy populations make all three states and wrong historical counts observable even if the source is empty.
 Sql "SELECT table_name,column_name,column_type,is_nullable,column_default FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name IN('book_comment','book_comment_reply') AND column_name IN('audit_status','moderation_version');"|Set-Content (Join-Path $run 'snapshot-version-columns.tsv')
 Statuses $database|Set-Content (Join-Path $run 'snapshot-history-statuses.tsv')
 $seed="INSERT INTO user(id,username,password,nick_name,password_algorithm,token_version,create_time,update_time) VALUES(990700001,'19900000001',LOWER(MD5('$password')),'Joint Reader','MD5',0,NOW(),NOW()),(990700002,'jointlegacy0','00000000000000000000000000000000','legacy0','MD5',0,NOW(),NOW()),(990700003,'jointlegacy1','00000000000000000000000000000000','legacy1','MD5',0,NOW(),NOW()),(990700004,'jointlegacy2','00000000000000000000000000000000','legacy2','MD5',0,NOW(),NOW()); INSERT INTO book(id,pic_url,book_name,author_name,book_desc,score,update_time,comment_count) VALUES(990900001,'/images/logo.png','Joint interaction','Verifier','Joint interaction fixture',8,NOW(),0),(990900002,'/images/logo.png','Legacy moderation','Verifier','Legacy snapshot fixture',8,NOW(),99); INSERT INTO book_comment(id,book_id,create_user_id,comment_content,reply_count,audit_status,create_time) VALUES(990800001,990900002,990700002,'legacy-pending',99,0,NOW()),(990800002,990900002,990700003,'legacy-approved',99,1,NOW()),(990800003,990900002,990700004,'legacy-rejected',99,2,NOW()); INSERT INTO book_comment_reply(id,comment_id,create_user_id,reply_content,audit_status,create_time) VALUES(990810001,990800001,990700002,'legacy-pending-reply',0,NOW()),(990810002,990800001,990700003,'legacy-approved-reply-hidden-parent',1,NOW()),(990810003,990800002,990700004,'legacy-rejected-reply',2,NOW());"
 Sql $seed|Out-Null
 $backup=Join-Path $run 'pre-migration-full.sql';Dump $database $backup
 Import $backup $trial;Import $backup $restore
 $before=Counts $database;Require (($before -join "`n") -ceq ((Counts $restore)-join "`n")) 'Backup restore count/status check failed'
 # Check all table checksums in backup restore, not just interaction counts.
 $tables=@(Sql 'SHOW TABLES;');$checks=@();foreach($t in $tables){Require ($t -match '^[a-zA-Z0-9_]+$') 'Unsafe table';$a=(Sql "CHECKSUM TABLE ``$t``;") -split "\t";$b=(Sql "CHECKSUM TABLE ``$t``;" $restore) -split "\t";Require ($a[-1] -ceq $b[-1] -and $a[-1] -ne 'NULL') "Backup table checksum mismatch: $t";$checks+=@{table=$t;checksum=$a[-1]}}
 Save 'backup-restoration-proof.json' @{sha256=(Get-FileHash $backup).Hash;tables=$checks;countStatusesIdentical=$true;restoredSchema=$restore}
 RootSql "CREATE USER '$dbUser'@'%' IDENTIFIED BY '$password' ACCOUNT LOCK; GRANT ALL ON ``$database``.* TO '$dbUser'@'%'; GRANT ALL ON ``$($prefix.Replace('_','\_'))\_%``.* TO '$dbUser'@'%';"|Out-Null
 $writers=@(Docker @('ps','-q','--filter',"label=com.docker.compose.project=$project"));Require ($writers.Count -eq 0) 'New project already has active application writers'
 $sessions=RootSql "SELECT COUNT(*) FROM information_schema.processlist WHERE user='$dbUser';";Require (($sessions -join '') -ceq '0') 'New application principal already connected'
 $denied=Invoke-CvNativeUtf8 -Executable $docker -Arguments @('--context','desktop-linux','exec','-e',"MYSQL_PWD=$password",'-i',$mysql,'mysql',"-u$dbUser",$database,'-e','UPDATE book SET comment_count=comment_count WHERE id=990900001') -ErrorFile (Join-Path $run 'locked-writer.stderr.txt')
 Require ($denied.ExitCode -ne 0 -and (Get-Content (Join-Path $run 'locked-writer.stderr.txt') -Raw) -match 'locked|3118') 'Maintenance account lock did not actually reject DML connection'
 Save 'maintenance-proof.json' @{accountLocked=$true;deniedWriteExitCode=$denied.ExitCode;appSessions=0;newProjectContainers=$writers.Count;appsCreatedAfterMigration=$true}
 $before|Set-Content (Join-Path $run 'counts-before.tsv') -Encoding utf8NoBOM
 $state.migration='RUNNING'
 Migrate $trial
 Sql ("SET @moderation_expected_database='$trial';SET @moderation_writes_paused=1;`n"+(Get-Content (Join-Path $source 'doc/sql/moderation-20261019/04-rollback-counts.sql') -Raw)) $trial|Out-Null
 Require (($before -join "`n") -ceq ((Counts $trial)-join "`n")) 'Trial immediate rollback did not restore exact legacy counts'
 Save 'trial-migration-rollback.json' @{migrationCountsPassed=$true;rollbackRestoredCounts=$true;writesNeverEnabled=$true;trial=$trial}
 Migrate $database;$state.migration='PASSED';$snapshotsReady=$true
 Sql "CREATE TABLE correctness_identity(id INT PRIMARY KEY,label VARCHAR(64));INSERT INTO correctness_identity VALUES(1,'$label');"|Out-Null
 RootSql "ALTER USER '$dbUser'@'%' ACCOUNT UNLOCK;"|Out-Null
 foreach($key in @('JAVA_HOME','CORRECTNESS_MYSQL_PREFIX','CORRECTNESS_MYSQL_USER','CORRECTNESS_MYSQL_PASSWORD','CORRECTNESS_EVIDENCE','CORRECTNESS_REDIS_PASSWORD','CORRECTNESS_REDIS_PORT','CORRECTNESS_APP_JDBC','CORRECTNESS_RUN_ID','CORRECTNESS_ADMIN_PASSWORD','CORRECTNESS_DEPLOYED','JOINT_DEPLOYED','JOINT_FRONT_URL','JOINT_ADMIN_URL','JOINT_REDIS_PORT','JOINT_BROWSER_PASSWORD','JOINT_NODE_MODULES')){$previous[$key]=[Environment]::GetEnvironmentVariable($key,'Process')}
 $env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.10';$env:CORRECTNESS_MYSQL_PREFIX=$prefix;$env:CORRECTNESS_MYSQL_USER=$dbUser;$env:CORRECTNESS_MYSQL_PASSWORD=$password;$env:CORRECTNESS_EVIDENCE=$run
 [Environment]::SetEnvironmentVariable('JOINT_DEPLOYED',$null,'Process');[Environment]::SetEnvironmentVariable('CORRECTNESS_DEPLOYED',$null,'Process');[Environment]::SetEnvironmentVariable('CORRECTNESS_REDIS_PASSWORD',$null,'Process')
 $state.codeTests='RUNNING'
 Maven 'related' @('-DskipTests=false','-Dtest=*Test','-Dsurefire.failIfNoSpecifiedTests=false','test');$state.codeTests='PASSED'
 Maven 'package' @('-DskipTests=true','package')
 Require (((Inputs ''|ConvertTo-Json -Depth 5 -Compress)) -ceq (($inputs|ConvertTo-Json -Depth 5 -Compress))) 'Source inputs changed during build; do not deploy mixed inputs'
 foreach($entry in @(@('front','Dockerfile.front',$frontImage),@('admin','Dockerfile.admin',$adminImage))){
  Docker @('build','-f',(Join-Path $source "deploy/correctness/$($entry[1])"),'-t',$entry[2],'--iidfile',(Join-Path $run "$($entry[0])-build-iid"),$source)|Set-Content (Join-Path $run "build-$($entry[0]).log")
  Docker @('image','inspect',$entry[2],'--format','{{.Id}}')|Set-Content (Join-Path $run "$($entry[0]).image")
 }
 Get-FileHash (Join-Path $source 'novel-front/target/novel-front-5.3.3.jar'),(Join-Path $source 'novel-admin/target/novel-admin-5.3.3.jar')|Export-Csv (Join-Path $run 'jar-sha256.csv') -NoTypeInformation
 $sharding=(Get-Content (Join-Path $source 'deploy/shardingsphere/shardingsphere-jdbc.yml.template') -Raw).Replace('${MYSQL_DATABASE}',$database).Replace('${MYSQL_USER_YAML}',"'$dbUser'").Replace('${MYSQL_PASSWORD_YAML}',"'$password'").Replace('sql-show: false','sql-show: true')
 $sharding|Set-Content (Join-Path $runtime 'admin-sharding.yml') -Encoding utf8NoBOM
 # Front entrypoint renders this validation-only copy with SQL logging enabled.
 $template=(Get-Content (Join-Path $source 'deploy/shardingsphere/shardingsphere-jdbc.yml.template') -Raw).Replace('sql-show: false','sql-show: true');$template|Set-Content (Join-Path $runtime 'front-sharding.template') -Encoding utf8NoBOM
 $kafka=@{novel=@{kafka=@{'book-visit'=@{topic="joint-$id-visit";'dlt-topic'="joint-$id-visit-dlt";'group-id'="joint-$id-visit-writer"};'reading-engagement'=@{topic="joint-$id-reading";'dlt-topic'="joint-$id-reading-dlt";'group-id'="joint-$id-reading-writer"}}}}|ConvertTo-Json -Depth 6 -Compress
 $spec=@{services=@{
 'validation-redis'=@{image='redis:7.4.7-alpine';mem_limit='128m';environment=@{REDIS_PASSWORD=$redisPassword};command=@('sh','-ec','exec redis-server --save "" --appendonly no --requirepass "$$REDIS_PASSWORD"');ports=@('127.0.0.1:26382:6379');networks=@('backend')}
 front=@{image=$frontImage;mem_limit='896m';environment=@{TZ='Asia/Shanghai';JAVA_TOOL_OPTIONS='-Xms256m -Xmx512m -XX:MaxMetaspaceSize=160m';SPRING_PROFILES_ACTIVE='prod,monitoring';SPRING_CONFIG_ADDITIONAL_LOCATION='file:/app/validation/front.yml';MYSQL_DATABASE=$database;MYSQL_USER=$dbUser;MYSQL_PASSWORD=$password;SPRING_DATA_REDIS_HOST="$project-validation-redis-1";REDIS_PASSWORD=$redisPassword;JWT_SECRET=$jwt;CACHE_MANAGER_PASSWORD=$secret;NOVEL_AUTH_HMAC_SECRET=$secret;NOVEL_READING_ENGAGEMENT_IP_HMAC_SECRET=$secret;SPRING_APPLICATION_JSON=$kafka;MAIL_USERNAME='validation@localhost';MAIL_PASSWORD='validation-only'};ports=@('127.0.0.1:28283:8083','127.0.0.1:28284:8084');volumes=@("$($runtime.Replace('\','/'))/front.yml:/app/validation/front.yml:ro","$($runtime.Replace('\','/'))/front-sharding.template:/app/config/shardingsphere-jdbc.yml.template:ro");networks=@('backend')}
 admin=@{image=$adminImage;mem_limit='640m';environment=@{TZ='Asia/Shanghai';JAVA_TOOL_OPTIONS='-Xms128m -Xmx384m -XX:MaxMetaspaceSize=160m';SPRING_PROFILES_ACTIVE='prod';SERVER_PORT='8080';SPRING_DATASOURCE_URL='jdbc:shardingsphere:absolutepath:/app/runtime/admin-sharding.yml';SPRING_REDIS_HOST="$project-validation-redis-1";SPRING_REDIS_PORT='6379';SPRING_REDIS_PASSWORD=$redisPassword;SPRING_REDIS_TIMEOUT='3000';JAVA2NB_UPLOADPATH='/app/pic/';NOVEL_ADMIN_DEMO_USERNAME='validation-only';NOVEL_ADMIN_DEMO_PASSWORD=''};ports=@('127.0.0.1:28280:8080');volumes=@("$($runtime.Replace('\','/'))/admin-sharding.yml:/app/runtime/admin-sharding.yml:ro");networks=@('backend')}
 };networks=@{backend=@{external=$true;name=$network}}}
 # No volumes declaration, no dependency services or foreign container management.
 foreach($s in $spec.services.Values){$s.labels=@{'novel.validation'=$label}}
 Assert-JointSpec $spec
 $spec|ConvertTo-Json -Depth 20|Set-Content (Join-Path $runtime 'compose.json') -Encoding utf8NoBOM
 FrontConfig 'builtin';Docker ((Compose)+@('config','--quiet'))|Out-Null
 Docker ((Compose)+@('up','-d','--no-build','validation-redis'))|Out-Null
 $timer=[Diagnostics.Stopwatch]::StartNew();do{try{$p=Docker @('exec',"$project-validation-redis-1",'sh','-ec','REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli ping');if(($p -join '') -eq 'PONG'){break}}catch{};Start-Sleep -Seconds 1}while($timer.Elapsed.TotalSeconds -lt 30)
 Require (($p -join '') -eq 'PONG') 'New Redis not ready'
 $state.deployment='RUNNING'
 Docker ((Compose)+@('up','-d','--no-build','--no-deps','front','admin'))|Out-Null;$state.writesResumed=$true
 WaitHttp 'http://127.0.0.1:28284/actuator/health';WaitHttp 'http://127.0.0.1:28280/login';$state.deployment='PASSED'
 $env:CORRECTNESS_APP_JDBC="jdbc:mysql://127.0.0.1:23307/$database`?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8";$env:CORRECTNESS_RUN_ID=$label;$env:CORRECTNESS_ADMIN_PASSWORD=$password;$env:CORRECTNESS_REDIS_PASSWORD=$redisPassword;$env:CORRECTNESS_REDIS_PORT='26382';$env:JOINT_DEPLOYED='true';$env:JOINT_REDIS_PORT='26382';$env:JOINT_FRONT_URL='http://127.0.0.1:28283';$env:JOINT_ADMIN_URL='http://127.0.0.1:28280'
 $state.http='RUNNING'
 Maven 'http' @('-DskipTests=false','-Dtest=DeployedInteractionIT','-Dsurefire.failIfNoSpecifiedTests=false','test');$state.http='PASSED';Invariants $database
 # Explain the current approved public query, not the obsolete all-status query.
 foreach($q in @('SELECT c.id,c.comment_content FROM book_comment c INNER JOIN user u ON u.id=c.create_user_id WHERE c.book_id=990900001 AND c.audit_status=1 ORDER BY c.create_time DESC,c.id DESC LIMIT 5','SELECT r.id FROM book_comment_reply r JOIN book_comment c ON c.id=r.comment_id WHERE r.comment_id=990800001 AND r.audit_status=1 AND c.audit_status=1 ORDER BY r.create_time DESC,r.id DESC LIMIT 5')){Sql "EXPLAIN FORMAT=JSON $q;EXPLAIN ANALYZE $q;"|Add-Content (Join-Path $run 'explain-actual-public-queries.txt') -Encoding utf8NoBOM}
 Sql 'SHOW INDEX FROM book_comment;SHOW INDEX FROM book_comment_reply;'|Set-Content (Join-Path $run 'deployed-indexes.tsv')
 $actual=@();foreach($service in @('front','admin')){$lines=@(Docker @('logs',"$project-$service-1"));$lines|Set-Content (Join-Path $run "http-$service.log") -Encoding utf8NoBOM;$actual+=@($lines|Where-Object {$_ -match 'Actual SQL:' -and $_ -match 'book_comment'})};Require ($actual.Count -gt 0) 'No real ShardingSphere routed interaction SQL observed';$actual|Set-Content (Join-Path $run 'actual-sharding-interaction-sql.txt') -Encoding utf8NoBOM
 # Real Redis same-intent concurrency is already covered in Stage B. This run records actual HTTP Set effects and post-lifecycle rows.
 $env:JOINT_BROWSER_PASSWORD=$password;$env:JOINT_NODE_MODULES=Join-Path $env:USERPROFILE '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules'
 $state.browser='RUNNING'
 foreach($variant in @('builtin','green','orange')){
  Write-Host "Real browser acceptance: $variant"
  FrontConfig $variant
  Docker ((Compose)+@('up','-d','--no-build','--no-deps','--force-recreate','front'))|Out-Null;WaitHttp 'http://127.0.0.1:28284/actuator/health'
  & $node (Join-Path $PSScriptRoot 'Test-BrowserInteraction.cjs') $env:JOINT_FRONT_URL $run $variant $source *> (Join-Path $run "browser-$variant.log")
  Require ($LASTEXITCODE -eq 0) "Browser $variant failed/unavailable; inspect browser-$variant.log (not accepted)"
 }
 $state.browser='PASSED'
 Invariants $database;$state.status='COMPLETE'
}catch{foreach($phase in @('codeTests','migration','deployment','http','browser')){if($state[$phase] -eq 'RUNNING'){$state[$phase]=if($phase -eq 'browser'){'FAILED_OR_UNAVAILABLE'}else{'FAILED'}}};$state.status='FAILED';$state.failure=$_.Exception.Message;$_|Out-String|Set-Content (Join-Path $run 'exception.txt');$_.ScriptStackTrace|Set-Content (Join-Path $run 'exception-stack.txt')}
finally{
 if($docker){
  foreach($s in @('front','admin','validation-redis')){try{Docker @('logs',"$project-$s-1")|Set-Content (Join-Path $run "final-$s.log") -Encoding utf8NoBOM;$x=((Docker @('inspect',"$project-$s-1"))-join "`n"|ConvertFrom-Json)[0];Save "deployed-$s.json" @{id=$x.Id;image=$x.Image;state=$x.State;mounts=$x.Mounts;ports=$x.NetworkSettings.Ports}}catch{}}
  foreach($c in $original){try{$x=((Docker @('inspect',$c.id))-join "`n"|ConvertFrom-Json)[0];Require ($x.Image -ceq $c.image -and $x.State.StartedAt -ceq $c.started) "Existing container changed: $($c.name)"}catch{$state.status='FAILED';$state.preservationFailure=$_.Exception.Message}}
  if($snapshotsReady){try{Counts $database|Set-Content (Join-Path $run 'counts-final.tsv');foreach($s in $preserved){$after=RootSql "CHECKSUM TABLE $s.book,$s.book_comment,$s.book_comment_reply,$s.user;";$after|Set-Content (Join-Path $run "$s-after.tsv");Require (($after -join "`n") -ceq ((Get-Content (Join-Path $run "$s-before.tsv"))-join "`n")) "Preserved schema changed during acceptance: $s"}}catch{$state.status='FAILED';$state.preservationFailure=$_.Exception.Message}}
 }
 foreach($key in $previous.Keys){[Environment]::SetEnvironmentVariable($key,$previous[$key],'Process')}
 $state.finished=[DateTimeOffset]::Now.ToString('o');Save 'acceptance.json' $state
 Write-Host "Joint evidence: $run; status=$($state.status); migration=$($state.migration); deployment=$($state.deployment); HTTP=$($state.http); browser=$($state.browser)"
}
if($state.status -ne 'COMPLETE'){throw "Joint acceptance incomplete; preserve environment and inspect: $run"}

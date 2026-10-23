#requires -Version 7
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$lex=$null;$errors=$null;$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'Run-InteractionAcceptance.ps1'),[ref]$lex,[ref]$errors)
if($errors){throw ($errors|Out-String)}
foreach($name in @('Require','Assert-JointSchema','Sql','Assert-JointSpec')){
 $node=$ast.Find({param($n)$n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq $name},$true)
 if(-not $node){throw "Required guard missing: $name"};Invoke-Expression $node.Extent.Text
}
$id='20261020000000000';$database="cv_it_${id}_app";$trial="cv_it_${id}_trial";$restore="cv_it_${id}_restore";$dbUser="joint_$id";$project="novel-interaction-$id";$network='novel-cache-ab-20261016_backend';$runtime='C:\test\joint'
function RootSql([string]$Text){$Text}
$checks=0
foreach($schema in @($database,$trial,$restore)){Assert-JointSchema $schema;$checks++}
foreach($schema in @('novel_ab','cv_app_20261017231415443','cv_it_20261020000000001_app','mysql',"$database;DROP DATABASE novel_ab")){
 $rejected=$false;try{Sql 'SELECT 1' $schema|Out-Null}catch{$rejected=$true};if(-not $rejected){throw "Unsafe schema accepted: $schema"};$checks++
}
if((Sql 'SELECT 1') -cne "USE ``$database``; SELECT 1"){throw 'Schema pinning failed'};$checks++
function Spec {
 $k=@{novel=@{kafka=@{'book-visit'=@{topic="joint-$id-visit"};'reading-engagement'=@{topic="joint-$id-reading"}}}}|ConvertTo-Json -Depth 7 -Compress
 return @{networks=@{backend=@{external=$true;name=$network}};services=@{
 front=@{ports=@('127.0.0.1:28283:8083','127.0.0.1:28284:8084');networks=@('backend');environment=@{MYSQL_DATABASE=$database;MYSQL_USER=$dbUser;SPRING_DATA_REDIS_HOST="$project-validation-redis-1";SPRING_APPLICATION_JSON=$k};volumes=@('C:/test/joint/front.yml:/app/validation/front.yml:ro')}
 admin=@{ports=@('127.0.0.1:28280:8080');networks=@('backend');environment=@{SPRING_REDIS_HOST="$project-validation-redis-1"}}
 'validation-redis'=@{command=@('sh','-ec','exec redis-server --requirepass "$$REDIS_PASSWORD"');ports=@('127.0.0.1:26382:6379');networks=@('backend');environment=@{}}
 }}
}
Assert-JointSpec (Spec);$checks++
foreach($case in @('named-volume','public-port','shared-db','shared-redis','source-db','foreign-service','foreign-network','writable-bind','outside-bind','frozen-topic','host-password-expansion')){
 $s=Spec
 switch($case){
  'named-volume'{$s.volumes=@{data=@{}}}
  'public-port'{$s.services.front.ports[0]='0.0.0.0:28283:8083'}
  'shared-db'{$s.services.front.environment.MYSQL_DATABASE='cv_app_20261017231415443'}
  'shared-redis'{$s.services.front.environment.SPRING_DATA_REDIS_HOST='validation-redis'}
  'source-db'{$s.services.front.environment.MYSQL_DATABASE='novel_ab'}
  'foreign-service'{$s.services.mysql=@{}}
  'foreign-network'{$s.networks.backend.name='other'}
  'writable-bind'{$s.services.front.volumes=@('C:/test/joint/front.yml:/app/front.yml:rw')}
  'outside-bind'{$s.services.front.volumes=@('C:/Windows/file:/app/front.yml:ro')}
  'host-password-expansion'{$s.services['validation-redis'].command=@('sh','-ec','exec redis-server --requirepass "$REDIS_PASSWORD"')}
  'frozen-topic'{$s.services.front.environment.SPRING_APPLICATION_JSON=$s.services.front.environment.SPRING_APPLICATION_JSON.Replace("joint-$id-visit",'novel-book-visit-v1')}
 }
 $rejected=$false;try{Assert-JointSpec $s}catch{$rejected=$true};if(-not $rejected){throw "Unsafe spec accepted: $case"};$checks++
}
foreach($file in @('Run-InteractionAcceptance.ps1','Test-BrowserInteraction.ps1')){$lex=$null;$errors=$null;$null=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $file),[ref]$lex,[ref]$errors);if($errors){throw ($errors|Out-String)};$checks++}
# No Docker command or entrypoint top-level code is evaluated by this test.
Write-Host "PASS: $checks joint schema/compose/AST guards; no Docker, SQL or browser operations executed."

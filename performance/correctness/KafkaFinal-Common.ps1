# Small helpers for the one-shot Kafka acceptance entry. No Docker calls at import time.
function Invoke-KviProcess {
 param([string]$Executable,[string[]]$Arguments,[string]$Log,[int]$TimeoutSeconds=30)
 $start=[Diagnostics.ProcessStartInfo]::new()
 $start.FileName=$Executable;$start.UseShellExecute=$false;$start.CreateNoWindow=$true
 $start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
 $start.StandardOutputEncoding=[Text.Encoding]::UTF8;$start.StandardErrorEncoding=[Text.Encoding]::UTF8
 foreach($arg in $Arguments){$start.ArgumentList.Add($arg)}
 $p=[Diagnostics.Process]::new();$p.StartInfo=$start
 try {
  $null=$p.Start();$stdout=$p.StandardOutput.ReadToEndAsync();$stderr=$p.StandardError.ReadToEndAsync()
  $timedOut=-not $p.WaitForExit($TimeoutSeconds*1000)
  if($timedOut){$p.Kill($true);if(-not $p.WaitForExit(10000)){throw 'Timed out child could not be terminated'}}
  if(-not $stdout.Wait(10000) -or -not $stderr.Wait(10000)){throw "Process pipe drain timed out; logs: $Log"}
  [IO.File]::WriteAllText("$Log.stdout.txt",$stdout.GetAwaiter().GetResult(),[Text.UTF8Encoding]::new($false))
  [IO.File]::WriteAllText("$Log.stderr.txt",$stderr.GetAwaiter().GetResult(),[Text.UTF8Encoding]::new($false))
  if($timedOut){throw "Process timed out after $TimeoutSeconds seconds; logs: $Log"}
  if($p.ExitCode -ne 0){throw "Process exit=$($p.ExitCode); logs: $Log"}
  return [IO.File]::ReadAllText("$Log.stdout.txt")
 }finally{$p.Dispose()}
}
function Assert-KviContainer($Container,[string]$Name,[string]$Id,[string]$Project,[string]$Service){
 if($Container.Id -cne $Id -or $Container.Name -cne "/$Name" -or
    $Container.Config.Labels.'com.docker.compose.project' -cne $Project -or
    $Container.Config.Labels.'com.docker.compose.service' -cne $Service){throw "Container identity drift: $Name"}
 $s=$Container.State
 if(-not $s.Running -or $s.Paused -or $s.Restarting -or $s.OOMKilled -or $s.Status -cne 'running'){throw "Unsafe dependency state: $Name"}
 if($s.PSObject.Properties['Health'] -and $s.Health.Status -cne 'healthy'){throw "Unhealthy dependency: $Name"}
}
function Get-KviInputs([string]$Source){
 $paths=@(Get-ChildItem -LiteralPath $Source -Filter pom.xml -File)
 foreach($module in @('novel-front','novel-common','novel-interaction','novel-cache-contract')){
  $paths+=Get-Item -LiteralPath (Join-Path $Source "$module/pom.xml")
  $paths+=Get-ChildItem -LiteralPath (Join-Path $Source "$module/src") -Recurse -File
 }
 $paths+=Get-ChildItem -LiteralPath (Join-Path $Source 'performance/correctness') -File|Where-Object Name -in @('Run-KafkaFinalAcceptance.ps1','KafkaFinal-Common.ps1','Test-KafkaFinalEntry.ps1')
 @($paths|Sort-Object FullName -Unique|ForEach-Object{[ordered]@{path=[IO.Path]::GetRelativePath($Source,$_.FullName);sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}})
}
function Get-KviCleanupState([string]$Evidence,[bool]$Attempted){
 foreach($name in @('cleanup-retry.json','acceptance.json')){
  $path=Join-Path $Evidence $name
  if(Test-Path -LiteralPath $path){
   try{$value=Get-Content -LiteralPath $path -Raw|ConvertFrom-Json
    if($value.cleanup -in @('VERIFIED','FAILED','NOT_NEEDED')){return [string]$value.cleanup}
   }catch{return 'UNVERIFIED'}
  }
 }
 if($Attempted){return 'UNVERIFIED'}else{return 'NOT_NEEDED'}
}
function Get-KviSafeContainer($c){
 [ordered]@{id=$c.Id;name=$c.Name;image=$c.Image;restartCount=$c.RestartCount;state=$c.State;
  memory=$c.HostConfig.Memory;memorySwap=$c.HostConfig.MemorySwap;networks=$c.NetworkSettings.Networks;
  project=$c.Config.Labels.'com.docker.compose.project';service=$c.Config.Labels.'com.docker.compose.service'}
}
